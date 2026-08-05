/* ============================================================
 * موتور سیگنال: امتیازدهی شفاف چندعاملی
 * هر عامل بین -1 تا +1 امتیاز می‌دهد؛ امتیاز کل به درصد
 * «احتمال» تبدیل می‌شود. این یک برآورد آماری-آزمایشی ساده است
 * و به هیچ‌وجه توصیه مالی نیست.
 * ============================================================ */
'use strict';

if (typeof module !== 'undefined' && module.exports) {
  var TAImpl = require('./indicators.js');
}

const Strategy = (function () {

  const WEIGHTS = {
    trend: 0.22,      // روند (EMA)
    macd: 0.18,       // مومنتوم مکدی
    rsi: 0.18,        // ناحیه RSI
    stoch: 0.12,      // استوکاستیک
    bollinger: 0.15,  // بازگشت به میانگین
    volume: 0.15      // تایید حجم
  };

  /* هر عامل: مقدار ‎-1..+1 + توضیح */
  function factorTrend(a) {
    let score = 0; const notes = [];
    if (a.ema21 !== null && a.ema50 !== null) {
      if (a.ema21 > a.ema50) { score += 0.6; notes.push('EMA21 بالای EMA50 (روند صعودی)'); }
      else { score -= 0.6; notes.push('EMA21 زیر EMA50 (روند نزولی)'); }
    }
    if (a.ema9 !== null && a.ema21 !== null) {
      if (a.ema9 > a.ema21) { score += 0.4; notes.push('EMA9 بالای EMA21 (مومنتوم کوتاه‌مدت مثبت)'); }
      else { score -= 0.4; notes.push('EMA9 زیر EMA21 (مومنتوم کوتاه‌مدت منفی)'); }
    }
    if (a.ema50 !== null && a.last) {
      if (a.last.c > a.ema50) score += 0.2; else score -= 0.2;
    }
    return { score: Math.max(-1, Math.min(1, score)), notes: notes };
  }

  function factorMacd(a) {
    if (!a.macd) return { score: 0, notes: ['داده کافی نیست'] };
    let score = 0; const notes = [];
    const { macd, signal, hist } = a.macd;
    if (macd > signal) { score += 0.5; notes.push('MACD بالای سیگنال'); }
    else { score -= 0.5; notes.push('MACD زیر سیگنال'); }
    if (hist > 0) { score += 0.3; notes.push('هیستوگرام مثبت'); }
    else { score -= 0.3; notes.push('هیستوگرام منفی'); }
    if (macd > 0) { score += 0.2; notes.push('MACD بالای صفر'); }
    else { score -= 0.2; notes.push('MACD زیر صفر'); }

    // تشخیص «تقاطع تازه» (تغییر علامت هیستوگرام در ۳ کندل اخیر)
    const ser = a.macdSeries;
    if (ser && ser.length > 3) {
      const h0 = ser[ser.length - 1].hist, h1 = ser[ser.length - 2].hist, h2 = ser[ser.length - 3].hist;
      if (h0 > 0 && h1 <= 0) { score += 0.3; notes.push('تقاطع تازه MACD به بالای سیگنال'); }
      else if (h0 < 0 && h1 >= 0) { score -= 0.3; notes.push('تقاطع تازه MACD به زیر سیگنال'); }
      else if (h0 > 0 && h1 > 0 && h2 <= 0) { score += 0.15; notes.push('تقاطع مثبت ۲ کندل پیش'); }
      else if (h0 < 0 && h1 < 0 && h2 >= 0) { score -= 0.15; notes.push('تقاطع منفی ۲ کندل پیش'); }
    }
    return { score: Math.max(-1, Math.min(1, score)), notes: notes };
  }

  function factorRsi(a) {
    if (a.rsi === null) return { score: 0, notes: ['داده کافی نیست'] };
    const r = a.rsi; let score = 0; const notes = [];
    // فیلتر روند: در روند نزولی، اشباع فروش «چاقوی در حال سقوط» است
    const trendUp = a.ema21 !== null && a.ema50 !== null ? a.ema21 >= a.ema50 : (a.last && a.ema50 !== null ? a.last.c >= a.ema50 : true);
    const trendDown = a.ema21 !== null && a.ema50 !== null ? a.ema21 < a.ema50 : (a.last && a.ema50 !== null ? a.last.c < a.ema50 : false);

    if (r < 30) {
      if (trendUp) { score += 0.9; notes.push('RSI اشباع فروش و روند صعودی (' + r.toFixed(1) + ') — بازگشت محتمل'); }
      else if (trendDown) { score += 0.25; notes.push('RSI اشباع فروش اما روند نزولی — ریسک ادامه ریزش (' + r.toFixed(1) + ')'); }
      else { score += 0.6; notes.push('RSI اشباع فروش (' + r.toFixed(1) + ')'); }
    } else if (r < 40) {
      if (trendUp) { score += 0.5; notes.push('RSI پایین و روند صعودی (' + r.toFixed(1) + ')'); }
      else if (trendDown) { score += 0.15; notes.push('RSI پایین اما روند نزولی (' + r.toFixed(1) + ')'); }
      else { score += 0.35; notes.push('RSI پایین (' + r.toFixed(1) + ')'); }
    } else if (r > 70) {
      if (trendDown) { score -= 0.9; notes.push('RSI اشباع خرید و روند نزولی (' + r.toFixed(1) + ') — اصلاح محتمل'); }
      else if (trendUp) { score -= 0.25; notes.push('RSI اشباع خرید اما روند صعودی — ممکن است ادامه دهد (' + r.toFixed(1) + ')'); }
      else { score -= 0.6; notes.push('RSI اشباع خرید (' + r.toFixed(1) + ')'); }
    } else if (r > 60) {
      if (trendDown) { score -= 0.5; notes.push('RSI بالا و روند نزولی (' + r.toFixed(1) + ')'); }
      else if (trendUp) { score -= 0.15; notes.push('RSI بالا اما روند صعودی (' + r.toFixed(1) + ')'); }
      else { score -= 0.35; notes.push('RSI بالا (' + r.toFixed(1) + ')'); }
    } else {
      notes.push('RSI خنثی (' + r.toFixed(1) + ')');
    }

    // واگرایی ساده: قیمت کف پایین‌تر اما RSI کف بالاتر (۵ کندل اخیر)
    const ser = a.rsiSeries;
    if (ser && ser.length > 5 && a.candles && a.candles.length > 5) {
      const rNow = ser[ser.length - 1], rPrev = ser[ser.length - 6];
      const priceNow = a.candles[a.candles.length - 1].l;
      const pricePrev = a.candles[a.candles.length - 6].l;
      if (priceNow < pricePrev && rNow > rPrev) { score += 0.2; notes.push('واگرایی مثبت قیمت/RSI'); }
      if (priceNow > pricePrev && rNow < rPrev) { score -= 0.2; notes.push('واگرایی منفی قیمت/RSI'); }
    }

    // جهت RSI نسبت به کندل قبل
    const prev = ser && ser.length > 1 ? ser[ser.length - 2] : null;
    if (prev !== null && prev !== undefined) {
      if (r > prev) { score += 0.1; notes.push('RSI در حال افزایش'); }
      else { score -= 0.1; notes.push('RSI در حال کاهش'); }
    }
    return { score: Math.max(-1, Math.min(1, score)), notes: notes };
  }

  function factorStoch(a) {
    if (!a.stoch) return { score: 0, notes: ['داده کافی نیست'] };
    const { k, d } = a.stoch; let score = 0; const notes = [];
    if (k < 20) { score += 0.55; notes.push('استوکاستیک اشباع فروش'); }
    else if (k > 80) { score -= 0.55; notes.push('استوکاستیک اشباع خرید'); }
    if (k > d) { score += 0.45; notes.push('K بالای D (تقاطع مثبت)'); }
    else { score -= 0.45; notes.push('K زیر D (تقاطع منفی)'); }
    return { score: Math.max(-1, Math.min(1, score)), notes: notes };
  }

  function factorBollinger(a) {
    if (!a.bb || a.bbPct === null || a.bbPct === undefined) return { score: 0, notes: ['داده کافی نیست'] };
    let score = 0; const notes = [];
    if (a.bbPct < 0.05) { score += 0.75; notes.push('قیمت روی باند پایین (احتمال بازگشت)'); }
    else if (a.bbPct < 0.25) { score += 0.35; notes.push('قیمت نزدیک باند پایین'); }
    else if (a.bbPct > 0.95) { score -= 0.75; notes.push('قیمت روی باند بالا (احتمال اصلاح)'); }
    else if (a.bbPct > 0.75) { score -= 0.35; notes.push('قیمت نزدیک باند بالا'); }
    else { notes.push('قیمت میانه باند'); }
    return { score: Math.max(-1, Math.min(1, score)), notes: notes };
  }

  function factorVolume(a) {
    if (!a.vol) return { score: 0, notes: ['داده کافی نیست'] };
    const r = a.vol.ratio; let score = 0; const notes = [];
    if (r > 1.5) { notes.push('حجم ' + r.toFixed(1) + ' برابر میانگین'); }
    else { notes.push('حجم ' + r.toFixed(1) + ' برابر میانگین'); }
    // حجم بالا + حرکت مثبت = تایید؛ حجم بالا + حرکت منفی = فشار فروش
    const rocShort = a.roc && a.roc[2];
    if (r > 1.5 && rocShort !== null) {
      if (rocShort > 0.5) { score += 0.7; notes.push('حجم بالا همراه رشد قیمت (تایید خرید)'); }
      else if (rocShort < -0.5) { score -= 0.7; notes.push('حجم بالا همراه ریزش قیمت (فشار فروش)'); }
    } else if (r < 0.7) {
      score -= 0.2; notes.push('حجم کم (تایید ضعیف)');
    }
    return { score: Math.max(-1, Math.min(1, score)), notes: notes };
  }

  /* ---------- محاسبه امتیاز کل ---------- */
  function evaluate(a) {
    const factors = {
      trend: factorTrend(a),
      macd: factorMacd(a),
      rsi: factorRsi(a),
      stoch: factorStoch(a),
      bollinger: factorBollinger(a),
      volume: factorVolume(a)
    };
    let total = 0, wsum = 0;
    for (const k in WEIGHTS) {
      total += WEIGHTS[k] * factors[k].score;
      wsum += WEIGHTS[k];
    }
    const score = (wsum > 0 ? total / wsum : 0) * 100; // -100..+100
    // احتمال: تبدیل امتیاز به درصد (تابع سیگموئید ملایم)
    const prob = 50 + 45 * Math.tanh(score / 60);
    let signal = 'NEUTRAL';
    if (score >= 25) signal = 'BUY';
    else if (score <= -25) signal = 'SELL';
    return {
      score: score,
      probability: Math.max(1, Math.min(99, prob)),
      signal: signal,
      factors: factors,
      timestamp: Date.now()
    };
  }

  /* ============================================================
   * بکتست سبک: دقت تجربی سیگنال‌ها روی داده تاریخی همان سکه
   * برای هر نقطه، سیگنال را روی پنجره قبل شبیه‌سازی و بازده
   * افق win کندل بعد را می‌سنجد.
   * خروجی: { buy, buyWins, sell, sellWins, buyRate, sellRate }
   * ============================================================ */
  function backtest(candles, opts) {
    opts = opts || {};
    const n = candles ? candles.length : 0;
    if (n < 80) return null;
    const win = opts.win || 4;          // افق: ۴ کندل بعد
    const minBars = opts.minBars || 60; // حداقل داده برای اولین سیگنال
    const step = opts.step || 3;        // هر چند کندل یک بار (سرعت)
    let buy = 0, buyWins = 0, sell = 0, sellWins = 0;
    const T = (typeof TAImpl !== 'undefined' && TAImpl) ? TAImpl : TA; // مرورگر: TA سراسری
    for (let i = minBars; i < n - win; i += step) {
      const a = T.analyze(candles.slice(0, i + 1));
      const ev = evaluate(a);
      const fwd = candles[i + win].c / candles[i].c - 1;
      if (ev.signal === 'BUY') { buy++; if (fwd > 0.001) buyWins++; }
      else if (ev.signal === 'SELL') { sell++; if (fwd < -0.001) sellWins++; }
    }
    if (!buy && !sell) return null;
    return {
      buy: buy, buyWins: buyWins,
      sell: sell, sellWins: sellWins,
      buyRate: buy ? (buyWins / buy * 100) : null,
      sellRate: sell ? (sellWins / sell * 100) : null
    };
  }

  /* ---------- ترکیب چند تایم‌فریم ---------- */
  function evaluateMulti(analyses) {
    // analyses: [{ tf, a }] — وزن بیشتر برای تایم‌فریم بالاتر (روند) + میان‌مدت (زمان‌بندی)
    const tfWeights = { '15m': 0.25, '1h': 0.45, '4h': 0.3, '1d': 0.35 };
    let total = 0, wsum = 0;
    const parts = [];
    for (const item of analyses) {
      const w = tfWeights[item.tf] || 0.2;
      const ev = evaluate(item.a);
      total += w * ev.score;
      wsum += w;
      parts.push({ tf: item.tf, ev: ev });
    }
    const score = wsum > 0 ? total / wsum : 0;
    const prob = 50 + 45 * Math.tanh(score / 60);
    let signal = 'NEUTRAL';
    if (score >= 25) signal = 'BUY';
    else if (score <= -25) signal = 'SELL';
    return { score, probability: Math.max(1, Math.min(99, prob)), signal, parts };
  }

  return { evaluate, evaluateMulti, backtest, WEIGHTS, factorTrend, factorMacd, factorRsi, factorStoch, factorBollinger, factorVolume };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = Strategy;
