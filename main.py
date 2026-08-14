# -*- coding: utf-8 -*-
"""
آربیتراژ تتر ایران — اپلیکیشن موبایل (Kivy)
===========================================

رابط گرافیکی برای هسته arbitrage_core.
ویژگی‌ها:
- نمایش قیمت خرید/فروش تتر در صرافی‌های داخلی
- محاسبه فرصت‌های آربیتراژ بین‌صرافی‌ای (با کسر کارمزد و کارمزد انتقال)
- حالت زنده و حالت دمو (برای تست بدون اینترنت)
- به‌روزرسانی در پس‌زمینه (بدون فریز شدن رابط)
- تنظیمات قابل ذخیره (مقدار معامله، کارمزد انتقال، حداقل حاشیه، خودکار)
- فونت فارسی (Vazirmatn)
"""

import os
import json
import threading
from kivy.app import App
from kivy.lang import Builder
from kivy.clock import Clock
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.textinput import TextInput
from kivy.uix.popup import Popup
from kivy.uix.widget import Widget
from kivy.graphics import Color, RoundedRectangle
from kivy.core.window import Window
from kivy.utils import get_color_from_hex

import arbitrage_core as ac

FONT = "Vazirmatn-Regular.ttf"

KV = f"""
<BaseLabel@Label>:
    font_name: '{FONT}'
    color: 0.92, 0.92, 0.95, 1
    halign: 'right'
    valign: 'middle'
    text_size: self.width, None
    size_hint_y: None
    height: self.texture_size[1] + 12

<BaseButton@Button>:
    font_name: '{FONT}'
    background_color: 0.16, 0.55, 0.95, 1
    color: 1, 1, 1, 1
    font_size: '16sp'
    size_hint_y: None
    height: 46
"""

# ---- رنگ‌ها ----
C_BG = (0.07, 0.08, 0.12, 1)
C_CARD = (0.13, 0.15, 0.22, 1)
C_GREEN = (0.30, 0.80, 0.45, 1)
C_RED = (0.95, 0.40, 0.40, 1)
C_GOLD = (0.98, 0.78, 0.25, 1)
C_BLUE = (0.30, 0.60, 0.95, 1)
C_MUTED = (0.6, 0.63, 0.7, 1)


def fmt(n, dec=0):
    if n is None:
        return "—"
    return f"{n:,.{dec}f}"


class Card(BoxLayout):
    """یه کارت با پس‌زمینه گرد."""
    def __init__(self, **kw):
        super().__init__(**kw)
        self.orientation = "vertical"
        self.padding = [12, 10, 12, 10]
        self.spacing = 4
        with self.canvas.before:
            Color(*C_CARD)
            self._bg = RoundedRectangle(radius=[12], pos=self.pos, size=self.size)
        self.bind(pos=self._upd, size=self._upd)

    def _upd(self, *a):
        self._bg.pos = self.pos
        self._bg.size = self.size


class ArbitrageApp(App):
    def build(self):
        self.title = "آربیتراژ تتر ایران"
        Builder.load_string(KV)
        Window.clearcolor = C_BG

        self.cfg = ac.Config()
        self.cache = ac.Cache()
        self.quotes = []
        self.ops = []
        self.summary = None
        self.usd = None
        self._busy = False
        self._lock = threading.Lock()

        self._load_settings()

        root = BoxLayout(orientation="vertical", padding=[10, 10, 10, 6], spacing=8)

        # ----- نوار بالا -----
        top = BoxLayout(size_hint_y=None, height=54, spacing=8)
        self.status_lbl = Label(text="در حال بارگذاری…", font_name=FONT,
                                color=C_MUTED, halign="right", size_hint_x=0.62,
                                font_size="14sp", text_size=(None, None), valign="middle")
        self.refresh_btn = Button(text="به‌روزرسانی", font_name=FONT,
                                  background_color=C_BLUE, color=(1, 1, 1, 1),
                                  size_hint_x=0.38, font_size="16sp")
        self.refresh_btn.bind(on_press=lambda *a: self.refresh())
        self.settings_btn = Button(text="⚙", font_name=FONT,
                                   background_color=(0.3, 0.32, 0.4, 1), color=(1, 1, 1, 1),
                                   size_hint_x=0.18, font_size="20sp")
        self.settings_btn.bind(on_press=lambda *a: self.open_settings())
        top.add_widget(self.status_lbl)
        top.add_widget(self.refresh_btn)
        top.add_widget(self.settings_btn)
        root.add_widget(top)

        # ----- کارت خلاصه -----
        self.summary_card = Card(size_hint_y=None, height=150)
        self.summary_card.add_widget(Label(text="📊 خلاصه بازار", font_name=FONT,
                                            color=C_GOLD, font_size="18sp", halign="right",
                                            size_hint_y=None, height=28))
        self.best_op_lbl = Label(text="بهترین فرصت: —", font_name=FONT, color=C_GREEN,
                                 font_size="16sp", halign="right", size_hint_y=None, height=26)
        self.market_lbl = Label(text="بازار: —", font_name=FONT, color=(1, 1, 1, 1),
                                font_size="14sp", halign="right", size_hint_y=None, height=24)
        self.premium_lbl = Label(text="حباب تتر: —", font_name=FONT, color=C_BLUE,
                                 font_size="14sp", halign="right", size_hint_y=None, height=24)
        for w in (self.best_op_lbl, self.market_lbl, self.premium_lbl):
            self.summary_card.add_widget(w)
        root.add_widget(self.summary_card)

        # ----- لیست صرافی‌ها (scroll) -----
        self.list_container = BoxLayout(orientation="vertical", size_hint_y=None, spacing=6)
        self.list_container.bind(minimum_height=self.list_container.setter("height"))
        scroll = ScrollView(size_hint_y=1, bar_width=4)
        scroll.add_widget(self.list_container)
        root.add_widget(scroll)

        # ----- نوار پایین (وضعیت) -----
        self.footer = Label(text="", font_name=FONT, color=C_MUTED, font_size="12sp",
                            size_hint_y=None, height=22, halign="right")
        root.add_widget(self.footer)

        # اولین بارگذاری + خودکار
        self.refresh()
        self._auto_ev = Clock.schedule_interval(lambda *a: self.refresh(), 30)
        return root

    # ------------------------------------------------------------------
    def _load_settings(self):
        path = os.path.join(self.user_data_dir, "settings.json")
        try:
            with open(path, "r", encoding="utf-8") as f:
                d = json.load(f)
            for k in ("trade_amount", "transfer_fee_usdt", "min_spread_pct", "timeout", "demo", "include_market"):
                if k in d:
                    setattr(self.cfg, k, d[k])
        except Exception:
            pass

    def _save_settings(self):
        path = os.path.join(self.user_data_dir, "settings.json")
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump({
                    "trade_amount": self.cfg.trade_amount,
                    "transfer_fee_usdt": self.cfg.transfer_fee_usdt,
                    "min_spread_pct": self.cfg.min_spread_pct,
                    "timeout": self.cfg.timeout,
                    "demo": self.cfg.demo,
                    "include_market": self.cfg.include_market,
                }, f, ensure_ascii=False)
        except Exception:
            pass

    # ------------------------------------------------------------------
    def refresh(self):
        if self._busy:
            return
        self._busy = True
        self.status_lbl.text = "⏳ در حال دریافت قیمت‌ها…"
        self.status_lbl.color = C_MUTED
        t = threading.Thread(target=self._worker, daemon=True)
        t.start()

    def _worker(self):
        try:
            quotes = ac.fetch_all(self.cfg, self.cache)
            usd = ac.fetch_usd_market(self.cfg, self.cache)
            ops = ac.compute_arbitrage(quotes, self.cfg)
            summary = ac.compute_summary(quotes, usd)
            with self._lock:
                self.quotes, self.ops, self.usd, self.summary = quotes, ops, usd, summary
        except Exception as e:
            with self._lock:
                self._last_error = str(e)
        Clock.schedule_once(self._update_ui, 0)

    def _update_ui(self, *a):
        with self._lock:
            quotes, ops, summary = self.quotes, self.ops, self.summary

        # نوار وضعیت
        if self.cfg.demo:
            self.status_lbl.text = "🟡 حالت دمو (دیتای نمونه)"
            self.status_lbl.color = C_GOLD
        else:
            self.status_lbl.text = "🟢 زنده — " + self._now()
            self.status_lbl.color = C_GREEN

        # خلاصه
        if summary:
            self.market_lbl.text = (
                f"بهترین خرید: {summary['best_buy_ex']} = {fmt(summary['best_buy'])}  |  "
                f"بهترین فروش: {summary['best_sell_ex']} = {fmt(summary['best_sell'])}"
            )
            if summary.get("premium_pct") is not None:
                p = summary["premium_pct"]
                col = C_GREEN if p >= 0 else C_RED
                self.premium_lbl.color = col
                self.premium_lbl.text = f"حباب تتر نسبت به دلار آزاد: {p:+.2f}٪  (دلار={fmt(summary['usd_market'])})"
            else:
                self.premium_lbl.text = "نرخ دلار آزاد در دسترس نیست"
        else:
            self.market_lbl.text = "قیمتی دریافت نشد"

        # بهترین فرصت
        best = ops[0] if ops else None
        if best and best.net_irt > 0:
            self.best_op_lbl.color = C_GREEN
            self.best_op_lbl.text = (
                f"💡 بخر {best.buy_ex} → بفروش {best.sell_ex}  |  "
                f"سود خالص {fmt(best.net_irt)} تومان ({best.net_pct:+.2f}٪)"
            )
        else:
            self.best_op_lbl.color = C_RED
            self.best_op_lbl.text = "❌ فرصت سودآوری با تنظیمات فعلی پیدا نشد"

        # لیست صرافی‌ها
        self.list_container.clear_widgets()
        best_buy = summary["best_buy"] if summary else None
        for q in quotes:
            row = self._make_row(q, best_buy)
            self.list_container.add_widget(row)

        # فوتر
        n_ok = sum(1 for q in quotes if q.ok)
        demo_n = sum(1 for q in quotes if q.demo)
        self.footer.text = f"صرافی‌های آنلاین: {n_ok}/{len(quotes)}  |  دمو: {demo_n}  |  فرصت‌ها: {len(ops)}"

        self._busy = False

    def _make_row(self, q, best_buy):
        row = Card(size_hint_y=None, height=92, padding=[10, 6, 10, 6])
        # سمت راست: نام + وضعیت
        left = BoxLayout(orientation="vertical", size_hint_x=0.42)
        name = Label(text=q.name, font_name=FONT, font_size="17sp", halign="right",
                     color=(1, 1, 1, 1), size_hint_y=None, height=30, valign="middle")
        status = Label(text=("🟡 دمو" if q.demo else ("🟢" if q.ok else "🔴 خطا")),
                       font_name=FONT, font_size="12sp", halign="right",
                       color=C_MUTED, size_hint_y=None, height=18)
        left.add_widget(name)
        left.add_widget(status)
        if not q.ok and q.error:
            err = Label(text=q.error[:40], font_name=FONT, font_size="10sp",
                        halign="right", color=C_RED, size_hint_y=None, height=16)
            left.add_widget(err)

        # سمت چپ: خرید/فروش + حاشیه
        right = BoxLayout(orientation="vertical", size_hint_x=0.58, spacing=2)
        buy_txt = f"خرید: {fmt(q.buy)}" if q.buy else "خرید: —"
        sell_txt = f"فروش: {fmt(q.sell)}" if q.sell else "فروش: —"
        buy_l = Label(text=buy_txt, font_name=FONT, font_size="14sp", halign="right",
                      color=C_GREEN, size_hint_y=None, height=24)
        sell_l = Label(text=sell_txt, font_name=FONT, font_size="14sp", halign="right",
                       color=C_RED, size_hint_y=None, height=24)
        # حاشیه نسبت به بهترین خرید بازار
        if best_buy and q.buy:
            diff = (q.buy - best_buy) / best_buy * 100
            spread_l = Label(text=f"نسبت به بهترین: {diff:+.2f}٪", font_name=FONT,
                             font_size="12sp", halign="right", color=C_MUTED,
                             size_hint_y=None, height=20)
        else:
            spread_l = Label(text="", size_hint_y=None, height=20)
        right.add_widget(buy_l)
        right.add_widget(sell_l)
        right.add_widget(spread_l)

        row.add_widget(left)
        row.add_widget(right)
        return row

    # ------------------------------------------------------------------
    def open_settings(self):
        content = BoxLayout(orientation="vertical", spacing=8, padding=10)
        content.add_widget(Label(text="⚙ تنظیمات", font_name=FONT, font_size="20sp",
                                 color=C_GOLD, size_hint_y=None, height=34, halign="right"))

        def field(label, val, key, is_float=True):
            box = BoxLayout(size_hint_y=None, height=40, spacing=6)
            lab = Label(text=label, font_name=FONT, font_size="13sp", size_hint_x=0.5,
                        halign="right", color=(1, 1, 1, 1))
            ti = TextInput(text=str(val), font_name=FONT, size_hint_x=0.5,
                           input_filter="float" if is_float else None,
                           multiline=False, halign="right")
            box.add_widget(lab); box.add_widget(ti)
            content.add_widget(box)
            return ti

        ti_amount = field("مقدار معامله (تتر):", self.cfg.trade_amount, "trade_amount")
        ti_transfer = field("کارمزد انتقال (تتر):", self.cfg.transfer_fee_usdt, "transfer_fee_usdt")
        ti_min = field("حداقل حاشیه سود (٪):", self.cfg.min_spread_pct, "min_spread_pct")
        ti_timeout = field("تایم‌اوت (ثانیه):", self.cfg.timeout, "timeout")

        demo_box = BoxLayout(size_hint_y=None, height=40, spacing=6)
        demo_lab = Label(text="حالت دمو:", font_name=FONT, font_size="13sp", size_hint_x=0.5,
                         halign="right", color=(1, 1, 1, 1))
        demo_btn = Button(text=("روشن" if self.cfg.demo else "خاموش"), font_name=FONT,
                          background_color=(C_GOLD if self.cfg.demo else (0.3, 0.32, 0.4, 1)),
                          color=(0, 0, 0, 1) if self.cfg.demo else (1, 1, 1, 1), size_hint_x=0.5)
        demo_box.add_widget(demo_lab); demo_box.add_widget(demo_btn)
        content.add_widget(demo_box)

        def toggle_demo(*a):
            self.cfg.demo = not self.cfg.demo
            demo_btn.text = "روشن" if self.cfg.demo else "خاموش"
            demo_btn.background_color = C_GOLD if self.cfg.demo else (0.3, 0.32, 0.4, 1)

        demo_btn.bind(on_press=toggle_demo)

        close_btn = Button(text="ذخیره و بستن", font_name=FONT, background_color=C_BLUE,
                           color=(1, 1, 1, 1), size_hint_y=None, height=46)

        def do_save(*a):
            try:
                self.cfg.trade_amount = float(ti_amount.text) or 1000
                self.cfg.transfer_fee_usdt = float(ti_transfer.text) or 0
                self.cfg.min_spread_pct = float(ti_min.text) or 0
                self.cfg.timeout = float(ti_timeout.text) or 8
            except Exception:
                pass
            self._save_settings()
            popup.dismiss()
            self.refresh()

        close_btn.bind(on_press=do_save)
        content.add_widget(close_btn)

        popup = Popup(title="", content=content, size_hint=(0.9, 0.85))
        popup.open()

    # ------------------------------------------------------------------
    def _now(self):
        import datetime
        return datetime.datetime.now().strftime("%H:%M:%S")


if __name__ == "__main__":
    ArbitrageApp().run()
