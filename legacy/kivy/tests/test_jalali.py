# -*- coding: utf-8 -*-
"""تست تقویم شمسی (بدون وابستگی به کیوی)."""
import datetime
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import jalali


class TestConversion(unittest.TestCase):
    # نوروزِ سال‌های مختلف (منطبق با تقویم رسمی ایران و دو پیاده‌سازی مرجع)
    ANCHORS = [
        ((1400, 1, 1), (2021, 3, 21)),
        ((1401, 1, 1), (2022, 3, 21)),
        ((1402, 1, 1), (2023, 3, 21)),
        ((1403, 1, 1), (2024, 3, 20)),
        ((1404, 1, 1), (2025, 3, 21)),
        ((1405, 1, 1), (2026, 3, 21)),
        ((1399, 1, 1), (2020, 3, 20)),
        ((1403, 12, 29), (2025, 3, 19)),
        ((1403, 12, 30), (2025, 3, 20)),   # ۱۴۰۳ کبیسه است
        ((1401, 10, 10), (2022, 12, 31)),
    ]

    def test_anchors_both_directions(self):
        for j, g in self.ANCHORS:
            self.assertEqual(jalali.jalali_to_gregorian(*j), g, "j2g %s" % (j,))
            self.assertEqual(jalali.gregorian_to_jalali(*g), j, "g2j %s" % (g,))

    def test_roundtrip_60_years(self):
        start = datetime.date(1990, 1, 1)
        for i in range(0, 22000, 3):
            day = start + datetime.timedelta(days=i)
            j = jalali.gregorian_to_jalali(day.year, day.month, day.day)
            back = jalali.jalali_to_gregorian(*j)
            self.assertEqual(back, (day.year, day.month, day.day))

    def test_month_lengths(self):
        self.assertEqual(jalali.jalali_month_length(1403, 12), 30)
        self.assertEqual(jalali.jalali_month_length(1404, 12), 29)
        self.assertEqual(jalali.jalali_month_length(1404, 7), 30)
        self.assertEqual(jalali.jalali_month_length(1404, 1), 31)

    def test_leap_years(self):
        self.assertTrue(jalali.is_leap_jalali(1403))
        self.assertFalse(jalali.is_leap_jalali(1404))
        self.assertTrue(jalali.is_leap_jalali(1408))
        self.assertFalse(jalali.is_leap_jalali(1402))

    def test_invalid_date_raises(self):
        with self.assertRaises(ValueError):
            jalali.jalali_to_gregorian(1404, 12, 30)   # ۱۴۰۴ کبیسه نیست
        with self.assertRaises(ValueError):
            jalali.jalali_to_gregorian(1404, 13, 1)

    def test_parse_jalali_invalid_returns_empty(self):
        self.assertEqual(jalali.parse_jalali(1404, 12, 30), "")
        self.assertEqual(jalali.parse_jalali("x", "y", "z"), "")

    def test_formatting(self):
        self.assertEqual(jalali.format_jalali("2026-09-15"), "1405/06/24")
        self.assertEqual(jalali.format_jalali(""), "")
        self.assertEqual(jalali.format_jalali(None), "")
        self.assertEqual(jalali.format_jalali("2026-09-15 14:30", with_time=True),
                         "1405/06/24 — 14:30")
        self.assertEqual(jalali.persian_digits("1405"), "۱۴۰۵")

    def test_day_arithmetic(self):
        self.assertEqual(jalali.add_days_iso("2026-09-15", 30), "2026-10-15")
        self.assertEqual(jalali.days_between_iso("2026-09-15", "2026-10-15"), 30)
        self.assertEqual(jalali.days_between_iso("2026-10-15", "2026-09-15"), -30)
        self.assertIsNone(jalali.days_between_iso("", "2026-09-15"))

    def test_weekday_and_month_name(self):
        self.assertEqual(jalali.jalali_weekday("2026-09-15"), "سه‌شنبه")
        self.assertEqual(jalali.jalali_month_name(1), "فروردین")
        self.assertEqual(jalali.jalali_month_name(13), "")


if __name__ == "__main__":
    unittest.main()
