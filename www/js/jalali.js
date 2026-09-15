/* تقویم شمسی — پیاده‌سازی مستقل (پورتِ نسخهٔ پایتونیِ تست‌شده) */
(function (global) {
  'use strict';

  var MONTH_NAMES = ['فروردین', 'اردیبهشت', 'خرداد', 'تیر', 'مرداد', 'شهریور',
    'مهر', 'آبان', 'آذر', 'دی', 'بهمن', 'اسفند'];
  var WEEKDAYS = ['دوشنبه', 'سه‌شنبه', 'چهارشنبه', 'پنج‌شنبه', 'جمعه', 'شنبه', 'یکشنبه'];
  var BREAKS = [-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635,
    2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178];
  var MIN_YEAR = 1178, MAX_YEAR = 1634;
  var ANCHOR_JY = 1400, ANCHOR_G = [2021, 3, 21];
  var PERSIAN = '۰۱۲۳۴۵۶۷۸۹';

  function div(a, b) { return Math.floor(a / b); }
  function mod(a, b) { return a - div(a, b) * b; }

  function segment(jy) {
    var jp = BREAKS[0], jump = 0;
    for (var i = 1; i < BREAKS.length; i++) {
      var jm = BREAKS[i];
      jump = jm - jp;
      if (jy < jm) break;
      jp = jm;
    }
    return { jp: jp, jump: jump };
  }

  function isLeap(jy) {
    if (jy < MIN_YEAR || jy > MAX_YEAR) return false;
    var s = segment(jy), n = jy - s.jp, jump = s.jump;
    if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33;
    var leap = mod(mod(n + 1, 33) - 1, 4);
    if (leap === -1) leap = 4;
    return leap === 0;
  }

  function monthLength(jy, jm) {
    if (jm <= 6) return 31;
    if (jm <= 11) return 30;
    return isLeap(jy) ? 30 : 29;
  }

  function toDate(gy, gm, gd) { return Date.UTC(gy, gm - 1, gd); }
  function fromDate(ms) { var d = new Date(ms); return [d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate()]; }

  var cache = {};
  cache[ANCHOR_JY] = toDate(ANCHOR_G[0], ANCHOR_G[1], ANCHOR_G[2]);

  function nowruz(jy) {
    if (jy < MIN_YEAR || jy > MAX_YEAR) throw new Error('سال شمسی خارج از بازه: ' + jy);
    if (cache[jy] !== undefined) return cache[jy];
    var best = null;
    Object.keys(cache).forEach(function (k) {
      var y = parseInt(k, 10);
      if (best === null || Math.abs(y - jy) < Math.abs(best - jy)) best = y;
    });
    var year = best, ms = cache[best];
    while (year !== jy) {
      if (jy > year) { ms += (isLeap(year) ? 366 : 365) * 86400000; year++; }
      else { year--; ms -= (isLeap(year) ? 366 : 365) * 86400000; }
      cache[year] = ms;
    }
    return ms;
  }

  function gregorianToJalali(gy, gm, gd) {
    var ms = toDate(gy, gm, gd);
    var jy = Math.min(MAX_YEAR, Math.max(MIN_YEAR, gy - 621));
    while (ms < nowruz(jy) && jy > MIN_YEAR) jy--;
    while (jy < MAX_YEAR && ms >= nowruz(jy + 1)) jy++;
    var doy = Math.round((ms - nowruz(jy)) / 86400000);
    if (doy < 186) return [jy, 1 + div(doy, 31), 1 + mod(doy, 31)];
    doy -= 186;
    return [jy, 7 + div(doy, 30), 1 + mod(doy, 30)];
  }

  function jalaliToGregorian(jy, jm, jd) {
    if (jy < MIN_YEAR || jy > MAX_YEAR || jm < 1 || jm > 12) throw new Error('تاریخ شمسی نامعتبر');
    var maxd = monthLength(jy, jm);
    if (jd < 1 || jd > maxd) throw new Error('روز شمسی نامعتبر');
    var offset = jm <= 7 ? (jm - 1) * 31 : 186 + (jm - 7) * 30;
    return fromDate(nowruz(jy) + (offset + jd - 1) * 86400000);
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function parse(value) {
    if (!value) return null;
    if (value instanceof Date) return value;
    var m = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?/.exec(String(value).trim());
    if (!m) return null;
    return new Date(+m[1], +m[2] - 1, +m[3], +(m[4] || 0), +(m[5] || 0));
  }

  function format(value, withTime) {
    var dt = parse(value);
    if (!dt) return '';
    var j = gregorianToJalali(dt.getFullYear(), dt.getMonth() + 1, dt.getDate());
    var out = j[0] + '/' + pad(j[1]) + '/' + pad(j[2]);
    if (withTime && (dt.getHours() || dt.getMinutes())) {
      out += ' — ' + pad(dt.getHours()) + ':' + pad(dt.getMinutes());
    }
    return out;
  }

  function todayIso() {
    var d = new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }

  function nowIso() {
    var d = new Date();
    return todayIso() + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function persianDigits(text) {
    return String(text).replace(/[0-9]/g, function (d) { return PERSIAN[+d]; });
  }

  function weekday(value) {
    var dt = parse(value);
    if (!dt) return '';
    return WEEKDAYS[(dt.getDay() + 6) % 7];
  }

  function monthName(jm) {
    jm = parseInt(jm, 10);
    return jm >= 1 && jm <= 12 ? MONTH_NAMES[jm - 1] : '';
  }

  function addDays(iso, days) {
    var dt = parse(iso);
    if (!dt) return '';
    dt.setDate(dt.getDate() + days);
    return dt.getFullYear() + '-' + pad(dt.getMonth() + 1) + '-' + pad(dt.getDate());
  }

  function daysBetween(startIso, endIso) {
    var a = parse(startIso), b = parse(endIso);
    if (!a || !b) return null;
    return Math.round((Date.UTC(b.getFullYear(), b.getMonth(), b.getDate())
      - Date.UTC(a.getFullYear(), a.getMonth(), a.getDate())) / 86400000);
  }

  function parseJalali(jy, jm, jd) {
    try {
      var g = jalaliToGregorian(parseInt(jy, 10), parseInt(jm, 10), parseInt(jd, 10));
      return g[0] + '-' + pad(g[1]) + '-' + pad(g[2]);
    } catch (e) {
      return '';
    }
  }

  var api = {
    MONTH_NAMES: MONTH_NAMES,
    isLeap: isLeap,
    monthLength: monthLength,
    gregorianToJalali: gregorianToJalali,
    jalaliToGregorian: jalaliToGregorian,
    format: format,
    parse: parse,
    todayIso: todayIso,
    nowIso: nowIso,
    persianDigits: persianDigits,
    weekday: weekday,
    monthName: monthName,
    addDays: addDays,
    daysBetween: daysBetween,
    parseJalali: parseJalali
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.Jalali = api;
})(typeof window !== 'undefined' ? window : globalThis);
