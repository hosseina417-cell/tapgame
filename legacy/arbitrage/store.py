# -*- coding: utf-8 -*-
"""
ذخیره‌سازی محلی (JsonStore کیوی):
- تنظیمات و ریسک
- کلیدهای API (به‌صورت obfuscate شده با XOR + شناسهٔ نصب؛ توجه: امنیت مطلق
  در پایتون موبایل ممکن نیست — به کاربر توصیه می‌شود دسترسی برداشت را
  در پنل صرافی غیرفعال کند)
- تاریخچهٔ معاملات (۲۰۰ رکورد آخر)
"""
import base64
import os
import time
import uuid

from kivy.storage.jsonstore import JsonStore
from kivy.app import App

from engine import RiskConfig

_store = None


def store():
    global _store
    if _store is None:
        app = App.get_running_app()
        base = app.user_data_dir if app else "."
        _store = JsonStore(os.path.join(base, "arb_store.json"))
    return _store


def _device_key():
    s = store()
    if not s.exists("device"):
        s.put("device", id=uuid.uuid4().hex)
    return s.get("device")["id"]


def _xor(data, key):
    kb = key.encode("utf-8")
    return bytes(b ^ kb[i % len(kb)] for i, b in enumerate(data))


def save_api_key(exchange, key):
    s = store()
    if not key:
        if s.exists("key_" + exchange):
            s.delete("key_" + exchange)
        return
    enc = base64.b64encode(_xor(key.encode("utf-8"), _device_key())).decode("ascii")
    s.put("key_" + exchange, v=enc)


def get_api_key(exchange):
    s = store()
    if not s.exists("key_" + exchange):
        return ""
    try:
        raw = base64.b64decode(s.get("key_" + exchange)["v"])
        return _xor(raw, _device_key()).decode("utf-8")
    except Exception:
        return ""


def save_settings(paper=None, auto=None):
    s = store()
    cur = s.get("settings") if s.exists("settings") else {"paper": True, "auto": False}
    if paper is not None:
        cur["paper"] = paper
    if auto is not None:
        cur["auto"] = auto
    s.put("settings", **cur)


def is_paper():
    s = store()
    return s.get("settings")["paper"] if s.exists("settings") else True


def is_auto():
    s = store()
    return s.get("settings")["auto"] if s.exists("settings") else False


def save_risk(cfg):
    store().put("risk", budget=cfg.budget_toman, min_net=cfg.min_net_pct,
                max_trades=cfg.max_trades_per_day)


def load_risk():
    s = store()
    if not s.exists("risk"):
        return RiskConfig()
    r = s.get("risk")
    return RiskConfig(budget_toman=float(r.get("budget", 50_000_000)),
                      min_net_pct=float(r.get("min_net", 0.3)),
                      max_trades_per_day=int(r.get("max_trades", 10)))


def append_trade(rec):
    """rec: dict(t, symbol, buy_ex, sell_ex, amount, buy_price, sell_price, profit, paper, status)"""
    s = store()
    hist = s.get("history")["items"] if s.exists("history") else []
    hist.append(rec)
    s.put("history", items=hist[-200:])


def load_trades():
    s = store()
    items = s.get("history")["items"] if s.exists("history") else []
    return list(reversed(items))


def trades_today():
    day_start = time.time() // 86400 * 86400
    return sum(1 for t in load_trades()
               if t["t"] >= day_start and t["status"] == "OK")


def last_trade_at():
    tr = load_trades()
    return tr[0]["t"] if tr else 0.0
