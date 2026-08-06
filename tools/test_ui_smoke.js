#!/usr/bin/env node
/* ============================================================
 * تست دود (Smoke Test) رابط کاربری با jsdom
 * اجرا: node tools/test_ui_smoke.js  (نیازمند: npm i jsdom)
 * ============================================================ */
'use strict';

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const APP = path.join(__dirname, '..', 'app');
const html = fs.readFileSync(path.join(APP, 'index.html'), 'utf8');

const dom = new JSDOM(html, {
  url: 'https://localhost/app/index.html',
  runScripts: 'outside-only',
  pretendToBeVisual: true
});
const { window } = dom;
const { document } = window;

window.alert = () => {};
window.confirm = () => false;
window.scrollTo = () => {};
window.fetch = () => Promise.reject(new Error('network blocked in test'));

// همه اسکریپت‌ها + بدنه تست در یک eval (مثل یک scope سراسری مرورگر)
const scripts = ['version.js', 'util.js', 'store.js', 'indicators.js', 'strategy.js', 'pumpdetect.js', 'providers.js', 'chart.js', 'ui.js', 'app.js'];
let code = '';
for (const s of scripts) {
  code += '\n;' + fs.readFileSync(path.join(APP, 'js', s), 'utf8') + '\n';
}

code += `
;(function(){
  const errors = [];
  window.__errors = errors;
  window.addEventListener('error', e => errors.push(e.message));

  // داده مصنوعی بازار (قبل از بوت)
  App.state.market = { list: [
    { id: 'bitcoin', sym: 'BTC', name: 'بیت‌کوین', price: 64200.5, chg24: 2.3, image: null },
    { id: 'ethereum', sym: 'ETH', name: 'اتریوم', price: 3120.1, chg24: -1.2, image: null }
  ] };
  App.state.marketAt = Date.now();

  // صبر می‌کنیم بوت (DOMContentLoaded) اجرا شود
  setTimeout(function () {
    try {
      App.setScreen('market');

      // ردیف‌ها
      const rows = document.querySelectorAll('.coin-row');
      if (rows.length !== 2) errors.push('فهرست بازار: ' + rows.length + ' ردیف (انتظار ۲)');

      // جستجو (با احترام به debounce)
      const search = document.getElementById('marketSearch');
      search.value = 'ETH';
      search.dispatchEvent(new window.Event('input'));
      setTimeout(function () {
        const visible = [...document.querySelectorAll('.coin-row')].filter(r => r.style.display !== 'none');
        if (visible.length !== 1) errors.push('جستجو: ' + visible.length + ' نتیجه (انتظار ۱)');

        // صفحه اسکنر
        App.setScreen('scanner');
        UI.renderScanResults(document.getElementById('scanList'), [
          { coin: { id: 'bitcoin', sym: 'BTC', name: 'بیت‌کوین' }, type: 'pump', severity: 'extreme', msg: 'رشد ۱۱٪', at: Date.now() }
        ], Date.now());
        if (!document.querySelector('.scan-row')) errors.push('اسکنر: ردیف هشدار رندر نشد');

        // صفحه تنظیمات
        App.setScreen('settings');
        if (!document.querySelector('.settings-form')) errors.push('تنظیمات: فرم رندر نشد');

        // پنل سیگنال
        const panel = U.el('div');
        UI.renderSignalPanel(panel, {
          probability: 68, signal: 'BUY', score: 34.5,
          parts: [{ tf: '1h', ev: { factors: {
            trend: { score: 0.6, notes: ['x'] }, macd: { score: 0.5, notes: ['y'] },
            rsi: { score: 0.4, notes: ['z'] }, stoch: { score: 0.3, notes: [] },
            bollinger: { score: 0.2, notes: [] }, volume: { score: 0.1, notes: [] }
          } } }]
        }, null);
        if (!panel.textContent.includes('٪')) errors.push('پنل سیگنال: درصد احتمال نمایش داده نشد');

        // صفحه جزئیات (رندر استاتیک)
        App.openCoin('bitcoin');
        if (!document.getElementById('backBtn')) errors.push('جزئیات: دکمه بازگشت نیست');
        if (!document.getElementById('chart')) errors.push('جزئیات: نمودار نیست');
        if (!document.getElementById('signalPanel')) errors.push('جزئیات: پنل سیگنال نیست');
        if (!document.getElementById('detailChips')) errors.push('جزئیات: چیپ‌های اطلاعاتی نیست');
        if (location.hash !== '#/coin/bitcoin') errors.push('مسیریابی: hash جزئیات (' + location.hash + ')');

        // مسیریابی hash: شبیه‌سازی دکمه بازگشت اندروید
        location.hash = '#/market';
        window.dispatchEvent(new window.HashChangeEvent('hashchange'));
        if (App.state.screen !== 'market') errors.push('مسیریابی: بازگشت به بازار کار نکرد');

        location.hash = '#/scanner';
        window.dispatchEvent(new window.HashChangeEvent('hashchange'));
        if (App.state.screen !== 'scanner') errors.push('مسیریابی: اسکنر کار نکرد');

        location.hash = '#/coin/ethereum';
        window.dispatchEvent(new window.HashChangeEvent('hashchange'));
        if (App.state.screen !== 'detail' || !App.state.coin || App.state.coin.id !== 'ethereum') errors.push('مسیریابی: جزئیات اتریوم باز نشد');

        // تست: دکمه با on* handler واقعاً کلیک می‌شود (رفع باگ افزودن)
        const btn = U.el('button', { class: 'btn', onclick: function () { window.__clicked = (window.__clicked || 0) + 1; } });
        btn.click();
        if (window.__clicked !== 1) errors.push('رویداد on* با U.el بسته نمی‌شود');

        window.__done = true;
      }, 300);
    } catch (e) {
      errors.push('runtime: ' + e.message);
      window.__done = true;
    }
  }, 100);
})();
`;

try {
  window.eval(code);
} catch (e) {
  console.log('✘ خطای اجرا:', e.message);
  process.exit(1);
}

setTimeout(() => {
  const errors = window.__errors || [];
  if (errors.length) {
    console.log('✘ خطاها:');
    for (const e of errors) console.log('  - ' + e);
    process.exit(1);
  }
  console.log('✔ تست دود رابط کاربری موفق بود');
  process.exit(0);
}, 1500);
