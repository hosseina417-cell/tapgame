# -*- coding: utf-8 -*-
"""تست مدل‌ها، اعتبارسنجی و ابزارهای متن."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from models import (
    CATEGORIES, SEVERITIES, STATUSES, Defect, Vehicle, clean_text,
    is_valid_iso_date, normalize_fa, to_int, validate_defect, validate_vehicle,
)


class TestTextUtils(unittest.TestCase):
    def test_normalize_fa_unifies_arabic_variants(self):
        self.assertEqual(normalize_fa("جيرجير"), "جیرجیر")
        self.assertEqual(normalize_fa("ترمزها"), normalize_fa("ترمز‌ها  "))
        self.assertEqual(normalize_fa(""), "")

    def test_normalize_removes_diacritics(self):
        self.assertEqual(normalize_fa("تست"), normalize_fa("تست"))

    def test_to_int_accepts_persian_digits_and_separators(self):
        self.assertEqual(to_int("۱۲۳"), 123)
        self.assertEqual(to_int("1,250,000"), 1250000)
        self.assertEqual(to_int("12abc"), 12)
        self.assertEqual(to_int(""), 0)
        self.assertEqual(to_int(None, 7), 7)
        self.assertEqual(to_int(-5, 0, 0, 10), 0)

    def test_clean_text_limits_and_control_chars(self):
        self.assertEqual(clean_text("a\x00b", 10), "ab")
        self.assertEqual(clean_text("x" * 100, 10), "x" * 10)
        self.assertEqual(clean_text(None, 5), "")

    def test_is_valid_iso_date(self):
        self.assertTrue(is_valid_iso_date("2026-09-15"))
        self.assertTrue(is_valid_iso_date(""))
        self.assertFalse(is_valid_iso_date("1405/06/24"))
        self.assertFalse(is_valid_iso_date("فردا"))


class TestVehicleValidation(unittest.TestCase):
    def test_name_required(self):
        errors = validate_vehicle({"name": " "})
        self.assertIn("name", errors)

    def test_odometer_cannot_be_less_than_last_service(self):
        errors = validate_vehicle({"name": "خودرو", "odometer": 1000, "last_service_km": 5000})
        self.assertIn("odometer", errors)

    def test_valid_vehicle_has_no_errors(self):
        errors = validate_vehicle({"name": "پژو", "year": 1398, "odometer": 100000})
        self.assertEqual(errors, {})

    def test_duplicate_name_is_rejected(self):
        class FakeRepo:
            vehicles = [Vehicle(name="پژو"), Vehicle(name="سمند")]

        errors = validate_vehicle({"name": "پژو"}, repository=FakeRepo())
        self.assertIn("name", errors)


class TestDefectValidation(unittest.TestCase):
    def setUp(self):
        class FakeRepo:
            def __init__(self):
                self.vehicles = [Vehicle(id="v1", name="پژو")]

            def get_vehicle(self, vid):
                return self.vehicles[0] if vid == "v1" else None

        self.repo = FakeRepo()

    def test_title_min_length(self):
        errors = validate_defect({"vehicle_id": "v1", "title": "ab"}, self.repo)
        self.assertIn("title", errors)

    def test_unknown_vehicle_rejected(self):
        errors = validate_defect({"vehicle_id": "نامعلوم", "title": "یک عنوان"}, self.repo)
        self.assertIn("vehicle_id", errors)

    def test_invalid_severity_status_category(self):
        errors = validate_defect({"vehicle_id": "v1", "title": "عنوان معتبر",
                                  "severity": "خیلی زیاد", "status": "حذف",
                                  "category": "هسته‌ای"}, self.repo)
        self.assertIn("severity", errors)
        self.assertIn("status", errors)
        self.assertIn("category", errors)

    def test_valid_defect(self):
        errors = validate_defect({"vehicle_id": "v1", "title": "صدای گیربکس",
                                  "severity": "زیاد", "status": "باز",
                                  "category": "گیربکس و انتقال قدرت"}, self.repo)
        self.assertEqual(errors, {})


class TestModelRoundTrip(unittest.TestCase):
    def test_defect_from_dict_drops_unknown_keys_and_clamps(self):
        d = Defect.from_dict({"title": "x" * 500, "severity": "نامعتبر",
                              "estimated_cost": "۱,۰۰۰", "nope": 1})
        self.assertEqual(len(d.title), 80)
        self.assertEqual(d.severity, "متوسط")
        self.assertEqual(d.estimated_cost, 1000)
        self.assertNotIn("nope", d.to_dict())

    def test_vehicle_roundtrip(self):
        v = Vehicle(name="پژو", plate="۱۲ب", odometer="۹۰,۰۰۰", year=1398)
        restored = Vehicle.from_dict(v.to_dict())
        self.assertEqual(restored.odometer, 90000)
        self.assertEqual(restored.name, "پژو")
        self.assertEqual(restored.display_name(), "پژو")

    def test_is_open_and_effective_cost(self):
        open_defect = Defect(status="باز", estimated_cost=1000)
        done_defect = Defect(status="انجام‌شده", estimated_cost=1000, actual_cost=1500)
        self.assertTrue(open_defect.is_open())
        self.assertFalse(done_defect.is_open())
        self.assertEqual(open_defect.effective_cost(), 1000)
        self.assertEqual(done_defect.effective_cost(), 1500)


if __name__ == "__main__":
    unittest.main()
