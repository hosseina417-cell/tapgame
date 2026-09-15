# -*- coding: utf-8 -*-
"""تقویم شمسی (جلالی) — بدون وابستگی خارجی، مناسب برای بسته‌شدن داخل APK.

روش: قاعدهٔ کبیسهٔ دوره‌های ۳۳ساله (منطبق با تقویم رسمی ایران،
صحت‌سنجی‌شده در برابر دو پیاده‌سازی مرجع برای بازهٔ ۱۳۰۰–۱۶۰۰ شمسی)
و لنگرِ تأییدشدهٔ نوروز: ۱ فروردین ۱۴۰۰ = ۲۱ مارس ۲۰۲۱.
تمام محاسبات با datetime انجام می‌شود تا خطای انباشته نداشته باشیم.
"""
import datetime

JALALI_MONTH_NAMES = [
    "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
    "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
]
WEEKDAY_NAMES = ["دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه", "شنبه", "یکشنبه"]
PERSIAN_DIGITS = str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")

# نقاط شکستِ دوره‌ها (سال‌های شمسی)
_BREAKS = [-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181,
           1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178]

MIN_YEAR = 1178
MAX_YEAR = 1634

# لنگرِ تأییدشده: ۱ فروردینِ این سال شمسی برابر این تاریخ میلادی
_ANCHOR_JY = 1400
_ANCHOR_G = datetime.date(2021, 3, 21)

_NOWRUZ_CACHE = {_ANCHOR_JY: _ANCHOR_G}


def _div(a, b):
    """تقسیم با گردکردن به سمت صفر (هم‌رفتار با پیاده‌سازی مرجع)"""
    return int(a / b) if b else 0


def _mod(a, b):
    return a - _div(a, b) * b


def _segment(jy):
    """بازگرداندن (jp, jump): ابتدا و طولِ دوره‌ای که سال در آن است."""
    jp = _BREAKS[0]
    jump = 0
    for i in range(1, len(_BREAKS)):
        jm = _BREAKS[i]
        jump = jm - jp
        if jy < jm:
            break
        jp = jm
    return jp, jump


def is_leap_jalali(jy):
    """آیا سال شمسی کبیسه است (اسفند ۳۰ روزه)؟"""
    if not (MIN_YEAR <= jy <= MAX_YEAR):
        return False
    jp, jump = _segment(jy)
    n = jy - jp
    if jump - n < 6:
        n = n - jump + _div(jump + 4, 33) * 33
    leap = _mod(_mod(n + 1, 33) - 1, 4)
    if leap == -1:
        leap = 4
    return leap == 0


def jalali_year_length(jy):
    return 366 if is_leap_jalali(jy) else 365


def jalali_month_length(jy, jm):
    if not 1 <= jm <= 12:
        return 0
    if jm <= 6:
        return 31
    if jm <= 11:
        return 30
    return 30 if is_leap_jalali(jy) else 29


def nowruz(jy):
    """تاریخ میلادیِ ۱ فروردینِ سال شمسیِ داده‌شده."""
    if not (MIN_YEAR <= jy <= MAX_YEAR):
        raise ValueError("سال شمسی خارج از بازهٔ پشتیبانی‌شده: %s" % jy)
    # نزدیک‌ترین سالِ کشه‌شده
    best = min(_NOWRUZ_CACHE, key=lambda y: abs(y - jy))
    year, date = best, _NOWRUZ_CACHE[best]
    step = 1 if jy > year else -1
    while year != jy:
        if step > 0:
            date += datetime.timedelta(days=jalali_year_length(year))
            year += 1
        else:
            year -= 1
            date -= datetime.timedelta(days=jalali_year_length(year))
        _NOWRUZ_CACHE[year] = date
    return date


def gregorian_to_jalali(gy, gm, gd):
    """(سال، ماه، روز) میلادی → (سال، ماه، روز) شمسی."""
    date = datetime.date(gy, gm, gd)
    jy = gy - 621
    jy = max(MIN_YEAR, min(MAX_YEAR, jy))
    while date < nowruz(jy) and jy > MIN_YEAR:
        jy -= 1
    while jy < MAX_YEAR and date >= nowruz(jy + 1):
        jy += 1
    doy = (date - nowruz(jy)).days
    if doy < 186:
        jm, jd = 1 + doy // 31, 1 + doy % 31
    else:
        doy -= 186
        jm, jd = 7 + doy // 30, 1 + doy % 30
    return jy, jm, jd


def jalali_to_gregorian(jy, jm, jd):
    """(سال، ماه، روز) شمسی → (سال، ماه، روز) میلادی."""
    if not (MIN_YEAR <= jy <= MAX_YEAR) or not (1 <= jm <= 12):
        raise ValueError("تاریخ شمسی نامعتبر: %s/%s/%s" % (jy, jm, jd))
    maxd = jalali_month_length(jy, jm)
    if not (1 <= jd <= maxd):
        raise ValueError("روز شمسی نامعتبر: %s/%s/%s" % (jy, jm, jd))
    if jm <= 7:
        offset = (jm - 1) * 31
    else:
        offset = 186 + (jm - 7) * 30
    date = nowruz(jy) + datetime.timedelta(days=offset + jd - 1)
    return date.year, date.month, date.day


def _coerce(value):
    if value is None or value == "":
        return None
    if isinstance(value, datetime.datetime):
        return value
    if isinstance(value, datetime.date):
        return datetime.datetime(value.year, value.month, value.day)
    if isinstance(value, str):
        txt = value.strip().replace("T", " ")
        for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
            try:
                return datetime.datetime.strptime(txt, fmt)
            except ValueError:
                continue
    return None


def format_jalali(value, with_time=False, default="-"):
    """نمایش شمسیِ یک تاریخ (رشتهٔ ISO یا date/datetime)."""
    dt = _coerce(value)
    if dt is None:
        return "" if value in (None, "") else str(value)
    try:
        jy, jm, jd = gregorian_to_jalali(dt.year, dt.month, dt.day)
    except (ValueError, OverflowError):
        return default
    out = "%s/%s/%s" % (jy, str(jm).zfill(2), str(jd).zfill(2))
    if with_time and (dt.hour or dt.minute):
        out += " — %s:%s" % (str(dt.hour).zfill(2), str(dt.minute).zfill(2))
    return out


def today_iso():
    return datetime.date.today().isoformat()


def now_iso():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M")


def today_jalali():
    return format_jalali(datetime.date.today())


def jalali_weekday(value):
    dt = _coerce(value)
    return WEEKDAY_NAMES[dt.weekday()] if dt else ""


def persian_digits(text):
    return str(text).translate(PERSIAN_DIGITS)


def jalali_month_name(jm):
    try:
        jm = int(jm)
    except (TypeError, ValueError):
        return ""
    return JALALI_MONTH_NAMES[jm - 1] if 1 <= jm <= 12 else ""


def add_days_iso(iso_date, days):
    """جمع/تفریق روز روی تاریخ ISO (خروجی ISO یا رشتهٔ خالی)."""
    dt = _coerce(iso_date)
    if dt is None:
        return ""
    return (dt.date() + datetime.timedelta(days=days)).isoformat()


def days_between_iso(start_iso, end_iso):
    """اختلاف روز (منفی یعنی گذشته)."""
    a, b = _coerce(start_iso), _coerce(end_iso)
    if a is None or b is None:
        return None
    return (b.date() - a.date()).days


def parse_jalali(jy, jm, jd):
    """تبدیل تاریخ شمسی به رشتهٔ ISO (میلادی) — برای ورودیِ کاربر."""
    try:
        return datetime.date(*jalali_to_gregorian(int(jy), int(jm), int(jd))).isoformat()
    except (ValueError, TypeError):
        return ""
