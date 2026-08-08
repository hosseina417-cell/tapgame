"""
نسخه وب آربیتراژیار؛ بدون وابستگی خارجی و قابل اجرا در مرورگر.
اجرا:
    python web_app.py
"""
from __future__ import annotations

from decimal import Decimal
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from arbitrage_core import (
    SUPPORTED_ASSETS,
    collect_market_data,
    default_fee_rates,
    default_providers,
    find_opportunities,
    format_money,
    timestamp_text,
)

HOST = "0.0.0.0"
PORT = 8000

HTML = r"""
<!doctype html>
<html lang="fa" dir="rtl">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>آربیتراژیار</title>
  <style>
    :root{--bg:#0d1117;--card:#161b22;--muted:#8b949e;--text:#f0f6fc;--blue:#2f81f7;--green:#2ea043;--orange:#d29922;--red:#f85149;--border:#30363d}
    *{box-sizing:border-box} body{margin:0;background:radial-gradient(circle at top,#172033,#0d1117 45%);color:var(--text);font-family:Tahoma,Arial,sans-serif;line-height:1.8}
    .wrap{max-width:1100px;margin:0 auto;padding:22px}.hero{padding:22px;border:1px solid var(--border);border-radius:22px;background:rgba(22,27,34,.88);box-shadow:0 12px 40px #0006}
    h1{margin:0 0 6px;font-size:30px}.sub{color:var(--muted);margin:0}.controls{display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;margin:18px 0}
    button,input{border:1px solid var(--border);border-radius:14px;padding:13px 14px;font:inherit;color:var(--text);background:#0d1117}button{cursor:pointer;background:var(--blue);font-weight:700}button.secondary{background:#238636}button.danger{background:#da3633}button:disabled{opacity:.65;cursor:wait}
    label{color:var(--muted);font-size:13px}.field{display:flex;flex-direction:column;gap:5px}.status{color:var(--orange);margin-top:8px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:14px;margin-top:14px}.card{border:1px solid var(--border);background:rgba(22,27,34,.94);border-radius:18px;padding:16px}.good{color:#3fb950}.warn{color:var(--orange)}.bad{color:var(--red)}.muted{color:var(--muted)}.row{display:flex;justify-content:space-between;gap:10px;border-top:1px solid var(--border);padding-top:9px;margin-top:9px}.mono{direction:ltr;text-align:left;font-family:ui-monospace,Consolas,monospace}.small{font-size:13px}.table{overflow:auto}.quote{display:grid;grid-template-columns:80px 100px 1fr;gap:8px;padding:8px 0;border-top:1px solid var(--border)}
    @media(max-width:720px){.controls{grid-template-columns:1fr}.quote{grid-template-columns:1fr}.row{display:block}}
  </style>
</head>
<body>
  <main class="wrap">
    <section class="hero">
      <h1>آربیتراژیار</h1>
      <p class="sub">پایش اختلاف قیمت تومانی بین Nobitex، Bitpin، Wallex و Ramzinex — فقط تحلیل، بدون معامله خودکار.</p>
      <div class="controls">
        <button id="scanBtn">دریافت قیمت‌ها</button>
        <button id="autoBtn" class="secondary">شروع بروزرسانی خودکار</button>
        <div class="field"><label>حداقل سود خالص برای نمایش ٪</label><input id="minProfit" inputmode="decimal" value="0.30"></div>
      </div>
      <div id="status" class="status">آماده دریافت داده‌های بازار</div>
    </section>
    <section id="opps" class="grid"></section>
    <section id="details" class="grid"></section>
  </main>
<script>
const scanBtn=document.getElementById('scanBtn'), autoBtn=document.getElementById('autoBtn'), minProfit=document.getElementById('minProfit'), statusEl=document.getElementById('status'), opps=document.getElementById('opps'), details=document.getElementById('details');
let timer=null;
function esc(s){return String(s??'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]))}
function setStatus(t,c=''){statusEl.textContent=t;statusEl.className='status '+c}
async function scan(){
  scanBtn.disabled=true; scanBtn.textContent='در حال دریافت...'; setStatus('در حال اتصال به APIهای عمومی صرافی‌ها...');
  try{
    const r=await fetch('/api/scan?min_profit='+encodeURIComponent(minProfit.value||'0'));
    const data=await r.json(); render(data);
  }catch(e){setStatus('خطا در دریافت داده: '+e,'bad');}
  finally{scanBtn.disabled=false; scanBtn.textContent='دریافت قیمت‌ها';}
}
function render(data){
  setStatus(`آخرین بروزرسانی: ${data.fetched_at} | قیمت معتبر: ${data.quotes.length} | فرصت‌ها: ${data.opportunities.length}`, data.opportunities.length?'good':'warn');
  opps.innerHTML=''; details.innerHTML='';
  if(!data.opportunities.length){opps.innerHTML='<div class="card"><b class="warn">فرصت بالاتر از آستانه فعلی پیدا نشد.</b><p class="muted">آستانه را کمتر کنید یا بعداً دوباره بررسی کنید.</p></div>'}
  data.opportunities.forEach((o,i)=>{opps.insertAdjacentHTML('beforeend',`<div class="card"><h3 class="${o.net_percent_num>=1.2?'good':'warn'}">#${i+1} ${esc(o.asset)} | خرید از ${esc(o.buy_exchange)} ← فروش در ${esc(o.sell_exchange)}</h3><div class="row"><span>خرید</span><b>${esc(o.buy_price)} تومان</b></div><div class="row"><span>فروش</span><b>${esc(o.sell_price)} تومان</b></div><div class="row"><span>سود خام</span><b>${esc(o.gross_profit)} تومان (${esc(o.gross_percent)}٪)</b></div><div class="row"><span>سود خالص تخمینی</span><b class="good">${esc(o.net_percent)}٪ ≈ ${esc(o.net_profit)} تومان</b></div><p class="muted small">${esc(o.risk_note)}</p></div>`)});
  let qhtml='<div class="card table"><h3>قیمت‌های دریافت‌شده</h3>';
  data.quotes.forEach(q=>{qhtml+=`<div class="quote"><b>${esc(q.asset)}</b><span>${esc(q.exchange)}</span><span class="small muted">خرید صرافی: ${esc(q.bid)} | فروش صرافی: ${esc(q.ask)} تومان</span></div>`});
  qhtml+='</div>'; details.insertAdjacentHTML('beforeend',qhtml);
  if(Object.keys(data.errors).length){let e='<div class="card"><h3 class="warn">خطای APIها</h3>'; for(const [k,v] of Object.entries(data.errors)) e+=`<p class="small"><b>${esc(k)}:</b> <span class="muted">${esc(v)}</span></p>`; details.insertAdjacentHTML('beforeend',e+'</div>')}
  details.insertAdjacentHTML('beforeend','<div class="card"><h3 class="warn">یادآوری ریسک</h3><p class="muted small">این ابزار سیگنال قطعی نیست. قبل از معامله، عمق سفارش، کارمزد دقیق، هزینه و زمان انتقال، محدودیت برداشت/واریز و لغزش قیمت را بررسی کنید.</p></div>');
}
scanBtn.onclick=scan; autoBtn.onclick=()=>{if(timer){clearInterval(timer);timer=null;autoBtn.textContent='شروع بروزرسانی خودکار';autoBtn.className='secondary'}else{scan();timer=setInterval(scan,60000);autoBtn.textContent='توقف بروزرسانی خودکار';autoBtn.className='danger'}};
</script>
</body>
</html>
"""


def dec2(value, places="0.01"):
    return str(value.quantize(Decimal(places)))


def scan_payload(min_profit: str):
    try:
        threshold = Decimal(min_profit or "0")
    except Exception:
        threshold = Decimal("0.30")
    providers = default_providers()
    result = collect_market_data(providers, SUPPORTED_ASSETS)
    opps = find_opportunities(result.quotes, default_fee_rates(providers), min_net_percent=threshold)
    return {
        "fetched_at": timestamp_text(result.fetched_at),
        "quotes": [
            {"exchange": q.exchange, "asset": q.asset, "bid": format_money(q.bid), "ask": format_money(q.ask)}
            for q in sorted(result.quotes, key=lambda x: (x.asset, x.exchange))
        ],
        "opportunities": [
            {
                "asset": o.asset,
                "buy_exchange": o.buy_exchange,
                "sell_exchange": o.sell_exchange,
                "buy_price": format_money(o.buy_price),
                "sell_price": format_money(o.sell_price),
                "gross_profit": format_money(o.gross_profit),
                "gross_percent": dec2(o.gross_percent),
                "net_profit": format_money(o.net_profit),
                "net_percent": dec2(o.net_percent),
                "net_percent_num": float(o.net_percent),
                "risk_note": o.risk_note,
            }
            for o in opps[:30]
        ],
        "errors": result.errors,
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("web:", fmt % args)

    def send(self, status, body, content_type):
        raw = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/":
            return self.send(200, HTML, "text/html; charset=utf-8")
        if parsed.path == "/api/scan":
            qs = parse_qs(parsed.query)
            payload = scan_payload(qs.get("min_profit", ["0.30"])[0])
            return self.send(200, json.dumps(payload, ensure_ascii=False), "application/json; charset=utf-8")
        return self.send(404, "Not found", "text/plain; charset=utf-8")


def run(host=HOST, port=PORT):
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"آربیتراژیار وب روی http://{host}:{port} اجرا شد")
    server.serve_forever()


if __name__ == "__main__":
    run()
