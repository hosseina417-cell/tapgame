# -*- coding: utf-8 -*-
"""
هستهٔ مشترک: شبکه + مدل‌ها
همهٔ قیمت‌ها داخل موتور به «تومان» نرمال می‌شوند.
"""
import json
import ssl
import time
import urllib.request
import urllib.error

try:
    import certifi
    _SSL_CTX = ssl.create_default_context(cafile=certifi.where())
except Exception:
    _SSL_CTX = ssl.create_default_context()

UA = "ArbitragePro/5.0 (Android; Kivy)"


class ApiError(Exception):
    pass


def http_get(url, headers=None, timeout=8):
    req = urllib.request.Request(url, headers=dict({"User-Agent": UA}, **(headers or {})))
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_SSL_CTX) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = ""
        try:
            body = e.read().decode("utf-8")[:200]
        except Exception:
            pass
        raise ApiError("HTTP %s: %s" % (e.code, body))
    except Exception as e:
        raise ApiError(str(e)[:150])


def http_post(url, payload, headers=None, timeout=10):
    data = json.dumps(payload).encode("utf-8")
    h = {"User-Agent": UA, "Content-Type": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_SSL_CTX) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = ""
        try:
            body = e.read().decode("utf-8")[:200]
        except Exception:
            pass
        raise ApiError("HTTP %s: %s" % (e.code, body))
    except Exception as e:
        raise ApiError(str(e)[:150])


def parse_levels(rows, scale=1.0, price_key=None, amount_key=None):
    """[[price, amount], ...] یا [{'price':..,'quantity':..}] → [(price_toman, amount)]"""
    out = []
    for r in rows or []:
        try:
            if price_key:
                p, a = float(r[price_key]), float(r[amount_key])
            else:
                p, a = float(r[0]), float(r[1])
            if p > 0 and a > 0:
                out.append((p * scale, a))
        except Exception:
            continue
    return out


class OrderBook(object):
    __slots__ = ("exchange", "symbol", "bids", "asks", "fetched_at")

    def __init__(self, exchange, symbol, bids, asks):
        self.exchange = exchange
        self.symbol = symbol
        # bids نزولی، asks صعودی
        self.bids = sorted(bids, key=lambda x: -x[0])
        self.asks = sorted(asks, key=lambda x: x[0])
        self.fetched_at = time.time()

    @property
    def best_bid(self):
        return self.bids[0][0] if self.bids else 0.0

    @property
    def best_ask(self):
        return self.asks[0][0] if self.asks else 0.0
