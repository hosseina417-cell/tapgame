# -*- coding: utf-8 -*-
"""
⚡ آربیتراژ پرو — نسخه ۵.۰
اپ اندرویدی پایش و معاملهٔ آربیتراژ بین صرافی‌های ایرانی
(نوبیتکس، والکس، بیت‌پین، رمزینکس)

اجرای دسکتاپ برای تست:  python main.py
"""
import os
import sys
import time

from kivy.app import App
from kivy.clock import Clock, mainthread
from kivy.core.text import LabelBase
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.scrollview import ScrollView
from kivy.uix.switch import Switch
from kivy.uix.textinput import TextInput
from kivy.utils import get_color_from_hex as hexc

import exchanges
import store
import trader
from monitor import MONITOR

# ---------- فونت فارسی + شکل‌دهی ----------
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
        """شکل‌دهی و راست‌به‌چپ‌سازی متن فارسی برای کیوی"""
        try:
            return get_display(_reshaper.reshape(str(text)))
        except Exception:
            return str(text)
except Exception:
    def fa(text):
        return str(text)

# ---------- پالت ----------
BG = hexc("#0D1117")
CARD = hexc("#161B22")
GREEN = hexc("#3FB950")
RED = hexc("#F85149")
YELLOW = hexc("#F0B429")
FG = hexc("#E6EDF3")
DIM = hexc("#8B949E")


def fmt(v):
    try:
        return "{:,.0f}".format(float(v))
    except Exception:
        return str(v)


class Card(BoxLayout):
    def __init__(self, **kw):
        kw.setdefault("orientation", "vertical")
        kw.setdefault("size_hint_y", None)
        kw.setdefault("padding", [dp(12), dp(8)])
        kw.setdefault("spacing", dp(2))
        super(Card, self).__init__(**kw)
        from kivy.graphics import Color, RoundedRectangle
        with self.canvas.before:
            Color(rgba=CARD)
            self._bgrect = RoundedRectangle(radius=[dp(8)])
        self.bind(pos=self._sync, size=self._sync)
        self.bind(minimum_height=self.setter("height"))

    def _sync(self, *a):
        self._bgrect.pos = self.pos
        self._bgrect.size = self.size


def flabel(text, color=FG, size=14, bold=False, halign="right", h=None):
    lb = Label(text=fa(text), font_name=FONT, color=color,
               font_size=dp(size), bold=bold, halign=halign, valign="middle",
               size_hint_y=None, markup=False)
    lb.bind(width=lambda s, w: setattr(s, "text_size", (w, None)))
    lb.bind(texture_size=lambda s, ts: setattr(s, "height", max(ts[1] + dp(4), dp(h or 0))))
    return lb


def fbutton(text, bgcolor, on_press, fg_black=True, h=44):
    b = Button(text=fa(text), font_name=FONT, bold=True, font_size=dp(14),
               background_normal="", background_color=bgcolor,
               color=(0, 0, 0, 1) if fg_black else FG,
               size_hint_y=None, height=dp(h))
    b.bind(on_release=lambda *a: on_press())
    return b


class ArbitrageApp(App):
    title = "آربیتراژ پرو"

    def build(self):
        try:
            from kivy.core.window import Window
            if Window is not None:
                Window.clearcolor = BG
        except Exception:
            pass

        self.tab_index = 0
        root = BoxLayout(orientation="vertical", padding=[dp(10), dp(8)], spacing=dp(6))

        root.add_widget(flabel("⚡ آربیتراژ پرو — صرافی‌های ایران",
                               color=FG, size=17, bold=True, halign="center", h=30))
        self.status_lb = flabel("...", color=DIM, size=11, halign="center", h=22)
        root.add_widget(self.status_lb)

        # برگه‌ها
        tabs = BoxLayout(size_hint_y=None, height=dp(42), spacing=dp(4))
        self.tab_buttons = []
        for i, name in enumerate(["تنظیمات", "تاریخچه", "قیمت‌ها", "فرصت‌ها"]):
            idx = 3 - i  # چیدمان راست‌به‌چپ
            b = Button(text=fa(name), font_name=FONT, font_size=dp(13),
                       background_normal="", background_color=CARD, color=DIM)
            b.bind(on_release=lambda w, ix=idx: self.switch_tab(ix))
            self.tab_buttons.append((idx, b))
            tabs.add_widget(b)
        root.add_widget(tabs)

        self.scroll = ScrollView()
        self.body = BoxLayout(orientation="vertical", size_hint_y=None,
                              spacing=dp(8), padding=[0, dp(4)])
        self.body.bind(minimum_height=self.body.setter("height"))
        self.scroll.add_widget(self.body)
        root.add_widget(self.scroll)

        MONITOR.on_alert = self._notify_opportunity
        Clock.schedule_interval(lambda dt: self.refresh(), 5)
        self.switch_tab(0)
        return root

    # ---------- عمومی ----------
    def switch_tab(self, idx):
        self.tab_index = idx
        for i, b in self.tab_buttons:
            b.color = YELLOW if i == idx else DIM
            b.bold = i == idx
        self.refresh()

    def refresh(self):
        age = "—"
        if MONITOR.last_cycle:
            age = "%d ثانیه پیش" % int(time.time() - MONITOR.last_cycle)
        mode = "🎓 تمرینی" if store.is_paper() else "🔴 واقعی"
        self.status_lb.text = fa("%s | %s | به‌روزرسانی: %s" % (mode, MONITOR.status, age))

        self.body.clear_widgets()
        [self.render_opps, self.render_prices,
         self.render_history, self.render_settings][self.tab_index]()

    def note(self, text, color=DIM):
        c = Card()
        c.add_widget(flabel(text, color=color, size=13))
        self.body.add_widget(c)

    def row(self, parent, right_text, left_text, rc=DIM, lc=FG, bold=False):
        r = BoxLayout(size_hint_y=None, height=dp(24))
        l = flabel(left_text, color=lc, size=13, bold=bold, halign="left")
        rr = flabel(right_text, color=rc, size=13, bold=bold, halign="right")
        l.size_hint_y = 1
        rr.size_hint_y = 1
        r.add_widget(l)
        r.add_widget(rr)
        parent.add_widget(r)

    # ---------- برگه: فرصت‌ها ----------
    def render_opps(self):
        if MONITOR.running:
            self.body.add_widget(fbutton("⛔ توقف پایش (Kill Switch)", RED, self.toggle_monitor))
        else:
            self.body.add_widget(fbutton("▶️ شروع پایش بازار", GREEN, self.toggle_monitor))

        if MONITOR.errors:
            msg = "⚠️ خطای اتصال:\n" + "\n".join(
                "• %s: %s" % (getattr(exchanges.by_name(k), "fa_name", k), v)
                for k, v in MONITOR.errors.items())
            self.note(msg, RED)

        with MONITOR.lock:
            opps = list(MONITOR.opportunities)

        if not opps:
            if MONITOR.running:
                self.note("هنوز فرصت سودده‌ای پیدا نشده.\nموتور هر ۱۰ ثانیه ۴ صرافی و ۵ ارز را بررسی می‌کند.")
            else:
                self.note("برای شروع، دکمهٔ «شروع پایش بازار» را بزنید.\n\n"
                          "نکته: برنامه در حالت تمرینی شروع می‌شود و هیچ پول واقعی جابه‌جا نمی‌کند.")
            return

        for o in opps:
            c = Card()
            buy_fa = exchanges.by_name(o.buy_ex).fa_name
            sell_fa = exchanges.by_name(o.sell_ex).fa_name
            self.row(c, "💎 " + o.symbol, "+%.2f٪ خالص" % o.net_pct, FG, GREEN, bold=True)
            self.row(c, "خرید از %s" % buy_fa, fmt(o.buy_vwap) + " ت")
            self.row(c, "فروش در %s" % sell_fa, fmt(o.sell_vwap) + " ت")
            self.row(c, "سود تخمینی", fmt(o.net_profit) + " تومان", DIM, YELLOW)
            c.add_widget(fbutton("اجرای این معامله", YELLOW,
                                 lambda o=o: self.confirm_trade(o), h=40))
            self.body.add_widget(c)

    def toggle_monitor(self):
        if MONITOR.running:
            MONITOR.stop()
        else:
            MONITOR.start()
        Clock.schedule_once(lambda dt: self.refresh(), 0.5)

    def confirm_trade(self, o):
        paper = store.is_paper()
        buy_fa = exchanges.by_name(o.buy_ex).fa_name
        sell_fa = exchanges.by_name(o.sell_ex).fa_name
        msg = ("ارز: %s\nخرید از: %s\nفروش در: %s\nمقدار: %.6f\n"
               "سود خالص تخمینی: %s تومان\n\n%s") % (
            o.symbol, buy_fa, sell_fa, o.amount, fmt(o.net_profit),
            "این معامله فقط شبیه‌سازی است." if paper
            else "⚠️ این معامله با پول واقعی انجام می‌شود!")

        box = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))
        box.add_widget(flabel(msg, size=13))
        btns = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        pop = Popup(title=fa("معاملهٔ تمرینی" if paper else "معاملهٔ واقعی"),
                    title_font=FONT, content=box, size_hint=(0.9, 0.6))

        def do_trade():
            pop.dismiss()
            import threading

            def run():
                rec = trader.execute(o, paper)
                self._after_trade(rec)
            threading.Thread(target=run, daemon=True).start()

        btns.add_widget(fbutton("انصراف", CARD, pop.dismiss, fg_black=False))
        btns.add_widget(fbutton("تأیید", GREEN, do_trade))
        box.add_widget(btns)
        pop.open()

    @mainthread
    def _after_trade(self, rec):
        if rec["status"] == "OK":
            self.toast("✅ ثبت شد: %s تومان" % fmt(rec["profit"]))
        else:
            self.toast("⚠️ " + rec["status"][:120])
        self.refresh()

    # ---------- برگه: قیمت‌ها ----------
    def render_prices(self):
        with MONITOR.lock:
            books = dict(MONITOR.books)
        if not books:
            self.note("داده‌ای موجود نیست — پایش را از برگهٔ «فرصت‌ها» شروع کنید.")
            return
        for sym in exchanges.SYMBOLS:
            lst = books.get(sym)
            if not lst:
                continue
            c = Card()
            self.row(c, "💰 %s / تومان" % sym, "", FG, FG, bold=True)
            for b in sorted(lst, key=lambda x: x.best_ask):
                fa_name = exchanges.by_name(b.exchange).fa_name
                self.row(c, fa_name,
                         "خرید: %s | فروش: %s" % (fmt(b.best_ask), fmt(b.best_bid)))
            self.body.add_widget(c)

    # ---------- برگه: تاریخچه ----------
    def render_history(self):
        trades = store.load_trades()
        total = sum(t["profit"] for t in trades if t["status"] == "OK")
        self.note("معاملات امروز: %d | مجموع سود ثبت‌شده: %s تومان"
                  % (store.trades_today(), fmt(total)), YELLOW)
        if not trades:
            self.note("هنوز معامله‌ای ثبت نشده.")
            return
        for t in trades[:50]:
            c = Card()
            ok = t["status"] == "OK"
            tag = "تمرینی" if t.get("paper") else "واقعی"
            ts = time.strftime("%m/%d %H:%M", time.localtime(t["t"]))
            self.row(c, "%s (%s) — %s" % (t["symbol"], tag, ts),
                     ("+%s ت" % fmt(t["profit"])) if ok else "✖",
                     FG, GREEN if ok else RED, bold=True)
            self.row(c, "خرید %s @ %s" % (exchanges.by_name(t["buy_ex"]).fa_name,
                                          fmt(t["buy_price"])),
                     "فروش %s @ %s" % (exchanges.by_name(t["sell_ex"]).fa_name,
                                       fmt(t["sell_price"])), DIM, DIM)
            if not ok:
                self.row(c, t["status"][:110], "", RED, RED)
            self.body.add_widget(c)

    # ---------- برگه: تنظیمات ----------
    def render_settings(self):
        self.section("حالت اجرا")
        self.switch_row("حالت تمرینی (بدون پول واقعی)", store.is_paper(), self._set_paper)
        self.switch_row("معاملهٔ خودکار (بدون تأیید دستی)", store.is_auto(),
                        lambda on: store.save_settings(auto=on))

        cfg = store.load_risk()
        self.section("مدیریت ریسک")
        self.input_row("بودجهٔ هر معامله (تومان)", str(int(cfg.budget_toman)),
                       self._save_budget)
        self.input_row("حداقل سود خالص (٪)", str(cfg.min_net_pct), self._save_min_net)
        self.input_row("حداکثر معامله در روز", str(cfg.max_trades_per_day),
                       self._save_max_trades)

        self.section("کلیدهای API")
        for ex in exchanges.EXCHANGES:
            if not ex.can_trade:
                continue
            has = bool(store.get_api_key(ex.name))
            label = "%s: %s" % (ex.fa_name, "✅ تنظیم شده — تغییر" if has else "تنظیم کلید API")
            self.body.add_widget(fbutton(label, CARD,
                                         lambda ex=ex: self.ask_api_key(ex), fg_black=False))

        self.section("درباره")
        self.note("نسخه ۵.۰ — پایش نوبیتکس، والکس، بیت‌پین و رمزینکس\n"
                  "معاملهٔ خودکار: نوبیتکس و والکس\n"
                  "محاسبه با VWAP عمقی + کارمزد + حاشیهٔ لغزش\n\n"
                  "⚠️ سلب مسئولیت: آربیتراژ دارای ریسک است؛ اجرای واقعی با مسئولیت کاربر.\n"
                  "توصیه: دسترسی «برداشت» کلید API را در پنل صرافی غیرفعال کنید.")

    def section(self, t):
        self.body.add_widget(flabel(t, color=YELLOW, size=14, bold=True, h=30))

    def switch_row(self, label, value, cb):
        c = Card(orientation="horizontal")
        c.height = dp(52)
        sw = Switch(active=value, size_hint_x=None, width=dp(70))
        sw.bind(active=lambda w, on: cb(on))
        lb = flabel(label, size=13)
        lb.size_hint_y = 1
        c.add_widget(sw)
        c.add_widget(lb)
        self.body.add_widget(c)

    def input_row(self, label, value, cb):
        c = Card()
        c.add_widget(flabel(label, color=DIM, size=12))
        ti = TextInput(text=value, font_name=FONT, multiline=False,
                       input_filter="float", size_hint_y=None, height=dp(40),
                       background_color=BG, foreground_color=FG,
                       cursor_color=YELLOW, font_size=dp(14))
        c.add_widget(ti)
        c.add_widget(fbutton("ذخیره", CARD,
                             lambda: (cb(ti.text), self.toast("ذخیره شد")),
                             fg_black=False, h=36))
        self.body.add_widget(c)

    def _set_paper(self, on):
        if not on:
            box = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))
            box.add_widget(flabel(
                "با خاموش‌کردن حالت تمرینی، معاملات با پول واقعی انجام می‌شوند.\n"
                "مسئولیت سود و زیان با شماست. ادامه می‌دهید؟", size=13))
            btns = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
            pop = Popup(title=fa("⚠️ هشدار جدی"), title_font=FONT,
                        content=box, size_hint=(0.9, 0.45))

            def yes():
                store.save_settings(paper=False)
                pop.dismiss()
                self.refresh()

            def no():
                pop.dismiss()
                self.refresh()

            btns.add_widget(fbutton("خیر", CARD, no, fg_black=False))
            btns.add_widget(fbutton("بله، حالت واقعی", RED, yes))
            box.add_widget(btns)
            pop.open()
        else:
            store.save_settings(paper=True)
            self.refresh()

    def _save_budget(self, v):
        try:
            x = float(v)
            if 1_000_000 <= x <= 10_000_000_000:
                cfg = store.load_risk()
                cfg.budget_toman = x
                store.save_risk(cfg)
        except Exception:
            pass

    def _save_min_net(self, v):
        try:
            x = float(v)
            if 0.05 <= x <= 20:
                cfg = store.load_risk()
                cfg.min_net_pct = x
                store.save_risk(cfg)
        except Exception:
            pass

    def _save_max_trades(self, v):
        try:
            x = int(float(v))
            if 1 <= x <= 100:
                cfg = store.load_risk()
                cfg.max_trades_per_day = x
                store.save_risk(cfg)
        except Exception:
            pass

    def ask_api_key(self, ex):
        box = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))
        box.add_widget(flabel(
            "کلید فقط روی همین گوشی ذخیره می‌شود.\n"
            "برای امنیت، دسترسی برداشت را در پنل صرافی غیرفعال کنید.", size=12, color=DIM))
        ti = TextInput(password=True, multiline=False, font_name=FONT,
                       size_hint_y=None, height=dp(40),
                       background_color=BG, foreground_color=FG, cursor_color=YELLOW)
        box.add_widget(ti)
        btns = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        pop = Popup(title=fa("کلید API " + ex.fa_name), title_font=FONT,
                    content=box, size_hint=(0.9, 0.5))

        def save():
            store.save_api_key(ex.name, ti.text.strip())
            pop.dismiss()
            self.toast("ذخیره شد")
            self.refresh()

        def delete():
            store.save_api_key(ex.name, "")
            pop.dismiss()
            self.toast("حذف شد")
            self.refresh()

        btns.add_widget(fbutton("حذف کلید", RED, delete))
        btns.add_widget(fbutton("ذخیره", GREEN, save))
        box.add_widget(btns)
        pop.open()

    # ---------- ابزار ----------
    @mainthread
    def toast(self, msg):
        try:
            from kivy.uix.label import Label as L
            p = Popup(title="", separator_height=0, content=L(
                text=fa(msg), font_name=FONT, font_size=dp(13)),
                size_hint=(0.85, 0.18), auto_dismiss=True)
            p.open()
            Clock.schedule_once(lambda dt: p.dismiss(), 2.2)
        except Exception:
            pass

    def _notify_opportunity(self, opp):
        # نوتیفیکیشن اندروید (در دسکتاپ نادیده گرفته می‌شود)
        try:
            from jnius import autoclass
            PythonActivity = autoclass("org.kivy.android.PythonActivity")
            Context = autoclass("android.content.Context")
            NotificationBuilder = autoclass("android.app.Notification$Builder")
            NotificationChannel = autoclass("android.app.NotificationChannel")
            NotificationManager = autoclass("android.app.NotificationManager")
            activity = PythonActivity.mActivity
            nm = activity.getSystemService(Context.NOTIFICATION_SERVICE)
            ch = NotificationChannel("arb", "Arbitrage",
                                     NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
            b = NotificationBuilder(activity, "arb")
            b.setContentTitle("فرصت آربیتراژ: %s (%.2f٪)" % (opp.symbol, opp.net_pct))
            b.setContentText("سود ≈ %s تومان" % fmt(opp.net_profit))
            b.setSmallIcon(activity.getApplicationInfo().icon)
            nm.notify(2, b.build())
        except Exception:
            pass

    def on_pause(self):
        return True  # پایش در پس‌زمینه ادامه می‌یابد

    def on_resume(self):
        self.refresh()


if __name__ == "__main__":
    ArbitrageApp().run()
