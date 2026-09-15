# -*- coding: utf-8 -*-
"""لایهٔ دسترسی به داده: بارگذاری، مهاجرت اسکیما، ذخیرهٔ اتمیک، کوئری و آمار.

بدون وابستگی به کیوی — قابل تست در CI.
"""
from __future__ import annotations

import json
import os
import shutil
import tempfile
import threading
import time

import jalali
from models import (
    CATEGORIES, DONE_STATUS, OPEN_STATUSES, SCHEMA_VERSION, SEVERITIES,
    SEVERITY_RANK, STATUSES, Defect, Vehicle, clean_text, new_id, now_iso,
    normalize_fa, to_int,
)


class Repository:
    """مخزن دادهٔ برنامه (JSON در پوشهٔ دادهٔ کاربر)."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()
        self.vehicles = []
        self.defects = []
        self.last_error = ""
        self.loaded_from_backup = False

    # ---------- بارگذاری / ذخیره ----------
    def load(self):
        with self.lock:
            if not os.path.exists(self.path):
                self.vehicles, self.defects = [], []
                return
            try:
                with open(self.path, "r", encoding="utf-8") as fh:
                    payload = json.load(fh)
            except Exception as exc:  # فایل خراب
                self.last_error = "فایل داده قابل خواندن نبود (%s)" % exc
                self._quarantine()
                self.loaded_from_backup = self._try_restore_backup()
                if not self.loaded_from_backup:
                    self.vehicles, self.defects = [], []
                return
            self._apply_payload(payload)

    def _quarantine(self):
        try:
            shutil.move(self.path, self.path + ".corrupt-%d" % int(time.time()))
        except Exception:
            pass

    def _try_restore_bak(self):
        bak = self.path + ".bak"
        if not os.path.exists(bak):
            return False
        try:
            with open(bak, "r", encoding="utf-8") as fh:
                self._apply_payload(json.load(fh))
            return True
        except Exception:
            return False

    def _try_restore_backup(self):
        return self._try_restore_bak()

    def save(self):
        """ذخیرهٔ اتمیک: نوشتن در فایل موقت + جایگزینی (خرابیِ نیمه‌نوشته نداریم)."""
        with self.lock:
            payload = self.to_payload()
            directory = os.path.dirname(self.path) or "."
            try:
                os.makedirs(directory, exist_ok=True)
            except Exception as exc:
                self.last_error = "دسترسی به پوشهٔ داده ممکن نیست: %s" % exc
                return False
            tmp = None
            try:
                fd, tmp = tempfile.mkstemp(dir=directory, prefix=".tmp-", suffix=".json")
                with os.fdopen(fd, "w", encoding="utf-8") as fh:
                    json.dump(payload, fh, ensure_ascii=False, indent=1)
                    fh.flush()
                    os.fsync(fh.fileno())
                if os.path.exists(self.path):
                    try:
                        shutil.copyfile(self.path, self.path + ".bak")
                    except Exception:
                        pass
                os.replace(tmp, self.path)
                self.last_error = ""
                return True
            except Exception as exc:
                self.last_error = "ذخیره انجام نشد: %s" % exc
                if tmp and os.path.exists(tmp):
                    try:
                        os.unlink(tmp)
                    except Exception:
                        pass
                return False

    # ---------- اسکیما / مهاجرت ----------
    def to_payload(self):
        return {
            "schema_version": SCHEMA_VERSION,
            "exported_at": now_iso(),
            "vehicles": [v.to_dict() for v in self.vehicles],
            "defects": [d.to_dict() for d in self.defects],
        }

    def _apply_payload(self, payload):
        """پذیرش داده با هر نسخهٔ اسکیما و مهاجرت به نسخهٔ جاری."""
        if isinstance(payload, list):          # نسخهٔ ۱: فقط یک لیست ایراد
            vehicles, defects = self._migrate_v1(payload)
        elif isinstance(payload, dict):
            version = to_int(payload.get("schema_version", 0), 0, 0, 999)
            if version <= 1 and "defects" in payload:
                vehicles, defects = self._migrate_v2(payload)
            else:
                vehicles, defects = self._migrate_current(payload)
        else:
            vehicles, defects = [], []
        self.vehicles = vehicles
        self.defects = defects
        self._repair_links()

    def _migrate_v1(self, items):
        """نسخهٔ ۱: ایرادهای بدون خودرو (فقط عنوان/دسته/شدت/تاریخ)."""
        vehicle = Vehicle(name="خودروی من", created_at=now_iso(), updated_at=now_iso())
        defects = []
        for item in items:
            if not isinstance(item, dict):
                continue
            defects.append(Defect(
                vehicle_id=vehicle.id,
                title=clean_text(item.get("title") or item.get("t") or "بدون عنوان", 80),
                category=item.get("category") if item.get("category") in CATEGORIES else CATEGORIES[-1],
                severity=item.get("severity") if item.get("severity") in SEVERITIES else "متوسط",
                description=clean_text(item.get("description") or item.get("desc") or "", 2000),
                reported_at=item.get("date") or "",
                created_at=item.get("date") or now_iso(),
                updated_at=now_iso(),
            ))
        return [vehicle], defects

    def _migrate_v2(self, payload):
        """نسخهٔ ۲: ایرادها با «نام خودرو» (رشته) اشاره می‌شدند → تبدیل به شناسه."""
        vehicles = [Vehicle.from_dict(v) for v in payload.get("vehicles", []) if isinstance(v, dict)]
        by_name = {normalize_fa(v.name): v for v in vehicles}
        generic = Vehicle(name="خودروی من", created_at=now_iso(), updated_at=now_iso())
        defects = []
        for item in payload.get("defects", []):
            if not isinstance(item, dict):
                continue
            data = dict(item)
            name = normalize_fa(data.pop("vehicle", "") or "")
            target = by_name.get(name)
            if target is None:
                if name and name not in ("همه", "خودرو"):
                    generic = generic if generic in vehicles else generic
                    if generic not in vehicles:
                        vehicles.append(generic)
                    target = generic
                elif vehicles:
                    target = vehicles[0]
                else:
                    if generic not in vehicles:
                        vehicles.append(generic)
                    target = generic
            data["vehicle_id"] = target.id
            data["estimated_cost"] = data.pop("cost", data.get("estimated_cost", 0))
            defects.append(Defect.from_dict(data))
        return vehicles, defects

    def _migrate_current(self, payload):
        vehicles = [Vehicle.from_dict(v) for v in payload.get("vehicles", []) if isinstance(v, dict)]
        defects = [Defect.from_dict(d) for d in payload.get("defects", []) if isinstance(d, dict)]
        return vehicles, defects

    def _repair_links(self):
        """یکپارچگیِ ارجاع‌ها: ایرادِ بدون خودرو و شناسهٔ تکراری."""
        ids = set()
        unique = []
        for v in self.vehicles:
            if v.id in ids or not v.id:
                v.id = new_id()
            ids.add(v.id)
            unique.append(v)
        self.vehicles = unique
        if not self.vehicles:
            self.defects = []
            return
        valid = {v.id for v in self.vehicles}
        seen = set()
        repaired = []
        for d in self.defects:
            if d.id in seen or not d.id:
                d.id = new_id()
            seen.add(d.id)
            if d.vehicle_id not in valid:
                d.vehicle_id = self.vehicles[0].id
            if d.status == DONE_STATUS and not d.resolved_at:
                d.resolved_at = d.updated_at or d.reported_at or now_iso(False)
            if d.status != DONE_STATUS:
                d.resolved_at = ""
            repaired.append(d)
        self.defects = repaired

    # ---------- خودرو ----------
    def get_vehicle(self, vehicle_id):
        for v in self.vehicles:
            if v.id == vehicle_id:
                return v
        return None

    def active_vehicles(self):
        return [v for v in self.vehicles if not v.archived]

    def add_vehicle(self, data, auto_save=True):
        vehicle = Vehicle.from_dict(data)
        vehicle.id = data.get("id") or new_id()
        vehicle.created_at = vehicle.created_at or now_iso()
        vehicle.updated_at = now_iso()
        self.vehicles.append(vehicle)
        if auto_save:
            self.save()
        return vehicle

    def update_vehicle(self, vehicle_id, patch, auto_save=True):
        vehicle = self.get_vehicle(vehicle_id)
        if vehicle is None:
            return None
        merged = vehicle.to_dict()
        merged.update({k: v for k, v in patch.items()})
        merged["id"] = vehicle_id
        merged["created_at"] = vehicle.created_at
        merged["updated_at"] = now_iso()
        updated = Vehicle.from_dict(merged)
        self.vehicles[self.vehicles.index(vehicle)] = updated
        if auto_save:
            self.save()
        return updated

    def delete_vehicle(self, vehicle_id, auto_save=True):
        """حذف خودرو همراه با ایرادهایش (تصمیم کاربر در رابط گرفته می‌شود)."""
        vehicle = self.get_vehicle(vehicle_id)
        if vehicle is None:
            return False
        self.vehicles.remove(vehicle)
        self.defects = [d for d in self.defects if d.vehicle_id != vehicle_id]
        if auto_save:
            self.save()
        return True

    # ---------- ایراد ----------
    def get_defect(self, defect_id):
        for d in self.defects:
            if d.id == defect_id:
                return d
        return None

    def add_defect(self, data, auto_save=True):
        defect = Defect.from_dict(data)
        defect.id = data.get("id") or new_id()
        defect.reported_at = defect.reported_at or now_iso(False)
        defect.created_at = now_iso()
        defect.updated_at = now_iso()
        if defect.status == DONE_STATUS and not defect.resolved_at:
            defect.resolved_at = now_iso(False)
        self.defects.append(defect)
        if auto_save:
            self.save()
        return defect

    def update_defect(self, defect_id, patch, auto_save=True):
        defect = self.get_defect(defect_id)
        if defect is None:
            return None
        merged = defect.to_dict()
        merged.update({k: v for k, v in patch.items()})
        merged["id"] = defect_id
        merged["created_at"] = defect.created_at
        merged["updated_at"] = now_iso()
        if merged.get("status") == DONE_STATUS:
            merged["resolved_at"] = merged.get("resolved_at") or now_iso(False)
        else:
            merged["resolved_at"] = ""
        updated = Defect.from_dict(merged)
        self.defects[self.defects.index(defect)] = updated
        if auto_save:
            self.save()
        return updated

    def delete_defect(self, defect_id, auto_save=True):
        defect = self.get_defect(defect_id)
        if defect is None:
            return False
        self.defects.remove(defect)
        if auto_save:
            self.save()
        return True

    def restore_defect(self, defect, auto_save=True):
        """بازگرداندنِ رکوردِ حذف‌شده (برای Undo)."""
        if defect is None or self.get_defect(defect.id):
            return False
        self.defects.append(defect)
        if auto_save:
            self.save()
        return True

    # ---------- کوئری ----------
    def query(self, vehicle_id=None, status=None, severity=None, category=None,
              search=None, sort=None, open_only=False, include_done=True):
        term = normalize_fa(search) if search else ""
        items = []
        for d in self.defects:
            if vehicle_id and d.vehicle_id != vehicle_id:
                continue
            if open_only and not d.is_open():
                continue
            if not include_done and d.status == DONE_STATUS:
                continue
            if status and d.status != status:
                continue
            if severity and d.severity != severity:
                continue
            if category and d.category != category:
                continue
            if term:
                haystack = normalize_fa(" ".join([
                    d.title, d.description, d.location, d.category, d.status, d.severity,
                ]))
                if term not in haystack:
                    continue
            items.append(d)
        return self._sort(items, sort)

    @staticmethod
    def _sort(items, sort):
        if sort in ("جدیدترین", "قدیمی‌ترین"):
            # برای تاریخ‌های کاملاً یکسان، ترتیبِ ثبت ملاک است (پایدار و قطعی)
            keyed = sorted(enumerate(items),
                           key=lambda pair: (pair[1].reported_at or "",
                                             pair[1].created_at or "", pair[0]),
                           reverse=(sort == "جدیدترین"))
            return [d for _, d in keyed]
        if sort == "هزینه (زیاد به کم)":
            return sorted(items, key=lambda d: d.effective_cost(), reverse=True)
        if sort == "کیلومتر":
            return sorted(items, key=lambda d: d.odometer, reverse=True)
        # پیش‌فرض: شدت (مهم‌ترین اول)، سپس باز بودن، سپس جدیدتر
        return sorted(items, key=lambda d: (
            SEVERITY_RANK.get(d.severity, 9),
            0 if d.is_open() else 1,
            -(to_int(d.estimated_cost, 0)),
            _reverse_date(d.reported_at),
        ))

    # ---------- آمار ----------
    def stats(self, vehicle_id=None):
        items = [d for d in self.defects if not vehicle_id or d.vehicle_id == vehicle_id]
        by_severity = {s: 0 for s in SEVERITIES}
        by_status = {s: 0 for s in STATUSES}
        by_category = {}
        open_items = [d for d in items if d.is_open()]
        estimated = sum(d.estimated_cost for d in open_items)
        actual = sum(d.actual_cost for d in items)
        for d in items:
            by_severity[d.severity] = by_severity.get(d.severity, 0) + 1
            by_status[d.status] = by_status.get(d.status, 0) + 1
            by_category[d.category] = by_category.get(d.category, 0) + 1
        today = jalali.today_iso()
        return {
            "total": len(items),
            "open": len(open_items),
            "done": by_status.get(DONE_STATUS, 0),
            "critical_open": sum(1 for d in open_items if d.severity == "بحرانی"),
            "by_severity": by_severity,
            "by_status": by_status,
            "by_category": by_category,
            "estimated_open_cost": estimated,
            "actual_cost": actual,
            "overdue": sum(1 for d in open_items if d.due_date and d.due_date < today),
            "due_soon": sum(1 for d in open_items
                            if d.due_date and 0 <= jalali.days_between_iso(today, d.due_date) <= 7),
            "vehicles": len(self.active_vehicles()) if not vehicle_id else 1,
        }

    # ---------- نگهداری / سرویس ----------
    def service_status(self, vehicle):
        """وضعیت سرویس دوره‌ایِ یک خودرو (بر اساس کیلومتر و تاریخ)."""
        out = {"km_due": False, "km_remaining": None, "km_progress": 0.0,
               "date_due": False, "days_remaining": None, "last_service": vehicle.last_service_date,
               "needs_service": False, "reason": ""}
        if not vehicle:
            return out
        interval_km = to_int(vehicle.service_interval_km, 0, 0, MAX := 1_000_000)
        if interval_km > 0:
            driven = max(0, to_int(vehicle.odometer, 0) - to_int(vehicle.last_service_km, 0))
            remaining = interval_km - driven
            out["km_remaining"] = remaining
            out["km_progress"] = min(1.0, max(0.0, driven / float(interval_km)))
            out["km_due"] = remaining <= 0
            out["driven"] = driven
        if vehicle.last_service_date and to_int(vehicle.service_interval_days, 0, 0, 3650) > 0:
            elapsed = jalali.days_between_iso(vehicle.last_service_date, jalali.today_iso())
            if elapsed is not None:
                remaining_days = to_int(vehicle.service_interval_days, 0) - elapsed
                out["days_remaining"] = remaining_days
                out["date_due"] = remaining_days <= 0
        out["needs_service"] = bool(out["km_due"] or out["date_due"])
        reasons = []
        if out["km_due"]:
            reasons.append("کیلومتر سرویس گذشته")
        if out["date_due"]:
            reasons.append("موعد زمانی سرویس گذشته")
        out["reason"] = " و ".join(reasons)
        return out

    def record_service(self, vehicle_id, odometer=None, date_iso=None, auto_save=True):
        """ثبت یک سرویس انجام‌شده (کیلومتر و تاریخِ مبنا را به‌روز می‌کند)."""
        vehicle = self.get_vehicle(vehicle_id)
        if vehicle is None:
            return None
        km = to_int(odometer if odometer is not None else vehicle.odometer, 0, 0, 10_000_000)
        return self.update_vehicle(vehicle_id, {
            "last_service_km": km,
            "last_service_date": date_iso or jalali.today_iso(),
            "odometer": max(km, vehicle.odometer),
        }, auto_save=auto_save)

    # ---------- پشتیبان / بازیابی ----------
    def replace_all(self, vehicles, defects):
        self.vehicles = list(vehicles)
        self.defects = list(defects)
        self._repair_links()
        return self.save()

    def merge_import(self, vehicles, defects):
        """ادغامِ هوشمند بر اساس شناسه (رکوردهای تکراری بازنویسی نمی‌شوند)."""
        existing_v = {v.id for v in self.vehicles}
        existing_d = {d.id for d in self.defects}
        added_v = added_d = 0
        for v in vehicles:
            if v.id not in existing_v:
                self.vehicles.append(v)
                existing_v.add(v.id)
                added_v += 1
        for d in defects:
            if d.id not in existing_d:
                self.defects.append(d)
                existing_d.add(d.id)
                added_d += 1
        self._repair_links()
        self.save()
        return added_v, added_d

    def vehicle_name(self, vehicle_id):
        v = self.get_vehicle(vehicle_id)
        return v.display_name() if v else "—"


def _reverse_date(value):
    """کلید مرتب‌سازی برای قدیمی‌تر-اولِ معکوس."""
    return "".join(chr(255 - ord(c)) if ord(c) < 255 else c for c in (value or ""))
