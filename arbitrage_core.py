# -*- coding: utf-8 -*-
"""
هسته برنامه آربیتراژ صرافی‌های داخل ایران
==========================================

این ماژول کاملاً مستقل از رابط کاربری (بدون kivy) نوشته شده تا بتونیم
منطق رو به‌صورت headless تست کنیم. رابط گرافیکی (main.py) این ماژول رو import می‌کنه.

مفاهیم کلیدی:
- هر صرافی برای یک نماد (مثل USDT/IRT) دو قیمت داره:
    * buy  (قیمت خرید تتر): کمترین قیمتِ «آسک» (شما تتر رو با این قیمت می‌خرید)
    * sell (قیمت فروش تتر): بیشترین قیمتِ «بید» (شما تتر رو با این قیمت می‌فروشید)
- آربیتراژ بین‌صرافی‌ای: تتر رو ارزان در صرافی A بخرید، به B منتقل کنید،
  گران در B بفروشید. سود خالص = (فروش − خرید) − کارمزدها − کارمزد انتقال.
"""

from __future__ import annotations

import json
import time
import threading
from dataclasses import dataclass, field, asdict
from typing import Callable, Optional

try:
    import requests
except Exception:  # pragma: no cover - در محیط‌های بدون requests
    requests = None


# ---------------------------------------------------------------------------
# تنظیمات پیش‌فرض (قابل تغییر از UI)
# ---------------------------------------------------------------------------

@dataclass
class Config:
    trade_amount: float = 1000.0          # مقدار معامله (واحد: تتر)
    transfer_fee_usdt: float = 1.5        # کارمزد شبکه انتقال تتر (TRC-20) بین صرافی‌ها
    min_spread_pct: float = 0.0           # حداقل حاشیه سود خالص (درصد) برای نمایش فرصت
    timeout: float = 8.0                  # تایم‌اوت درخواست (ثانیه)
    demo: bool = False                    # حالت دمو (دیتای نمونه، بدون اینترنت)
    include_market: bool = True           # مقایسه با نرخ دلار آزاد (tgju)


# ---------------------------------------------------------------------------
# ساختار خروجی هر صرافی
# ---------------------------------------------------------------------------

@dataclass
class Quote:
    name: str
    symbol: str
    buy: Optional[float] = None     # قیمت خرید تتر (آسک)
    sell: Optional[float] = None    # قیمت فروش تتر (بید)
    vol_buy: float = 0.0            # حجم در دسترس روی بهترین آسک (تتر)
    vol_sell: float = 0.0           # حجم در دسترس روی بهترین بید (تتر)
    fee_buy: float = 0.001          # کارمزد خرید (کسری)
    fee_sell: float = 0.001         # کارمزد فروش (کسری)
    ok: bool = False
    demo: bool = False
    error: str = ""
    ts: float = 0.0

    def to_dict(self):
        return asdict(self)


# ---------------------------------------------------------------------------
# کش ساده (آخرین دیتای موفق، برای کار آفلاین و جلوگیری از درخواست‌های تکراری)
# ---------------------------------------------------------------------------

class Cache:
    def __init__(self):
        self._data: dict[str, Quote] = {}
        self._lock = threading.Lock()

    def put(self, q: Quote):
        if q.ok:
            with self._lock:
                self._data[q.name] = q

    def get(self, name: str) -> Optional[Quote]:
        with self._lock:
            return self._data.get(name)

    def all(self):
        with self._lock:
            return list(self._data.values())


# ---------------------------------------------------------------------------
# آداپتورهای صرافی
# هر صرافی: نام، نماد، endpoint، تابع پارس، کارمزد پیش‌فرض، و دیتای دمو.
# کد طوری نوشته شده که حتی اگه ساختار JSON تغییر کنه یا endpoint مسدود باشه،
# برنامه کرش نکنه و به حالت دمو/کش برگرده.
# ---------------------------------------------------------------------------

def _num(x) -> Optional[float]:
    """تبدیل امن به float (مقادیر رشته‌ای/عددی/None)."""
    if x is None:
        return None
    try:
        return float(x)
    except (ValueError, TypeError):
        return None


def _first_ask(asks, price_key="price", amount_key="amount"):
    """کمترین آسک (قیمت خرید) رو برمی‌گردونه. فرمت‌های مختلف رو پشتیبانی می‌کنه."""
    if not asks:
        return None, 0.0
    try:
        if isinstance(asks[0], (list, tuple)):
            # فرمت نوبیتکس قدیمی: [[price, amount], ...]
            best = min(asks, key=lambda o: _num(o[0]) or 1e18)
            return _num(best[0]), _num(best[1]) or 0.0
        # فرمت آبجکت: {"price":.., "amount":..} یا {"price":.., "value":..}
        key_a = amount_key if amount_key in asks[0] else ("value" if "value" in asks[0] else amount_key)
        best = min(asks, key=lambda o: _num(o.get(price_key)) or 1e18)
        return _num(best.get(price_key)), _num(best.get(key_a)) or 0.0
    except Exception:
        return None, 0.0


def _first_bid(bids, price_key="price", amount_key="amount"):
    """بیشترین بید (قیمت فروش) رو برمی‌گردونه."""
    if not bids:
        return None, 0.0
    try:
        if isinstance(bids[0], (list, tuple)):
            best = max(bids, key=lambda o: _num(o[0]) or -1)
            return _num(best[0]), _num(best[1]) or 0.0
        key_a = amount_key if amount_key in bids[0] else ("value" if "value" in bids[0] else amount_key)
        best = max(bids, key=lambda o: _num(o.get(price_key)) or -1)
        return _num(best.get(price_key)), _num(best.get(key_a)) or 0.0
    except Exception:
        return None, 0.0


# تعاریف صرافی‌ها ------------------------------------------------------------

EXCHANGES = [
    {
        "name": "نوبیتکس",
        "symbol": "USDTIRT",
        "url": "https://api.nobitex.ir/v2/orderbook/USDTIRT",
        "fee_buy": 0.001, "fee_sell": 0.001,
        "demo": {"buy": 92850.0, "sell": 92600.0, "vol_buy": 8000, "vol_sell": 6500},
        "parse": lambda d: _nobitex(d),
    },
    {
        "name": "والکس",
        "symbol": "USDTIRT",
        "url": "https://api.wallex.ir/v1/orderbook/USDTIRT",
        "fee_buy": 0.002, "fee_sell": 0.002,
        "demo": {"buy": 93100.0, "sell": 92850.0, "vol_buy": 5200, "vol_sell": 4100},
        "parse": lambda d: _wallex(d),
    },
    {
        "name": "بیت‌پین",
        "symbol": "USDT_IRT",
        "url": "https://api.bitpin.ir/api/v1/mkt/orderbook/USDT_IRT/",
        "fee_buy": 0.001, "fee_sell": 0.001,
        "demo": {"buy": 92400.0, "sell": 92150.0, "vol_buy": 6100, "vol_sell": 5400},
        "parse": lambda d: _bitpin(d),
    },
    {
        "name": "اکسیر",
        "symbol": "usdt-irt",
        "url": "https://api.exir.io/v1/orderbook?symbol=usdt-irt",
        "fee_buy": 0.0025, "fee_sell": 0.0025,
        "demo": {"buy": 93300.0, "sell": 93000.0, "vol_buy": 3000, "vol_sell": 2500},
        "parse": lambda d: _exir(d),
    },
    {
        "name": "رمزینکس",
        "symbol": "usdt",
        "url": "https://ramzinex.com/exchange/api/v1.0/exchange/orderbooks/usdt",
        "fee_buy": 0.0035, "fee_sell": 0.0035,
        "demo": {"buy": 93500.0, "sell": 93200.0, "vol_buy": 2200, "vol_sell": 1900},
        "parse": lambda d: _ramzinex(d),
    },
    {
        "name": "تترلند",
        "symbol": "USDT",
        "url": "https://api.tetherland.com/exchange/api/v1/orderbook/USDT",
        "fee_buy": 0.002, "fee_sell": 0.002,
        "demo": {"buy": 92950.0, "sell": 92700.0, "vol_buy": 4000, "vol_sell": 3500},
        "parse": lambda d: _tetherland(d),
    },
]


def _nobitex(d):
    asks = d.get("asks") or []
    bids = d.get("bids") or []
    buy, vb = _first_ask(asks)
    sell, vs = _first_bid(bids)
    return buy, sell, vb, vs


def _wallex(d):
    # والکس گاهی در results قرار می‌ده
    result = d.get("result") or d
    asks = result.get("asks") or result.get("orderbook", {}).get("asks") or []
    bids = result.get("bids") or result.get("orderbook", {}).get("bids") or []
    buy, vb = _first_ask(asks)
    sell, vs = _first_bid(bids)
    return buy, sell, vb, vs


def _bitpin(d):
    asks = d.get("asks") or []
    bids = d.get("bids") or []
    buy, vb = _first_ask(asks, amount_key="value")
    sell, vs = _first_bid(bids, amount_key="value")
    return buy, sell, vb, vs


def _exir(d):
    asks = d.get("asks") or []
    bids = d.get("bids") or []
    # اکسیر آسک/بید رو معکوس داره: asks = فروشنده (شما می‌خرید)، bids = خریدار (شما می‌فروشید)
    buy, vb = _first_ask(asks)
    sell, vs = _first_bid(bids)
    return buy, sell, vb, vs


def _ramzinex(d):
    ob = d.get("data") or d
    buy_entries = ob.get("buy") or ob.get("bids") or []
    sell_entries = ob.get("sell") or ob.get("asks") or []
    buy, vb = _first_bid(buy_entries)   # در رمزینکس لیست buy قیمت‌های خریداره (بالاترین=فروش شما)
    sell, vs = _first_ask(sell_entries) # لیست sell قیمت‌های فروشنده‌ست (کمترین=خرید شما)
    return sell, buy, vs, vb


def _tetherland(d):
    asks = d.get("asks") or d.get("data", {}).get("asks") or []
    bids = d.get("bids") or d.get("data", {}).get("bids") or []
    buy, vb = _first_ask(asks)
    sell, vs = _first_bid(bids)
    return buy, sell, vb, vs


# ---------------------------------------------------------------------------
# دریافت قیمت‌ها
# ---------------------------------------------------------------------------

def _fetch_one(cfg: Config, ex: dict, cache: Cache) -> Quote:
    name = ex["name"]
    q = Quote(name=name, symbol=ex["symbol"], fee_buy=ex["fee_buy"],
              fee_sell=ex["fee_sell"], ts=time.time())

    if cfg.demo:
        d = ex["demo"]
        q.buy, q.sell, q.vol_buy, q.vol_sell = d["buy"], d["sell"], d["vol_buy"], d["vol_sell"]
        q.ok = True
        q.demo = True
        return q

    if requests is None:
        cached = cache.get(name)
        if cached:
            return cached
        q.error = "کتابخانه requests در دسترس نیست"
        return q

    try:
        r = requests.get(ex["url"], timeout=cfg.timeout, headers={"User-Agent": "Mozilla/5.0"})
        if r.status_code != 200:
            raise RuntimeError(f"HTTP {r.status_code}")
        data = r.json()
        buy, sell, vb, vs = ex["parse"](data)
        if buy is None or sell is None:
            raise RuntimeError("ساختار پاسخ نامشخص")
        q.buy, q.sell, q.vol_buy, q.vol_sell = buy, sell, vb, vs
        q.ok = True
    except Exception as e:
        cached = cache.get(name)
        if cached and cached.ok:
            cached.demo = False
            cached.error = f"آفلاین (کش): {e}"
            return cached
        q.error = str(e)
        q.ok = False
    return q


def fetch_all(cfg: Config, cache: Optional[Cache] = None,
              progress: Optional[Callable[[int, int, str], None]] = None) -> list[Quote]:
    """تمام صرافی‌ها رو fetch می‌کنه (می‌تونه در ترد جدا صدا زده بشه)."""
    if cache is None:
        cache = Cache()
    quotes: list[Quote] = []
    total = len(EXCHANGES)
    for i, ex in enumerate(EXCHANGES):
        q = _fetch_one(cfg, ex, cache)
        cache.put(q)
        quotes.append(q)
        if progress:
            progress(i + 1, total, q.name)
    return quotes


# ---------------------------------------------------------------------------
# نرخ دلار آزاد (tgju) - برای نمایش حباب/تخفیف تتر نسبت به دلار
# ---------------------------------------------------------------------------

def fetch_usd_market(cfg: Config, cache: Cache) -> Optional[float]:
    if not cfg.include_market:
        return None
    if cfg.demo:
        return 92000.0
    if requests is None:
        return None
    try:
        r = requests.get("https://api.tgju.org/v1/data/price/json",
                         timeout=cfg.timeout, headers={"User-Agent": "Mozilla/5.0"})
        if r.status_code != 200:
            return None
        arr = r.json()
        for item in arr:
            label = (item.get("label") or "")
            slug = (item.get("slug") or "")
            if "دلار" in label or slug in ("akharin-gheymat-dolar", "sana-sepolia-sell"):
                price = _num(item.get("price"))
                if price:
                    return price
        return None
    except Exception:
        return None


# ---------------------------------------------------------------------------
# موتور آربیتراژ
# ---------------------------------------------------------------------------

@dataclass
class Opportunity:
    buy_ex: str
    sell_ex: str
    buy_price: float
    sell_price: float
    amount: float
    gross_irt: float
    fee_irt: float
    transfer_irt: float
    net_irt: float
    spread_pct: float
    net_pct: float
    feasible: bool


def compute_arbitrage(quotes: list[Quote], cfg: Config) -> list[Opportunity]:
    """تمام جفت‌های (خرید در A، فروش در B) رو بررسی می‌کنه."""
    valid = [q for q in quotes if q.ok and q.buy and q.sell]
    ops: list[Opportunity] = []
    amount = cfg.trade_amount

    for a in valid:
        for b in valid:
            if a.name == b.name:
                continue
            # معیار امکان‌پذیری حجم: مقدار معامله نباید از بهترین سطح عمق بیشتر باشه
            feasible_vol = amount <= min(a.vol_buy, b.vol_sell) if (a.vol_buy and b.vol_sell) else True

            buy_cost = amount * a.buy
            sell_rev = amount * b.sell
            buy_fee = buy_cost * a.fee_buy
            sell_fee = sell_rev * b.fee_sell
            transfer_irt = cfg.transfer_fee_usdt * ((a.buy + b.sell) / 2.0)
            gross = sell_rev - buy_cost
            fees = buy_fee + sell_fee + transfer_irt
            net = gross - fees

            spread_pct = (b.sell - a.buy) / a.buy * 100.0 if a.buy else 0.0
            net_pct = net / buy_cost * 100.0 if buy_cost else 0.0

            ops.append(Opportunity(
                buy_ex=a.name, sell_ex=b.name,
                buy_price=a.buy, sell_price=b.sell,
                amount=amount, gross_irt=gross, fee_irt=fees - transfer_irt,
                transfer_irt=transfer_irt, net_irt=net,
                spread_pct=spread_pct, net_pct=net_pct,
                feasible=bool(feasible_vol),
            ))

    # مرتب‌سازی بر اساس سود خالص نزولی
    ops.sort(key=lambda o: o.net_irt, reverse=True)
    # فیلتر حداقل حاشیه
    ops = [o for o in ops if o.net_pct >= cfg.min_spread_pct]
    return ops


def compute_summary(quotes: list[Quote], usd_market: Optional[float]):
    """خلاصه: بهترین قیمت خرید/فروش کل بازار + حباب تتر نسبت به دلار."""
    valid = [q for q in quotes if q.ok and q.buy and q.sell]
    if not valid:
        return None
    best_buy = min(valid, key=lambda q: q.buy)
    best_sell = max(valid, key=lambda q: q.sell)
    avg = sum((q.buy + q.sell) / 2 for q in valid) / len(valid)
    premium = None
    if usd_market:
        premium = (avg - usd_market) / usd_market * 100.0
    return {
        "best_buy_ex": best_buy.name, "best_buy": best_buy.buy,
        "best_sell_ex": best_sell.name, "best_sell": best_sell.sell,
        "avg": avg, "usd_market": usd_market, "premium_pct": premium,
        "n_exchanges": len(valid),
    }


# ---------------------------------------------------------------------------
# تست headless (وقتی فایل مستقیم اجرا بشه)
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    cfg = Config(demo=True)
    cache = Cache()
    qs = fetch_all(cfg, cache)
    print("=== قیمت‌ها (دمو) ===")
    for q in qs:
        print(f"  {q.name}: خرید={q.buy:,.0f}  فروش={q.sell:,.0f}  حجمخرید={q.vol_buy:.0f}")

    usd = fetch_usd_market(cfg, cache)
    summ = compute_summary(qs, usd)
    print("\n=== خلاصه ===")
    print(json.dumps(summ, ensure_ascii=False, indent=2))

    print("\n=== فرصت‌های آربیتراژ (۵ تا برتر) ===")
    ops = compute_arbitrage(qs, cfg)
    for o in ops[:5]:
        print(f"  بخر {o.buy_ex} ({o.buy_price:,.0f}) → بفروش {o.sell_ex} ({o.sell_price:,.0f}) | "
              f"سود خالص={o.net_irt:,.0f} تومان ({o.net_pct:.2f}%) | حجمOK={o.feasible}")
