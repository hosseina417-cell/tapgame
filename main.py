# -*- coding: utf-8 -*-
"""V2Ray Config Finder & Generator — اندروید"""
import os
import sys
import time
import threading

from kivy.app import App
from kivy.clock import Clock, mainthread
from kivy.core.text import LabelBase
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.scrollview import ScrollView
from kivy.uix.spinner import Spinner
from kivy.uix.textinput import TextInput
from kivy.uix.togglebutton import ToggleButton
from kivy.utils import get_color_from_hex as hexc

import core
import search
import store

FONT = "Roboto"
_here = os.path.dirname(os.path.abspath(__file__))
_font_path = os.path.join(_here, "assets", "Vazirmatn-Regular.ttf")
_font_bold = os.path.join(_here, "assets", "Vazirmatn-Bold.ttf")
if os.path.exists(_font_path):
    LabelBase.register(name="Vazir", fn_regular=_font_path,
                       fn_bold=_font_bold if os.path.exists(_font_bold) else _font_path)
    FONT = "Vazir"

try:
    import arabic_reshaper
    from bidi.algorithm import get_display
    _reshaper = arabic_reshaper.ArabicReshaper()
    def fa(text):
        try:
            return get_display(_reshaper.reshape(str(text)))
        except Exception:
            return str(text)
except Exception:
    def fa(text):
        return str(text)

# Palette
BG = hexc("#0B1020")
CARD = hexc("#151B2E")
CARD2 = hexc("#1E2640")
GREEN = hexc("#22C55E")
RED = hexc("#EF4444")
YELLOW = hexc("#F59E0B")
BLUE = hexc("#3B82F6")
FG = hexc("#E6EDF3")
DIM = hexc("#8B949E")
ACCENT = hexc("#6366F1")

try:
    Window.clearcolor = BG
except Exception:
    pass


def flabel(text, color=FG, size=14, bold=False, halign="right", h=None):
    lb = Label(text=fa(text), font_name=FONT, color=color,
               font_size=dp(size), bold=bold, halign=halign, valign="middle",
               size_hint_y=None, markup=False)
    lb.bind(width=lambda s, w: setattr(s, "text_size", (w, None)))
    lb.bind(texture_size=lambda s, ts: setattr(s, "height", max(ts[1] + dp(4), dp(h or 0))))
    return lb


class Card(BoxLayout):
    def __init__(self, **kw):
        kw.setdefault("orientation", "vertical")
        kw.setdefault("size_hint_y", None)
        kw.setdefault("padding", [dp(12), dp(8)])
        kw.setdefault("spacing", dp(4))
        super().__init__(**kw)
        from kivy.graphics import Color, RoundedRectangle
        with self.canvas.before:
            Color(rgba=CARD)
            self._bg = RoundedRectangle(radius=[dp(10)])
        self.bind(pos=self._sync, size=self._sync)
        self.bind(minimum_height=self.setter("height"))

    def _sync(self, *a):
        self._bg.pos = self.pos
        self._bg.size = self.size


class Btn(Button):
    def __init__(self, **kw):
        kw.setdefault("font_name", FONT)
        kw.setdefault("font_size", dp(14))
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(44))
        super().__init__(**kw)
        self.bind(text=lambda s, t: setattr(s, "text", fa(t)))


class Pill(ToggleButton):
    def __init__(self, **kw):
        kw.setdefault("font_name", FONT)
        kw.setdefault("font_size", dp(12))
        kw.setdefault("size_hint", (None, None))
        kw.setdefault("height", dp(32))
        kw.setdefault("width", dp(80))
        super().__init__(**kw)
        self.bind(text=lambda s, t: setattr(s, "text", fa(t)))


def toast(msg):
    try:
        App.get_running_app().show_toast(msg)
    except Exception:
        pass


class ToastPopup(Popup):
    def __init__(self, msg, **kw):
        super().__init__(title="", size_hint=(0.7, None), height=dp(60), auto_dismiss=True, **kw)
        self.content = flabel(msg, color=FG, size=13)
        Clock.schedule_once(lambda dt: self.dismiss(), 2.0)


class BaseScreen(BoxLayout):
    def __init__(self, app, **kw):
        super().__init__(orientation="vertical", **kw)
        self.app = app


# ---------- Generator Screen ----------
class GeneratorScreen(BaseScreen):
    def __init__(self, app, **kw):
        super().__init__(app, **kw)
        self.build_ui()

    def build_ui(self):
        self.clear_widgets()
        scroll = ScrollView()
        box = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(8), padding=dp(8))
        box.bind(minimum_height=box.setter("height"))

        box.add_widget(flabel("تولید کانفیگ V2Ray", color=FG, size=18, bold=True))

        # protocol
        row = BoxLayout(size_hint_y=None, height=dp(40), spacing=dp(6))
        row.add_widget(flabel("پروتکل:", size=13, h=dp(40)))
        self.proto = Spinner(text="vmess", values=core.PROTOCOLS, size_hint=(0.6, None), height=dp(40), font_name=FONT)
        self.proto.bind(text=self._on_proto)
        Clock.schedule_once(lambda dt: self._toggle_fields(self.proto.text), 0)
        row.add_widget(self.proto)
        box.add_widget(row)

        # fields
        self.fields = {}
        self.field_rows = {}
        def add_field(key, label, default="", password=False):
            r = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(6))
            r.add_widget(flabel(label, size=12, h=dp(44)))
            ti = TextInput(text=str(default), password=password, multiline=False, font_name=FONT, size_hint=(0.65, None), height=dp(40))
            r.add_widget(ti)
            self.fields[key] = ti
            self.field_rows[key] = r
            box.add_widget(r)

        add_field("remark", "نام (remark)", "MyConfig")
        add_field("address", "آدرس سرور", "example.com")
        add_field("port", "پورت", "443")
        add_field("uuid", "UUID / ID", core.new_uuid())
        add_field("password", "رمز (trojan/ss)", "password")
        add_field("alter_id", "AlterID", "0")
        add_field("security", "امنیت (vmess)", "auto")
        add_field("network", "شبکه", "tcp")
        add_field("tls", "TLS", "tls")
        add_field("sni", "SNI", "")
        add_field("host", "Host", "")
        add_field("path", "Path", "/")
        add_field("flow", "Flow (vless)", "xtls-rprx-vision")
        add_field("method", "Method (ss)", "aes-256-gcm")
        add_field("public_key", "Public Key (reality)", "")
        add_field("short_id", "Short ID", "")
        add_field("service_name", "Service Name (grpc)", "")
        add_field("fingerprint", "Fingerprint", "chrome")
        add_field("alpn", "ALPN", "h2,http/1.1")

        # buttons
        btnrow = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(6))
        b1 = Btn(text="تولید لینک", background_color=ACCENT)
        b1.bind(on_press=self.generate)
        b2 = Btn(text="UUID جدید", background_color=CARD2)
        b2.bind(on_press=lambda x: self._set("uuid", core.new_uuid()))
        btnrow.add_widget(b1)
        btnrow.add_widget(b2)
        box.add_widget(btnrow)

        self.result = TextInput(text="", readonly=True, font_name=FONT, size_hint_y=None, height=dp(80), multiline=True)
        box.add_widget(flabel("نتیجه (لینک):", size=12))
        box.add_widget(self.result)

        btnrow2 = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(6))
        bimport = Btn(text="وارد کردن", background_color=CARD2)
        bimport.bind(on_press=self.import_clipboard)
        bcopy = Btn(text="کپی", background_color=GREEN)
        bcopy.bind(on_press=self.copy_link)
        bjson = Btn(text="JSON کلاینت", background_color=BLUE)
        bjson.bind(on_press=self.show_json)
        bsave = Btn(text="ذخیره", background_color=YELLOW)
        bsave.bind(on_press=self.save_config)
        btnrow2.add_widget(bimport)
        btnrow2.add_widget(bcopy)
        btnrow2.add_widget(bjson)
        btnrow2.add_widget(bsave)
        box.add_widget(btnrow2)

        scroll.add_widget(box)
        self.add_widget(scroll)

    def _on_proto(self, spinner, text):
        self._toggle_fields(text)

    def _toggle_fields(self, proto):
        # which fields relevant per protocol
        common = {"remark","address","port","network","tls","sni","host","path","fingerprint","alpn"}
        vmess_extra = {"uuid","alter_id","security"}
        vless_extra = {"uuid","flow","public_key","short_id","service_name","header_type"}
        trojan_extra = {"password","flow","service_name"}
        ss_extra = {"password","method"}
        if proto == "vmess":
            show = common | vmess_extra
        elif proto == "vless":
            show = common | vless_extra
        elif proto == "trojan":
            show = common | trojan_extra
        else:
            show = common | ss_extra
        # always show uuid for vless/vmess; password for trojan/ss already
        for key, row in self.field_rows.items():
            if key in show:
                row.height = dp(44)
                row.opacity = 1
                row.disabled = False
            else:
                row.height = 0
                row.opacity = 0
                row.disabled = True

    def _get(self, key):
        return self.fields[key].text.strip()

    def _set(self, key, val):
        self.fields[key].text = str(val)

    def _collect(self):
        p = {
            "protocol": self.proto.text,
            "remark": self._get("remark"),
            "address": self._get("address"),
            "port": int(self._get("port") or 0),
            "uuid": self._get("uuid"),
            "password": self._get("password"),
            "alter_id": int(self._get("alter_id") or 0),
            "security": self._get("security") or "auto",
            "network": self._get("network") or "tcp",
            "tls": self._get("tls") or "none",
            "sni": self._get("sni"),
            "host": self._get("host"),
            "path": self._get("path") or "/",
            "flow": self._get("flow"),
            "method": self._get("method") or "aes-256-gcm",
            "public_key": self._get("public_key"),
            "short_id": self._get("short_id"),
            "service_name": self._get("service_name"),
            "fingerprint": self._get("fingerprint"),
            "alpn": self._get("alpn"),
            "header_type": "none",
        }
        return p

    def generate(self, *a):
        try:
            p = self._collect()
            errs = core.validate_config(p)
            if errs:
                toast("خطا: " + ", ".join(errs))
                return
            link = core.generate_link(p)
            self.result.text = link
            toast("تولید شد")
        except Exception as e:
            toast("خطا: " + str(e))

    def copy_link(self, *a):
        txt = self.result.text.strip()
        if not txt:
            toast("چیزی برای کپی نیست")
            return
        try:
            from kivy.core.clipboard import Clipboard
            Clipboard.copy(txt)
            toast("کپی شد")
        except Exception as e:
            toast("کپی ناموفق: " + str(e))

    def import_clipboard(self, *a):
        try:
            from kivy.core.clipboard import Clipboard
            txt = Clipboard.paste()
        except Exception as e:
            toast("کلیپ‌بورد در دسترس نیست")
            return
        txt = (txt or "").strip()
        if not txt:
            toast("کلیپ‌بورد خالی است")
            return
        # try parse as link
        try:
            if "://" in txt:
                cfg = core.parse_link(txt.split()[0])
                # fill fields
                for k, v in cfg.items():
                    if k in self.fields:
                        self.fields[k].text = str(v) if v is not None else ""
                if cfg.get("protocol"):
                    self.proto.text = cfg["protocol"]
                self.result.text = txt.split()[0]
                toast("وارد شد")
                return
        except Exception:
            pass
        toast("لینک معتبر نیست")

    def show_json(self, *a):
        try:
            p = self._collect()
            j = core.build_client_json(p)
            pop = Popup(title=fa("JSON کلاینت"), content=ScrollView(), size_hint=(0.95, 0.85))
            ti = TextInput(text=j, readonly=True, font_name="Roboto", font_size=dp(10))
            pop.content.add_widget(ti)
            pop.open()
        except Exception as e:
            toast("خطا: " + str(e))

    def save_config(self, *a):
        txt = self.result.text.strip()
        if not txt:
            toast("ابتدا تولید کنید")
            return
        try:
            cfg = core.parse_link(txt)
            cfgs = store.load_configs()
            cfgs = [c for c in cfgs if c.get("link") != txt]
            cfgs.insert(0, {"link": txt, "cfg": cfg, "saved_at": time.time(), "status": "unknown"})
            store.save_configs(cfgs)
            toast("ذخیره شد")
            self.app.refresh_saved()
        except Exception as e:
            toast("خطا: " + str(e))


# ---------- Search Screen ----------
class SearchScreen(BaseScreen):
    def __init__(self, app, **kw):
        super().__init__(app, **kw)
        self.results = []
        self.build_ui()

    def build_ui(self):
        self.clear_widgets()
        box = BoxLayout(orientation="vertical", padding=dp(8), spacing=dp(6))

        box.add_widget(flabel("جستجوی کانفیگ فعال", color=FG, size=18, bold=True))

        # query
        qrow = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(6))
        self.query = TextInput(text="", hint_text=fa("عبارت جستجو یا لینک..."), multiline=False, font_name=FONT, size_hint=(0.7, None), height=dp(40))
        qrow.add_widget(self.query)
        bsearch = Btn(text="جستجو", size_hint=(0.3, None), height=dp(40), background_color=ACCENT)
        bsearch.bind(on_press=self.do_search)
        qrow.add_widget(bsearch)
        box.add_widget(qrow)

        # channels
        crow = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(6))
        crow.add_widget(flabel("کانال‌ها:", size=12, h=dp(44)))
        self.channels = TextInput(text="", hint_text=fa("v2rayngvpn,V2rayNG (با کاما)"), multiline=False, font_name=FONT, size_hint=(0.7, None), height=dp(40))
        crow.add_widget(self.channels)
        box.add_widget(crow)

        # toggles
        trow = BoxLayout(size_hint_y=None, height=dp(36), spacing=dp(6))
        self.tg = ToggleButton(text="تلگرام", state="down", font_name=FONT, size_hint=(0.33, None), height=dp(32))
        self.ddg = ToggleButton(text="DuckDuckGo", state="down", font_name=FONT, size_hint=(0.33, None), height=dp(32))
        self.ggl = ToggleButton(text="Google", state="down", font_name=FONT, size_hint=(0.33, None), height=dp(32))
        for t in (self.tg, self.ddg, self.ggl):
            trow.add_widget(t)
        box.add_widget(trow)

        self.status = flabel("آماده", size=12, color=DIM)
        box.add_widget(self.status)

        frow = BoxLayout(size_hint_y=None, height=dp(32), spacing=dp(6))
        self.filter_active = ToggleButton(text="فقط فعال", state="normal", font_name=FONT, size_hint=(0.33, None), height=dp(30))
        self.filter_active.bind(state=lambda *a: self.render())
        frow.add_widget(self.filter_active)
        frow.add_widget(Label())  # spacer
        box.add_widget(frow)

        # results list
        self.scroll = ScrollView()
        self.listbox = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(6))
        self.listbox.bind(minimum_height=self.listbox.setter("height"))
        self.scroll.add_widget(self.listbox)
        box.add_widget(self.scroll)

        # bottom actions
        arow = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(6))
        bcheck = Btn(text="بررسی فعال", background_color=GREEN)
        bcheck.bind(on_press=self.check_all)
        bsave = Btn(text="ذخیرهٔ همه", background_color=YELLOW)
        bsave.bind(on_press=self.save_all)
        arow.add_widget(bcheck)
        arow.add_widget(bsave)
        box.add_widget(arow)

        self.add_widget(box)

    def do_search(self, *a):
        q = self.query.text.strip()
        chans = [c.strip() for c in self.channels.text.split(",") if c.strip()]
        if not chans:
            s = store.load_settings()
            if s.get("channels"):
                chans = [c.strip() for c in s["channels"].split(",") if c.strip()]
        self.status.text = fa("در حال جستجو...")
        self.listbox.clear_widgets()
        self.results = []

        def worker():
            try:
                res = search.search_all(
                    q,
                    channels=chans if chans else None,
                    use_google=self.ggl.state == "down",
                    use_ddg=self.ddg.state == "down",
                )
                # if telegram disabled, skip channels
                if self.tg.state != "down":
                    res = [r for r in res if not r["source"].startswith("telegram")]
                Clock.schedule_once(lambda dt: self._on_results(res), 0)
            except Exception as e:
                Clock.schedule_once(lambda dt: self._on_error(str(e)), 0)

        threading.Thread(target=worker, daemon=True).start()

    def _on_results(self, res):
        self.results = res
        self.status.text = fa("یافت شد: {} کانفیگ".format(len(res)))
        self.render()

    def _on_error(self, e):
        self.status.text = fa("خطا: " + e)

    def render(self):
        self.listbox.clear_widgets()
        items = self.results
        if self.filter_active.state == "down":
            items = [r for r in items if r.get("status") == "active"]
        for i, r in enumerate(items):
            card = Card()
            card.add_widget(flabel(r["link"][:90], size=11, color=FG))
            card.add_widget(flabel("منبع: " + r["source"], size=10, color=DIM))
            st = r.get("status", "unknown")
            color = GREEN if st == "active" else (RED if st == "dead" else DIM)
            card.add_widget(flabel("وضعیت: " + st, size=10, color=color))
            brow = BoxLayout(size_hint_y=None, height=dp(36), spacing=dp(4))
            b1 = Btn(text="کپی", size_hint=(0.33, None), height=dp(32), background_color=CARD2)
            b1.bind(on_press=lambda x, link=r["link"]: self.copy(link))
            b2 = Btn(text="ذخیره", size_hint=(0.33, None), height=dp(32), background_color=CARD2)
            b2.bind(on_press=lambda x, link=r["link"]: self.save_one(link))
            b3 = Btn(text="بررسی", size_hint=(0.33, None), height=dp(32), background_color=CARD2)
            b3.bind(on_press=lambda x, link=r["link"]: self.check_by_link(link))
            brow.add_widget(b1)
            brow.add_widget(b2)
            brow.add_widget(b3)
            card.add_widget(brow)
            self.listbox.add_widget(card)

    def copy(self, link):
        try:
            from kivy.core.clipboard import Clipboard
            Clipboard.copy(link)
            toast("کپی شد")
        except Exception as e:
            toast(str(e))

    def save_one(self, link):
        try:
            cfg = core.parse_link(link)
            cfgs = store.load_configs()
            cfgs = [c for c in cfgs if c.get("link") != link]
            cfgs.insert(0, {"link": link, "cfg": cfg, "saved_at": time.time(), "status": "unknown"})
            store.save_configs(cfgs)
            toast("ذخیره شد")
            self.app.refresh_saved()
        except Exception as e:
            toast("خطا: " + str(e))

    def save_all(self, *a):
        n = 0
        cfgs = store.load_configs()
        existing = {c.get("link") for c in cfgs}
        new_items = []
        for r in self.results:
            try:
                cfg = core.parse_link(r["link"])
                if r["link"] in existing:
                    # update status
                    for c in cfgs:
                        if c.get("link") == r["link"]:
                            c["status"] = r.get("status", c.get("status"))
                    continue
                new_items.append({"link": r["link"], "cfg": cfg, "saved_at": time.time(), "status": r.get("status", "unknown")})
                n += 1
            except Exception:
                pass
        cfgs = new_items + cfgs
        store.save_configs(cfgs)
        toast("{} ذخیره شد".format(n))
        self.app.refresh_saved()

    def check_by_link(self, link):
        for i, r in enumerate(self.results):
            if r['link'] == link:
                self.check_one(i)
                return

    def check_one(self, idx):
        if idx >= len(self.results):
            return
        r = self.results[idx]
        try:
            cfg = core.parse_link(r["link"])
        except Exception as e:
            toast("خطا: " + str(e))
            return
        self.status.text = fa("در حال بررسی...")

        def worker():
            ok, ms, err = core.check_active(cfg)
            r["status"] = "active" if ok else "dead"
            r["latency"] = ms
            r["error"] = err
            Clock.schedule_once(lambda dt: self._after_check(), 0)

        threading.Thread(target=worker, daemon=True).start()

    def _after_check(self):
        self.render()
        self.status.text = fa("بررسی انجام شد")

    def check_all(self, *a):
        self.status.text = fa("در حال بررسی همه...")

        def worker():
            for r in self.results:
                try:
                    cfg = core.parse_link(r["link"])
                    ok, ms, err = core.check_active(cfg)
                    r["status"] = "active" if ok else "dead"
                    r["latency"] = ms
                except Exception:
                    r["status"] = "dead"
            Clock.schedule_once(lambda dt: self._after_check_all(), 0)

        threading.Thread(target=worker, daemon=True).start()

    def _after_check_all(self):
        self.render()
        act = sum(1 for r in self.results if r.get("status") == "active")
        self.status.text = fa("فعال: {} / {}".format(act, len(self.results)))


# ---------- Saved Screen ----------
class SavedScreen(BaseScreen):
    def __init__(self, app, **kw):
        super().__init__(app, **kw)
        self.build_ui()

    def build_ui(self):
        self.clear_widgets()
        box = BoxLayout(orientation="vertical", padding=dp(8), spacing=dp(6))
        box.add_widget(flabel("کانفیگ‌های ذخیره‌شده", color=FG, size=18, bold=True))

        arow = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(6))
        bcheck = Btn(text="بررسی همه", background_color=GREEN)
        bcheck.bind(on_press=self.check_all)
        bclear = Btn(text="پاک کردن همه", background_color=RED)
        bclear.bind(on_press=self.clear_all)
        arow.add_widget(bcheck)
        arow.add_widget(bclear)
        box.add_widget(arow)

        self.scroll = ScrollView()
        self.listbox = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(6))
        self.listbox.bind(minimum_height=self.listbox.setter("height"))
        self.scroll.add_widget(self.listbox)
        box.add_widget(self.scroll)
        self.add_widget(box)
        self.refresh()

    def refresh(self):
        self.listbox.clear_widgets()
        cfgs = store.load_configs()
        def keyf(it):
            st = it.get("status", "unknown")
            rank = 0 if st == "active" else (1 if st == "unknown" else 2)
            lat = it.get("latency")
            lat = lat if lat is not None else 999999
            return (rank, lat)
        cfgs = sorted(cfgs, key=keyf)
        for i, item in enumerate(cfgs):
            card = Card()
            link = item.get("link", "")
            cfg = item.get("cfg", {})
            card.add_widget(flabel((cfg.get("remark") or "") + "  " + cfg.get("protocol", "").upper(), size=13, bold=True, color=FG))
            card.add_widget(flabel("{}:{}".format(cfg.get("address"), cfg.get("port")), size=11, color=DIM))
            st = item.get("status", "unknown")
            color = GREEN if st == "active" else (RED if st == "dead" else DIM)
            lat = item.get("latency")
            txt = "وضعیت: " + st
            if lat is not None:
                txt += "  ({} ms)".format(lat)
            card.add_widget(flabel(txt, size=10, color=color))
            brow = BoxLayout(size_hint_y=None, height=dp(36), spacing=dp(4))
            b1 = Btn(text="کپی", size_hint=(0.2, None), height=dp(32), background_color=CARD2)
            b1.bind(on_press=lambda x, l=link: self.copy(l))
            b2 = Btn(text="JSON", size_hint=(0.2, None), height=dp(32), background_color=CARD2)
            b2.bind(on_press=lambda x, c=cfg: self.show_json(c))
            b3 = Btn(text="بررسی", size_hint=(0.2, None), height=dp(32), background_color=CARD2)
            b3.bind(on_press=lambda x, l=link: self.check_link(l))
            bedit = Btn(text="ویرایش", size_hint=(0.2, None), height=dp(32), background_color=CARD2)
            bedit.bind(on_press=lambda x, c=cfg, l=link: self.edit(c, l))
            b4 = Btn(text="حذف", size_hint=(0.2, None), height=dp(32), background_color=RED)
            b4.bind(on_press=lambda x, l=link: self.delete_link(l))
            brow.add_widget(bedit)
            for b in (b1, b2, b3, b4):
                brow.add_widget(b)
            card.add_widget(brow)
            self.listbox.add_widget(card)

    def copy(self, link):
        try:
            from kivy.core.clipboard import Clipboard
            Clipboard.copy(link)
            toast("کپی شد")
        except Exception as e:
            toast(str(e))

    def show_json(self, cfg):
        try:
            j = core.build_client_json(cfg)
            pop = Popup(title=fa("JSON"), content=ScrollView(), size_hint=(0.95, 0.85))
            ti = TextInput(text=j, readonly=True, font_name="Roboto", font_size=dp(10))
            pop.content.add_widget(ti)
            pop.open()
        except Exception as e:
            toast(str(e))

    def check_link(self, link):
        cfgs = store.load_configs()
        idx = None
        for i, c in enumerate(cfgs):
            if c.get("link") == link:
                idx = i
                break
        if idx is None:
            return
        item = cfgs[idx]
        try:
            cfg = item.get("cfg") or core.parse_link(item["link"])
        except Exception:
            cfg = core.parse_link(item["link"])
        self.app.status_msg("در حال بررسی...")

        def worker():
            ok, ms, err = core.check_active(cfg)
            cfgs2 = store.load_configs()
            for c in cfgs2:
                if c.get("link") == link:
                    c["status"] = "active" if ok else "dead"
                    c["latency"] = ms
            store.save_configs(cfgs2)
            Clock.schedule_once(lambda dt: self.refresh(), 0)

        threading.Thread(target=worker, daemon=True).start()

    def check_one(self, idx):
        pass

    def check_all(self, *a):
        self.app.status_msg("در حال بررسی همه...")

        def worker():
            cfgs = store.load_configs()
            for i, item in enumerate(cfgs):
                try:
                    cfg = item.get("cfg") or core.parse_link(item["link"])
                    ok, ms, err = core.check_active(cfg)
                    cfgs[i]["status"] = "active" if ok else "dead"
                    cfgs[i]["latency"] = ms
                except Exception:
                    cfgs[i]["status"] = "dead"
            store.save_configs(cfgs)
            Clock.schedule_once(lambda dt: self.refresh(), 0)

        threading.Thread(target=worker, daemon=True).start()

    def delete_link(self, link):
        cfgs = [c for c in store.load_configs() if c.get("link") != link]
        store.save_configs(cfgs)
        self.refresh()
        toast("حذف شد")

    def delete(self, idx):
        pass

    def edit(self, cfg, link):
        try:
            self.app.load_into_generator(cfg, link)
            self.app.switch("gen")
            toast("برای ویرایش باز شد")
        except Exception as e:
            toast(str(e))

    def clear_all(self, *a):
        store.save_configs([])
        self.refresh()
        toast("پاک شد")


# ---------- Settings Screen ----------
class SettingsScreen(BaseScreen):
    def __init__(self, app, **kw):
        super().__init__(app, **kw)
        self.build_ui()

    def build_ui(self):
        self.clear_widgets()
        scroll = ScrollView()
        box = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(8), padding=dp(8))
        box.bind(minimum_height=box.setter("height"))
        box.add_widget(flabel("تنظیمات", color=FG, size=18, bold=True))

        s = store.load_settings()

        def add_field(key, label, val):
            r = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(6))
            r.add_widget(flabel(label, size=12, h=dp(44)))
            ti = TextInput(text=str(val), multiline=False, font_name=FONT, size_hint=(0.65, None), height=dp(40))
            r.add_widget(ti)
            self.fields[key] = ti
            box.add_widget(r)

        self.fields = {}
        add_field("channels", "کانال‌های تلگرام (با کاما)", s.get("channels", ""))
        add_field("timeout", "تایم‌اوت بررسی (ثانیه)", s.get("timeout", 5))

        brow = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(6))
        bsave = Btn(text="ذخیره", background_color=GREEN)
        bsave.bind(on_press=self.save)
        brow.add_widget(bsave)
        box.add_widget(brow)

        box.add_widget(flabel("راهنما:", size=13, bold=True, color=YELLOW))
        box.add_widget(flabel("• کانفیگ‌ها از کانال‌های عمومی تلگرام و وب جمع‌آوری می‌شوند.", size=11, color=DIM))
        box.add_widget(flabel("• بررسی فعال بودن فقط تست TCP است.", size=11, color=DIM))
        box.add_widget(flabel("• از این ابزار به‌صورت قانونی استفاده کنید.", size=11, color=DIM))

        scroll.add_widget(box)
        self.add_widget(scroll)

    def save(self, *a):
        s = store.load_settings()
        s["channels"] = self.fields["channels"].text.strip()
        try:
            s["timeout"] = int(self.fields["timeout"].text.strip())
        except Exception:
            s["timeout"] = 5
        store.save_settings(s)
        toast("ذخیره شد")


# ---------- App ----------
class V2RayApp(App):
    def build(self):
        self.title = "V2Ray Config Finder"
        root = BoxLayout(orientation="vertical")
        # top bar
        self.topbar = BoxLayout(size_hint_y=None, height=dp(48), padding=[dp(8), 0])
        from kivy.graphics import Color, Rectangle
        with self.topbar.canvas.before:
            Color(rgba=CARD)
            self._tb = Rectangle()
        self.topbar.bind(pos=lambda s, p: setattr(self._tb, "pos", p), size=lambda s, sz: setattr(self._tb, "size", sz))
        self.title_lbl = flabel("V2Ray Config Finder", size=16, bold=True, color=FG)
        self.topbar.add_widget(self.title_lbl)
        root.add_widget(self.topbar)

        # screens
        self.screens = {}
        self.content = BoxLayout()
        root.add_widget(self.content)

        self.generator = GeneratorScreen(self)
        self.search = SearchScreen(self)
        self.saved = SavedScreen(self)
        self.settings = SettingsScreen(self)

        self.screens = {
            "gen": self.generator,
            "search": self.search,
            "saved": self.saved,
            "settings": self.settings,
        }
        self.current = "search"
        self.content.add_widget(self.search)

        # bottom nav
        nav = BoxLayout(size_hint_y=None, height=dp(56), spacing=dp(2), padding=dp(4))
        with nav.canvas.before:
            Color(rgba=CARD)
            self._nb = Rectangle()
        nav.bind(pos=lambda s, p: setattr(self._nb, "pos", p), size=lambda s, sz: setattr(self._nb, "size", sz))
        items = [("جستجو", "search"), ("تولید", "gen"), ("ذخیره", "saved"), ("تنظیمات", "settings")]
        self.nav_buttons = {}
        for label, key in items:
            b = Btn(text=label, size_hint=(0.25, None), height=dp(48), background_color=CARD2)
            b.bind(on_press=lambda x, k=key: self.switch(k))
            self.nav_buttons[key] = b
            nav.add_widget(b)
        self._update_nav(self.current)
        root.add_widget(nav)

        # toast container
        self._toast = None
        return root

    def switch(self, key):
        if key == self.current:
            return
        self.content.clear_widgets()
        self.content.add_widget(self.screens[key])
        self.current = key
        self._update_nav(key)
        if key == "saved":
            self.saved.refresh()

    def load_into_generator(self, cfg, link):
        g = self.generator
        if cfg.get("protocol"):
            g.proto.text = cfg["protocol"]
        for k, v in cfg.items():
            if k in g.fields:
                g.fields[k].text = str(v) if v is not None else ""
        g.result.text = link or ""
        Clock.schedule_once(lambda dt: g._toggle_fields(g.proto.text), 0)

    def _update_nav(self, key):
        for k, b in getattr(self, "nav_buttons", {}).items():
            b.background_color = ACCENT if k == key else CARD2

    def refresh_saved(self):
        try:
            self.saved.refresh()
        except Exception:
            pass

    def status_msg(self, msg):
        try:
            self.search.status.text = fa(msg)
        except Exception:
            pass

    def show_toast(self, msg):
        Clock.schedule_once(lambda dt: ToastPopup(msg).open(), 0)


if __name__ == "__main__":
    V2RayApp().run()
