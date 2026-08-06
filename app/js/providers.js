/* ============================================================
 * لایه داده: چند منبع داده با fallback خودکار
 * کندل‌ها (OHLC): بایننس ← بای‌بیت ← اوکی‌ایکس ← کراکن ← کریپتوکامپیر ← کوین‌گکو
 * فهرست بازار: کوین‌گکو (۱۰۰ ارز برتر) ← کوین‌کپ ← بایننس
 * جستجوی سکه: کوین‌گکو ← کوین‌کپ ← بایننس
 * ============================================================ */
'use strict';

const Providers = (function () {

  /* ---------- نمادهای ویژه صرافی‌ها (استثناها) ---------- */
  const SYM_OVERRIDES = {
    BTC:  { kraken: 'XBTUSD' },
    DOGE: { kraken: 'XDGUSD' },
    XBT:  { kraken: 'XBTUSD' },
    XDG:  { kraken: 'XDGUSD' }
  };

  /* ---------- فهرست پایه سکه‌ها (نماد، شناسه، نام فارسی) ----------
   * نمادهای صرافی به‌صورت خودکار ساخته می‌شوند (SYM+USDT و ...)
   * و استثناها در SYM_OVERRIDES اصلاح می‌شوند.
   * فهرست اصلی برنامه «۱۰۰ ارز برتر بازار» است که به‌صورت پویا
   * از کوین‌گکو گرفته می‌شود؛ این فهرست برای حالت آفلاین و
   * نمایش فارسی نام‌هاست. */
  const SYMBOLS = [
    // نماد، شناسه کوین‌گکو، نام فارسی
    ['BTC',  'bitcoin', 'بیت‌کوین'],
    ['ETH',  'ethereum', 'اتریوم'],
    ['BNB',  'binancecoin', 'بایننس کوین'],
    ['XRP',  'ripple', 'ریپل'],
    ['SOL',  'solana', 'سولانا'],
    ['ADA',  'cardano', 'کاردانو'],
    ['DOGE', 'dogecoin', 'دوج‌کوین'],
    ['AVAX', 'avalanche-2', 'آوالانچ'],
    ['DOT',  'polkadot', 'پولکادات'],
    ['LINK', 'chainlink', 'چین‌لینک'],
    ['TRX',  'tron', 'ترون'],
    ['MATIC','matic-network', 'پالیگان (MATIC)'],
    ['POL',  'polygon-ecosystem-token', 'پالیگان (POL)'],
    ['LTC',  'litecoin', 'لایت‌کوین'],
    ['SHIB', 'shiba-inu', 'شیبا'],
    ['UNI',  'uniswap', 'یونی‌سواپ'],
    ['NEAR', 'near', 'نیر'],
    ['APT',  'aptos', 'آپتوس'],
    ['ARB',  'arbitrum', 'آربیتروم'],
    ['OP',   'optimism', 'اپتیمیزم'],
    ['ALGO', 'algorand', 'الگورند'],
    ['XLM',  'stellar', 'استلار'],
    ['FIL',  'filecoin', 'فایل‌کوین'],
    ['ATOM', 'cosmos', 'کازموس'],
    ['XMR',  'monero', 'مونرو'],
    ['TON',  'the-open-network', 'تون‌کوین'],
    ['ICP',  'internet-computer', 'اینترنت کامپیوتر'],
    ['VET',  'vechain', 'وی‌چین'],
    ['GRT',  'the-graph', 'دگراف'],
    ['AAVE', 'aave', 'آوه'],
    ['SUI',  'sui', 'سویی'],
    ['PEPE', 'pepe', 'پپه'],
    ['RNDR', 'render-token', 'رندر'],  // نماد صرافی: RENDERUSDT
    ['BCH',  'bitcoin-cash', 'بیت‌کوین کش'],
    ['ETC',  'ethereum-classic', 'اتریوم کلاسیک'],
    ['HBAR', 'hedera-hashgraph', 'هدرا'],
    ['INJ',  'injective-protocol', 'این‌جکتیو'],
    ['IMX',  'immutable-x', 'ایمیوتبل'],
    ['STX',  'stacks', 'استکس'],
    ['LDO',  'lido-dao', 'لیدو'],
    ['FTM',  'fantom', 'فانتوم'],
    ['SAND', 'the-sandbox', 'سندباکس'],
    ['MANA', 'decentraland', 'دیسنترالند'],
    ['GALA', 'gala', 'گالا'],
    ['AXS',  'axie-infinity', 'اکسی'],
    ['APE',  'apecoin', 'ایپ‌کوین'],
    ['CRV',  'curve-dao-token', 'کرو'],
    ['MKR',  'maker', 'میکر'],
    ['AR',   'arweave', 'آرویو'],
    ['ENS',  'ethereum-name-service', 'ای‌ان‌اس'],
    ['RUNE', 'thorchain', 'تورچین'],
    ['KAVA', 'kava', 'کاوا'],
    ['ZEC',  'zcash', 'زد‌کش'],
    ['DASH', 'dash', 'دش'],
    ['EOS',  'eos', 'ای‌او‌اس'],
    ['NEO',  'neo', 'نئو'],
    ['XTZ',  'tezos', 'تزوس'],
    ['THETA','theta-token', 'تتا'],
    ['IOTA', 'iota', 'آیوتا'],
    ['CHZ',  'chiliz', 'چیلز'],
    ['ENJ',  'enjincoin', 'انجین'],
    ['1INCH','1inch', 'وان‌اینچ'],
    ['SNX',  'synthetix-network-token', 'سینتتیکس'],
    ['COMP', 'compound-governance-token', 'کامپاند'],
    ['YFI',  'yearn-finance', 'یرن'],
    ['BAT',  'basic-attention-token', 'بت'],
    ['ZIL',  'zilliqa', 'زیلیکا'],
    ['DYDX', 'dydx', 'دی‌وای‌دی‌ایکس'],
    ['BLUR', 'blur', 'بلور'],
    ['KAS',  'kaspa', 'کاسپا'],
    ['TIA',  'celestia', 'سلستیا'],
    ['SEI',  'sei-network', 'سِی'],
    ['WLD',  'worldcoin-wld', 'ورلدکوین'],
    ['ORDI', 'ordinals', 'اوردینالز'],
    ['WIF',  'dogwifcoin', 'دوج‌ویف'],
    ['BONK', 'bonk', 'بانک'],
    ['FLOKI','floki', 'فلوکی'],
    ['JUP',  'jupiter-exchange-solana', 'ژوپیتر'],
    ['PYTH', 'pyth-network', 'پایت'],
    ['FET',  'fetch-ai', 'فچ'],
    ['AGIX', 'singularitynet', 'ای‌جی‌ایکس'],
    ['OCEAN','ocean-protocol', 'اوشن'],
    ['ROSE', 'oasis-network', 'رز'],
    ['MINA', 'mina-protocol', 'مینا'],
    ['EGLD', 'multiversx-egld', 'ملتی‌ورس'],
    ['KSM',  'kusama', 'کوساما'],
    ['FLOW', 'flow', 'فلو'],
    ['CFX',  'conflux-token', 'کانفلاکس'],
    ['GMT',  'stepn', 'جی‌ام‌تی'],
    ['ID',   'space-id', 'آی‌دی'],
    ['NOT',  'notcoin', 'نات‌کوین'],
    ['PEOPLE','constitutiondao', 'پیپل'],
    ['MEME', 'memecoin', 'میم‌کوین'],
    ['ENA',  'ethena', 'اتنا'],
    ['ONDO', 'ondo-finance', 'اوندو'],
    ['PENDLE','pendle', 'پندل'],
    ['JTO',  'jito-governance-token', 'جیتو'],
    ['STRK', 'starknet', 'استارک‌نت'],
    ['ZRO',  'layerzero', 'لیرزرو'],
    ['LQTY', 'liquity', 'لیکوئیتی'],
    ['CELO', 'celo', 'سلو'],
    ['SKL',  'skale', 'اسکیل'],
    ['API3', 'api3', 'ای‌پی‌آی‌۳'],
    ['BAND', 'band-protocol', 'بند'],
    ['QNT',  'quant-network', 'کوانت'],
    ['NEXO', 'nexo', 'نکسو'],
    ['W',    'wormhole', 'وُرم‌هول'],
    ['BOME', 'book-of-meme', 'بوم'],
    ['ETHFI','ether-fi', 'اتر‌فای'],
    ['TURBO','turbo', 'توربو']
  ];

  function buildCoins() {
    return SYMBOLS.map(([sym, id, name]) => {
      const c = {
        id: id, name: name, sym: sym,
        binance: sym + 'USDT',
        bybit: sym + 'USDT',
        okx: sym + '-USDT',
        kraken: sym + 'USD',
        cc: sym
      };
      const o = SYM_OVERRIDES[sym];
      if (o) Object.assign(c, o);
      // تغییرنام‌های رسمی: RNDR → RENDER، MATIC → POL
      if (sym === 'RNDR') { c.binance = 'RENDERUSDT'; c.bybit = 'RENDERUSDT'; c.okx = 'RENDER-USDT'; }
      if (sym === 'MATIC') { c.binance = 'POLUSDT'; c.bybit = 'POLUSDT'; c.okx = 'POL-USDT'; }
      return c;
    });
  }

  const COINS = buildCoins();

  /* ---------- ثبت‌نام پویا: ارزهایی که از فهرست بازار می‌آیند ---------- */
  const dynamicCoins = {};   // شناسه → سکه
  const extraCoins = [];     // سکه‌های سفارشی کاربر
  const priceCache = {};     // آخرین قیمت شناخته‌شده (برای سکه‌های سفارشی)

  /* ساخت کامل سکه از روی داده ناقص (نماد کافی است) */
  function buildCoin(base) {
    if (!base || !base.id || !base.sym) return null;
    const sym = String(base.sym).toUpperCase();
    const coin = {
      id: base.id,
      name: base.name || base.sym,
      sym: sym,
      binance: sym + 'USDT',
      bybit: sym + 'USDT',
      okx: sym + '-USDT',
      kraken: sym + 'USD',
      cc: sym
    };
    const o = SYM_OVERRIDES[sym];
    if (o) Object.assign(coin, o);
    if (sym === 'RNDR') { coin.binance = 'RENDERUSDT'; coin.bybit = 'RENDERUSDT'; coin.okx = 'RENDER-USDT'; }
    if (sym === 'MATIC') { coin.binance = 'POLUSDT'; coin.bybit = 'POLUSDT'; coin.okx = 'POL-USDT'; }
    return coin;
  }

  function ensureCoin(base) {
    if (!base || !base.id || !base.sym) return null;
    const existing = coinById(base.id);
    if (existing) return existing;
    const coin = buildCoin(base);
    dynamicCoins[base.id] = coin;
    return coin;
  }

  /* ثبت همه ارزهای فهرست بازار در رجیستری (تا قابل تحلیل/اسکن شوند) */
  function registerMarket(list) {
    for (const m of list) {
      ensureCoin({ id: m.id, sym: m.sym, name: m.name });
    }
  }

  function coinById(id) {
    return COINS.find(c => c.id === id) || dynamicCoins[id] || extraCoins.find(c => c.id === id) || null;
  }
  function coinBySymbol(sym) {
    const s = String(sym || '').toUpperCase();
    if (!s) return null;
    let c = COINS.find(c => c.sym === s);
    if (c) return c;
    for (const k in dynamicCoins) { if (dynamicCoins[k].sym === s) return dynamicCoins[k]; }
    return extraCoins.find(c => c.sym === s) || null;
  }
  function allCoins() { return COINS.concat(Object.keys(dynamicCoins).map(k => dynamicCoins[k]), extraCoins); }

  /* ---------- سکه‌های سفارشی ---------- */
  function addCoin(coin) {
    if (!coin || !coin.id || !coin.sym) return false;
    if (coinById(coin.id)) return false;
    extraCoins.push(buildCoin(coin));
    return true;
  }

  function removeCoin(id) {
    const i = extraCoins.findIndex(c => c.id === id);
    if (i >= 0) { extraCoins.splice(i, 1); return true; }
    return false;
  }

  /* ---------- استیبل‌کوین‌ها (برای اسکنر بی‌فایده‌اند) ---------- */
  const STABLECOINS = new Set(['USDT','USDC','DAI','FDUSD','TUSD','BUSD','PYUSD','USDE','EURC','USDD','FRAX','LUSD','GHO','USDS','USTC']);
  function isStablecoin(sym) { return STABLECOINS.has(String(sym || '').toUpperCase()); }

  /* ---------- قیمت کش برای سکه‌های سفارشی ---------- */
  function setPrice(id, price) { if (id && isFinite(price)) priceCache[id] = { price: price, at: Date.now() }; }
  function getPrice(id) { return priceCache[id] ? priceCache[id].price : null; }

  /* ادغام سکه‌های سفارشی در فهرست بازار (اگر در ۱۰۰ ارز برتر نباشند) */
  function mergeCustom(list) {
    let wl = [];
    try {
      if (typeof Store !== 'undefined' && Store.get) wl = Store.get('watchlist') || [];
    } catch (e) { /* ignore */ }
    for (const c of wl) {
      if (!list.find(m => m.id === c.id)) {
        list.push({
          id: c.id, sym: c.sym, name: c.name,
          price: getPrice(c.id), chg24: null, mcap: null, image: null
        });
      }
    }
    return list;
  }

  /* ---------- fetch با Timeout + سیگنال لغو ---------- */
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
      if (signal && signal.aborted) throw e;
      if (String(e.message).indexOf('HTTP') === 0) throw e;
      await new Promise(r => setTimeout(r, 400));
      if (signal && signal.aborted) throw e;
      return await attempt();
    }
  }

  /* ============================================================
   * کندل‌ها — زنجیره fallback موازی
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
    const good = candles.filter(c => c.o > 0 && c.h >= c.l && c.c > 0);
    good.sort((a, b) => a.t - b.t);
    return good;
  }

  async function getKlines(coin, tfName, limit, signal) {
    limit = limit || 200;
    const tf = TF[tfName] || TF['1h'];
    const errs = [];

    const attempts = [
      { name: 'binance', run: async () => {
          const raw = await fetchJSON('https://api.binance.com/api/v3/klines?symbol=' + coin.binance + '&interval=' + tf.binance + '&limit=' + limit, 5000, signal);
          return normalize(tfName, 'binance', raw);
        } },
      { name: 'bybit', run: async () => {
          const raw = await fetchJSON('https://api.bybit.com/v5/market/kline?category=spot&symbol=' + coin.bybit + '&interval=' + tf.bybit + '&limit=' + limit, 6000, signal);
          return normalize(tfName, 'bybit', raw);
        } },
      { name: 'okx', run: async () => {
          const raw = await fetchJSON('https://www.okx.com/api/v5/market/candles?instId=' + coin.okx + '&bar=' + tf.okx + '&limit=' + limit, 6000, signal);
          return normalize(tfName, 'okx', raw);
        } },
      { name: 'kraken', run: async () => {
          const raw = await fetchJSON('https://api.kraken.com/0/public/OHLC?pair=' + coin.kraken + '&interval=' + tf.kraken, 6000, signal);
          return normalize(tfName, 'kraken', raw);
        } },
      { name: 'cryptocompare', run: async () => {
          const raw = await fetchJSON('https://min-api.cryptocompare.com/data/v2/' + tf.cc + '?fsym=' + coin.cc + '&tsym=USD&limit=' + limit, 6000, signal);
          return normalize(tfName, 'cryptocompare', raw);
        } }
    ];
    if (tf.cgDays) {
      attempts.push({ name: 'coingecko', run: async () => {
          const raw = await fetchJSON('https://api.coingecko.com/api/v3/coins/' + coin.id + '/ohlc?vs_currency=usd&days=' + tf.cgDays, 6000, signal);
          return normalize(tfName, 'coingecko', raw);
        } });
    }

    const result = await firstValid(attempts, 30, errs, signal);
    if (result) return result;
    throw new Error('همه منابع داده ناموفق بودند: ' + errs.join(' | '));
  }

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
    let winner = null;
    await Promise.all(pending.map(p => p.then(r => { if (r && !winner) winner = r; })));
    return winner;
  }

  /* نسخه سریع کندل برای اسکنر: فقط بایننس و بای‌بیت */
  async function getKlinesFast(coin, tfName, limit, signal) {
    limit = limit || 60;
    const tf = TF[tfName] || TF['1h'];
    const errs = [];
    try {
      const raw = await fetchJSON('https://api.binance.com/api/v3/klines?symbol=' + coin.binance + '&interval=' + tf.binance + '&limit=' + limit, 5000, signal);
      const c = normalize(tfName, 'binance', raw);
      if (c.length >= 30) return { candles: c, source: 'binance' };
      errs.push('binance: short');
    } catch (e) { errs.push('binance: ' + e.message); }
    try {
      const raw = await fetchJSON('https://api.bybit.com/v5/market/kline?category=spot&symbol=' + coin.bybit + '&interval=' + tf.bybit + '&limit=' + limit, 5000, signal);
      const c = normalize(tfName, 'bybit', raw);
      if (c.length >= 30) return { candles: c, source: 'bybit' };
      errs.push('bybit: short');
    } catch (e) { errs.push('bybit: ' + e.message); }
    throw new Error('fast sources failed: ' + errs.join(' | '));
  }

  /* ============================================================
   * فهرست بازار — ۱۰۰ ارز برتر با fallback
   * ============================================================ */
  async function getMarketList() {
    const errs = [];

    // 1) کوین‌گکو: ۱۰۰ ارز برتر (بدون فیلتر id — همه ارزها!)
    try {
      const raw = await fetchJSON('https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=100&page=1&sparkline=false&price_change_percentage=24h', 8000);
      if (Array.isArray(raw) && raw.length) {
        const list = raw.map(r => ({
          id: r.id, sym: (r.symbol || '').toUpperCase(), name: r.name,
          price: r.current_price, chg24: r.price_change_percentage_24h,
          mcap: r.market_cap, image: r.image
        }));
        registerMarket(list);
        return { source: 'coingecko', list: mergeCustom(list) };
      }
      errs.push('coingecko: empty');
    } catch (e) { errs.push('coingecko: ' + e.message); }

    // 2) کوین‌کپ: ۱۰۰ ارز برتر
    try {
      const raw = await fetchJSON('https://api.coincap.io/v2/assets?limit=100', 8000);
      if (raw && raw.data && raw.data.length) {
        const list = raw.data.map(r => ({
          id: r.id, sym: (r.symbol || '').toUpperCase(), name: r.name,
          price: parseFloat(r.priceUsd), chg24: parseFloat(r.changePercent24Hr),
          mcap: parseFloat(r.marketCapUsd), image: null
        }));
        registerMarket(list);
        return { source: 'coincap', list: mergeCustom(list) };
      }
      errs.push('coincap: empty');
    } catch (e) { errs.push('coincap: ' + e.message); }

    // 3) بایننس: فقط سکه‌های فهرست پایه
    try {
      const symbols = allCoins().filter(c => c.binance).map(c => '"' + c.binance + '"').join(',');
      const raw = await fetchJSON('https://api.binance.com/api/v3/ticker/24hr?symbols=[' + symbols + ']', 8000);
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
        return { source: 'binance', list: mergeCustom(list) };
      }
      errs.push('binance: empty');
    } catch (e) { errs.push('binance: ' + e.message); }

    throw new Error('همه منابع بازار ناموفق بودند: ' + errs.join(' | '));
  }

  /* ============================================================
   * جستجوی سکه — کوین‌گکو ← کوین‌کپ ← بایننس
   * ============================================================ */
  async function searchCoin(query) {
    const q = String(query || '').trim().toLowerCase();
    if (!q) return [];
    const errs = [];

    // 1) کوین‌گکو
    try {
      const raw = await fetchJSON('https://api.coingecko.com/api/v3/search?query=' + encodeURIComponent(q), 6000);
      const coins = (raw && raw.coins) || [];
      if (coins.length) {
        return coins.slice(0, 6).map(c => ({
          id: c.id, sym: (c.symbol || '').toUpperCase(), name: c.name || c.symbol
        }));
      }
      errs.push('coingecko: empty');
    } catch (e) { errs.push('coingecko: ' + e.message); }

    // 2) کوین‌کپ
    try {
      const raw = await fetchJSON('https://api.coincap.io/v2/assets?search=' + encodeURIComponent(q) + '&limit=6', 6000);
      if (raw && raw.data && raw.data.length) {
        return raw.data.map(a => ({
          id: a.id, sym: (a.symbol || '').toUpperCase(), name: a.name || a.symbol
        }));
      }
      errs.push('coincap: empty');
    } catch (e) { errs.push('coincap: ' + e.message); }

    // 3) بایننس (فهرست کامل صرافی)
    try {
      const raw = await fetchJSON('https://api.binance.com/api/v3/exchangeInfo', 8000);
      const symbols = (raw.symbols || []).filter(s =>
        s.quoteAsset === 'USDT' && s.status === 'TRADING' &&
        (s.baseAsset.toLowerCase().indexOf(q) >= 0 || s.symbol.toLowerCase().indexOf(q) >= 0)
      ).slice(0, 6);
      if (symbols.length) {
        return symbols.map(s => ({
          id: s.baseAsset.toLowerCase() + '-usdt',
          sym: s.baseAsset.toUpperCase(),
          name: s.baseAsset.toUpperCase()
        }));
      }
      errs.push('binance: nothing found');
    } catch (e) { errs.push('binance: ' + e.message); }

    throw new Error('جستجو در هیچ منبعی جواب نداد (' + errs.join(' | ') + ')');
  }

  /* ---------- جزئیات ۲۴ ساعته ---------- */
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

  return { COINS, coinById, coinBySymbol, allCoins, addCoin, removeCoin, buildCoin, ensureCoin, registerMarket,
           isStablecoin, setPrice, getPrice, mergeCustom, searchCoin,
           getKlines, getKlinesFast, getMarketList, getTicker24h, fetchJSON };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = Providers;
