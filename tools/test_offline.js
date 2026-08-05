#!/usr/bin/env node
/* ============================================================
 * تست حالت آفلاین: fetch خراب → fallback به کش → بنر آفلاین
 * اجرا: node tools/test_offline.js
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
// شبیه‌سازی قطع کامل شبکه (خطای HTTP → بدون retry → مسیر کش سریع)
window.fetch = () => Promise.reject(new Error('HTTP 503'));

let code = '';
for (const s of ['util.js', 'store.js', 'indicators.js', 'strategy.js', 'pumpdetect.js', 'providers.js', 'chart.js', 'ui.js', 'app.js']) {
  code += '\n;' + fs.readFileSync(path.join(APP, 'js', s), 'utf8') + '\n';
}

code += `
;(function(){
  const errors = [];
  window.__errors = errors;
  window.addEventListener('error', e => errors.push(e.message));

  // از قبل کش بازار را پر می‌کنیم (مثل جلسه قبل)
  Store.cacheMarket([
    { id: 'bitcoin', sym: 'BTC', name: 'بیت‌کوین', price: 64000, chg24: 1.5, mcap: 1.2e12, image: null },
    { id: 'ethereum', sym: 'ETH', name: 'اتریوم', price: 3100, chg24: -0.8, mcap: 3.7e11, image: null }
  ], 'coingecko');

  setTimeout(function () {
    try {
      // بوت با شبکه قطع → باید از کش بخواند
      App.boot();
      setTimeout(function () {
        if (!App.state.market || App.state.market.list.length !== 2)
          errors.push('آفلاین: داده بازار از کش نیامد');
        if (!App.state.marketStale)
          errors.push('آفلاین: پرچم stale تنظیم نشد');
        App.setScreen('market');
        const banner = document.querySelector('.stale-banner');
        if (!banner) errors.push('آفلاین: بنر «داده کش‌شده» نمایش داده نشد');
        const rows = document.querySelectorAll('.coin-row');
        if (rows.length !== 2) errors.push('آفلاین: ردیف‌های بازار رندر نشدند (' + rows.length + ')');
        const dot = document.getElementById('netStatus');
        if (!dot || dot.className.indexOf('offline') < 0) errors.push('آفلاین: نشان وضعیت آفلاین نیست');
        window.__done = true;
      }, 1200);
    } catch (e) {
      errors.push('runtime: ' + e.message);
      window.__done = true;
    }
  }, 50);
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
    console.log('✘ خطاهای آفلاین:');
    for (const e of errors) console.log('  - ' + e);
    process.exit(1);
  }
  console.log('✔ تست آفلاین موفق بود (کش + بنر + ردیف‌ها)');
  process.exit(0);
}, 2500);
