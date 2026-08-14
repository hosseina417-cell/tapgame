# -*- coding: utf-8 -*-
"""
پایشگر بازار: ترد پس‌زمینه که هر ۱۰ ثانیه دفترهای سفارش را موازی می‌گیرد،
فرصت‌ها را حساب می‌کند و در حالت خودکار (پس از عبور از قواعد ریسک) معامله می‌زند.
Kill Switch: متد stop() بلافاصله حلقه را قطع می‌کند.
"""
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import exchanges
import engine
import store
import trader


class Monitor(object):
    def __init__(self):
        self._thread = None
        self._stop = threading.Event()
        self.lock = threading.Lock()
        self.opportunities = []
        self.books = {}            # symbol -> [OrderBook]
        self.errors = {}           # exchange -> message
        self.last_cycle = 0.0
        self.status = "آماده"
        self.on_trade = None       # callback(rec)
        self.on_alert = None       # callback(opp)
        self._last_alert = 0.0

    @property
    def running(self):
        return self._thread is not None and self._thread.is_alive()

    def start(self):
        if self.running:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        self.status = "متوقف"

    def _loop(self):
        while not self._stop.is_set():
            try:
                self._cycle()
            except Exception as e:
                self.status = "خطا: %s" % str(e)[:80]
            self._stop.wait(10)

    def _fetch_one(self, ex, sym):
        try:
            b = ex.fetch_orderbook(sym)
            self.errors.pop(ex.name, None)
            return b
        except Exception as e:
            self.errors[ex.name] = str(e)[:80]
            return None

    def _cycle(self):
        cfg = store.load_risk()
        books = {}
        with ThreadPoolExecutor(max_workers=6) as pool:
            futures = [pool.submit(self._fetch_one, ex, sym)
                       for sym in exchanges.SYMBOLS
                       for ex in exchanges.EXCHANGES]
            for f in as_completed(futures, timeout=25):
                b = f.result()
                if b is not None:
                    books.setdefault(b.symbol, []).append(b)

        opps = engine.find_opportunities(books, cfg)
        with self.lock:
            self.books = books
            self.opportunities = opps[:20]
            self.last_cycle = time.time()

        best = opps[0] if opps else None
        if best:
            self.status = "بهترین فرصت: %s — %.2f٪ خالص" % (best.symbol, best.net_pct)
        else:
            n = sum(len(v) for v in books.values())
            self.status = "فرصتی بالای آستانه نیست (%d دفتر فعال)" % n

        if best and best.net_pct >= cfg.min_net_pct:
            now = time.time()
            if self.on_alert and now - self._last_alert > 60:
                self._last_alert = now
                try:
                    self.on_alert(best)
                except Exception:
                    pass
            if store.is_auto():
                reason = engine.risk_check(best, cfg, store.trades_today(),
                                           store.last_trade_at())
                if reason is None:
                    rec = trader.execute(best, store.is_paper())
                    if rec["status"] == "OK":
                        mode = "تمرینی" if rec["paper"] else "واقعی"
                        self.status = "✅ معاملهٔ {}: {:,.0f} تومان سود".format(mode, rec["profit"])
                    else:
                        self.status = "⚠️ %s" % rec["status"][:90]
                    if self.on_trade:
                        try:
                            self.on_trade(rec)
                        except Exception:
                            pass
                else:
                    self.status = "فرصت %.2f٪ — رد شد: %s" % (best.net_pct, reason)


MONITOR = Monitor()
