# -*- coding: utf-8 -*-
"""ظاهر برنامه: فونت فارسی، شکل‌دهی/راست‌به‌چپ، رنگ‌ها و قالب‌بندی اعداد."""
import os

from kivy.core.text import LabelBase
from kivy.metrics import dp, sp
from kivy.utils import get_color_from_hex as hexc

import jalali

FONT = "Roboto"
BOLD = "Roboto"
_here = os.path.dirname(os.path.abspath(__file__))
_regular = os.path.join(_here, "assets", "Vazirmatn-Regular.ttf")
_bold = os.path.join(_here, "assets", "Vazirmatn-Bold.ttf")
if os.path.exists(_regular):
    LabelBase.register(name="Vazir", fn_regular=_regular,
                       fn_bold=_bold if os.path.exists(_bold) else _regular)
    FONT = "Vazir"
    BOLD = "Vazir"

try:
    import arabic_reshaper
    from bidi.algorithm import get_display

    _reshaper = arabic_reshaper.ArabicReshaper()

    def fa(text):
        """شکل‌دهی و راست‌به‌چپ‌سازی متن فارسی برای نمایش در کیوی."""
        if text is None:
            return ""
        try:
            return get_display(_reshaper.reshape(str(text)))
        except Exception:
            return str(text)
except Exception:  # اگر کتابخانه در دسترس نبود، برنامه بدون شکل‌دهی هم کار می‌کند
    def fa(text):
        return "" if text is None else str(text)


# ---------- پالت ----------
BG = hexc("#0B0F14")
SURFACE = hexc("#131A22")
CARD = hexc("#182230")
BORDER = hexc("#26303D")
FG = hexc("#E6EDF3")
MUTED = hexc("#8B98A5")
ACCENT = hexc("#2EA043")
ACCENT_SOFT = hexc("#1F6FEB")
DANGER = hexc("#F85149")
WARNING = hexc("#D29922")
OK = hexc("#3FB950")
INFO = hexc("#58A6FF")

SEVERITY_COLOR = {
    "بحرانی": DANGER,
    "زیاد": hexc("#F0883E"),
    "متوسط": WARNING,
    "کم": OK,
}
STATUS_COLOR = {
    "باز": INFO,
    "در دست اقدام": hexc("#D2A8FF"),
    "منتظر قطعه": WARNING,
    "انجام‌شده": OK,
    "به تعویق افتاده": MUTED,
}

SIZE = {"title": sp(17), "body": sp(14), "small": sp(12), "tiny": sp(11)}


def severity_color(name):
    return SEVERITY_COLOR.get(name, MUTED)


def status_color(name):
    return STATUS_COLOR.get(name, MUTED)


def fa_num(value, default="0"):
    """عدد با جداکنندهٔ هزارگان و ارقام فارسی."""
    try:
        return jalali.persian_digits("{:,}".format(int(value)))
    except (TypeError, ValueError):
        return jalali.persian_digits(str(default))


def fa_money(value):
    """مبلغ به تومان با جداکننده (صفر یعنی «—»)."""
    try:
        n = int(value)
    except (TypeError, ValueError):
        return "—"
    if n <= 0:
        return "—"
    return fa_num(n) + " تومان"


def fa_date(value, with_time=False):
    txt = jalali.format_jalali(value, with_time=with_time)
    return jalali.persian_digits(txt) if txt else "—"


PAD = dp(12)
GAP = dp(8)
ROW_H = dp(84)
