/* تست‌های منطق وب‌اپ (اجرا: node tests/node/logic.test.js) */
const assert = require('assert');
const path = require('path');
const Jalali = require(path.join(__dirname, '..', '..', 'www', 'js', 'jalali.js'));
const CarModel = require(path.join(__dirname, '..', '..', 'www', 'js', 'store.js'));

global.Jalali = Jalali;

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log('  ✓ ' + name); }
  catch (e) { failed++; console.log('  ✗ ' + name + '\n      ' + e.message); }
}

// ---------- تقویم شمسی ----------
const ANCHORS = [
  [[1400, 1, 1], [2021, 3, 21]], [[1401, 1, 1], [2022, 3, 21]],
  [[1402, 1, 1], [2023, 3, 21]], [[1403, 1, 1], [2024, 3, 20]],
  [[1404, 1, 1], [2025, 3, 21]], [[1405, 1, 1], [2026, 3, 21]],
  [[1399, 1, 1], [2020, 3, 20]], [[1403, 12, 30], [2025, 3, 20]],
  [[1401, 10, 10], [2022, 12, 31]],
];

test('لنگرهای نوروز در هر دو جهت', () => {
  ANCHORS.forEach(([j, g]) => {
    assert.deepStrictEqual(Jalali.jalaliToGregorian(j[0], j[1], j[2]), g, 'j2g ' + j);
    assert.deepStrictEqual(Jalali.gregorianToJalali(g[0], g[1], g[2]), j, 'g2j ' + g);
  });
});

test('گردش کامل ۳۰ ساله', () => {
  let d = new Date(Date.UTC(1995, 0, 1));
  for (let i = 0; i < 11000; i += 7) {
    const day = new Date(d.getTime() + i * 86400000);
    const j = Jalali.gregorianToJalali(day.getUTCFullYear(), day.getUTCMonth() + 1, day.getUTCDate());
    const back = Jalali.jalaliToGregorian(j[0], j[1], j[2]);
    assert.deepStrictEqual(back, [day.getUTCFullYear(), day.getUTCMonth() + 1, day.getUTCDate()]);
  }
});

test('سال‌های کبیسه', () => {
  assert.strictEqual(Jalali.isLeap(1403), true);
  assert.strictEqual(Jalali.isLeap(1404), false);
  assert.strictEqual(Jalali.isLeap(1408), true);
  assert.strictEqual(Jalali.monthLength(1403, 12), 30);
  assert.strictEqual(Jalali.monthLength(1404, 12), 29);
});

test('تاریخ نامعتبر خطا می‌دهد', () => {
  assert.throws(() => Jalali.jalaliToGregorian(1404, 12, 30));
  assert.strictEqual(Jalali.parseJalali(1404, 12, 30), '');
});

test('قالب‌بندی و ارقام فارسی', () => {
  assert.strictEqual(Jalali.format('2026-09-15'), '1405/06/24');
  assert.strictEqual(Jalali.format('2026-09-15 14:30', true), '1405/06/24 — 14:30');
  assert.strictEqual(Jalali.format(''), '');
  assert.strictEqual(Jalali.persianDigits('1405'), '۱۴۰۵');
  assert.strictEqual(Jalali.weekday('2026-09-15'), 'سه‌شنبه');
  assert.strictEqual(Jalali.addDays('2026-09-15', 30), '2026-10-15');
  assert.strictEqual(Jalali.daysBetween('2026-09-15', '2026-10-15'), 30);
  assert.strictEqual(Jalali.daysBetween('2026-10-15', '2026-09-15'), -30);
});

// ---------- ابزارها ----------
test('نرمال‌سازی متن فارسی', () => {
  assert.strictEqual(CarModel.normalizeFa('جيرجير'), 'جیرجیر');
  assert.strictEqual(CarModel.normalizeFa('  ترمز‌ها  '), CarModel.normalizeFa('ترمزها'));
  assert.strictEqual(CarModel.toInt('۱۲۳'), 123);
  assert.strictEqual(CarModel.toInt('1,250,000'), 1250000);
  assert.strictEqual(CarModel.toInt(''), 0);
  assert.strictEqual(CarModel.toInt(-5, 0, 0, 10), 0);
  assert.strictEqual(CarModel.cleanText('x'.repeat(200), 80).length, 80);
});

// ---------- مخزن ----------
function memStorage() {
  const data = {};
  return {
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => { data[k] = String(v); },
    removeItem: (k) => { delete data[k]; },
    _data: data,
  };
}

function seededStore() {
  const store = new CarModel.Store(memStorage());
  const v = store.addVehicle({ name: 'پژو ۴۰۵', plate: '۱۲ب۳۴۵', odometer: 182000, serviceIntervalKm: 10000, lastServiceKm: 175000 });
  store.addDefect({ vehicleId: v.id, title: 'صدای گیربکس', category: 'گیربکس و انتقال قدرت', severity: 'بحرانی', estimatedCost: 3500000, dueDate: Jalali.addDays(Jalali.todayIso(), -3) });
  store.addDefect({ vehicleId: v.id, title: 'لرزش فرمان', category: 'جلوبندی و تعلیق', severity: 'متوسط', status: 'انجام‌شده', estimatedCost: 800000, actualCost: 950000 });
  store.addDefect({ vehicleId: v.id, title: 'تعویض روغن', category: 'سرویس دوره‌ای', severity: 'کم', estimatedCost: 450000, dueDate: Jalali.addDays(Jalali.todayIso(), 3) });
  return { store, v };
}

test('ذخیره و بازخوانی', () => {
  const storage = memStorage();
  const a = new CarModel.Store(storage);
  const v = a.addVehicle({ name: 'سمند', odometer: 90000 });
  a.addDefect({ vehicleId: v.id, title: 'نشت روغن', estimatedCost: 1200000 });
  const b = new CarModel.Store(storage);
  b.load();
  assert.strictEqual(b.vehicles.length, 1);
  assert.strictEqual(b.defects.length, 1);
  assert.strictEqual(b.defects[0].title, 'نشت روغن');
  assert.strictEqual(b.defects[0].estimatedCost, 1200000);
});

test('بازیابی پس از خرابی داده', () => {
  const storage = memStorage();
  const a = new CarModel.Store(storage);
  const v = a.addVehicle({ name: 'خودروی سالم' });
  a.addDefect({ vehicleId: v.id, title: 'یک ایراد' });   // ذخیرهٔ دوم → نسخهٔ پشتیبان ساخته می‌شود
  const backup = storage.getItem(CarModel.STORAGE_KEY);
  storage.setItem(CarModel.STORAGE_KEY, '{ دادهٔ خراب');
  const b = new CarModel.Store(storage);
  assert.ok(backup);
  b.load();
  assert.strictEqual(b.restoredFromBackup, true);
  assert.strictEqual(b.vehicles.length, 1);
});

test('مهاجرت از نسخهٔ ۱ (فهرست ساده)', () => {
  const storage = memStorage();
  storage.setItem('cardefect.data', JSON.stringify([{ title: 'صدای موتور', category: 'موتور', severity: 'زیاد', date: '2026-01-05' }]));
  const s = new CarModel.Store(storage);
  s.load();
  assert.strictEqual(s.vehicles.length, 1);
  assert.strictEqual(s.defects.length, 1);
  assert.strictEqual(s.defects[0].vehicleId, s.vehicles[0].id);
  assert.strictEqual(s.defects[0].title, 'صدای موتور');
});

test('پاک‌سازیِ ارجاع‌های یتیم', () => {
  const { store, v } = seededStore();
  store.defects[0].vehicleId = 'نامعلوم';
  store.repairLinks();
  assert.strictEqual(store.defects[0].vehicleId, v.id);
});

test('فیلتر و جستجو', () => {
  const { store } = seededStore();
  assert.strictEqual(store.query({ status: 'انجام‌شده' }).length, 1);
  assert.strictEqual(store.query({ openOnly: true }).length, 2);
  assert.strictEqual(store.query({ severity: 'بحرانی' }).length, 1);
  assert.strictEqual(store.query({ search: 'گيربکس' }).length, 1, 'ی/ي عربی یکسان‌سازی شود');
  assert.strictEqual(store.query({ search: '  گیربکس  ' }).length, 1);
  assert.strictEqual(store.query({ search: 'چیزی‌که‌نیست' }).length, 0);
});

test('مرتب‌سازی', () => {
  const { store } = seededStore();
  assert.strictEqual(store.query({ sort: 'شدت' })[0].severity, 'بحرانی');
  assert.strictEqual(store.query({ sort: 'هزینه' })[0].estimatedCost, 3500000);
  assert.strictEqual(store.query({ sort: 'جدیدترین' }).length, 3);
});

test('آمار', () => {
  const { store } = seededStore();
  const s = store.stats();
  assert.strictEqual(s.total, 3);
  assert.strictEqual(s.open, 2);
  assert.strictEqual(s.done, 1);
  assert.strictEqual(s.criticalOpen, 1);
  assert.strictEqual(s.estimatedOpenCost, 3950000);
  assert.strictEqual(s.actualCost, 950000);
  assert.strictEqual(s.overdue, 1);
  assert.strictEqual(s.dueSoon, 1);
});

test('وضعیت سرویس', () => {
  const { store, v } = seededStore();
  let st = store.serviceStatus(v);
  assert.strictEqual(st.kmRemaining, 3000, '۷۰۰۰ کیلومتر از سرویس گذشته');
  assert.strictEqual(st.kmDue, false);
  store.updateVehicle(v.id, { odometer: 190000 });
  st = store.serviceStatus(store.getVehicle(v.id));
  assert.strictEqual(st.kmDue, true);
  assert.strictEqual(st.kmRemaining, -5000);
  store.recordService(v.id, 190000);
  st = store.serviceStatus(store.getVehicle(v.id));
  assert.strictEqual(st.kmDue, false);
  assert.strictEqual(st.kmRemaining, 10000);
  assert.strictEqual(store.getVehicle(v.id).lastServiceDate, Jalali.todayIso());
});

test('حذف و بازگردانی', () => {
  const { store } = seededStore();
  const d = store.defects[0];
  const snapshot = JSON.parse(JSON.stringify(d));
  store.deleteDefect(d.id);
  assert.strictEqual(store.getDefect(d.id), null);
  store.restoreDefect(snapshot);
  assert.strictEqual(store.getDefect(d.id).title, d.title);
});

test('حذف خودرو، ایرادها را هم پاک می‌کند', () => {
  const { store, v } = seededStore();
  store.deleteVehicle(v.id);
  assert.strictEqual(store.defects.length, 0);
  assert.strictEqual(store.getVehicle(v.id), null);
});

test('اعتبارسنجی', () => {
  const { store, v } = seededStore();
  assert.ok(CarModel.validateDefect({ vehicleId: v.id, title: 'ab' }).title);
  assert.ok(CarModel.validateDefect({ vehicleId: 'نامعلوم', title: 'عنوان معتبر', severity: 'متوسط', status: 'باز', category: 'موتور' }, store).vehicleId);
  assert.ok(CarModel.validateDefect({ vehicleId: v.id, title: 'عنوان معتبر', severity: 'خیلی‌زیاد', status: 'باز', category: 'موتور' }).severity);
  assert.deepStrictEqual(CarModel.validateDefect({ vehicleId: v.id, title: 'صدای موتور', severity: 'زیاد', status: 'باز', category: 'موتور' }), {});
  assert.ok(CarModel.validateVehicle({ name: ' ' }, store).name);
  assert.ok(CarModel.validateVehicle({ name: 'پژو ۴۰۵' }, store).name, 'نام تکراری رد شود');
  assert.ok(CarModel.validateVehicle({ name: 'خودرو', odometer: 1000, lastServiceKm: 5000 }).odometer);
});

test('پشتیبان‌گیری و بازیابی', () => {
  const { store } = seededStore();
  const payload = store.exportPayload();
  const fresh = new CarModel.Store(memStorage());
  const res = fresh.importPayload(payload, 'replace');
  assert.strictEqual(res.ok, true);
  assert.strictEqual(fresh.vehicles.length, 1);
  assert.strictEqual(fresh.defects.length, 3);
  const merge = fresh.importPayload(payload, 'merge');
  assert.strictEqual(merge.added, 0, 'ادغامِ تکراری چیزی اضافه نکند');
  assert.strictEqual(fresh.importPayload('متن خراب').ok, false);
});

console.log('\n' + passed + ' تست موفق، ' + failed + ' تست ناموفق');
process.exit(failed ? 1 : 0);
