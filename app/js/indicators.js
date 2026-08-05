/* ============================================================
 * اندیکاتورهای تحلیل تکنیکال — خالص و قابل تست
 * همه توابع روی آرایه‌ای از کندل‌ها کار می‌کنند:
 *   candle = { t, o, h, l, c, v }  (زمان، باز، بیشینه، کمینه، بسته، حجم)
 * اگر داده کافی نباشد → null برمی‌گردد (نه NaN!)
 * ============================================================ */
'use strict';

const TA = (function () {

  function valid(x) { return x !== null && x !== undefined && isFinite(x); }

  /* ---------- میانگین متحرک ساده ---------- */
  function sma(values, period) {
    if (!values || values.length < period) return null;
    const out = [];
    let sum = 0;
    for (let i = 0; i < values.length; i++) {
      sum += values[i];
      if (i >= period) sum -= values[i - period];
      if (i >= period - 1) out.push(sum / period);
    }
    return out; // شاخص i-th معادل values[i-period+1]
  }

  /* ---------- میانگین متحرک نمایی (Wilder-style seed) ---------- */
  function ema(values, period) {
    if (!values || values.length < period) return null;
    const k = 2 / (period + 1);
    const out = [];
    let prev = values.slice(0, period).reduce((a, b) => a + b, 0) / period;
    out.push(prev);
    for (let i = period; i < values.length; i++) {
      prev = values[i] * k + prev * (1 - k);
      out.push(prev);
    }
    return out; // شاخص i-th معادل values[i+period]
  }

  /* ---------- RSI (Wilder) ---------- */
  function rsi(closes, period) {
    period = period || 14;
    if (!closes || closes.length < period + 1) return null;
    const out = [];
    let avgGain = 0, avgLoss = 0;
    for (let i = 1; i <= period; i++) {
      const d = closes[i] - closes[i - 1];
      if (d >= 0) avgGain += d; else avgLoss -= d;
    }
    avgGain /= period; avgLoss /= period;
    out.push(avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss));
    for (let i = period + 1; i < closes.length; i++) {
      const d = closes[i] - closes[i - 1];
      const gain = d > 0 ? d : 0;
      const loss = d < 0 ? -d : 0;
      avgGain = (avgGain * (period - 1) + gain) / period;
      avgLoss = (avgLoss * (period - 1) + loss) / period;
      out.push(avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss));
    }
    return out; // شاخص i-th معادل closes[i+period]
  }

  /* ---------- MACD ---------- */
  function macd(closes, fast, slow, signal) {
    fast = fast || 12; slow = slow || 26; signal = signal || 9;
    const emaFast = ema(closes, fast);
    const emaSlow = ema(closes, slow);
    if (!emaFast || !emaSlow) return null;
    const diffLen = Math.min(emaFast.length, emaSlow.length);
    const macdLine = [];
    const offset = emaFast.length - diffLen;
    for (let i = 0; i < diffLen; i++) {
      macdLine.push(emaFast[offset + i] - emaSlow[i]);
    }
    if (macdLine.length < signal) return null;
    const sig = ema(macdLine, signal);
    if (!sig) return null;
    const sigOff = macdLine.length - sig.length;
    const out = [];
    for (let i = 0; i < sig.length; i++) {
      const m = macdLine[sigOff + i];
      const s = sig[i];
      out.push({ macd: m, signal: s, hist: m - s });
    }
    return out; // شاخص i-th معادل closes[i + slow + signal - 1 + ...]
  }

  /* ---------- باند بولینگر ---------- */
  function bollinger(closes, period, mult) {
    period = period || 20; mult = mult || 2;
    const mid = sma(closes, period);
    if (!mid) return null;
    const out = [];
    for (let i = period - 1; i < closes.length; i++) {
      const slice = closes.slice(i - period + 1, i + 1);
      const m = slice.reduce((a, b) => a + b, 0) / period;
      let sd = 0;
      for (let j = 0; j < slice.length; j++) sd += (slice[j] - m) * (slice[j] - m);
      sd = Math.sqrt(sd / period);
      out.push({ mid: m, upper: m + mult * sd, lower: m - mult * sd });
    }
    return out;
  }

  /* ---------- ATR ---------- */
  function atr(candles, period) {
    period = period || 14;
    if (!candles || candles.length < period + 1) return null;
    const trs = [];
    for (let i = 1; i < candles.length; i++) {
      const c = candles[i], p = candles[i - 1];
      trs.push(Math.max(c.h - c.l, Math.abs(c.h - p.c), Math.abs(c.l - p.c)));
    }
    const out = [];
    let prev = trs.slice(0, period).reduce((a, b) => a + b, 0) / period;
    out.push(prev);
    for (let i = period; i < trs.length; i++) {
      prev = (prev * (period - 1) + trs[i]) / period;
      out.push(prev);
    }
    return out; // شاخص i-th معادل candles[i+period]
  }

  /* ---------- استوکاستیک ---------- */
  function stochastic(candles, kPeriod, dPeriod, smooth) {
    kPeriod = kPeriod || 14; dPeriod = dPeriod || 3; smooth = smooth || 3;
    if (!candles || candles.length < kPeriod + dPeriod + smooth) return null;
    const kRaw = [];
    for (let i = kPeriod - 1; i < candles.length; i++) {
      let hh = -Infinity, ll = Infinity;
      for (let j = i - kPeriod + 1; j <= i; j++) {
        if (candles[j].h > hh) hh = candles[j].h;
        if (candles[j].l < ll) ll = candles[j].l;
      }
      kRaw.push(hh === ll ? 50 : ((candles[i].c - ll) / (hh - ll)) * 100);
    }
    const kSmooth = sma(kRaw, smooth);
    if (!kSmooth) return null;
    const d = sma(kSmooth, dPeriod);
    if (!d) return null;
    const dOff = kSmooth.length - d.length;
    const out = [];
    for (let i = 0; i < d.length; i++) {
      out.push({ k: kSmooth[dOff + i], d: d[i] });
    }
    return out;
  }

  /* ---------- حجم: میانگین + نسبت ---------- */
  function volumeAnalysis(candles, period) {
    period = period || 20;
    if (!candles || candles.length < period + 1) return null;
    const vols = candles.map(c => c.v || 0);
    const avg = sma(vols, period);
    if (!avg) return null;
    const out = [];
    for (let i = 0; i < avg.length; i++) {
      const candleIdx = i + period - 1; // اندیس کندل متناظر با این میانگین
      const ratio = avg[i] > 0 ? vols[candleIdx] / avg[i] : 1;
      out.push({ avg: avg[i], ratio: ratio });
    }
    return out;
  }

  /* ---------- نرخ تغییر (ROC) ---------- */
  function roc(closes, periods) {
    const out = {};
    for (const p of periods) {
      if (!closes || closes.length <= p) { out[p] = null; continue; }
      const last = closes[closes.length - 1];
      const prev = closes[closes.length - 1 - p];
      out[p] = prev > 0 ? ((last - prev) / prev) * 100 : null;
    }
    return out;
  }

  /* ---------- جمع‌بندی روی آخرین کندل ---------- */
  function analyze(candles, opts) {
    opts = opts || {};
    const closes = candles.map(c => c.c);
    const highs = candles.map(c => c.h);
    const lows = candles.map(c => c.l);
    const last = candles[candles.length - 1];
    const res = {
      candles: candles,
      last: last,
      closes: closes,
      count: candles.length,
      rsi: null, macd: null, bb: null, atr: null, stoch: null,
      vol: null, roc: null, ema9: null, ema21: null, ema50: null,
      sma20: null, sma50: null
    };
    const r = rsi(closes, opts.rsiPeriod || 14);
    if (r) { res.rsi = r[r.length - 1]; res.rsiSeries = r; }
    const m = macd(closes, opts.macdFast || 12, opts.macdSlow || 26, opts.macdSignal || 9);
    if (m) { res.macd = m[m.length - 1]; res.macdSeries = m; }
    const bb = bollinger(closes, opts.bbPeriod || 20, opts.bbMult || 2);
    if (bb) { res.bb = bb[bb.length - 1]; res.bbSeries = bb; }
    const at = atr(candles, opts.atrPeriod || 14);
    if (at) { res.atr = at[at.length - 1]; res.atrSeries = at; }
    const st = stochastic(candles, opts.stochK || 14, opts.stochD || 3, opts.stochSmooth || 3);
    if (st) { res.stoch = st[st.length - 1]; res.stochSeries = st; }
    const va = volumeAnalysis(candles, opts.volPeriod || 20);
    if (va) { res.vol = va[va.length - 1]; res.volSeries = va; }
    res.roc = roc(closes, [2, 4, 12, 24, 96]);
    for (const p of [9, 21, 50]) {
      const e = ema(closes, p);
      if (e) {
        res['ema' + p] = e[e.length - 1];
        res['ema' + p + 'Series'] = e;
      }
    }
    const s20 = sma(closes, 20), s50 = sma(closes, 50);
    if (s20) res.sma20 = s20[s20.length - 1];
    if (s50) res.sma50 = s50[s50.length - 1];
    // باند درصدی (آخرین قیمت کجای باند است)
    if (res.bb && res.bb.upper > res.bb.lower) {
      res.bbPct = (last.c - res.bb.lower) / (res.bb.upper - res.bb.lower);
    }
    return res;
  }

  return { sma, ema, rsi, macd, bollinger, atr, stochastic, volumeAnalysis, roc, analyze, valid };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = TA;
