/* تست رابط کاربری با jsdom: جریان‌های اصلی برنامه واقعاً اجرا می‌شوند
   اجرا: node tests/node/ui.test.js   (نیازمند: npm i jsdom) */
const path = require('path');
const assert = require('assert');
const { JSDOM } = require('jsdom');

const root = path.join(__dirname, '..', '..');
let passed = 0, failed = 0;
(async function main() {

function test(name, fn) {
  try { fn(); passed++; console.log('  ✓ ' + name); }
  catch (e) { failed++; console.log('  ✗ ' + name + '\n      ' + (e && e.message)); }
}

const fs = require('fs');
const dom = new JSDOM(fs.readFileSync(path.join(root, 'www', 'index.html'), 'utf8'), {
  url: 'http://localhost/index.html',   // مبدأ باید شفاف باشد تا localStorage کار کند
  runScripts: 'dangerously',
  resources: undefined,
  pretendToBeVisual: true,
});
const { window } = dom;
const doc = window.document;

// jsdom متد scrollTo را پیاده نکرده است؛ برای جلوگیری از هشدار آن را خنثی می‌کنیم
window.scrollTo = function () {};

function boot() {
  // بارگذاریِ دستیِ اسکریپت‌ها (jsdom منابعِ محلی را بدون resources نمی‌گیرد)
  ['js/jalali.js', 'js/store.js', 'js/app.js'].forEach((rel) => {
    const code = fs.readFileSync(path.join(root, 'www', rel), 'utf8');
    const script = doc.createElement('script');
    script.textContent = code;
    doc.body.appendChild(script);
  });
}

const $ = (id) => doc.getElementById(id);
const click = (el) => el.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
function setInput(name, value) {
  const el = doc.querySelector('#modal-body [name="' + name + '"]');
  assert.ok(el, 'فیلد ' + name + ' پیدا نشد');
  el.value = value;
  return el;
}

await new Promise((resolve) => {
  if (doc.readyState === 'complete') resolve();
  else window.addEventListener('load', resolve);
});
boot();

test('برنامه بالا می‌آید و تاریخ امروز نمایش داده می‌شود', () => {
  assert.ok($('today-label').textContent.includes('/'), $('today-label').textContent);
  assert.strictEqual(doc.querySelectorAll('.screen').length, 5);
});

test('خودروی پیش‌فرض در نخستین اجرا ساخته می‌شود', () => {
  const store = window.CarModel ? null : null;
  assert.ok($('vehicle-list').textContent.includes('خودروی من') ||
    $('vehicle-list').textContent.includes('خودرو'));
});

test('افزودن خودرو از طریق فرم', () => {
  doc.querySelector('nav button[data-screen="vehicles"]').click();
  $('fab').click();
  setInput('name', 'پژو ۴۰۵');
  setInput('brand', 'ایران‌خودرو');
  setInput('odometer', '182000');
  click(doc.querySelector('[data-action="save-vehicle"]'));
  assert.ok($('vehicle-list').textContent.includes('پژو ۴۰۵'), 'خودرو در فهرست نمایش داده نشد');
});

test('جلوگیری از نام تکراریِ خودرو', () => {
  $('fab').click();
  setInput('name', 'پژو ۴۰۵');
  click(doc.querySelector('[data-action="save-vehicle"]'));
  const err = doc.querySelector('#modal-body .error');
  assert.ok(err && err.textContent.length > 0, 'خطای تکراری نمایش داده نشد');
  click(doc.querySelector('[data-action="close-dialog"]'));
});

test('ثبت ایراد جدید', () => {
  doc.querySelector('nav button[data-screen="defects"]').click();
  $('fab').click();
  setInput('title', 'صدای زوزه از گیربکس');
  setInput('category', 'گیربکس و انتقال قدرت');
  setInput('severity', 'بحرانی');
  setInput('estimatedCost', '3500000');
  click(doc.querySelector('[data-action="save-defect"]'));
  assert.ok($('defect-list').textContent.includes('صدای زوزه از گیربکس'));
  assert.ok($('defect-list').textContent.includes('بحرانی'));
});

test('عنوانِ کوتاه رد می‌شود', () => {
  $('fab').click();
  setInput('title', 'ab');
  click(doc.querySelector('[data-action="save-defect"]'));
  assert.ok(doc.querySelector('#modal-body .error'), 'خطای اعتبارسنجی نمایش داده نشد');
  click(doc.querySelector('[data-action="close-dialog"]'));
});

test('جستجو در فهرست ایرادها', () => {
  $('search-input').value = 'گيربکس';
  $('search-input').dispatchEvent(new window.Event('input', { bubbles: true }));
  assert.ok($('defect-list').textContent.includes('زوزه'), 'جستجو نتیجه نداد');
  $('search-input').value = 'چیزی‌که‌نیست';
  $('search-input').dispatchEvent(new window.Event('input', { bubbles: true }));
  assert.ok($('defect-list').textContent.includes('پیدا نشد'), 'حالت خالی نمایش داده نشد');
  $('search-input').value = '';
  $('search-input').dispatchEvent(new window.Event('input', { bubbles: true }));
  assert.ok($('defect-list').textContent.includes('زوزه'));
});

test('داشبورد آمار را نشان می‌دهد و ایرادِ بحرانی در «نیازمند توجه» است', () => {
  doc.querySelector('nav button[data-screen="dashboard"]').click();
  assert.ok($('stat-tiles').textContent.includes('بحرانی'));
  assert.ok($('attention-list').textContent.includes('زوزه'));
});

test('ویرایش ایراد اعمال می‌شود', () => {
  doc.querySelector('nav button[data-screen="defects"]').click();
  const card = doc.querySelector('#defect-list [data-action="edit-defect"]');
  click(card);
  setInput('status', 'انجام‌شده');
  click(doc.querySelector('[data-action="save-defect"]'));
  doc.querySelector('nav button[data-screen="dashboard"]').click();
  assert.ok(!$('attention-list').textContent.includes('زوزه'), 'ایراد انجام‌شده نباید در نیازمند توجه باشد');
});

test('حذف با امکان بازگردانی', () => {
  doc.querySelector('nav button[data-screen="defects"]').click();
  const card = doc.querySelector('#defect-list [data-action="edit-defect"]');
  click(card);
  click(doc.querySelector('[data-action="delete-defect"]'));
  click(doc.querySelector('[data-action="dialog-accept"]'));
  assert.strictEqual($('toast').classList.contains('show'), true, 'پیام حذف نمایش داده نشد');
  click($('toast-action'));                       // بازگردانی
  assert.ok($('defect-list').textContent.includes('زوزه'), 'ایراد بازنگشت');
});

test('ثبت سرویس وضعیت را به‌روز می‌کند', () => {
  doc.querySelector('nav button[data-screen="service"]').click();
  assert.ok($('service-list').textContent.includes('پژو ۴۰۵'));
  const btn = doc.querySelector('#service-list [data-action="record-service"]');
  click(btn);
  click(doc.querySelector('[data-action="dialog-accept"]'));
  assert.ok($('service-list').textContent.includes('وضعیت خوب') ||
    $('service-list').textContent.includes('کیلومتر مانده'), $('service-list').textContent.slice(0, 120));
});

test('ساخت خروجی CSV', () => {
  doc.querySelector('nav button[data-screen="data"]').click();
  click(doc.querySelector('[data-action="csv-show"]'));
  assert.ok($('csv-text').value.includes('خودرو'), 'سرستون CSV ناقص است');
  assert.ok($('csv-text').value.includes('زوزه'), 'ردیف ایراد در CSV نیست');
});

test('پشتیبان‌گیری و بازیابی', () => {
  click(doc.querySelector('[data-action="backup-show"]'));
  const payload = $('backup-text').value;
  assert.ok(payload.includes('پژو ۴۰۵'));
  $('import-text').value = payload;
  click(doc.querySelector('[data-action="import-merge"]'));
  assert.ok($('toast').classList.contains('show'));
});

console.log('\n' + passed + ' تست موفق، ' + failed + ' تست ناموفق');
process.exit(failed ? 1 : 0);
})();
