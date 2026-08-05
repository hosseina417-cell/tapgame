/* ============================================================
 * تنظیمات + علاقه‌مندی‌ها + کش داده — ذخیره در localStorage
 * ============================================================ */
'use strict';

const Store = (function () {

  const KEY = 'cryptoscanner_v1';

  const DEFAULTS = {
    refreshSec: 60,        // فاصله به‌روزرسانی فهرست بازار
    detailRefreshSec: 120, // فاصله به‌روزرسانی صفحه جزئیات
    scanIntervalMin: 5,    // اسکن خودکار پامپ/دامپ
    pumpPct15m: 2.5,
    pumpPct1h: 5,
    dumpPct15m: -2.5,
    dumpPct1h: -5,
    volRatio: 2,
    favorites: [],         // شناسه سکه‌های نشان شده
    notifyEnabled: true,   // اعلان سیستمی هشدار شدید
    watchlist: []          // سکه‌های سفارشی اضافه‌شده
  };

  let data = null;

  function load() {
    if (data) return data;
    try {
      const raw = localStorage.getItem(KEY);
      data = Object.assign({}, DEFAULTS, raw ? JSON.parse(raw) : {});
    } catch (e) {
      data = Object.assign({}, DEFAULTS);
    }
    return data;
  }

  function save() {
    try { localStorage.setItem(KEY, JSON.stringify(data)); } catch (e) { /* پر شدن حافظه */ }
  }

  function get(key) { return load()[key]; }
  function set(key, value) { load()[key] = value; save(); }

  function isFavorite(id) { return load().favorites.indexOf(id) >= 0; }
  function toggleFavorite(id) {
    const f = load().favorites;
    const i = f.indexOf(id);
    if (i >= 0) f.splice(i, 1); else f.push(id);
    save();
    return i < 0;
  }

  function reset() {
    data = Object.assign({}, DEFAULTS);
    try {
      const keys = [];
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k && (k.indexOf('cscache_') === 0 || k.indexOf('cscacheK_') === 0)) keys.push(k);
      }
      for (const k of keys) localStorage.removeItem(k);
    } catch (e) { /* ignore */ }
    save();
  }

  /* ============================================================
   * کش داده برای حالت آفلاین (با سقف حجم)
   * ============================================================ */
  const CACHE_MAX = 1.5 * 1024 * 1024; // 1.5 مگابایت

  function cacheSize() {
    let total = 0;
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k && k.indexOf('cscache') === 0) total += (localStorage.getItem(k) || '').length;
      }
    } catch (e) { /* ignore */ }
    return total;
  }

  function cachePut(key, value) {
    try {
      localStorage.setItem('cscache_' + key, JSON.stringify(value));
      // هرس اگر از سقف گذشت
      let guard = 0;
      while (cacheSize() > CACHE_MAX && guard++ < 50) {
        let oldest = null, oldestKey = null;
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i);
          if (!k || k.indexOf('cscache_') !== 0) continue;
          try {
            const t = JSON.parse(localStorage.getItem(k) || '{}').at || 0;
            if (oldest === null || t < oldest) { oldest = t; oldestKey = k; }
          } catch (e) { localStorage.removeItem(k); }
        }
        if (oldestKey) localStorage.removeItem(oldestKey); else break;
      }
    } catch (e) { /* پر شدن حافظه */ }
  }

  function cacheGet(key) {
    try {
      const raw = localStorage.getItem('cscache_' + key);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  function cacheMarket(list, source) {
    cachePut('market', { list: list, source: source, at: Date.now() });
  }
  function getMarketCache() { return cacheGet('market'); }

  function cacheKlines(coinId, tf, candles, source) {
    cachePut('k_' + coinId + '_' + tf, { candles: candles, source: source, at: Date.now() });
  }
  function getKlinesCache(coinId, tf) { return cacheGet('k_' + coinId + '_' + tf); }

  function cacheScan(results) {
    cachePut('scan', { results: results, at: Date.now() });
  }
  function getScanCache() { return cacheGet('scan'); }

  return { load, save, get, set, isFavorite, toggleFavorite, reset, DEFAULTS,
           cacheMarket, getMarketCache, cacheKlines, getKlinesCache, cacheScan, getScanCache };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = Store;
