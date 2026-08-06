#!/usr/bin/env node
/* ============================================================
 * تست واحد منطق برنامه (بدون نیاز به مرورگر)
 * اجرا: node tools/test_logic.js
 * ============================================================ */
'use strict';

const TA = require('../app/js/indicators.js');
const Strategy = require('../app/js/strategy.js');
const PumpDetect = require('../app/js/pumpdetect.js');
const Providers = require('../app/js/providers.js');
const U = require('../app/js/util.js');

let passed = 0, failed = 0;
function ok(cond, msg) {
  if (cond) { passed++; console.log('  ✔ ' + msg); }
  else { failed++; console.log('  ✘ ' + msg); }
}
function section(name) { console.log('\n== ' + name + ' =='); }

/* ---------- تولید داده مصنوعی ---------- */
function makeCandles(n, opts) {
  opts = opts || {};
  let price = opts.start || 100;
  const trend = opts.trend || 0;
  const vol = opts.vol || 100;
  const out = [];
  let seed = opts.seed || 42;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  const stepMs = opts.stepMs || 15 * 60 * 1000;
  const t0 = opts.t0 || Date.now() - n * stepMs;
  for (let i = 0; i < n; i++) {
    const drift = trend * i;
    const open = price;
    const close = Math.max(0.0001, open + (rnd() - 0.48) * 2 + drift);
    const high = Math.max(open, close) + rnd() * 1.5;
    const low = Math.min(open, close) - rnd() * 1.5;
    const v = vol * (0.5 + rnd());
    out.push({ t: t0 + i * stepMs, o: open, h: high, l: low, c: close, v: v });
    price = close;
  }
  return out;
}

/* ============ اندیکاتورها ============ */
section('اندیکاتورها');
{
  const candles = makeCandles(300, { seed: 7 });
  const a = TA.analyze(candles);

  ok(a.rsi !== null && a.rsi >= 0 && a.rsi <= 100, 'RSI در بازه 0..100');
  ok(a.ema9 !== null && a.ema21 !== null && a.ema50 !== null, 'EMA ها محاسبه شدند');
  ok(a.macd && typeof a.macd.hist === 'number', 'MACD محاسبه شد');
  ok(a.bb && a.bb.upper >= a.bb.mid && a.bb.mid >= a.bb.lower, 'باند بولینگر مرتب است');
  ok(a.atr !== null && a.atr > 0, 'ATR مثبت است');
  ok(a.stoch && a.stoch.k >= 0 && a.stoch.k <= 100 && a.stoch.d >= 0 && a.stoch.d <= 100, 'استوکاستیک در بازه 0..100');
  ok(a.vol && a.vol.ratio > 0, 'تحلیل حجم انجام شد');
  ok(a.roc && typeof a.roc[12] === 'number', 'ROC محاسبه شد');

  // داده ناکافی
  const short = makeCandles(5);
  const a2 = TA.analyze(short);
  ok(a2.rsi === null && a2.macd === null && a2.bb === null, 'داده کم → null (نه NaN)');

  // قیمت ثابت (تقسیم بر صفر)
  const flat = makeCandles(100, { start: 50 }).map(c => ({ t: c.t, o: 50, h: 50, l: 50, c: 50, v: c.v }));
  const a3 = TA.analyze(flat);
  ok(a3.rsi === 100 || a3.rsi === null || isFinite(a3.rsi), 'RSI قیمت ثابت متناهی است');
  ok(isFinite(a3.bbPct) || a3.bbPct === null || a3.bbPct === undefined, 'bbPct متناهی است');

  // آرایه خالی
  const a4 = TA.analyze([]);
  ok(a4.count === 0 && a4.rsi === null, 'آرایه خالی خراب نمی‌شود');
}

/* ============ استراتژی ============ */
section('استراتژی سیگنال');
{
  // روند صعودی تند → سیگنال خرید
  const up = makeCandles(300, { trend: 0.4, seed: 3 });
  const au = TA.analyze(up);
  const eu = Strategy.evaluate(au);
  ok(['BUY', 'NEUTRAL'].indexOf(eu.signal) >= 0, 'روند صعودی → خرید/خنثی (سیگنال: ' + eu.signal + ')');
  ok(eu.probability >= 50, 'احتمال خرید در روند صعودی ≥ 50٪ (' + eu.probability.toFixed(1) + ')');
  ok(eu.score >= -100 && eu.score <= 100, 'امتیاز در بازه -100..100');

  // روند نزولی تند → سیگنال فروش
  const down = makeCandles(300, { trend: -0.4, seed: 9 });
  const ad = TA.analyze(down);
  const ed = Strategy.evaluate(ad);
  ok(['SELL', 'NEUTRAL'].indexOf(ed.signal) >= 0, 'روند نزولی → فروش/خنثی (سیگنال: ' + ed.signal + ')');
  ok(ed.probability <= 50, 'احتمال خرید در روند نزولی ≤ 50٪ (' + ed.probability.toFixed(1) + ')');

  // ترکیب چند تایم‌فریم
  const multi = Strategy.evaluateMulti([
    { tf: '15m', a: TA.analyze(makeCandles(100, { trend: 0.1 })) },
    { tf: '1h', a: TA.analyze(makeCandles(200, { trend: 0.2 })) },
    { tf: '4h', a: TA.analyze(makeCandles(200, { trend: 0.15 })) }
  ]);
  ok(multi.score >= -100 && multi.score <= 100, 'امتیاز چند تایم‌فریم معتبر');
  ok(multi.parts.length === 3, 'جزئیات هر تایم‌فریم موجود است');
  ok(multi.probability >= 1 && multi.probability <= 99, 'احتمال در بازه 1..99');

  // بررسی اجزای شفاف
  ok(eu.factors && eu.factors.rsi && eu.factors.rsi.notes.length > 0, 'عوامل با توضیح فارسی');
}

/* ============ پامپ/دامپ ============ */
section('تشخیص پامپ/دامپ');
{
  // بازار خنثی → بدون هشدار
  const flat = makeCandles(60, { trend: 0.0, seed: 11 });
  ok(PumpDetect.check(flat, {}, '15m') === null, 'بازار خنثی → بدون هشدار');

  // پامپ: کندل آخر رشد ۱۱٪ با حجم ۶ برابر
  const pumpC = makeCandles(60, { trend: 0.0, seed: 5 });
  const last = pumpC[pumpC.length - 1];
  const prev = pumpC[pumpC.length - 2];
  pumpC[pumpC.length - 1] = { t: last.t, o: prev.c, h: prev.c * 1.12, l: prev.c * 0.995, c: prev.c * 1.11, v: last.v * 10 };
  const p = PumpDetect.check(pumpC, {}, '15m');
  ok(p && p.type === 'pump', 'رشد ۱۱٪ + حجم ۶ برابر → پامپ');
  ok(p && p.severity === 'extreme', 'شدت پامپ شدید (' + (p && p.severity) + ')');

  // دامپ: کندل آخر ریزش ۶٪ با حجم ۶ برابر
  const dumpC = makeCandles(60, { trend: 0.0, seed: 6 });
  const l2 = dumpC[dumpC.length - 1];
  const p2 = dumpC[dumpC.length - 2];
  dumpC[dumpC.length - 1] = { t: l2.t, o: p2.c, h: p2.c * 1.005, l: p2.c * 0.93, c: p2.c * 0.94, v: l2.v * 6 };
  const d = PumpDetect.check(dumpC, {}, '15m');
  ok(d && d.type === 'dump', 'ریزش ۶٪ + حجم ۶ برابر → دامپ');

  // رشد زیاد بدون حجم → هشدار نیست
  const noVol = makeCandles(60, { trend: 0.0, seed: 8 });
  const l3 = noVol[noVol.length - 1];
  const p3 = noVol[noVol.length - 2];
  noVol[noVol.length - 1] = { t: l3.t, o: p3.c, h: p3.c * 1.06, l: p3.c * 0.99, c: p3.c * 1.05, v: l3.v * 1.1 };
  ok(PumpDetect.check(noVol, {}, '15m') === null, 'رشد بدون حجم غیرعادی → بدون هشدار');
}

/* ============ بهبودهای V2 ============ */
section('V2: فیلتر روند RSI (چاقوی سقوط)');
{
  // روند نزولی + اشباع فروش: امتیاز RSI باید ضعیف باشد
  const down = makeCandles(200, { trend: -0.5, seed: 21 });
  const ad = TA.analyze(down);
  ok(ad.rsi !== null && ad.rsi < 40, 'روند نزولی تند → RSI پایین (' + (ad.rsi !== null ? ad.rsi.toFixed(1) : 'null') + ')');
  const ev = Strategy.evaluate(ad);
  const rsiFactor = ev.factors.rsi.score;
  ok(rsiFactor <= 0.5, 'در روند نزولی، اشباع فروش امتیاز ضعیف می‌گیرد (' + rsiFactor.toFixed(2) + ')');
  ok(ev.factors.rsi.notes.some(n => n.indexOf('روند نزولی') >= 0), 'توضیح ریسک «چاقوی سقوط» نمایش داده می‌شود');

  // روند صعودی + RSI پایین: امتیاز قوی
  const up = makeCandles(200, { trend: 0.4, seed: 22 });
  // ساخت دستی: آخرین کندل‌ها طوری که RSI پایین بیاید ولی روند بالا بماند
  const eu = Strategy.evaluate(TA.analyze(up));
  ok(eu.factors.rsi.score >= -1 && eu.factors.rsi.score <= 1, 'امتیاز RSI معتبر در روند صعودی');
}

section('V2: تقاطع MACD و واگرایی');
{
  // تقاطع مثبت تازه: هیستوگرام از منفی به مثبت
  const c = makeCandles(200, { trend: 0.05, seed: 31 });
  const ser = TA.analyze(c);
  const m = ser.macdSeries;
  if (m && m.length > 3) {
    m[m.length - 2].hist = -0.5; // کندل قبل منفی
    m[m.length - 1].hist = 0.5;  // کندل آخر مثبت → تقاطع تازه
    const ev = Strategy.evaluate(ser);
    ok(ev.factors.macd.notes.some(n => n.indexOf('تقاطع تازه') >= 0), 'تقاطع تازه MACD شناسایی شد');
  } else {
    ok(false, 'macdSeries در دسترس نیست');
  }
}

section('V2: هم‌ترازی پنجره نمودار');
{
  const candles = makeCandles(300, { seed: 41 });
  const a = TA.analyze(candles);
  const ema9 = { values: a.ema9Series, lag: 9 };
  // شبیه‌سازی پنجره‌بندی chart.render برای maxBars=120
  const nAll = 300, s = 180;
  const ChartMod = require('../app/js/chart.js');
  const win = ChartMod.windowSeries(ema9.values, 9, s, nAll);
  ok(win && win.arr.length > 0, 'پنجره EMA9 استخراج شد (' + (win && win.arr.length) + ' عضو)');
  if (win) {
    // ema9[i] متناظر با کندل i+9 است؛ اولین عضو پنجره باید متناظر کندل (s - 9) باشد
    const expectedFirst = a.ema9Series[s - 9];
    ok(Math.abs(win.arr[0] - expectedFirst) < 1e-9, 'اولین عضو پنجره با کندل متناظر هم‌تراز است');
    // آخرین عضو باید متناظر کندل nAll-1 باشد → شاخص nAll-1-9
    const expectedLast = a.ema9Series[nAll - 1 - 9];
    ok(Math.abs(win.arr[win.arr.length - 1] - expectedLast) < 1e-9, 'آخرین عضو پنجره هم‌تراز است');
    ok(win.off === 0, 'آفست رسم صحیح است (off=0)');
  }
}

section('V2: قالب‌بندی قیمت');
{
  const U2 = require('../app/js/util.js');
  ok(U2.fmtPrice(0.00001234) === '0.00001234', 'قیمت خیلی ریز ۸ رقم اعشار (' + U2.fmtPrice(0.00001234) + ')');
  ok(U2.fmtPrice(64200.5) === '64,200.50', 'قیمت بزرگ جداکننده دارد');
  ok(U2.fmtPrice(0.123456) === '0.1235', 'قیمت زیر ۱ دلار ۴ رقم معنی‌دار');
  ok(U2.fmtPrice(null) === '—', 'مقدار نامعتبر → خط تیره');
}

/* ============ V3: بکتست و صف ============ */
section('V3: بکتست (دقت تجربی)');
{
  // روند صعودی → سیگنال‌های خرید باید نرخ موفقیت بالایی داشته باشند
  const up = makeCandles(300, { trend: 0.35, seed: 51 });
  const bt = Strategy.backtest(up);
  ok(bt !== null, 'بکتست اجرا شد');
  if (bt) {
    ok(bt.buy >= 0 && bt.sell >= 0, 'تعداد سیگنال‌ها نامنفی');
    ok(bt.buyRate === null || (bt.buyRate >= 0 && bt.buyRate <= 100), 'نرخ موفقیت خرید در بازه');
    ok(bt.sellRate === null || (bt.sellRate >= 0 && bt.sellRate <= 100), 'نرخ موفقیت فروش در بازه');
    if (bt.buyRate !== null) ok(bt.buyRate > 50, 'در روند صعودی، نرخ موفقیت خرید بالای ۵۰٪ (' + bt.buyRate.toFixed(0) + '٪)');
  }
  // داده کم → null
  ok(Strategy.backtest(makeCandles(40)) === null, 'داده کم → بکتست null');
}

section('V3: صف با وعده پایان (drain)');
{
  const q = U.makeQueue(2);
  let done = 0;
  for (let i = 0; i < 5; i++) {
    q.add(() => new Promise(r => setTimeout(() => { done++; r(); }, 20)));
  }
  q.drain().then(() => {
    ok(done === 5, 'drain بعد از اتمام همه کارها resolve شد (' + done + ')');
    ok(q.size === 0, 'صف خالی است');
  });
}

/* ============ نرمال‌سازی داده صرافی‌ها ============ */
section('نرمال‌سازی داده صرافی‌ها');
{
  const binance = [[1700000000000, "100", "105", "99", "103", "1000"], [1700000900000, "103", "104", "101", "102", "800"]];
  const c1 = Providers.normalize ? null : null;
  // از طریق Providers (توابع داخلی نیستند؛ اینجا فقط fetchJSON ساختار را تست می‌کنیم)
  ok(typeof Providers.fetchJSON === 'function', 'fetchJSON در دسترس است');

  // بررسی نقشه سکه‌ها
  const btc = Providers.coinBySymbol('BTC');
  ok(btc && btc.binance === 'BTCUSDT' && btc.okx === 'BTC-USDT', 'نقشه سکه BTC کامل است');
  const eth = Providers.coinById('ethereum');
  ok(eth && eth.kraken === 'ETHUSD', 'نقشه سکه ETH کامل است');
}

/* ============ نتیجه ============ */
console.log('\n==================================');
console.log('نتیجه: ' + passed + ' موفق، ' + failed + ' ناموفق');
if (failed > 0) process.exit(1);

/* ============ V1.1: ارزهای بیشتر و ثبت پویا ============ */
section('V1.1: فهرست ارزها و ثبت پویا');
{
  const P = require('../app/js/providers.js');
  ok(P.allCoins().length >= 100, 'فهرست پایه حداقل ۱۰۰ ارز دارد (' + P.allCoins().length + ')');
  ok(P.coinById('bitcoin').kraken === 'XBTUSD', 'استثنای کراکن بیت‌کوین (XBTUSD)');
  ok(P.coinById('dogecoin').kraken === 'XDGUSD', 'استثنای کراکن دوج (XDGUSD)');
  const wif = P.coinById('dogwifcoin');
  ok(wif && wif.binance === 'WIFUSDT' && wif.okx === 'WIF-USDT', 'سکه‌های جدید مثل WIF در فهرست هستند');

  // ثبت پویا از فهرست بازار
  P.registerMarket([{ id: 'mega-coin', sym: 'MEGA', name: 'Mega Coin' }]);
  const dyn = P.coinBySymbol('MEGA');
  ok(dyn && dyn.id === 'mega-coin', 'ارز جدید از فهرست بازار قابل شناسایی است');
  ok(dyn.binance === 'MEGAUSDT', 'نماد صرافی ارز پویا ساخته شد');

  // استیبل‌کوین‌ها
  ok(P.isStablecoin('usdt') === true, 'USDT استیبل‌کوین است');
  ok(P.isStablecoin('BTC') === false, 'BTC استیبل‌کوین نیست');

  // قیمت کش
  P.setPrice('mega-coin', 1.23);
  ok(P.getPrice('mega-coin') === 1.23, 'قیمت کش برای سکه سفارشی ذخیره شد');
}

/* ============ V1.1.1: باگ‌های افزودن سکه ============ */
section('V1.1.1: buildCoin و افزودن سکه');
{
  const P = require('../app/js/providers.js');
  // نتیجه جستجو فقط id/sym/name دارد — buildCoin باید نمادهای صرافی را بسازد
  const c = P.buildCoin({ id: 'dogwifcoin', sym: 'WIF', name: 'دوج‌ویف' });
  ok(c.binance === 'WIFUSDT' && c.bybit === 'WIFUSDT' && c.okx === 'WIF-USDT', 'buildCoin نمادهای صرافی را می‌سازد');
  ok(c.kraken === 'WIFUSD' && c.cc === 'WIF', 'buildCoin کراکن و کریپتوکامپیر را می‌سازد');
  const btc = P.buildCoin({ id: 'bitcoin', sym: 'BTC' });
  ok(btc.kraken === 'XBTUSD', 'buildCoin استثنای کراکن BTC را اعمال می‌کند');
  // addCoin باید سکه ناقص را کامل ذخیره کند
  P.removeCoin('mega-custom');
  ok(P.addCoin({ id: 'mega-custom', sym: 'MGC', name: 'Mega' }) === true, 'addCoin سکه جدید را می‌پذیرد');
  const stored = P.coinById('mega-custom');
  ok(stored && stored.binance === 'MGCUSDT', 'addCoin سکه کامل با نماد صرافی ذخیره می‌کند');
  ok(P.addCoin({ id: 'mega-custom', sym: 'MGC' }) === false, 'افزودن تکراری رد می‌شود');
  // addCoin با ورودی ناقص
  ok(P.addCoin({}) === false, 'addCoin ورودی ناقص را رد می‌کند');
}

/* ============ دسته‌بندی روند ============ */
section('دسته‌بندی صعودی/نزولی');
{
  const S = require('../app/js/strategy.js');
  ok(S.categoryOf({ score: 60 }) === 'strong-buy', 'امتیاز +۶۰ → صعودی قوی');
  ok(S.categoryOf({ score: 30 }) === 'buy', 'امتیاز +۳۰ → صعودی');
  ok(S.categoryOf({ score: 0 }) === 'neutral', 'امتیاز صفر → خنثی');
  ok(S.categoryOf({ score: -30 }) === 'sell', 'امتیاز -۳۰ → نزولی');
  ok(S.categoryOf({ score: -60 }) === 'strong-sell', 'امتیاز -۶۰ → نزولی قوی');
  ok(S.categoryOf(null) === 'neutral', 'بدون امتیاز → خنثی');
  ok(S.CATEGORY_LABELS['strong-buy'] === 'صعودی قوی' && S.CATEGORY_LABELS['strong-sell'] === 'نزولی قوی', 'برچسب فارسی دسته‌ها');

  // مولد ضربی واقعی‌تر: بازده روزانه درصدی
  function gen(n, dailyPct, seed) {
    let p = 100; const out = []; let s = seed || 42;
    function rnd() { s = (s * 1103515245 + 12345) % 2147483648; return s / 2147483648; }
    const stepMs = 60 * 60 * 1000; const t0 = Date.now() - n * stepMs;
    const perCandle = dailyPct / 24;
    for (let i = 0; i < n; i++) {
      const o = p;
      const ret = perCandle / 100 + (rnd() - 0.5) * 0.008;
      const c = Math.max(0.0001, p * (1 + ret));
      const h = Math.max(o, c) * (1 + rnd() * 0.004);
      const l = Math.min(o, c) * (1 - rnd() * 0.004);
      out.push({ t: t0 + i * stepMs, o, h, l, c, v: 100 * (0.5 + rnd()) });
      p = c;
    }
    return out;
  }

  const upStrong = S.categoryFromAnalysis(TA.analyze(gen(400, 3, 71)));
  ok(upStrong === 'strong-buy', 'روند صعودی قوی (+۳٪ روزانه) → صعودی قوی (' + upStrong + ')');
  const upMild = S.categoryFromAnalysis(TA.analyze(gen(400, 0.5, 72)));
  ok(upMild === 'buy' || upMild === 'strong-buy', 'روند صعودی ملایم → صعودی (' + upMild + ')');
  const dnMild = S.categoryFromAnalysis(TA.analyze(gen(400, -0.5, 74)));
  ok(dnMild === 'sell' || dnMild === 'strong-sell', 'روند نزولی ملایم → نزولی (' + dnMild + ')');
  const dnStrong = S.categoryFromAnalysis(TA.analyze(gen(400, -3, 76)));
  ok(dnStrong === 'strong-sell', 'روند نزولی قوی (-۳٪ روزانه) → نزولی قوی (' + dnStrong + ')');
}

/* ============ لینک تریدینگ ویو ============ */
section('لینک تریدینگ ویو');
{
  const UI = require('../app/js/ui.js');
  // بدون watchlist → بایننس
  const btc = UI.tradingViewUrl({ id: 'bitcoin', sym: 'BTC', kraken: 'XBTUSD' });
  ok(btc.indexOf('symbol=BINANCE:BTCUSDT') >= 0, 'لینک بیت‌کوین → symbol=BINANCE:BTCUSDT (کولون خام، نه %3A) (' + btc + ')');
  ok(btc.indexOf("%3A") < 0, "کولون انکود نشده است (رفع باگ symbol error)");
  const sol = UI.tradingViewUrl({ id: 'solana', sym: 'SOL' });
  ok(sol.indexOf('symbol=BINANCE:SOLUSDT') >= 0, 'لینک سولانا → symbol=BINANCE:SOLUSDT');
  // RNDR تغییر نام داده → RENDER
  const rndr = UI.tradingViewUrl({ id: 'render-token', sym: 'RNDR' });
  ok(rndr.indexOf('BINANCE:RENDERUSDT') >= 0, 'لینک رندر → BINANCE:RENDERUSDT (تغییر نام) (' + rndr + ')');
  // MATIC تغییر نام داده → POL
  const matic = UI.tradingViewUrl({ id: 'matic-network', sym: 'MATIC' });
  ok(matic.indexOf('BINANCE:POLUSDT') >= 0, 'لینک پالیگان → BINANCE:POLUSDT (تغییر نام) (' + matic + ')');
  // با watchlist و منبع بای‌بیت
  global.Store = { get: () => [{ id: 'xcoin', sym: 'XCOIN', src: 'bybit' }] };
  const x = UI.tradingViewUrl({ id: 'xcoin', sym: 'XCOIN' });
  ok(x.indexOf('symbol=BYBIT:XCOINUSDT') >= 0, 'لینک با منبع بای‌بیت → BYBIT:XCOINUSDT (' + x + ')');
  delete global.Store;
  // کراکن با XBT
  global.Store = { get: () => [{ id: 'bitcoin', sym: 'BTC', src: 'kraken' }] };
  const kb = UI.tradingViewUrl({ id: 'bitcoin', sym: 'BTC', kraken: 'XBTUSD' });
  ok(kb.indexOf('symbol=KRAKEN:XBTUSD') >= 0, 'لینک کراکن → KRAKEN:XBTUSD (' + kb + ')');
  delete global.Store;
  ok(UI.tradingViewUrl(null).indexOf('tradingview.com') >= 0, 'سکه نامعتبر → صفحه اصلی تریدینگ ویو');
}

/* ============ جستجوی نماد واقعی تریدینگ ویو ============ */
section('resolveTradingViewSymbol (رفع خطای LIT)');
{
  const UI = require('../app/js/ui.js');
  const origFetch = global.fetch;

  // شبیه‌سازی پاسخ symbol-search تریدینگ ویو
  function mockSearch(hits) {
    global.fetch = () => Promise.resolve({
      ok: true,
      json: () => Promise.resolve(hits)
    });
  }

  // مورد LIT: بایننس فقط پرپچوال دارد، اسپات روی OKX/بای‌بیت
  mockSearch([
    { symbol: 'LITUSDT.P', description: 'Lighter / TetherUS PERPETUAL', exchange: 'BINANCE', type: 'crypto' },
    { symbol: 'LITUSDT', description: 'Lighter / TetherUS', exchange: 'OKX', type: 'crypto' },
    { symbol: 'LITUSDT', description: 'Lighter / TetherUS', exchange: 'BYBIT', type: 'crypto' },
    { symbol: 'LITE', description: 'Litecoin', exchange: 'NASDAQ', type: 'stock' }
  ]);
  UI.resolveTradingViewSymbol({ sym: 'LIT' }).then(r => {
    ok(r && r.exchange === 'OKX' && r.symbol === 'LITUSDT', 'LIT: اسپات OKX انتخاب شد (نه پرپچوال بایننس) (' + (r && r.exchange + ':' + r.symbol) + ')');
    ok(r && !r.perp, 'علامت پرپچوال false است');

    // فقط پرپچوال موجود است → همان را برمی‌گرداند
    mockSearch([
      { symbol: 'XYZUSDT.P', description: 'XYZ / TetherUS PERPETUAL', exchange: 'BINANCE', type: 'crypto' }
    ]);
    return UI.resolveTradingViewSymbol({ sym: 'XYZ' });
  }).then(r2 => {
    ok(r2 && r2.exchange === 'BINANCE' && r2.symbol === 'XYZUSDT.P', 'فقط پرپچوال → BINANCE:XYZUSDT.P (' + (r2 && r2.exchange + ':' + r2.symbol) + ')');
    ok(r2 && r2.perp, 'علامت پرپچوال true است');

    // هیچ نتیجه‌ای → null (fallback به لینک قبلی)
    mockSearch([]);
    return UI.resolveTradingViewSymbol({ sym: 'NOTHING' });
  }).then(r3 => {
    ok(r3 === null, 'بدون نتیجه → null (fallback)');

    // خطای شبکه → null
    global.fetch = () => Promise.reject(new Error('network'));
    return UI.resolveTradingViewSymbol({ sym: 'BTC' });
  }).then(r4 => {
    ok(r4 === null, 'خطای شبکه → null (fallback)');
    global.fetch = origFetch;
  });
}
