# -*- coding: utf-8 -*-
"""تست لایهٔ داده: ذخیرهٔ اتمیک، بازیابی از خرابی، مهاجرت، کوئری و آمار."""
import json
import os
import shutil
import tempfile
import unittest

sys_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import sys
if sys_path not in sys.path:
    sys.path.insert(0, sys_path)

import jalali
from models import Defect, Vehicle
from repository import Repository


class RepoTestCase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "data", "car_defects.json")
        self.repo = Repository(self.path)
        self.vehicle = self.repo.add_vehicle(
            {"name": "پژو ۴۰۵", "plate": "۱۲ب۳۴۵", "brand": "ایران‌خودرو",
             "model": "GLX", "year": 1398, "odometer": 182000,
             "service_interval_km": 10000, "last_service_km": 175000,
             "last_service_date": jalali.add_days_iso(jalali.today_iso(), -400)})

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def add(self, **kwargs):
        base = {"vehicle_id": self.vehicle.id, "title": "یک ایراد نمونه",
                "category": "موتور", "severity": "متوسط", "status": "باز"}
        base.update(kwargs)
        return self.repo.add_defect(base)


class TestPersistence(RepoTestCase):
    def test_atomic_save_creates_file_and_reload(self):
        self.add(title="نشت روغن", estimated_cost=1200000)
        self.assertTrue(os.path.exists(self.path))
        fresh = Repository(self.path)
        fresh.load()
        self.assertEqual(len(fresh.defects), 1)
        self.assertEqual(fresh.defects[0].title, "نشت روغن")
        self.assertEqual(fresh.defects[0].estimated_cost, 1200000)
        self.assertEqual(len(fresh.vehicles), 1)

    def test_no_temp_files_left_behind(self):
        self.add()
        leftovers = [f for f in os.listdir(os.path.dirname(self.path))
                     if f.startswith(".tmp-")]
        self.assertEqual(leftovers, [])

    def test_corrupt_file_is_quarantined_and_backup_restored(self):
        self.add(title="دادهٔ سالم")
        self.repo.save()                      # نسخهٔ .bak ساخته می‌شود
        with open(self.path, "w", encoding="utf-8") as fh:
            fh.write("{ این JSON خراب است")
        fresh = Repository(self.path)
        fresh.load()
        quarantined = [f for f in os.listdir(os.path.dirname(self.path))
                       if ".corrupt-" in f]
        self.assertEqual(len(quarantined), 1)
        self.assertTrue(fresh.loaded_from_backup)
        self.assertEqual(len(fresh.defects), 1)
        self.assertEqual(fresh.defects[0].title, "دادهٔ سالم")

    def test_fresh_start_when_no_file(self):
        repo = Repository(os.path.join(self.dir, "missing", "x.json"))
        repo.load()
        self.assertEqual(repo.defects, [])
        self.assertEqual(repo.vehicles, [])


class TestMigration(RepoTestCase):
    def test_migrate_v1_plain_list(self):
        payload = [{"title": "صدای موتور", "category": "موتور",
                    "severity": "زیاد", "date": "2026-01-05"}]
        with open(self.path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False)
        repo = Repository(self.path)
        repo.load()
        self.assertEqual(len(repo.vehicles), 1)
        self.assertEqual(len(repo.defects), 1)
        self.assertEqual(repo.defects[0].vehicle_id, repo.vehicles[0].id)
        self.assertEqual(repo.defects[0].title, "صدای موتور")

    def test_migrate_v2_vehicle_by_name(self):
        payload = {
            "vehicles": [{"id": "v1", "name": "پراید", "odometer": 90000}],
            "defects": [{"id": "d1", "vehicle": "پراید", "title": "کمک‌فنر",
                         "category": "جلوبندی و تعلیق", "severity": "کم",
                         "status": "باز", "cost": 500000}],
        }
        with open(self.path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False)
        repo = Repository(self.path)
        repo.load()
        self.assertEqual(repo.defects[0].vehicle_id, "v1")
        self.assertEqual(repo.defects[0].estimated_cost, 500000)

    def test_orphan_defects_are_reattached(self):
        self.add(title="یتیم")
        self.repo.defects[0].vehicle_id = "نامعلوم"
        self.repo._repair_links()
        self.assertEqual(self.repo.defects[0].vehicle_id, self.vehicle.id)

    def test_duplicate_ids_are_fixed(self):
        a = self.add(title="اول")
        b = self.add(title="دوم")
        b.id = a.id
        self.repo._repair_links()
        ids = [d.id for d in self.repo.defects]
        self.assertEqual(len(ids), len(set(ids)))


class TestCrud(RepoTestCase):
    def test_update_sets_timestamps_and_resolved_at(self):
        d = self.add(title="تسمه تایم")
        updated = self.repo.update_defect(d.id, {"status": "انجام‌شده", "actual_cost": 2000000})
        self.assertTrue(updated.resolved_at)
        self.assertEqual(updated.actual_cost, 2000000)
        reopened = self.repo.update_defect(d.id, {"status": "باز"})
        self.assertEqual(reopened.resolved_at, "")

    def test_delete_defect_and_undo_restore(self):
        d = self.add(title="حذفی")
        snapshot = Defect.from_dict(d.to_dict())
        self.assertTrue(self.repo.delete_defect(d.id))
        self.assertIsNone(self.repo.get_defect(d.id))
        self.assertTrue(self.repo.restore_defect(snapshot))
        self.assertEqual(self.repo.get_defect(d.id).title, "حذفی")

    def test_delete_vehicle_cascades_defects(self):
        self.add(title="مرتبط")
        self.repo.delete_vehicle(self.vehicle.id)
        self.assertEqual(self.repo.defects, [])
        self.assertIsNone(self.repo.get_vehicle(self.vehicle.id))

    def test_record_service_updates_baseline(self):
        self.repo.record_service(self.vehicle.id, odometer=185000)
        v = self.repo.get_vehicle(self.vehicle.id)
        self.assertEqual(v.last_service_km, 185000)
        self.assertEqual(v.odometer, 185000)
        self.assertEqual(v.last_service_date, jalali.today_iso())


class TestQuery(RepoTestCase):
    def setUp(self):
        super().setUp()
        self.critical = self.add(title="ترمز عقب قفل می‌کند", severity="بحرانی",
                                 category="ترمز", estimated_cost=4000000)
        self.medium = self.add(title="صدای جیرجیر تسمه", severity="متوسط",
                               category="موتور", estimated_cost=300000)
        self.done = self.add(title="تعویض لنت", severity="زیاد", status="انجام‌شده",
                             category="ترمز", estimated_cost=900000, actual_cost=850000)

    def test_filter_by_status_and_severity(self):
        self.assertEqual(len(self.repo.query(status="باز")), 2)
        self.assertEqual(len(self.repo.query(severity="بحرانی")), 1)
        self.assertEqual(len(self.repo.query(open_only=True)), 2)
        self.assertEqual([d.id for d in self.repo.query(status="انجام‌شده")], [self.done.id])

    def test_search_is_persian_normalized(self):
        # «ي» عربی در برابر «ی» فارسی و فاصله‌های اضافه باید یکسان دیده شوند
        self.assertEqual(len(self.repo.query(search="جيرجير")), 1)
        self.assertEqual(len(self.repo.query(search="  تسمه  ")), 1)
        self.assertEqual(len(self.repo.query(search="ترمز")), 2)
        self.assertEqual(len(self.repo.query(search="چیزی‌که‌نیست")), 0)

    def test_sort_by_severity_puts_critical_first(self):
        items = self.repo.query(sort=None)
        self.assertEqual(items[0].severity, "بحرانی")

    def test_sort_by_newest_and_cost(self):
        newest = self.repo.query(sort="جدیدترین")
        self.assertEqual(newest[0].id, self.done.id)
        costly = self.repo.query(sort="هزینه (زیاد به کم)")
        self.assertEqual(costly[0].id, self.critical.id)


class TestStats(RepoTestCase):
    def setUp(self):
        super().setUp()
        self.add(title="بحرانی باز", severity="بحرانی", estimated_cost=5000000)
        self.add(title="متوسط باز", severity="متوسط", estimated_cost=500000)
        self.add(title="انجام‌شده", severity="زیاد", status="انجام‌شده",
                 estimated_cost=1000000, actual_cost=1200000)

    def test_stats_counts_and_costs(self):
        s = self.repo.stats()
        self.assertEqual(s["total"], 3)
        self.assertEqual(s["open"], 2)
        self.assertEqual(s["done"], 1)
        self.assertEqual(s["critical_open"], 1)
        self.assertEqual(s["estimated_open_cost"], 5500000)
        self.assertEqual(s["actual_cost"], 1200000)

    def test_overdue_and_due_soon(self):
        self.add(title="سررسید گذشته", due_date=jalali.add_days_iso(jalali.today_iso(), -3))
        self.add(title="سررسید نزدیک", due_date=jalali.add_days_iso(jalali.today_iso(), 2))
        s = self.repo.stats()
        self.assertEqual(s["overdue"], 1)
        self.assertEqual(s["due_soon"], 1)


class TestServiceStatus(RepoTestCase):
    def test_km_based_due(self):
        self.repo.update_vehicle(self.vehicle.id, {"odometer": 190000})
        v = self.repo.get_vehicle(self.vehicle.id)
        status = self.repo.service_status(v)
        self.assertTrue(status["km_due"])
        self.assertEqual(status["km_remaining"], -5000)
        self.assertTrue(status["needs_service"])

    def test_date_based_due(self):
        status = self.repo.service_status(self.vehicle)   # آخرین سرویس ۴۰۰ روز پیش
        self.assertTrue(status["date_due"])
        self.assertLess(status["days_remaining"], 0)

    def test_not_due_after_service(self):
        self.repo.record_service(self.vehicle.id, odometer=182000)
        status = self.repo.service_status(self.repo.get_vehicle(self.vehicle.id))
        self.assertFalse(status["needs_service"])
        self.assertEqual(status["km_remaining"], 10000)


if __name__ == "__main__":
    unittest.main()
