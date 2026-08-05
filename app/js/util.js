/* ============================================================
 * ابزارهای عمومی: قالب‌بندی اعداد، DOM، زمان
 * ============================================================ */
'use strict';

const U = (function () {

  function fmtPrice(x, digits) {
    if (x === null || x === undefined || !isFinite(x)) return '—';
    let d;
    if (digits !== undefined) d = digits;
    else if (x >= 1000) d = 2;
    else if (x >= 1) d = 2;
    else if (x >= 0.01) d = 4;
    else if (x >= 0.0001) d = 6;
    else d = 8; // سکه‌های خیلی ریز مثل SHIB
    return x.toLocaleString('en-US', { minimumFractionDigits: x >= 1 ? 2 : 0, maximumFractionDigits: d });
  }

  function fmtCompact(x) {
    if (x === null || x === undefined || !isFinite(x)) return '—';
    if (x >= 1e12) return (x / 1e12).toFixed(2) + 'T';
    if (x >= 1e9) return (x / 1e9).toFixed(2) + 'B';
    if (x >= 1e6) return (x / 1e6).toFixed(2) + 'M';
    if (x >= 1e3) return (x / 1e3).toFixed(1) + 'K';
    return x.toFixed(2);
  }

  function fmtPct(x, signed) {
    if (x === null || x === undefined || !isFinite(x)) return '—';
    const s = signed && x > 0 ? '+' : '';
    return s + x.toFixed(2) + '٪';
  }

  function fmtTime(ts) {
    const d = new Date(ts);
    return d.toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit' });
  }

  function fmtDateTime(ts) {
    const d = new Date(ts);
    return d.toLocaleString('fa-IR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  /* ---------- DOM ---------- */
  function el(tag, attrs, children) {
    const node = document.createElement(tag);
    if (attrs) {
      for (const k in attrs) {
        if (k === 'class') node.className = attrs[k];
        else if (k === 'text') node.textContent = attrs[k];
        else if (k === 'html') node.innerHTML = attrs[k];
        else if (k === 'style') node.style.cssText = attrs[k];
        else node.setAttribute(k, attrs[k]);
      }
    }
    if (children) {
      for (const c of [].concat(children)) {
        if (c === null || c === undefined) continue;
        node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
      }
    }
    return node;
  }

  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

  /* ---------- صف محدود (برای کنترل همزمانی اسکن) با وعده پایان ---------- */
  function makeQueue(limit) {
    let running = 0;
    let idleResolvers = [];
    const queue = [];
    function checkIdle() {
      if (queue.length === 0 && running === 0) {
        for (const r of idleResolvers) r();
        idleResolvers = [];
      }
    }
    function next() {
      if (running >= limit || !queue.length) return;
      running++;
      const task = queue.shift();
      task().finally(() => { running--; next(); checkIdle(); });
    }
    return {
      add(task) { queue.push(task); next(); },
      get size() { return queue.length + running; },
      drain() {
        if (queue.length === 0 && running === 0) return Promise.resolve();
        return new Promise(resolve => idleResolvers.push(resolve));
      }
    };
  }

  /* ---------- debounce (با حفظ this) ---------- */
  function debounce(fn, ms) {
    let t = null;
    return function () {
      const args = arguments;
      const ctx = this;
      if (t) clearTimeout(t);
      t = setTimeout(() => fn.apply(ctx, args), ms);
    };
  }

  return { fmtPrice, fmtCompact, fmtPct, fmtTime, fmtDateTime, el, clear, makeQueue, debounce };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = U;
