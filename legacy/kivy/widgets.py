# -*- coding: utf-8 -*-
"""ویجت‌های کمکی و قابل‌استفادهٔ مجدد (تعریف‌شده در ماژول تا در Factory ثبت شوند)."""
from kivy.properties import (
    BooleanProperty, ColorProperty, ListProperty, NumericProperty,
    ObjectProperty, StringProperty,
)
from kivy.uix.behaviors import ButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.spinner import Spinner
from kivy.uix.textinput import TextInput
from kivy.utils import get_color_from_hex as hexc

from theme import ACCENT, BORDER, CARD, FG, FONT, MUTED, SIZE, fa, severity_color, status_color


class FaLabel(Label):
    """برچسب با فونت فارسی، راست‌چین و شکل‌دهی خودکار."""

    def __init__(self, **kwargs):
        kwargs.setdefault("font_name", FONT)
        kwargs.setdefault("halign", "right")
        kwargs.setdefault("valign", "middle")
        kwargs.setdefault("color", FG)
        text = kwargs.get("text", "")
        kwargs["text"] = fa(text)
        kwargs.setdefault("font_size", SIZE["body"])
        super().__init__(**kwargs)
        self.bind(size=self._on_size)

    def _on_size(self, *args):
        # راست‌چین‌کردن واقعی نیازمند محدودکردن text_size است
        self.text_size = (self.width, None)

    def set_text(self, value):
        self.text = fa(value)


class FaTextInput(TextInput):
    """ورودی متن فارسی.

    هنگام تایپ، متنِ منطقی را نگه می‌دارد (تا ویرایش با کیبورد درست کار کند)؛
    هنگامِ بدون‌تمرکز، همان متن را شکل‌دهی و راست‌به‌چپ نمایش می‌دهد.
    محدودیت: رندرر متن کیوی هنگام تایپ، حروف را جدا نشان می‌دهد (محدودیت پلتفرم).
    """

    logical_text = StringProperty("")
    _setting = BooleanProperty(False)

    def __init__(self, **kwargs):
        kwargs.setdefault("font_name", FONT)
        kwargs.setdefault("halign", "right")
        kwargs.setdefault("font_size", SIZE["body"])
        kwargs.setdefault("foreground_color", FG)
        kwargs.setdefault("background_color", hexc("#0F1620"))
        kwargs.setdefault("cursor_color", ACCENT)
        kwargs.setdefault("write_tab", False)
        hint = kwargs.get("hint_text", "")
        if hint:
            kwargs["hint_text"] = fa(hint)
        super().__init__(**kwargs)
        self.bind(focus=self._sync_focus, text=self._sync_text)

    # ---- همگام‌سازی متنِ منطقی و نمایشی ----
    def _sync_focus(self, _inst, focused):
        if focused:
            self._set(self.logical_text)
        else:
            if not self._setting:
                self.logical_text = self.text
            self._set(fa(self.logical_text))

    def _sync_text(self, _inst, value):
        if self._setting:
            return
        if self.focus:
            self.logical_text = value
        else:
            self.logical_text = self._strip_display(value)

    @staticmethod
    def _strip_display(value):
        """اگر متنِ نمایشی دست‌کاری شد، همان را به عنوان متن منطقی می‌پذیریم."""
        return value

    def _set(self, value):
        self._setting = True
        try:
            self.text = value
        finally:
            self._setting = False

    def set_value(self, value):
        self.logical_text = "" if value is None else str(value)
        self._set(self.logical_text if self.focus else fa(self.logical_text))

    def get_value(self):
        return self.text if self.focus else self.logical_text


class ValueSpinner(Spinner):
    """Spinner که مقدارِ خام را نگه می‌دارد و متنِ شکل‌دهی‌شده نشان می‌دهد."""

    raw_values = ListProperty([])
    display_values = ListProperty([])

    def set_values(self, raw_values, display=None):
        self.raw_values = list(raw_values)
        self.display_values = [display(r) if display else fa(r) for r in self.raw_values]
        self.values = self.display_values
        if self.text not in self.values and self.values:
            self.text = self.values[0]

    def select_raw(self, raw_value, display=None):
        if raw_value in self.raw_values:
            idx = self.raw_values.index(raw_value)
            self.text = self.display_values[idx]
            return True
        return False

    @property
    def selected_raw(self):
        if self.text in self.display_values:
            return self.raw_values[self.display_values.index(self.text)]
        return None


class Card(BoxLayout):
    background_color = ColorProperty(CARD)


class DefectCard(ButtonBehavior, Card):
    """ردیفِ یک ایراد در لیست (بازیافت‌شونده در RecycleView)."""

    defect_id = StringProperty("")
    title = StringProperty("")
    subtitle = StringProperty("")
    meta = StringProperty("")
    severity = StringProperty("")
    sev_color = ColorProperty(MUTED)
    status = StringProperty("")

    def on_release(self, *args):
        app = _running_app()
        if app is not None and self.defect_id:
            app.open_defect(self.defect_id)


class VehicleCard(ButtonBehavior, Card):
    vehicle_id = StringProperty("")
    title = StringProperty("")
    subtitle = StringProperty("")
    badge = StringProperty("")

    def on_release(self, *args):
        app = _running_app()
        if app is not None and self.vehicle_id:
            app.open_vehicle(self.vehicle_id)


class Chip(ButtonBehavior, Label):
    """فیلترِ کوچکِ قابل‌انتخاب."""
    active = BooleanProperty(False)


class StatTile(Card):
    label = StringProperty("")
    value = StringProperty("")
    tone = ColorProperty(FG)


class EmptyState(BoxLayout):
    message = StringProperty("")


def _running_app():
    from kivy.app import App
    try:
        return App.get_running_app()
    except Exception:
        return None
