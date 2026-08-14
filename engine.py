# -*- coding: utf-8 -*-
"""
موتور آربیتراژ:
1) VWAP عمقی به‌اندازهٔ بودجه (نه فقط سطح اول دفتر)
2) پاک‌سازی دیتا: sanity check مقیاس با میانهٔ بین‌صرافی
3) کسر کارمزد دو سمت + حاشیهٔ لغزش
4) قواعد ریسک: حداقل سود، سقف روزانه، cooldown، سقف کهنگی دیتا

مدل معامله: موجودی دوطرفه از قبل (تومان در صرافی خرید، ارز در صرافی فروش)
→ خرید و فروش هم‌زمان، بدون نیاز به انتقال ارز بین صرافی‌ها.
"""
import time

import exchanges


class RiskConfig(object):
    def __init__(self, budget_toman=50_000_000.0, min_net_pct=0.3,
                 slippage_pct=0.05, max_trades_per_day=10,
                 cooldown_sec=120, max_book_age=15.0):
        self.budget_toman = budget_toman
        self.min_net_pct = min_net_pct
        self.slippage_pct = slippage_pct
        self.max_trades_per_day = max_trades_per_day
        self.cooldown_sec = cooldown_sec
        self.max_book_age = max_book_age


class Opportunity(object):
    __slots__ = ("symbol", "buy_ex", "sell_ex", "buy_vwap", "sell_vwap",
                 "amount", "gross_pct", "net_pct", "net_profit", "time")

    def __init__(self, symbol, buy_ex, sell_ex, buy_vwap, sell_vwap,
                 amount, gross_pct, net_pct, net_profit):
        self.symbol = symbol
        self.buy_ex = buy_ex
        self.sell_ex = sell_ex
        self.buy_vwap = buy_vwap
        self.sell_vwap = sell_vwap
        self.amount = amount
        self.gross_pct = gross_pct
        self.net_pct = net_pct
        self.net_profit = net_profit
        self.time = time.time()


def buy_vwap(asks, budget):
    """با این بودجه چقدر ارز از askها می‌گیریم؟ → (vwap, amount) یا None"""
    remaining, total_amt, total_cost = budget, 0.0, 0.0
    for price, amount in asks:
        if remaining <= 0:
            break
        cost = price * amount
        used = min(cost, remaining)
        total_amt += used / price
        total_cost += used
        remaining -= used
    if remaining > budget * 0.01 or total_amt <= 0:
        return None  # عمق کافی نیست
    return total_cost / total_amt, total_amt


def sell_vwap(bids, amount):
    """فروش amount ارز روی bidها → vwap یا None"""
    remaining, revenue = amount, 0.0
    for price, avail in bids:
        if remaining <= 0:
            break
        take = min(avail, remaining)
        revenue += take * price
        remaining -= take
    if remaining > amount * 0.01:
        return None
    return revenue / amount


def sanitize(books):
    """حذف دفترهای فاسد یا با مقیاس غلط (ریال/تومان) نسبت به میانهٔ بین‌صرافی"""
    valid = [b for b in books
             if b.best_bid > 0 and b.best_ask > 0 and b.best_ask >= b.best_bid * 0.5]
    if len(valid) < 2:
        return valid
    mids = sorted((b.best_bid + b.best_ask) / 2 for b in valid)
    median = mids[len(mids) // 2]
    return [b for b in valid
            if median / 5 < (b.best_bid + b.best_ask) / 2 < median * 5]


def find_opportunities(books_by_symbol, cfg):
    out = []
    now = time.time()
    for symbol, raw in books_by_symbol.items():
        books = sanitize([b for b in raw if now - b.fetched_at <= cfg.max_book_age])
        for buy_book in books:
            for sell_book in books:
                if buy_book.exchange == sell_book.exchange:
                    continue
                bv = buy_vwap(buy_book.asks, cfg.budget_toman)
                if not bv:
                    continue
                b_vwap, amount = bv
                s_vwap = sell_vwap(sell_book.bids, amount)
                if not s_vwap:
                    continue
                buy_fee = getattr(exchanges.by_name(buy_book.exchange), "taker_fee", 0.003)
                sell_fee = getattr(exchanges.by_name(sell_book.exchange), "taker_fee", 0.003)
                gross = (s_vwap - b_vwap) / b_vwap * 100
                net = gross - (buy_fee + sell_fee) * 100 - cfg.slippage_pct
                if net <= 0:
                    continue
                out.append(Opportunity(
                    symbol, buy_book.exchange, sell_book.exchange,
                    b_vwap, s_vwap, amount,
                    round(gross, 2), round(net, 2),
                    cfg.budget_toman * net / 100))
    out.sort(key=lambda o: -o.net_pct)
    return out


def risk_check(opp, cfg, trades_today, last_trade_at):
    """None یعنی مجاز؛ در غیر این صورت دلیل رد."""
    if opp.net_pct < cfg.min_net_pct:
        return "سود خالص %.2f٪ کمتر از آستانه %.2f٪" % (opp.net_pct, cfg.min_net_pct)
    if trades_today >= cfg.max_trades_per_day:
        return "سقف %d معامله در روز پر شده" % cfg.max_trades_per_day
    since = time.time() - last_trade_at
    if last_trade_at > 0 and since < cfg.cooldown_sec:
        return "فاصلهٔ ایمنی: %d ثانیه دیگر" % (cfg.cooldown_sec - since)
    if abs(opp.gross_pct) > 10:
        return "اختلاف %.1f٪ غیرعادی است؛ احتمال خطای دیتا" % opp.gross_pct
    return None
