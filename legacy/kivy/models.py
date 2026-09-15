# -*- coding: utf-8 -*-
"""مدل‌های داده، ثابت‌ها و اعتبارسنجی.

این ماژول عمداً هیچ وابستگی‌ای به کیوی ندارد تا بشود آن را در CI و
خارج از محیط گرافیکی به‌طور خودکار تست کرد.
"""
from __future__ import annotations

import datetime
import re
import uuid
from dataclasses import dataclass, field

SCHEMA_VERSION = 3

# ---------- ثابت‌های دامنه ----------
CATEGORIES = [
    "موتور", "گیربکس و انتقال قدرت", "ترمز", "برق و سنسور", "بدنه و رنگ",
    "جلوبندی و تعلیق", "تایر و چرخ", "تهویه و کولر", "سوخت و اگزوز",
    "کابین و تجهیزات", "سرویس دوره‌ای", "سایر",
]
# ترتیبِ اهمیت: بحرانی اول
SEVERITIES = ["بحرانی", "زیاد", "متوسط", "کم"]
SEVERITY_RANK = {name: i for i, name in enumerate(SEVERITIES)}

STATUSES = ["باز", "در دست اقدام", "منتظر قطعه", "انجام‌شده", "به تعویق افتاده"]
OPEN_STATUSES = ("باز", "در دست اقدام", "منتظر قطعه")
DONE_STATUS = "انجام‌شده"

SORTS = ["شدت (مهم‌ترین اول)", "جدیدترین", "قدیمی‌ترین", "هزینه (زیاد به کم)", "کیلومتر"]

# ---------- محدودیت‌های ورودی ----------
LIMITS = {
    "title": (3, 80),
    "description": (0, 2000),
    "location": (0, 60),
    "name": (2, 40),
    "plate": (0, 20),
    "brand": (0, 30),
    "model": (0, 30),
    "vin": (0, 17),
}
MAX_COST = 100_000_000_000      # ۱۰۰ میلیارد تومان
MAX_ODOMETER = 10_000_000       # ۱۰ میلیون کیلومتر

_WS_RE = re.compile(r"\s+")
_DIACRITICS_RE = re.compile(r"[\u064B-\u065F\u0670\u0640\u200C\u200D\u200E\u200F]")
_AR_TO_FA = {
    "ي": "ی", "ى": "ی", "ك": "ک", "ۀ": "ه", "ة": "ه",
    "ؤ": "و", "إ": "ا", "أ": "ا", "ٱ": "ا",
}
_FA_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
_AR_DIGITS = "٠١٢٣٤٥٦٧٨٩"
_DIGIT_MAP = {ord(_FA_DIGITS[i]): str(i) for i in range(10)}
_DIGIT_MAP.update({ord(_AR_DIGITS[i]): str(i) for i in range(10)})


# ---------- ابزارهای متن ----------
def normalize_fa(text):
    """نرمال‌سازی متن برای جستجو/مقایسه: یکسان‌سازی ی/ک، حذف اعراب، ارقام لاتین."""
    if text is None:
        return ""
    s = str(text)
    s = _DIACRITICS_RE.sub("", s)
    s = "".join(_AR_TO_FA.get(ch, ch) for ch in s)
    s = s.translate(_DIGIT_MAP)
    s = _WS_RE.sub(" ", s).strip()
    return s.lower()


def clean_text(value, max_len):
    """پاک‌سازیِ ایمنِ ورودی کاربر: حذف کاراکترهای کنترلی و محدودسازی طول."""
    if value is None:
        return ""
    s = str(value).replace("\x00", "")
    s = "".join(ch for ch in s if ch == "\n" or ch == "\t" or ord(ch) >= 32)
    s = s.strip()
    if len(s) > max_len:
        s = s[:max_len].rstrip()
    return s


def to_int(value, default=0, minimum=0, maximum=None):
    """تبدیل ایمن به عدد صحیح (ورودی ممکن است فارسی/با جداکننده باشد)."""
    if value is None:
        return default
    if isinstance(value, bool):
        return default
    if isinstance(value, int):
        n = value
    else:
        s = str(value).translate(_DIGIT_MAP).replace(",", "").replace("،", "").strip()
        s = re.sub(r"[^\d\-]", "", s)
        if s in ("", "-"):
            return default
        try:
            n = int(s)
        except ValueError:
            return default
    if n < minimum:
        return minimum
    if maximum is not None and n > maximum:
        return maximum
    return n


def now_iso(with_time=True):
    fmt = "%Y-%m-%d %H:%M" if with_time else "%Y-%m-%d"
    return datetime.datetime.now().strftime(fmt)


def new_id():
    return uuid.uuid4().hex[:12]


def is_valid_iso_date(value, with_time=False):
    if not value:
        return True
    if isinstance(value, datetime.datetime):
        return True
    if isinstance(value, datetime.date):
        return True
    txt = str(value).strip().replace("T", " ")
    fmts = ("%Y-%m-%d %H:%M", "%Y-%m-%d") if with_time else ("%Y-%m-%d",)
    for fmt in fmts:
        try:
            datetime.datetime.strptime(txt, fmt)
            return True
        except ValueError:
            continue
    return False


# ---------- مدل‌ها ----------
@dataclass
class Vehicle:
    id: str = field(default_factory=new_id)
    name: str = ""
    plate: str = ""
    brand: str = ""
    model: str = ""
    year: int = 0
    vin: str = ""
    odometer: int = 0
    service_interval_km: int = 10000
    service_interval_days: int = 180
    last_service_km: int = 0
    last_service_date: str = ""
    created_at: str = ""
    updated_at: str = ""
    archived: bool = False

    @classmethod
    def from_dict(cls, data):
        known = {f.name for f in cls.__dataclass_fields__.values()}
        clean = {}
        for key, value in (data or {}).items():
            if key not in known:
                continue
            if key in ("year", "odometer", "service_interval_km", "service_interval_days", "last_service_km"):
                value = to_int(value, 0, 0, MAX_ODOMETER)
            elif key == "archived":
                value = bool(value)
            elif key in ("name", "plate", "brand", "model", "vin"):
                limit = LIMITS.get(key, (0, 60))[1]
                value = clean_text(value, limit)
            clean[key] = value
        return cls(**clean)

    def to_dict(self):
        return {
            "id": self.id, "name": self.name, "plate": self.plate, "brand": self.brand,
            "model": self.model, "year": self.year, "vin": self.vin, "odometer": self.odometer,
            "service_interval_km": self.service_interval_km,
            "service_interval_days": self.service_interval_days,
            "last_service_km": self.last_service_km, "last_service_date": self.last_service_date,
            "created_at": self.created_at, "updated_at": self.updated_at, "archived": self.archived,
        }

    def display_name(self):
        parts = [p for p in (self.name,) if p]
        sub = " ".join(p for p in (self.brand, self.model) if p)
        if sub:
            parts.append("(%s)" % sub)
        return " ".join(parts) if parts else "بدون نام"


@dataclass
class Defect:
    id: str = field(default_factory=new_id)
    vehicle_id: str = ""
    title: str = ""
    category: str = CATEGORIES[-1]
    severity: str = "متوسط"
    status: str = "باز"
    location: str = ""
    description: str = ""
    odometer: int = 0
    estimated_cost: int = 0
    actual_cost: int = 0
    due_date: str = ""
    reported_at: str = ""
    resolved_at: str = ""
    created_at: str = ""
    updated_at: str = ""

    @classmethod
    def from_dict(cls, data):
        known = {f.name for f in cls.__dataclass_fields__.values()}
        clean = {}
        for key, value in (data or {}).items():
            if key not in known:
                continue
            if key in ("odometer", "estimated_cost", "actual_cost"):
                value = to_int(value, 0, 0, MAX_ODOMETER if key == "odometer" else MAX_COST)
            elif key in ("title", "location", "description"):
                value = clean_text(value, LIMITS[key][1])
            clean[key] = value
        obj = cls(**clean)
        obj.category = obj.category if obj.category in CATEGORIES else CATEGORIES[-1]
        obj.severity = obj.severity if obj.severity in SEVERITIES else "متوسط"
        obj.status = obj.status if obj.status in STATUSES else "باز"
        return obj

    def to_dict(self):
        return {
            "id": self.id, "vehicle_id": self.vehicle_id, "title": self.title,
            "category": self.category, "severity": self.severity, "status": self.status,
            "location": self.location, "description": self.description, "odometer": self.odometer,
            "estimated_cost": self.estimated_cost, "actual_cost": self.actual_cost,
            "due_date": self.due_date, "reported_at": self.reported_at,
            "resolved_at": self.resolved_at, "created_at": self.created_at,
            "updated_at": self.updated_at,
        }

    def is_open(self):
        return self.status in OPEN_STATUSES

    def effective_cost(self):
        return self.actual_cost if self.actual_cost else self.estimated_cost


# ---------- اعتبارسنجی ----------
def validate_vehicle(data, repository=None):
    """بررسی دادهٔ خودرو؛ خروجی دیکشنریِ {فیلد: پیام خطا} (خالی یعنی معتبر)."""
    errors = {}
    name = clean_text(data.get("name", ""), LIMITS["name"][1])
    if len(name) < LIMITS["name"][0]:
        errors["name"] = "نام خودرو باید حداقل %d حرف باشد." % LIMITS["name"][0]
    elif duplicate_vehicle_name(repository, name, data.get("id")):
        errors["name"] = "خودرویی با این نام وجود دارد."
    year = to_int(data.get("year", 0), 0, 0, 2100)
    if year and year < 1300:
        errors["year"] = "سال ساخت معتبر نیست."
    odo = to_int(data.get("odometer", 0), 0, 0, MAX_ODOMETER)
    if odo and odo < to_int(data.get("last_service_km", 0), 0, 0, MAX_ODOMETER):
        errors["odometer"] = "کیلومتر فعلی نمی‌تواند کمتر از کیلومتر آخرین سرویس باشد."
    for key in ("service_interval_km", "service_interval_days"):
        if to_int(data.get(key, 0), 0, 0, MAX_ODOMETER) < 0:
            errors[key] = "مقدار نامعتبر است."
    if not is_valid_iso_date(data.get("last_service_date", "")):
        errors["last_service_date"] = "تاریخ نامعتبر است."
    return errors


def duplicate_vehicle_name(repository, name, exclude_id=None):
    if repository is None:
        return False
    target = normalize_fa(name)
    for v in repository.vehicles:
        if v.id == exclude_id or v.archived:
            continue
        if normalize_fa(v.name) == target:
            return True
    return False


def validate_defect(data, repository=None):
    """بررسی دادهٔ ایراد؛ خروجی دیکشنریِ خطاها (خالی یعنی معتبر)."""
    errors = {}
    title = clean_text(data.get("title", ""), LIMITS["title"][1])
    if len(title) < LIMITS["title"][0]:
        errors["title"] = "عنوان باید حداقل %d حرف باشد." % LIMITS["title"][0]
    if not data.get("vehicle_id"):
        errors["vehicle_id"] = "ابتدا یک خودرو انتخاب کنید."
    elif repository is not None and repository.get_vehicle(data["vehicle_id"]) is None:
        errors["vehicle_id"] = "خودروی انتخاب‌شده دیگر وجود ندارد."
    if data.get("severity") not in SEVERITIES:
        errors["severity"] = "شدت نامعتبر است."
    if data.get("status") not in STATUSES:
        errors["status"] = "وضعیت نامعتبر است."
    if data.get("category") not in CATEGORIES:
        errors["category"] = "دسته نامعتبر است."
    if not is_valid_iso_date(data.get("due_date", "")):
        errors["due_date"] = "تاریخ سررسید نامعتبر است."
    if to_int(data.get("estimated_cost", 0), 0, 0, MAX_COST) > MAX_COST:
        errors["estimated_cost"] = "مبلغ بسیار بزرگ است."
    if to_int(data.get("actual_cost", 0), 0, 0, MAX_COST) > MAX_COST:
        errors["actual_cost"] = "مبلغ بسیار بزرگ است."
    return errors
