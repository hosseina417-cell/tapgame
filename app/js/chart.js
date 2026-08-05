/* ============================================================
 * نمودار کندل‌استیک روی Canvas — بدون وابستگی خارجی
 * قرارداد سری‌های EMA: ema[i] متناظر با کندل i+lag است
 * تعامل: کشیدن افقی (پیمایش تاریخ) + لمس (کراس‌هیر قیمت/زمان)
 * ============================================================ */
'use strict';

const Chart = (function () {

  const COLORS = {
    up: '#22c55e', down: '#ef4444', grid: 'rgba(148,163,184,0.12)',
    ema9: '#38bdf8', ema21: '#f59e0b', vol: 'rgba(148,163,184,0.35)',
    text: '#94a3b8', cross: 'rgba(148,163,184,0.5)'
  };

  function render(canvas, candles, opts) {
    opts = opts || {};
    if (!canvas || !candles || candles.length < 2) return;

    const dpr = window.devicePixelRatio || 1;
    const W = canvas.clientWidth, H = canvas.clientHeight;
    if (W === 0 || H === 0) return;
    canvas.width = W * dpr;
    canvas.height = H * dpr;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const volH = Math.round(H * 0.18);
    const priceH = H - volH - 6;
    const padL = 6, padR = 56, padT = 8, padB = 4;

    // پنجره نمایش: [end-maxBars, end)
    const maxBars = opts.maxBars || 120;
    const end = opts.windowEnd || candles.length;
    const s = Math.max(0, end - maxBars);
    candles = candles.slice(s, end);
    const n = candles.length;
    if (n < 2) return;

    // سری‌های EMA هم‌تراز با پنجره کندل‌ها
    const ema9 = opts.ema9 ? windowSeries(opts.ema9.values, opts.ema9.lag, s, end) : null;
    const ema21 = opts.ema21 ? windowSeries(opts.ema21.values, opts.ema21.lag, s, end) : null;

    // محدوده قیمت
    let min = Infinity, max = -Infinity;
    for (const c of candles) {
      if (c.l < min) min = c.l;
      if (c.h > max) max = c.h;
    }
    if (ema9) for (const v of ema9.arr) { if (v < min) min = v; if (v > max) max = v; }
    if (ema21) for (const v of ema21.arr) { if (v < min) min = v; if (v > max) max = v; }
    const range = max - min || 1;
    min -= range * 0.05; max += range * 0.05;
    const fullRange = max - min;

    const plotW = W - padL - padR;
    const plotH = priceH - padT - padB;
    const slot = plotW / n;
    const bodyW = Math.max(1, Math.min(slot * 0.65, 14));

    const x = i => padL + slot * (i + 0.5);
    const y = p => padT + (max - p) / fullRange * plotH;

    // پس‌زمینه
    ctx.fillStyle = '#0b1220';
    ctx.fillRect(0, 0, W, H);

    // شبکه افقی
    ctx.strokeStyle = COLORS.grid;
    ctx.lineWidth = 1;
    ctx.font = '10px Vazirmatn, sans-serif';
    ctx.fillStyle = COLORS.text;
    for (let g = 0; g <= 4; g++) {
      const p = min + fullRange * g / 4;
      const yy = y(p);
      ctx.beginPath(); ctx.moveTo(padL, yy); ctx.lineTo(W - padR, yy); ctx.stroke();
      ctx.textAlign = 'left';
      ctx.fillText(fmt(p), W - padR + 4, yy + 3);
    }

    // حجم‌ها
    let maxVol = 1;
    for (const c of candles) maxVol = Math.max(maxVol, c.v || 0);
    for (let i = 0; i < n; i++) {
      const c = candles[i];
      const vh = (c.v || 0) / maxVol * (volH - 4);
      ctx.fillStyle = c.c >= c.o ? 'rgba(34,197,94,0.35)' : 'rgba(239,68,68,0.35)';
      ctx.fillRect(x(i) - bodyW / 2, H - 2 - vh, bodyW, vh);
    }

    // کندل‌ها
    for (let i = 0; i < n; i++) {
      const c = candles[i];
      const up = c.c >= c.o;
      ctx.strokeStyle = up ? COLORS.up : COLORS.down;
      ctx.fillStyle = up ? COLORS.up : COLORS.down;
      const xc = x(i);
      // فتیله
      ctx.beginPath();
      ctx.moveTo(xc, y(c.h));
      ctx.lineTo(xc, y(c.l));
      ctx.stroke();
      // بدنه
      const yO = y(c.o), yC = y(c.c);
      const top = Math.min(yO, yC), h = Math.max(1, Math.abs(yO - yC));
      ctx.fillRect(xc - bodyW / 2, top, bodyW, h);
    }

    // EMA ها
    if (ema9 && ema9.arr.length) drawLine(ctx, ema9, COLORS.ema9, x, y);
    if (ema21 && ema21.arr.length) drawLine(ctx, ema21, COLORS.ema21, x, y);

    // کراس‌هیر (لمس)
    if (opts.crosshair && opts.crosshair.i >= 0 && opts.crosshair.i < n) {
      const ci = opts.crosshair.i;
      const cc = candles[ci];
      const xc = x(ci), yc = y(cc.c);
      ctx.setLineDash([3, 3]);
      ctx.strokeStyle = COLORS.cross;
      ctx.beginPath(); ctx.moveTo(xc, padT); ctx.lineTo(xc, H - volH); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(padL, yc); ctx.lineTo(W - padR, yc); ctx.stroke();
      ctx.setLineDash([]);
      ctx.fillStyle = cc.c >= cc.o ? COLORS.up : COLORS.down;
      ctx.beginPath(); ctx.arc(xc, yc, 3.5, 0, Math.PI * 2); ctx.fill();
    }

    // قیمت آخر
    const last = candles[n - 1];
    ctx.setLineDash([3, 3]);
    ctx.strokeStyle = COLORS.cross;
    ctx.beginPath(); ctx.moveTo(padL, y(last.c)); ctx.lineTo(W - padR, y(last.c)); ctx.stroke();
    ctx.setLineDash([]);

    function fmt(p) {
      if (p >= 1000) return p.toLocaleString('en-US', { maximumFractionDigits: 0 });
      if (p >= 1) return p.toFixed(2);
      return p.toFixed(4);
    }
  }

  /* پنجره هم‌تراز سری با کندل‌ها: سری با تاخیر lag، برای کندل‌های [s, end)
   * خروجی: { arr, off } که arr[0] متناظر با کندل (s + off) است */
  function windowSeries(values, lag, s, end) {
    if (!values || !values.length) return null;
    const from = Math.max(0, s - lag);
    const to = end - 1 - lag;
    if (to < from) return null;
    const arr = [];
    for (let i = from; i <= to && i < values.length; i++) arr.push(values[i]);
    if (!arr.length) return null;
    return { arr: arr, off: from + lag - s };
  }

  function drawLine(ctx, sw, color, x, y) {
    const series = sw.arr;
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    for (let i = 0; i < series.length; i++) {
      const xi = x(sw.off + i);
      const yi = y(series[i]);
      if (i === 0) ctx.moveTo(xi, yi); else ctx.lineTo(xi, yi);
    }
    ctx.stroke();
  }

  /* ============================================================
   * جلسه تعاملی: پیمایش افقی + کراس‌هیر + دابل‌تپ برای بازنشانی
   * ============================================================ */
  function createSession(canvas, candles, opts) {
    opts = opts || {};
    let windowEnd = candles.length;
    let drag = null;
    let raf = null;
    let lastTouchAt = 0;
    let crosshair = null;

    const overlay = document.createElement('div');
    overlay.className = 'chart-overlay';
    overlay.style.display = 'none';
    canvas.parentNode.appendChild(overlay);

    function draw() {
      render(canvas, candles, Object.assign({}, opts, {
        windowEnd: windowEnd,
        crosshair: crosshair
      }));
      // به‌روزرسانی متن کراس‌هیر
      if (crosshair && crosshair.i >= 0) {
        const s = Math.max(0, windowEnd - (opts.maxBars || 120));
        const ci = s + crosshair.i;
        const c = candles[ci];
        if (c) {
          const d = new Date(c.t);
          overlay.innerHTML =
            '<span>' + d.toLocaleString('fa-IR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) + '</span>' +
            '<span>O:' + c.o.toFixed(c.o < 1 ? 6 : 2) + '</span>' +
            '<span>H:' + c.h.toFixed(c.h < 1 ? 6 : 2) + '</span>' +
            '<span>L:' + c.l.toFixed(c.l < 1 ? 6 : 2) + '</span>' +
            '<span>C:' + c.c.toFixed(c.c < 1 ? 6 : 2) + '</span>';
          overlay.style.display = 'block';
        }
      } else {
        overlay.style.display = 'none';
      }
    }

    function scheduleDraw() {
      if (raf) return;
      raf = requestAnimationFrame(() => { raf = null; draw(); });
    }

    function candleIndexFromX(clientX) {
      const rect = canvas.getBoundingClientRect();
      const W = rect.width;
      const padL = 6, padR = 56;
      const maxBars = opts.maxBars || 120;
      const n = Math.min(maxBars, windowEnd);
      const slot = (W - padL - padR) / n;
      const i = Math.floor((clientX - rect.left - padL) / slot);
      return Math.max(0, Math.min(n - 1, i));
    }

    canvas.addEventListener('pointerdown', function (e) {
      drag = { startX: e.clientX, startEnd: windowEnd };
      canvas.setPointerCapture(e.pointerId);
    });

    canvas.addEventListener('pointermove', function (e) {
      if (drag) {
        const rect = canvas.getBoundingClientRect();
        const maxBars = opts.maxBars || 120;
        const n = Math.min(maxBars, drag.startEnd);
        const slot = (rect.width - 6 - 56) / n;
        const dCandles = Math.round((drag.startX - e.clientX) / slot);
        windowEnd = Math.max(maxBars, Math.min(candles.length, drag.startEnd + dCandles));
        crosshair = null;
        scheduleDraw();
      } else {
        crosshair = { i: candleIndexFromX(e.clientX) };
        scheduleDraw();
      }
    });

    canvas.addEventListener('pointerup', function () {
      if (drag) {
        const now = Date.now();
        if (now - lastTouchAt < 300) {
          // دابل‌تپ: بازنشانی به آخرین داده
          windowEnd = candles.length;
          crosshair = null;
          draw();
        }
        lastTouchAt = now;
        drag = null;
      }
    });

    canvas.addEventListener('pointerleave', function () {
      if (!drag) { crosshair = null; scheduleDraw(); }
    });

    draw();
    return {
      draw: draw,
      setData(c, o) { candles = c; opts = o || opts; windowEnd = candles.length; crosshair = null; draw(); },
      destroy() {
        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        canvas.replaceWith(canvas.cloneNode(true));
      }
    };
  }

  return { render, createSession, windowSeries, COLORS };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = Chart;
