/* ============================================================
 * لایه داده: چند منبع داده با fallback خودکار
 * کندل‌ها (OHLC): بایننس → بای‌بیت → اوکی‌ایکس → کراکن → کریپتوکامپیر
 * فهرست بازار: کوین‌گکو → کوین‌کپ → بایننس
 * ============================================================ */
'use strict';

const Providers = (function () {

  /* ---------- فهرست سکه‌ها (شناسه کوین‌گکو + نماد صرافی‌ها) ---------- */
  const COINS = [
    { id: 'bitcoin', name: 'بیت‌کوین', sym: 'BTC',  binance: 'BTCUSDT',  bybit: 'BTCUSDT',  okx: 'BTC-USDT',  kraken: 'XBTUSD', cc: 'BTC' },
    { id: 'ethereum', name: 'اتریوم', sym: 'ETH',  binance: 'ETHUSDT',  bybit: 'ETHUSDT',  okx: 'ETH-USDT',  kraken: 'ETHUSD', cc: 'ETH' },
    { id: 'binancecoin', name: 'بایننس کوین', sym: 'BNB', binance: 'BNBUSDT', bybit: 'BNBUSDT', okx: 'BNB-USDT', kraken: 'BNBUSD', cc: 'BNB' },
    { id: 'ripple', name: 'ریپل', sym: 'XRP',  binance: 'XRPUSDT',  bybit: 'XRPUSDT',  okx: 'XRP-USDT',  kraken: 'XRPUSD', cc: 'XRP' },
    { id: 'solana', name: 'سولانا', sym: 'SOL',  binance: 'SOLUSDT',  bybit: 'SOLUSDT',  okx: 'SOL-USDT',  kraken: 'SOLUSD', cc: 'SOL' },
    { id: 'cardano', name: 'کاردانو', sym: 'ADA',  binance: 'ADAUSDT',  bybit: 'ADAUSDT',  okx: 'ADA-USDT',  kraken: 'ADAUSD', cc: 'ADA' },
    { id: 'dogecoin', name: 'دوج‌کوین', sym: 'DOGE', binance: 'DOGEUSDT', bybit: 'DOGEUSDT', okx: 'DOGE-USDT', kraken: 'XDGUSD', cc: 'DOGE' },
    { id: 'avalanche-2', name: 'آوالانچ', sym: 'AVAX', binance: 'AVAXUSDT', bybit: 'AVAXUSDT', okx: 'AVAX-USDT', kraken: 'AVAXUSD', cc: 'AVAX' },
    { id: 'polkadot', name: 'پولکادات', sym: 'DOT',  binance: 'DOTUSDT',  bybit: 'DOTUSDT',  okx: 'DOT-USDT',  kraken: 'DOTUSD', cc: 'DOT' },
    { id: 'chainlink', name: 'چین‌لینک', sym: 'LINK', binance: 'LINKUSDT', bybit: 'LINKUSDT', okx: 'LINK-USDT', kraken: 'LINKUSD', cc: 'LINK' },
    { id: 'tron', name: 'ترون', sym: 'TRX',  binance: 'TRXUSDT',  bybit: 'TRXUSDT',  okx: 'TRX-USDT',  kraken: 'TRXUSD', cc: 'TRX' },
    { id: 'matic-network', name: 'پالیگان', sym: 'MATIC', binance: 'MATICUSDT', bybit: 'MATICUSDT', okx: 'MATIC-USDT', kraken: 'MATICUSD', cc: 'MATIC' },
    { id: 'litecoin', name: 'لایت‌کوین', sym: 'LTC',  binance: 'LTCUSDT',  bybit: 'LTCUSDT',  okx: 'LTC-USDT',  kraken: 'LTCUSD', cc: 'LTC' },
    { id: 'shiba-inu', name: 'شیبا', sym: 'SHIB', binance: 'SHIBUSDT', bybit: 'SHIBUSDT', okx: 'SHIB-USDT', kraken: 'SHIBUSD', cc: 'SHIB' },
    { id: 'uniswap', name: 'یونی‌سواپ', sym: 'UNI',  binance: 'UNIUSDT',  bybit: 'UNIUSDT',  okx: 'UNI-USDT',  kraken: 'UNIUSD', cc: 'UNI' },
    { id: 'near', name: 'نیر', sym: 'NEAR', binance: 'NEARUSDT', bybit: 'NEARUSDT', okx: 'NEAR-USDT', kraken: 'NEARUSD', cc: 'NEAR' },
    { id: 'aptos', name: 'آپتوس', sym: 'APT',  binance: 'APTUSDT',  bybit: 'APTUSDT',  okx: 'APT-USDT',  kraken: 'APTUSD', cc: 'APT' },
    { id: 'arbitrum', name: 'آربیتروم', sym: 'ARB', binance: 'ARBUSDT', bybit: 'ARBUSDT', okx: 'ARB-USDT', kraken: 'ARBUSD', cc: 'ARB' },
    { id: 'optimism', name: 'اپتیمیزم', sym: 'OP',  binance: 'OPUSDT',  bybit: 'OPUSDT',  okx: 'OP-USDT',  kraken: 'OPUSD', cc: 'OP' },
    { id: 'algorand', name: 'الگورند', sym: 'ALGO', binance: 'ALGOUSDT', bybit: 'ALGOUSDT', okx: 'ALGO-USDT', kraken: 'ALGOUSD', cc: 'ALGO' },
    { id: 'stellar', name: 'استلار', sym: 'XLM',  binance: 'XLMUSDT',  bybit: 'XLMUSDT',  okx: 'XLM-USDT',  kraken: 'XLMUSD', cc: 'XLM' },
    { id: 'filecoin', name: 'فایل‌کوین', sym: 'FIL', binance: 'FILUSDT', bybit: 'FILUSDT', okx: 'FIL-USDT', kraken: 'FILUSD', cc: 'FIL' },
    { id: 'cosmos', name: 'کازموس', sym: 'ATOM', binance: 'ATOMUSDT', bybit: 'ATOMUSDT', okx: 'ATOM-USDT', kraken: 'ATOMUSD', cc: 'ATOM' },
    { id: 'monero', name: 'مونرو', sym: 'XMR',  binance: 'XMRUSDT',  bybit: 'XMRUSDT',  okx: 'XMR-USDT',  kraken: 'XMRUSD', cc: 'XMR' },
    { id: 'toncoin', name: 'تون‌کوین', sym: 'TON', binance: 'TONUSDT', bybit: 'TONUSDT', okx: 'TON-USDT', kraken: 'TONUSD', cc: 'TON' },
    { id: 'internet-computer', name: 'اینترنت کامپیوتر', sym: 'ICP', binance: 'ICPUSDT', bybit: 'ICPUSDT', okx: 'ICP-USDT', kraken: 'ICPUSD', cc: 'ICP' },
    { id: 'vechain', name: 'وی‌چین', sym: 'VET',  binance: 'VETUSDT',  bybit: 'VETUSDT',  okx: 'VET-USDT',  kraken: 'VETUSD', cc: 'VET' },
    { id: 'the-graph', name: 'دگراف', sym: 'GRT',  binance: 'GRTUSDT',  bybit: 'GRTUSDT',  okx: 'GRT-USDT',  kraken: 'GRTUSD', cc: 'GRT' },
    { id: 'aave', name: 'آوه', sym: 'AAVE', binance: 'AAVEUSDT', bybit: 'AAVEUSDT', okx: 'AAVE-USDT', kraken: 'AAVEUSD', cc: 'AAVE' },
    { id: 'sui', name: 'سویی', sym: 'SUI',  binance: 'SUIUSDT',  bybit: 'SUIUSDT',  okx: 'SUI-USDT',  kraken: 'SUIUSD', cc: 'SUI' },
    { id: 'pepe', name: 'پپه', sym: 'PEPE', binance: 'PEPEUSDT', bybit: 'PEPEUSDT', okx: 'PEPE-USDT', kraken: 'PEPEUSD', cc: 'PEPE' },
    { id: 'render-token', name: 'رندر', sym: 'RNDR', binance: 'RNDRUSDT', bybit: 'RNDRUSDT', okx: 'RNDR-USDT', kraken: 'RNDRUSD', cc: 'RNDR' }
  ];

  /* سکه‌های سفارشی که کاربر اضافه می‌کند */
  const extraCoins = [];

  function coinById(id) { return COINS.find(c => c.id === id) || extraCoins.find(c => c.id === id) || null; }
  function coinBySymbol(sym) {
    const s = String(sym || '').toUpperCase();
    return COINS.find(c => c.sym === s) || extraCoins.find(c => c.sym === s) || null;
  }
  function allCoins() { return COINS.concat(extraCoins); }

  /* افزودن سکه سفارشی (از جستجوی کوین‌گکو) */
  function addCoin(coin) {
    if (coinById(coin.id)) return false;
    extraCoins.push(coin);
    return true;
  }

  /* حذف سکه سفارشی */
  function removeCoin(id) {
    const i = extraCoins.findIndex(c => c.id === id);
    if (i >= 0) { extraCoins.splice(i, 1); return true; }
    return false;
  }

  /* جستجوی سکه با نماد (مثلاً WIF) در کوین‌گکو */
  async function searchCoin(query) {
    const raw = await fetchJSON('https://api.coingecko.com/api/v3/search?query=' + encodeURIComponent(query), 8000);
    const coins = (raw && raw.coins) || [];
    return coins.slice(0, 5).map(c => ({
      id: c.id, sym: (c.symbol || '').toUpperCase(), name: c.name || c.symbol,
      binance: (c.symbol || '').toUpperCase() + 'USDT',
      bybit: (c.symbol || '').toUpperCase() + 'USDT',
      okx: (c.symbol || '').toUpperCase() + '-USDT',
      kraken: (c.symbol || '').toUpperCase() + 'USD',
      cc: (c.symbol || '').toUpperCase()
    }));
  }

  /* ---------- fetch با Timeout + سیگنال لغو ----------
   * - 429/5xx سریع رد می‌شود (بدون retry تا سقف منابع نسوزد)
   * - خطای شبکه یک بار با ۴۰۰ms تاخیر retry می‌شود */
  async function fetchJSON(url, timeoutMs, signal) {
    timeoutMs = timeoutMs || 8000;
    const attempt = async () => {
      const ctrl = new AbortController();
      const onOuterAbort = () => ctrl.abort();
      if (signal) {
        if (signal.aborted) throw new Error('aborted');
        signal.addEventListener('abort', onOuterAbort);
      }
      const timer = setTimeout(() => ctrl.abort(), timeoutMs);
      try {
        const resp = await fetch(url, { signal: ctrl.signal, cache: 'no-store' });
        if (resp.status === 429 || resp.status >= 500) throw new Error('HTTP ' + resp.status);
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        return await resp.json();
      } finally {
        clearTimeout(timer);
        if (signal) signal.removeEventListener('abort', onOuterAbort);
      }
    };
    try {
      return await attempt();
    } catch (e) {
      // retry فقط برای خطای شبکه/تایم‌اوت (نه HTTP 4xx/429/5xx)
      if (signal && signal.aborted) throw e;
      if (String(e.message).indexOf('HTTP') === 0) throw e;
      await new Promise(r => setTimeout(r, 400));
      if (signal && signal.aborted) throw e;
      return await attempt();
    }
  }

  /* ============================================================
   * کندل‌ها — زنجیره fallback
   * ============================================================ */
  const TF = {
    '15m': { binance: '15m', bybit: '15', okx: '15m', kraken: 15, cc: 'histominute', cgDays: null },
    '1h':  { binance: '1h',  bybit: '60', okx: '1H',  kraken: 60, cc: 'histohour',   cgDays: null },
    '4h':  { binance: '4h',  bybit: '240', okx: '4H', kraken: 240, cc: 'histohour',  cgDays: 7 },
    '1d':  { binance: '1d',  bybit: 'D',  okx: '1D',  kraken: 1440, cc: 'histoday',   cgDays: 30 }
  };

  function normalize(tf, src, raw) {
    const candles = [];
    if (src === 'binance') {
      for (const k of raw) candles.push({ t: k[0], o: +k[1], h: +k[2], l: +k[3], c: +k[4], v: +k[5] });
    } else if (src === 'bybit' || src === 'okx') {
      // نزولی: آخرین کندل اول است
      const arr = raw.result ? raw.result.list : raw.data;
      for (const k of arr) candles.push({ t: +k[0], o: +k[1], h: +k[2], l: +k[3], c: +k[4], v: +k[5] });
      candles.reverse();
    } else if (src === 'kraken') {
      const pairKey = Object.keys(raw.result || {})[0];
      const arr = (raw.result && raw.result[pairKey]) || [];
      for (const k of arr) candles.push({ t: +k[0] * 1000, o: +k[1], h: +k[2], l: +k[3], c: +k[4], v: +k[6] });
    } else if (src === 'cryptocompare') {
      const arr = (raw.Data && raw.Data.Data) || [];
      for (const k of arr) candles.push({ t: k.time * 1000, o: k.open, h: k.high, l: k.low, c: k.close, v: k.volumefrom || 0 });
    } else if (src === 'coingecko') {
      for (const k of raw) candles.push({ t: k[0], o: k[1], h: k[2], l: k[3], c: k[4], v: 0 });
    }
    // حذف کندل‌های ناقص و مرتب‌سازی
    const good = candles.filter(c => c.o > 0 && c.h >= c.l && c.c > 0);
    good.sort((a, b) => a.t - b.t);
    return good;
  }

  async function getKlines(coin, tfName, limit, signal) {
    limit = limit || 200;
    const tf = TF[tfName] || TF['1h'];
    const errs = [];

    // تلاش موازی: اولین منبع معتبر برنده است (بدترین حالت ~۶ ثانیه به جای ۴۸)
    const attempts = [];

    attempts.push({
      name: 'binance',
      run: async () => {
        const raw = await fetchJSON('https://api.binance.com/api/v3/klines?symbol=' + coin.binance + '&interval=' + tf.binance + '&limit=' + limit, 5000, signal);
        return normalize(tfName, 'binance', raw);
      }
    });
    attempts.push({
      name: 'bybit',
      run: async () => {
        const raw = await fetchJSON('https://api.bybit.com/v5/market/kline?category=spot&symbol=' + coin.bybit + '&interval=' + tf.bybit + '&limit=' + limit, 6000, signal);
        return normalize(tfName, 'bybit', raw);
      }
    });
    attempts.push({
      name: 'okx',
      run: async () => {
        const raw = await fetchJSON('https://www.okx.com/api/v5/market/candles?instId=' + coin.okx + '&bar=' + tf.okx + '&limit=' + limit, 6000, signal);
        return normalize(tfName, 'okx', raw);
      }
    });
    attempts.push({
      name: 'kraken',
      run: async () => {
        const raw = await fetchJSON('https://api.kraken.com/0/public/OHLC?pair=' + coin.kraken + '&interval=' + tf.kraken, 6000, signal);
        return normalize(tfName, 'kraken', raw);
      }
    });
    attempts.push({
      name: 'cryptocompare',
      run: async () => {
        const raw = await fetchJSON('https://min-api.cryptocompare.com/data/v2/' + tf.cc + '?fsym=' + coin.cc + '&tsym=USD&limit=' + limit, 6000, signal);
        return normalize(tfName, 'cryptocompare', raw);
      }
    });
    if (tf.cgDays) {
      attempts.push({
        name: 'coingecko',
        run: async () => {
          const raw = await fetchJSON('https://api.coingecko.com/api/v3/coins/' + coin.id + '/ohlc?vs_currency=usd&days=' + tf.cgDays, 6000, signal);
          return normalize(tfName, 'coingecko', raw);
        }
      });
    }

    const result = await firstValid(attempts, 30, errs, signal);
    if (result) return result;
    throw new Error('همه منابع داده ناموفق بودند: ' + errs.join(' | '));
  }

  /* موازی اجرا می‌کند و اولین نتیجه معتبر (≥۳۰ کندل) را برمی‌گرداند */
  async function firstValid(attempts, minLen, errs, signal) {
    const pending = attempts.map(async (a) => {
      try {
        const c = await a.run();
        if (c && c.length >= minLen) return { candles: c, source: a.name };
        errs.push(a.name + ': data too short');
      } catch (e) {
        if (signal && signal.aborted) throw e;
        errs.push(a.name + ': ' + e.message);
      }
      return null;
    });
    // هر وقت اولین موفق شد برگرد (بقیه بی‌اثر می‌شوند)
    let winner = null;
    await Promise.all(pending.map(p => p.then(r => { if (r && !winner) winner = r; })));
    return winner;
  }

  /* ============================================================
   * فهرست بازار — زنجیره fallback
   * ============================================================ */
  async function getMarketList(ids) {
    ids = ids || allCoins().map(c => c.id).join(',');
    const errs = [];

    // 1) کوین‌گکو
    try {
      const raw = await fetchJSON('https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=' + ids + '&order=market_cap_desc&per_page=100&page=1&sparkline=false');
      if (Array.isArray(raw) && raw.length) {
        return {
          source: 'coingecko',
          list: raw.map(r => ({
            id: r.id, sym: (r.symbol || '').toUpperCase(), name: r.name,
            price: r.current_price, chg24: r.price_change_percentage_24h,
            mcap: r.market_cap, image: r.image
          }))
        };
      }
      errs.push('coingecko: empty');
    } catch (e) { errs.push('coingecko: ' + e.message); }

    // 2) کوین‌کپ
    try {
      const raw = await fetchJSON('https://api.coincap.io/v2/assets?ids=' + ids);
      if (raw && raw.data && raw.data.length) {
        return {
          source: 'coincap',
          list: raw.data.map(r => ({
            id: r.id, sym: (r.symbol || '').toUpperCase(), name: r.name,
            price: parseFloat(r.priceUsd), chg24: parseFloat(r.changePercent24Hr),
            mcap: parseFloat(r.marketCapUsd), image: null
          }))
        };
      }
      errs.push('coincap: empty');
    } catch (e) { errs.push('coincap: ' + e.message); }

    // 3) بایننس (فقط ticker 24 ساعته)
    try {
      const symbols = allCoins().filter(c => c.binance).map(c => '"' + c.binance + '"').join(',');
      const raw = await fetchJSON('https://api.binance.com/api/v3/ticker/24hr?symbols=[' + symbols + ']');
      if (Array.isArray(raw) && raw.length) {
        const bySym = {};
        for (const t of raw) bySym[t.symbol] = t;
        const list = [];
        for (const c of allCoins()) {
          const t = bySym[c.binance];
          if (t) list.push({
            id: c.id, sym: c.sym, name: c.name,
            price: parseFloat(t.lastPrice), chg24: parseFloat(t.priceChangePercent),
            mcap: null, image: null
          });
        }
        return { source: 'binance', list: list };
      }
      errs.push('binance: empty');
    } catch (e) { errs.push('binance: ' + e.message); }

    throw new Error('همه منابع بازار ناموفق بودند: ' + errs.join(' | '));
  }

  /* ---------- جزئیات ۲۴ ساعته یک سکه (برای صفحه جزئیات) ---------- */
  async function getTicker24h(coin) {
    try {
      const raw = await fetchJSON('https://api.binance.com/api/v3/ticker/24hr?symbol=' + coin.binance);
      return {
        high: parseFloat(raw.highPrice), low: parseFloat(raw.lowPrice),
        vol: parseFloat(raw.volume), quoteVol: parseFloat(raw.quoteVolume),
        chg: parseFloat(raw.priceChangePercent)
      };
    } catch (e) {
      return null;
    }
  }

  /* نسخه سریع کندل برای اسکنر: فقط بایننس و بای‌بیت (سرعت بالا) */
  async function getKlinesFast(coin, tfName, limit, signal) {
    limit = limit || 60;
    const tf = TF[tfName] || TF['1h'];
    const errs = [];
    try {
      const raw = await fetchJSON('https://api.binance.com/api/v3/klines?symbol=' + coin.binance + '&interval=' + tf.binance + '&limit=' + limit, 5000, signal);
      const c = normalize(tfName, 'binance', raw);
      if (c.length >= 30) return { candles: c, source: 'binance' };
    } catch (e) { errs.push('binance: ' + e.message); }
    try {
      const raw = await fetchJSON('https://api.bybit.com/v5/market/kline?category=spot&symbol=' + coin.bybit + '&interval=' + tf.bybit + '&limit=' + limit, 5000, signal);
      const c = normalize(tfName, 'bybit', raw);
      if (c.length >= 30) return { candles: c, source: 'bybit' };
    } catch (e) { errs.push('bybit: ' + e.message); }
    throw new Error('fast sources failed: ' + errs.join(' | '));
  }

  return { COINS, coinById, coinBySymbol, allCoins, addCoin, removeCoin, searchCoin, getKlines, getKlinesFast, getMarketList, getTicker24h, fetchJSON };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = Providers;
