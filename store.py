# -*- coding: utf-8 -*-
"""ذخیرهٔ تنظیمات و کانفیگ‌های ذخیره‌شده"""
import json
import os

def data_dir():
    try:
        from kivy.app import App
        app = App.get_running_app()
        if app is not None:
            return app.user_data_dir
    except Exception:
        pass
    d = os.path.join(os.path.expanduser("~"), ".v2rayfinder")
    os.makedirs(d, exist_ok=True)
    return d

def _path(name):
    return os.path.join(data_dir(), name)

def load_json(name, default=None):
    p = _path(name)
    if not os.path.exists(p):
        return default if default is not None else {}
    try:
        with open(p, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default if default is not None else {}

def save_json(name, data):
    p = _path(name)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

def load_configs():
    return load_json("configs.json", [])

def save_configs(cfgs):
    save_json("configs.json", cfgs)

def load_settings():
    return load_json("settings.json", {
        "channels": "",
        "timeout": 5,
        "auto_check": True,
        "theme": "dark",
    })

def save_settings(s):
    save_json("settings.json", s)
