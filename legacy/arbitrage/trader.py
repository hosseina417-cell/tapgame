# -*- coding: utf-8 -*-
"""
اجرای معامله (تمرینی/واقعی).
ترتیب: اول خرید، بعد فروش. اگر فروش شکست خورد وضعیت PARTIAL ثبت می‌شود؛
هرگز وانمود به موفقیت نمی‌کنیم.
"""
import time

import exchanges
import store
from core import ApiError


def _rec(opp, buy_price, sell_price, profit, paper, status):
    r = {
        "t": time.time(), "symbol": opp.symbol,
        "buy_ex": opp.buy_ex, "sell_ex": opp.sell_ex,
        "amount": opp.amount, "buy_price": buy_price,
        "sell_price": sell_price, "profit": profit,
        "paper": paper, "status": status,
    }
    store.append_trade(r)
    return r


def execute(opp, paper):
    if paper:
        return _rec(opp, opp.buy_vwap, opp.sell_vwap, opp.net_profit, True, "OK")

    buy_ex = exchanges.by_name(opp.buy_ex)
    sell_ex = exchanges.by_name(opp.sell_ex)
    if not (buy_ex and sell_ex):
        return _rec(opp, 0, 0, 0, False, "FAILED: صرافی یافت نشد")
    if not (buy_ex.can_trade and sell_ex.can_trade):
        return _rec(opp, 0, 0, 0, False,
                    "FAILED: معاملهٔ خودکار فقط برای نوبیتکس و والکس فعال است")

    buy_key = store.get_api_key(buy_ex.name)
    sell_key = store.get_api_key(sell_ex.name)
    if not buy_key:
        return _rec(opp, 0, 0, 0, False, "FAILED: کلید API %s تنظیم نشده" % buy_ex.fa_name)
    if not sell_key:
        return _rec(opp, 0, 0, 0, False, "FAILED: کلید API %s تنظیم نشده" % sell_ex.fa_name)

    # limit تهاجمی تا مثل سفارش بازار سریع پر شود
    buy_price = opp.buy_vwap * 1.001
    sell_price = opp.sell_vwap * 0.999

    try:
        buy_ex.place_order(opp.symbol, "buy", opp.amount, buy_price, buy_key)
    except ApiError as e:
        return _rec(opp, 0, 0, 0, False, "FAILED: خرید ناموفق — %s" % str(e)[:120])

    try:
        sell_ex.place_order(opp.symbol, "sell", opp.amount, sell_price, sell_key)
        return _rec(opp, buy_price, sell_price, opp.net_profit, False, "OK")
    except ApiError as e:
        return _rec(opp, buy_price, 0, 0, False,
                    "PARTIAL: خرید انجام شد اما فروش شکست خورد — %s" % str(e)[:100])
