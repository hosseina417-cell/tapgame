/* ============================================================
 * تشخیص پامپ و دامپ (فقط ابزار هشدار — نه پیش‌بینی قطعی)
 * منطق: جهش قیمت در پنجره کوتاه + جهش غیرعادی حجم
 * ورودی: کندل‌های ۱۵ دقیقه‌ای یا ۱ ساعته + tf ('15m'|'1h')
 * ============================================================ */
'use strict';

const PumpDetect = (function () {

  const DEFAULTS = {
    pumpPct15m: 2.5,   // درصد رشد در ۱۵ دقیقه برای هشدار پامپ
    pumpPct1h: 5.0,    // درصد رشد در ۱ ساعت
    dumpPct15m: -2.5,  // درصد ریزش در ۱۵ دقیقه
    dumpPct1h: -5.0,   // درصد ریزش در ۱ ساعت
    volRatio: 2.0      // نسبت حجم به میانگین
  };

  function severity(returnPct, volRatio, isPump) {
    const mag = Math.abs(returnPct);
    let level = 'mild';
    if (isPump) {
      if (mag >= 10 && volRatio >= 5) level = 'extreme';
      else if (mag >= 6 && volRatio >= 3) level = 'high';
    } else {
      if (mag >= 8 && volRatio >= 5) level = 'extreme';
      else if (mag >= 5 && volRatio >= 3) level = 'high';
    }
    return level;
  }

  /**
   * بررسی سری کندل
   * @param candles کندل‌ها (۱۵ دقیقه‌ای یا ۱ ساعته)
   * @param opts تنظیمات آستانه
   * @param tf '15m' یا '1h'
   */
  function check(candles, opts, tf) {
    opts = Object.assign({}, DEFAULTS, opts || {});
    tf = tf || '15m';
    if (!candles || candles.length < 30) return null;

    const closes = candles.map(c => c.c);
    const vols = candles.map(c => c.v || 0);
    const last = closes[closes.length - 1];
    const n = closes.length;

    // پنجره‌های بازده متناسب با تایم‌فریم
    // 15m: کوتاه = ۱ کندل (۱۵ دقیقه)، میان = ۴ کندل (۱ ساعت)
    // 1h:  کوتاه = ۱ کندل (۱ ساعت)، میان = ۴ کندل (۴ ساعت)
    const retShort = n > 2 ? (last - closes[n - 2]) / closes[n - 2] * 100 : 0;
    const retMed = n > 5 ? (last - closes[n - 5]) / closes[n - 5] * 100 : retShort;

    const pctShort = tf === '15m' ? opts.pumpPct15m : opts.pumpPct1h;
    const pctMed = tf === '15m' ? opts.pumpPct1h : opts.pumpPct1h * 2;
    const dmpShort = tf === '15m' ? opts.dumpPct15m : opts.dumpPct1h;
    const dmpMed = tf === '15m' ? opts.dumpPct1h : opts.dumpPct1h * 2;

    // نسبت حجم: آخرین کندل به میانگین ۲۰ کندل قبلی
    const lookback = Math.min(20, vols.length - 1);
    let sum = 0;
    for (let i = vols.length - 1 - lookback; i < vols.length - 1; i++) sum += vols[i];
    const avgVol = sum / lookback;
    const volRatio = avgVol > 0 ? vols[vols.length - 1] / avgVol : 1;

    // پامپ: رشد سریع + حجم بالا
    if (retShort >= pctShort || retMed >= pctMed) {
      if (volRatio >= opts.volRatio) {
        const r = Math.max(retShort, retMed);
        return {
          type: 'pump', tf: tf,
          retShort: retShort, retMed: retMed, volRatio: volRatio,
          severity: severity(r, volRatio, true),
          msg: 'رشد ' + r.toFixed(1) + '٪ با حجم ' + volRatio.toFixed(1) + ' برابر میانگین'
        };
      }
    }
    // دامپ: ریزش سریع + حجم بالا
    if (retShort <= dmpShort || retMed <= dmpMed) {
      if (volRatio >= opts.volRatio) {
        const r = Math.min(retShort, retMed);
        return {
          type: 'dump', tf: tf,
          retShort: retShort, retMed: retMed, volRatio: volRatio,
          severity: severity(r, volRatio, false),
          msg: 'ریزش ' + Math.abs(r).toFixed(1) + '٪ با حجم ' + volRatio.toFixed(1) + ' برابر میانگین'
        };
      }
    }
    // آماده‌باش نوسان: فشردگی واقعی (دامنه ۴ کندل اخیر) + حجم غیرعادی
    if (volRatio >= opts.volRatio * 1.5 && n > 5) {
      let hh = -Infinity, ll = Infinity;
      for (let i = n - 4; i < n; i++) {
        if (candles[i].h > hh) hh = candles[i].h;
        if (candles[i].l < ll) ll = candles[i].l;
      }
      const rangePct = last > 0 ? (hh - ll) / last * 100 : 0;
      if (rangePct < 1.5) {
        return {
          type: 'watch', tf: tf,
          retShort: retShort, retMed: retMed, volRatio: volRatio,
          severity: 'mild',
          msg: 'فشردگی قیمت (' + rangePct.toFixed(1) + '٪) با حجم غیرعادی — آماده‌باش نوسان'
        };
      }
    }
    return null;
  }

  return { check, DEFAULTS, severity };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = PumpDetect;
