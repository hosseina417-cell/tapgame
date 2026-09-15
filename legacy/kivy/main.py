# -*- coding: utf-8 -*-
"""
دفترچهٔ ایرادات خودرو — نسخهٔ ۳

معماریِ ماژولار:
  models      مدل داده و اعتبارسنجی (بدون وابستگی به رابط)
  repository  ذخیره‌سازی، مهاجرت اسکیما، کوئری و آمار
  jalali      تقویم شمسی
  theme       فونت، رنگ، شکل‌دهی فارسی
  widgets     ویجت‌های بازیافت‌شونده و ورودیِ راست‌به‌چپ

اجرای دسکتاپ برای تست:  python main.py
"""
import os
import threading

from kivy.app import App
from kivy.clock import Clock
from kivy.lang import Builder
from kivy.metrics import dp, sp
from kivy.properties import StringProperty
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.recycleview import RecycleView
from kivy.uix.recycleview.layout import LayoutSelectionBehavior
from kivy.uix.recycleboxlayout import RecycleBoxLayout
from kivy.uix.screenmanager import Screen, ScreenManager
from kivy.uix.scrollview import ScrollView
from kivy.utils import get_color_from_hex as hexc

import jalali
import models
from models import (
    CATEGORIES, DONE_STATUS, LIMITS, SEVERITIES, SORTS, STATUSES,
    Defect, Vehicle, new_id, now_iso, to_int,
)
from repository import Repository
from theme import (
    ACCENT, ACCENT_SOFT, BG, BORDER, CARD, DANGER, FG, FONT, INFO, MUTED, OK,
    SIZE, WARNING, fa, fa_date, fa_money, fa_num, severity_color, status_color,
)
from widgets import DefectCard, FaTextInput, ValueSpinner, VehicleCard

ALL = "__all__"

KV = """
#:import dp kivy.metrics.dp
#:import sp kivy.metrics.sp

<Card>:
    canvas.before:
        Color:
            rgba: self.background_color
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(12),]

<DefectCard>:
    orientation: 'vertical'
    size_hint_y: None
    height: dp(94)
    padding: [dp(16), dp(10), dp(12), dp(10)]
    spacing: dp(3)
    canvas.before:
        Color:
            rgba: self.background_color
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(12),]
        Color:
            rgba: self.sev_color
        RoundedRectangle:
            pos: self.x + dp(4), self.y + dp(10)
            size: dp(4), self.height - dp(20)
            radius: [dp(2),]
    Label:
        text: root.title
        font_name: app.font_name
        font_size: sp(15)
        bold: True
        color: 0.90, 0.93, 0.95, 1
        halign: 'right'
        valign: 'middle'
        text_size: self.width, None
        shorten: True
        shorten_from: 'right'
        size_hint_y: None
        height: dp(22)
    Label:
        text: root.subtitle
        font_name: app.font_name
        font_size: sp(12)
        color: 0.55, 0.62, 0.68, 1
        halign: 'right'
        valign: 'middle'
        text_size: self.width, None
        shorten: True
        shorten_from: 'right'
        size_hint_y: None
        height: dp(18)
    Label:
        text: root.meta
        font_name: app.font_name
        font_size: sp(11)
        color: 0.62, 0.70, 0.78, 1
        halign: 'right'
        valign: 'middle'
        text_size: self.width, None
        shorten: True
        shorten_from: 'right'
        size_hint_y: None
        height: dp(16)
    Label:
        text: root.status
        font_name: app.font_name
        font_size: sp(11)
        color: root.sev_color
        halign: 'left'
        valign: 'middle'
        text_size: self.width, None
        size_hint_y: None
        height: dp(16)

<VehicleCard>:
    orientation: 'vertical'
    size_hint_y: None
    height: dp(78)
    padding: [dp(16), dp(10), dp(12), dp(10)]
    spacing: dp(2)
    canvas.before:
        Color:
            rgba: self.background_color
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(12),]
    Label:
        text: root.title
        font_name: app.font_name
        font_size: sp(15)
        bold: True
        color: 0.90, 0.93, 0.95, 1
        halign: 'right'
        valign: 'middle'
        text_size: self.width, None
        shorten: True
        shorten_from: 'right'
        size_hint_y: None
        height: dp(22)
    Label:
        text: root.subtitle
        font_name: app.font_name
        font_size: sp(12)
        color: 0.55, 0.62, 0.68, 1
        halign: 'right'
        valign: 'middle'
        text_size: self.width, None
        size_hint_y: None
        height: dp(18)
    Label:
        text: root.badge
        font_name: app.font_name
        font_size: sp(11)
        color: 0.62, 0.70, 0.78, 1
        halign: 'right'
        valign: 'middle'
        text_size: self.width, None
        size_hint_y: None
        height: dp(16)

<StatTile>:
    orientation: 'vertical'
    size_hint: 1, None
    height: dp(74)
    padding: [dp(10), dp(8), dp(10), dp(8)]
    spacing: dp(2)
    Label:
        text: root.value
        font_name: app.font_name
        font_size: sp(20)
        bold: True
        color: root.tone
        halign: 'right'
        valign: 'middle'
        text_size: self.width, None
        size_hint_y: None
        height: dp(30)
    Label:
        text: root.label
        font_name: app.font_name
        font_size: sp(11)
        color: 0.55, 0.62, 0.68, 1
        halign: 'right'
        valign: 'middle'
        text_size: self.width, None
        size_hint_y: None
        height: dp(18)
"""


# ---------- ابزارهای رابط ----------
def fa_label(text, size=None, color=None, bold=False, height=None, halign="right"):
    lbl = Label(text=fa(text), font_name=App.get_running_app().font_name,
                font_size=size or SIZE["body"], color=color or FG,
                halign=halign, valign="middle", bold=bold,
                size_hint_y=None, height=height or dp(24))
    lbl.bind(width=lambda w, _w: setattr(w, "text_size", (w.width, None)))
    return lbl


def fa_button(text, on_press=None, bg=None, height=dp(44), size_hint_x=1):
    btn = Button(text=fa(text), font_name=App.get_running_app().font_name,
                 font_size=SIZE["body"], size_hint=(size_hint_x, None), height=height,
                 background_normal="", background_color=bg or ACCENT_SOFT,
                 color=(1, 1, 1, 1))
    if on_press:
        btn.bind(on_press=on_press)
    return btn


def form_row(label_text, widget):
    """یک ردیف فرم: برچسبِ راست‌چین بالای ورودی."""
    box = BoxLayout(orientation="vertical", size_hint_y=None,
                    height=widget.height + dp(22), spacing=dp(2))
    box.add_widget(fa_label(label_text, size=SIZE["small"], color=MUTED, height=dp(20)))
    box.add_widget(widget)
    return box


class Toast(Popup):
    pass


# ---------- صفحهٔ ایرادات ----------
class DefectsScreen(Screen):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.filters = {"vehicle_id": ALL, "status": ALL, "severity": ALL, "sort": SORTS[0]}
        root = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))

        self.search = FaTextInput(hint_text="جستجو در عنوان، توضیحات، دسته...",
                                  size_hint_y=None, height=dp(42), multiline=False)
        self.search.bind(logical_text=self.on_search_change)
        root.add_widget(self.search)

        # ردیف فیلترها
        row = BoxLayout(size_hint_y=None, height=dp(42), spacing=dp(6))
        self.vehicle_spinner = ValueSpinner(size_hint_x=0.34)
        self.status_spinner = ValueSpinner(size_hint_x=0.24)
        self.severity_spinner = ValueSpinner(size_hint_x=0.22)
        self.sort_spinner = ValueSpinner(size_hint_x=0.20)
        for sp_ in (self.vehicle_spinner, self.status_spinner,
                    self.severity_spinner, self.sort_spinner):
            sp_.bind(text=self.on_filter_change)
            row.add_widget(sp_)
        root.add_widget(row)

        self.rv = RecycleView(viewclass="DefectCard", bar_width=dp(6))
        self.rv.layout_manager = RecycleBoxLayout(orientation="vertical", spacing=dp(8),
                                                  padding=[dp(2), dp(2), dp(2), dp(80)],
                                                  default_size=(1, dp(94)),
                                                  default_size_hint=(1, None),
                                                  size_hint_y=None)
        self.rv.layout_manager.bind(minimum_height=self.rv.layout_manager.setter("height"))
        self.rv.add_widget(self.rv.layout_manager)
        root.add_widget(self.rv)

        self.empty = Label(text=fa(""), font_name=App.get_running_app().font_name,
                           halign="center", color=MUTED, font_size=SIZE["body"],
                           size_hint_y=None, height=0)
        root.add_widget(self.empty)

        root.add_widget(fa_button("+ ثبت ایراد جدید", self.add_new, bg=ACCENT, height=dp(52)))
        self.add_widget(root)

    # ---- رویدادها ----
    def on_search_change(self, _inst, value):
        app = App.get_running_app()
        app.refresh_defects()

    def on_filter_change(self, _inst, _value):
        app = App.get_running_app()
        if app:
            app.refresh_defects()

    def add_new(self, *_args):
        App.get_running_app().defect_dialog(None)

    # ---- داده ----
    def active_filters(self):
        return {
            "vehicle_id": self.vehicle_spinner.selected_raw,
            "status": self.status_spinner.selected_raw,
            "severity": self.severity_spinner.selected_raw,
            "sort": self.sort_spinner.selected_raw,
            "search": self.search.logical_text.strip(),
        }


# ---------- صفحهٔ خودروها ----------
class VehiclesScreen(Screen):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        root = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))
        root.add_widget(fa_label("خودروهای من", size=SIZE["title"], height=dp(30), bold=True))
        self.rv = RecycleView(viewclass="VehicleCard", bar_width=dp(6))
        self.rv.layout_manager = RecycleBoxLayout(orientation="vertical", spacing=dp(8),
                                                  default_size=(1, dp(78)),
                                                  default_size_hint=(1, None),
                                                  size_hint_y=None)
        self.rv.layout_manager.bind(minimum_height=self.rv.layout_manager.setter("height"))
        self.rv.add_widget(self.rv.layout_manager)
        root.add_widget(self.rv)
        root.add_widget(fa_button("+ خودروی جدید", self.add_new, bg=ACCENT, height=dp(52)))
        self.add_widget(root)

    def add_new(self, *_args):
        App.get_running_app().vehicle_dialog(None)


# ---------- برنامه ----------
class CarDefectApp(App):
    font_name = StringProperty("Roboto")

    def build(self):
        self.title = "دفترچه ایرادات خودرو"
        self.font_name = FONT
        Builder.load_string(KV)
        self.repo = Repository(os.path.join(self.user_data_dir, "car_defects.json"))
        self.repo.load()
        self.pending_undo = None

        self.sm = ScreenManager()
        self.defects_screen = DefectsScreen(name="defects")
        self.vehicles_screen = VehiclesScreen(name="vehicles")
        self.sm.add_widget(self.defects_screen)
        self.sm.add_widget(self.vehicles_screen)

        root = BoxLayout(orientation="vertical")
        root.add_widget(self.header())
        root.add_widget(self.sm)
        root.add_widget(self.nav_bar())
        return root

    # ---------- اجزای ثابت رابط ----------
    def header(self):
        bar = BoxLayout(size_hint_y=None, height=dp(52), padding=[dp(12), dp(6)])
        bar.add_widget(fa_label("دفترچهٔ ایرادات خودرو", size=SIZE["title"], bold=True,
                                height=dp(40), halign="right"))
        return bar

    def nav_bar(self):
        bar = BoxLayout(size_hint_y=None, height=dp(56), spacing=dp(6), padding=[dp(8), dp(6)])
        for label, name in (("ایرادات", "defects"), ("خودروها", "vehicles")):
            btn = fa_button(label, lambda *_a, n=name: self.go(n),
                            bg=ACCENT_SOFT, height=dp(44))
            bar.add_widget(btn)
        return bar

    def go(self, name):
        self.sm.current = name
        self.refresh_all()

    # ---------- به‌روزرسانی ----------
    def on_start(self):
        self.refresh_all()

    def refresh_all(self):
        self.refresh_filter_options()
        self.refresh_defects()
        self.refresh_vehicles()

    def refresh_filter_options(self):
        screen = self.defects_screen
        vehicles = self.repo.active_vehicles()
        raw = [ALL] + [v.id for v in vehicles]
        screen.vehicle_spinner.set_values(raw, display=lambda r: fa(
            "همهٔ خودروها" if r == ALL else self.repo.vehicle_name(r)))
        screen.status_spinner.set_values([ALL] + STATUSES,
                                         display=lambda r: fa("همهٔ وضعیت‌ها" if r == ALL else r))
        screen.severity_spinner.set_values([ALL] + SEVERITIES,
                                           display=lambda r: fa("همهٔ شدت‌ها" if r == ALL else r))
        screen.sort_spinner.set_values(SORTS, display=lambda r: fa(r))

    def refresh_defects(self):
        screen = self.defects_screen
        f = screen.active_filters()
        items = self.repo.query(
            vehicle_id=None if f["vehicle_id"] in (None, ALL) else f["vehicle_id"],
            status=None if f["status"] in (None, ALL) else f["status"],
            severity=None if f["severity"] in (None, ALL) else f["severity"],
            search=f["search"], sort=f["sort"],
        )
        data = []
        for d in items:
            vehicle = self.repo.get_vehicle(d.vehicle_id)
            km = ("%s km" % fa_num(d.odometer)) if d.odometer else ""
            data.append({
                "defect_id": d.id,
                "title": fa(d.title),
                "subtitle": fa("%s • %s%s" % (d.category, self.repo.vehicle_name(d.vehicle_id),
                                              (" • " + km) if km else "")),
                "meta": fa("%s • برآورد %s" % (fa_date(d.reported_at), fa_money(d.estimated_cost))),
                "status": fa(d.status),
                "severity": d.severity,
                "sev_color": severity_color(d.severity),
            })
        screen.rv.data = data
        if data:
            screen.empty.text = ""
            screen.empty.height = 0
        else:
            text = fa("هیچ ایرادی با این فیلترها پیدا نشد." if getattr(self.repo, "defects", None)
                      else "هنوز ایرادی ثبت نشده است. دکمهٔ زیر را بزنید.")
            screen.empty.text = text
            screen.empty.height = dp(40)

    def refresh_vehicles(self):
        data = []
        for v in self.repo.vehicles:
            count = sum(1 for d in self.repo.defects if d.vehicle_id == v.id)
            open_count = sum(1 for d in self.repo.defects
                             if d.vehicle_id == v.id and d.is_open())
            subtitle = " ".join(p for p in (v.plate, "%s %s" % (v.brand, v.model)) if p.strip())
            data.append({
                "vehicle_id": v.id,
                "title": fa(v.display_name()),
                "subtitle": fa(subtitle or "بدون مشخصات"),
                "badge": fa("%s ایراد (%s باز) • %s km" % (
                    fa_num(count), fa_num(open_count), fa_num(v.odometer))),
            })
        self.vehicles_screen.rv.data = data

    # ---------- ناوبریِ رکوردها ----------
    def open_defect(self, defect_id):
        self.defect_dialog(self.repo.get_defect(defect_id))

    def open_vehicle(self, vehicle_id):
        self.vehicle_dialog(self.repo.get_vehicle(vehicle_id))

    # ---------- دیالوگ‌ها ----------
    def _popup(self, title, content, size=(0.96, 0.94)):
        popup = Popup(title=fa(title), content=content, size_hint=size,
                      background="", title_font=self.font_name,
                      title_size=SIZE["body"], separator_color=ACCENT)
        popup.open()
        return popup

    def toast(self, message):
        lbl = Label(text=fa(message), font_name=self.font_name, font_size=SIZE["body"],
                    color=FG)
        popup = Popup(title="", content=lbl, size_hint=(0.8, None), height=dp(60),
                      background="", separator_height=0, title_size=0.1)
        popup.open()
        Clock.schedule_once(lambda *_a: popup.dismiss(), 1.6)

    def confirm(self, title, message, on_yes, yes_text="تأیید"):
        box = BoxLayout(orientation="vertical", padding=dp(14), spacing=dp(12))
        box.add_widget(fa_label(message, height=dp(60)))
        row = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(8))
        row.add_widget(fa_button("انصراف", lambda *_a: popup.dismiss(), bg=BORDER))
        # در رابطِ راست‌به‌چپ، دکمهٔ اصلی سمت راست قرار می‌گیرد (آخرین فرزند)
        row.add_widget(fa_button(yes_text, lambda *_a: (popup.dismiss(), on_yes()), bg=DANGER))
        box.add_widget(row)
        popup = self._popup(title, box, size=(0.9, 0.42))
        return popup

    def vehicle_dialog(self, vehicle):
        editing = vehicle is not None
        data = vehicle.to_dict() if editing else {}
        box = BoxLayout(orientation="vertical", padding=dp(12), spacing=dp(8))
        name = FaTextInput(hint_text="نام خودرو (اجباری)", multiline=False,
                           size_hint_y=None, height=dp(42))
        name.set_value(data.get("name", ""))
        plate = FaTextInput(hint_text="پلاک", multiline=False, size_hint_y=None, height=dp(42))
        plate.set_value(data.get("plate", ""))
        brand = FaTextInput(hint_text="برند", multiline=False, size_hint_y=None, height=dp(42))
        brand.set_value(data.get("brand", ""))
        model = FaTextInput(hint_text="مدل", multiline=False, size_hint_y=None, height=dp(42))
        model.set_value(data.get("model", ""))
        year = FaTextInput(hint_text="سال ساخت", multiline=False, input_filter="int",
                           size_hint_y=None, height=dp(42))
        year.set_value(str(data.get("year") or ""))
        odo = FaTextInput(hint_text="کیلومتر فعلی", multiline=False, input_filter="int",
                          size_hint_y=None, height=dp(42))
        odo.set_value(str(data.get("odometer") or ""))
        interval = FaTextInput(hint_text="بازهٔ سرویس (کیلومتر)", multiline=False,
                               input_filter="int", size_hint_y=None, height=dp(42))
        interval.set_value(str(data.get("service_interval_km") or 10000))
        error = fa_label("", size=SIZE["small"], color=DANGER, height=dp(24))

        for widget in (name, plate, brand, model, year, odo, interval):
            box.add_widget(widget)
        box.add_widget(error)
        box.add_widget(fa_button("ذخیره", lambda *_a: save(), bg=ACCENT, height=dp(50)))
        if editing:
            box.add_widget(fa_button("حذف خودرو و ایرادهایش",
                                     lambda *_a: self._delete_vehicle(vehicle, popup),
                                     bg=DANGER, height=dp(44)))
        popup = self._popup("ویرایش خودرو" if editing else "خودروی جدید", box)

        def save():
            payload = {
                "name": name.get_value().strip(),
                "plate": plate.get_value().strip(),
                "brand": brand.get_value().strip(),
                "model": model.get_value().strip(),
                "year": to_int(year.get_value(), 0, 0, 2100),
                "odometer": to_int(odo.get_value(), 0, 0, 10_000_000),
                "service_interval_km": to_int(interval.get_value(), 10000, 0, 1_000_000),
            }
            errors = models.validate_vehicle(payload, self.repo)
            if errors:
                error.set_text(list(errors.values())[0])
                return
            if editing:
                self.repo.update_vehicle(vehicle.id, payload, auto_save=False)
                self.toast("خودرو به‌روزرسانی شد")
            else:
                self.repo.add_vehicle(payload, auto_save=False)
                self.toast("خودرو اضافه شد")
            self.persist()
            popup.dismiss()

    def _delete_vehicle(self, vehicle, parent_popup):
        def do_delete():
            self.repo.delete_vehicle(vehicle.id, auto_save=False)
            self.persist()
            self.toast("خودرو و ایرادهایش حذف شدند")
        parent_popup.dismiss()
        self.confirm("حذف خودرو",
                     "خودروی «%s» و همهٔ ایرادهای آن حذف می‌شوند. این کار قابل بازگشت نیست."
                     % vehicle.display_name(), do_delete, yes_text="حذف")

    def defect_dialog(self, defect):
        editing = defect is not None
        data = defect.to_dict() if editing else {}
        vehicles = self.repo.active_vehicles()
        if not vehicles:
            self.toast("ابتدا یک خودرو اضافه کنید")
            self.sm.current = "vehicles"
            return

        box = BoxLayout(orientation="vertical", padding=dp(12), spacing=dp(6))
        scroll_content = BoxLayout(orientation="vertical", spacing=dp(6), size_hint_y=None)
        scroll_content.bind(minimum_height=scroll_content.setter("height"))

        vehicle_spinner = ValueSpinner(size_hint_y=None, height=dp(42))
        raw_ids = [v.id for v in vehicles]
        vehicle_spinner.set_values(raw_ids, display=lambda r: fa(self.repo.vehicle_name(r)))
        if data.get("vehicle_id") in raw_ids:
            vehicle_spinner.select_raw(data["vehicle_id"], display=lambda r: fa(self.repo.vehicle_name(r)))

        title = FaTextInput(hint_text="عنوان ایراد (اجباری)", multiline=False,
                            size_hint_y=None, height=dp(42))
        title.set_value(data.get("title", ""))
        category = ValueSpinner(size_hint_y=None, height=dp(42))
        category.set_values(CATEGORIES)
        category.select_raw(data.get("category", CATEGORIES[-1]))
        severity = ValueSpinner(size_hint_y=None, height=dp(42))
        severity.set_values(SEVERITIES)
        severity.select_raw(data.get("severity", "متوسط"))
        status = ValueSpinner(size_hint_y=None, height=dp(42))
        status.set_values(STATUSES)
        status.select_raw(data.get("status", "باز"))
        location = FaTextInput(hint_text="محل/قطعه (اختیاری)", multiline=False,
                               size_hint_y=None, height=dp(42))
        location.set_value(data.get("location", ""))
        odo = FaTextInput(hint_text="کیلومتر در زمان ثبت", multiline=False, input_filter="int",
                          size_hint_y=None, height=dp(42))
        odo.set_value(str(data.get("odometer") or ""))
        cost = FaTextInput(hint_text="هزینهٔ تخمینی (تومان)", multiline=False, input_filter="int",
                           size_hint_y=None, height=dp(42))
        cost.set_value(str(data.get("estimated_cost") or ""))
        actual = FaTextInput(hint_text="هزینهٔ واقعی پس از تعمیر (تومان)", multiline=False,
                             input_filter="int", size_hint_y=None, height=dp(42))
        actual.set_value(str(data.get("actual_cost") or ""))
        due = FaTextInput(hint_text="سررسید (شمسی: 1405/06/24)", multiline=False,
                          size_hint_y=None, height=dp(42))
        due.set_value(jalali.format_jalali(data.get("due_date", "")) if data.get("due_date") else "")
        desc = FaTextInput(hint_text="توضیحات", size_hint_y=None, height=dp(96))
        desc.set_value(data.get("description", ""))
        error = fa_label("", size=SIZE["small"], color=DANGER, height=dp(22))

        for w in (form_row("خودرو", vehicle_spinner), form_row("عنوان", title),
                  form_row("دسته", category), form_row("شدت", severity),
                  form_row("وضعیت", status), form_row("محل/قطعه", location),
                  form_row("کیلومتر", odo), form_row("هزینهٔ تخمینی", cost),
                  form_row("هزینهٔ واقعی", actual), form_row("سررسید", due),
                  form_row("توضیحات", desc)):
            scroll_content.add_widget(w)
        scroll = ScrollView(size_hint=(1, 1), bar_width=dp(6))
        scroll.add_widget(scroll_content)
        box.add_widget(scroll)
        box.add_widget(error)
        box.add_widget(fa_button("ذخیره", lambda *_a: save(), bg=ACCENT, height=dp(50)))
        if editing:
            box.add_widget(fa_button("حذف ایراد", lambda *_a: self._delete_defect(defect, popup),
                                     bg=DANGER, height=dp(44)))
        popup = self._popup("ویرایش ایراد" if editing else "ثبت ایراد جدید", box)

        def save():
            due_raw = due.get_value().strip()
            due_iso = ""
            if due_raw:
                parts = [p for p in due_raw.replace("/", " ").replace("-", " ").split() if p]
                if len(parts) == 3:
                    due_iso = jalali.parse_jalali(parts[0], parts[1], parts[2])
                if not due_iso:
                    error.set_text("فرمت سررسید باید مانند 1405/06/24 باشد")
                    return
            payload = {
                "vehicle_id": vehicle_spinner.selected_raw,
                "title": title.get_value().strip(),
                "category": category.selected_raw,
                "severity": severity.selected_raw,
                "status": status.selected_raw,
                "location": location.get_value().strip(),
                "odometer": to_int(odo.get_value(), 0, 0, 10_000_000),
                "estimated_cost": to_int(cost.get_value(), 0, 0, 100_000_000_000),
                "actual_cost": to_int(actual.get_value(), 0, 0, 100_000_000_000),
                "due_date": due_iso,
                "description": desc.get_value().strip(),
            }
            errors = models.validate_defect(payload, self.repo)
            if errors:
                error.set_text(list(errors.values())[0])
                return
            if editing:
                self.repo.update_defect(defect.id, payload, auto_save=False)
                self.toast("ایراد به‌روزرسانی شد")
            else:
                self.repo.add_defect(payload, auto_save=False)
                self.toast("ایراد ثبت شد")
            self.persist()
            popup.dismiss()

    def _delete_defect(self, defect, parent_popup):
        def do_delete():
            removed = defect.to_dict()
            self.repo.delete_defect(defect.id, auto_save=False)
            self.persist()
            self.pending_undo = (Defect.from_dict(removed), Clock.schedule_once(
                lambda *_a: setattr(self, "pending_undo", None), 8))
            self.toast("ایراد حذف شد")

        parent_popup.dismiss()
        self.confirm("حذف ایراد", "«%s» حذف می‌شود. این کار قابل بازگشت نیست." % defect.title,
                     do_delete, yes_text="حذف")

    # ---------- ذخیره‌سازی پس‌زمینه ----------
    def persist(self):
        """ذخیره در ترد پس‌زمینه تا رابط هرگز قفل نشود."""
        def work():
            ok = self.repo.save()
            Clock.schedule_once(lambda *_a: self._after_save(ok), 0)

        threading.Thread(target=work, daemon=True).start()

    def _after_save(self, ok):
        if not ok:
            self.toast("خطا در ذخیره‌سازی: %s" % (self.repo.last_error or "نامعلوم"))
        self.refresh_all()


if __name__ == "__main__":
    CarDefectApp().run()
