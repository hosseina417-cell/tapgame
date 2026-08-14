# -*- coding: utf-8 -*-
"""
کانکتورهای صرافی‌های ایرانی — خروجی همه بر حسب «تومان».
- نوبیتکس و رمزینکس: قیمت ریالی → ÷۱۰
- والکس و بیت‌پین: تومان
"""
from core import http_get, http_post, parse_levels, OrderBook, ApiError

SYMBOLS = ["USDT", "BTC", "ETH", "TRX", "DOGE"]


class Exchange(object):
    name = ""
    fa_name = ""
    taker_fee = 0.003
    can_trade = False

    def fetch_orderbook(self, symbol):
        raise NotImplementedError

    def place_order(self, symbol, side, amount, price_toman, api_key):
        raise ApiError("معاملهٔ خودکار %s فعال نیست" % self.fa_name)


class Nobitex(Exchange):
    name = "nobitex"
    fa_name = "نوبیتکس"
    taker_fee = 0.0025
    can_trade = True

    def fetch_orderbook(self, symbol):
        d = http_get("https://api.nobitex.ir/v3/orderbook/%sIRT" % symbol)
        if d.get("status") != "ok":
            raise ApiError("نوبیتکس: پاسخ نامعتبر")
        return OrderBook(self.name, symbol,
                         parse_levels(d.get("bids"), scale=0.1),
                         parse_levels(d.get("asks"), scale=0.1))

    def place_order(self, symbol, side, amount, price_toman, api_key):
        d = http_post(
            "https://api.nobitex.ir/market/orders/add",
            {
                "type": side,                       # buy / sell
                "execution": "limit",
                "srcCurrency": symbol.lower(),
                "dstCurrency": "rls",
                "amount": str(amount),
                "price": int(price_toman * 10),     # تومان → ریال
            },
            headers={"Authorization": "Token " + api_key},
        )
        if d.get("status") != "ok":
            raise ApiError("نوبیتکس: %s" % str(d.get("message", d))[:150])
        return str((d.get("order") or {}).get("id", "?"))


class Wallex(Exchange):
    name = "wallex"
    fa_name = "والکس"
    taker_fee = 0.0025
    can_trade = True

    def fetch_orderbook(self, symbol):
        d = http_get("https://api.wallex.ir/v1/depth?symbol=%sTMN" % symbol)
        if not d.get("success"):
            raise ApiError("والکس: %s" % str(d.get("message", ""))[:100])
        res = d.get("result") or {}
        return OrderBook(self.name, symbol,
                         parse_levels(res.get("bid"), price_key="price", amount_key="quantity"),
                         parse_levels(res.get("ask"), price_key="price", amount_key="quantity"))

    def place_order(self, symbol, side, amount, price_toman, api_key):
        d = http_post(
            "https://api.wallex.ir/v1/account/orders",
            {
                "symbol": "%sTMN" % symbol,
                "type": "LIMIT",
                "side": side.upper(),
                "price": str(price_toman),
                "quantity": str(amount),
            },
            headers={"x-api-key": api_key},
        )
        if not d.get("success"):
            raise ApiError("والکس: %s" % str(d.get("message", d))[:150])
        return str((d.get("result") or {}).get("clientOrderId", "?"))


class Bitpin(Exchange):
    name = "bitpin"
    fa_name = "بیت‌پین"
    taker_fee = 0.002

    def fetch_orderbook(self, symbol):
        d = http_get("https://api.bitpin.ir/api/v1/mth/orderbook/%s_IRT/" % symbol)
        bids = parse_levels(d.get("bids"))
        asks = parse_levels(d.get("asks"))
        if not bids and not asks:
            raise ApiError("بیت‌پین: دفتر سفارش خالی")
        return OrderBook(self.name, symbol, bids, asks)


class Ramzinex(Exchange):
    name = "ramzinex"
    fa_name = "رمزینکس"
    taker_fee = 0.0035
    PAIR_IDS = {"USDT": 11, "BTC": 2, "ETH": 7, "TRX": 20, "DOGE": 61}

    def fetch_orderbook(self, symbol):
        pid = self.PAIR_IDS.get(symbol)
        if pid is None:
            raise ApiError("رمزینکس: %s پشتیبانی نمی‌شود" % symbol)
        d = http_get(
            "https://publicapi.ramzinex.com/exchange/api/v1.0/exchange/orderbooks/%d/buys_sells" % pid)
        data = d.get("data") or {}
        # قیمت‌ها ریالی‌اند
        bids = parse_levels(data.get("buys"), scale=0.1)
        asks = parse_levels(data.get("sells"), scale=0.1)
        if not bids and not asks:
            raise ApiError("رمزینکس: دفتر سفارش خالی")
        return OrderBook(self.name, symbol, bids, asks)


EXCHANGES = [Nobitex(), Wallex(), Bitpin(), Ramzinex()]


def by_name(n):
    for e in EXCHANGES:
        if e.name == n:
            return e
    return None
