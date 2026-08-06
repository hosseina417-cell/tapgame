/* ============================================================
 * کنترلر اصلی برنامه
 * ============================================================ */
'use strict';

const App = (function () {

  const state = {
    screen: 'market',        // market | detail | scanner | settings
    coin: null,              // سکه انتخابی در جزئیات
    tf: '1h',                // تایم‌فریم انتخابی
    market: null,            // داده فهرست بازار
    marketAt: 0,
    marketSource: null,
    marketStale: false,      // داده از کش آفلاین است؟
    detail: null,
    detailAbort: null,       // AbortController درخواست‌های جزئیات
    scanning: false,
    scanAbort: false,
    scanProgress: 0,
    scanResults: null,
    scanAt: 0,
    scanManual: false,       // اسکن دستی یا خودکار
    lastAutoScanAt: 0,       // زمان آخرین اسکن خودکار
    lastAutoCount: 0,        // تعداد هشدار اسکن خودکار اخیر
    lastScanByCoin: {},      // برای dedupe هشدارهای تکراری
    lastNotifyByCoin: {},    // برای محدود کردن اعلان‌ها
    listSignals: {},         // سیگنال‌های سبک فهرست (شناسه سکه → badge)
    listSignalsAt: {},       // زمان محاسبه هر سیگنال (برای TTL)
    chartSession: null,      // جلسه تعاملی نمودار
    notifAsked: false,       // مجوز اعلان یک بار در نشست خواسته شود
    marketFilter: 'all',     // فیلتر دسته‌بندی فهرست
    marketSearch: '',        // متن جستجوی فهرست
    loading: false
  };

  let timers = { market: null, detail: null, scan: null, clock: null };
  let clockTimer = null;
  let applyingHash = false;

  /* ---------- ناوبری (با مسیریابی hash برای دکمه بازگشت اندروید) ---------- */
  function setScreen(name) {
    if (name !== 'scanner' && state.scanning && state.scanManual) cancelScan();
    // هنگام خروج از صفحه جزئیات، جلسه نمودار (شنونده‌ها + overlay) آزاد شود
    if (name !== 'detail' && state.chartSession) {
      state.chartSession.destroy();
      state.chartSession = null;
    }
    state.screen = name;
    document.querySelectorAll('.nav-btn').forEach(b => b.classList.toggle('active', b.getAttribute('data-screen') === name));
    const container = document.getElementById('screen');
    if (name === 'market') UI.renderMarket(container, state);
    else if (name === 'scanner') UI.renderScanner(container, state);
    else if (name === 'settings') UI.renderSettings(container, state);
    window.scrollTo(0, 0);
  }

  function navigate(hash) {
    if (location.hash === hash) { applyHash(); return; }
    // pushState: رندر فوری بدون رویداد اضافه (دکمه بازگشت هنوز کار می‌کند)
    try { history.pushState(null, '', hash); } catch (e) { location.hash = hash; }
    applyHash();
  }

  function applyHash() {
    applyingHash = true;
    try {
      const h = location.hash || '#/market';
      const parts = h.replace(/^#\//, '').split('/');
      if (parts[0] === 'coin' && parts[1]) {
        const coin = Providers.coinById(parts[1]);
        if (coin) {
          state.coin = coin;
          state.tf = parts[2] || state.tf || '1h';
          state.detail = null;
          setScreen('detail');
          UI.renderDetail(document.getElementById('screen'), state);
          return;
        }
      }
      const names = ['market', 'scanner', 'settings'];
      const target = names.indexOf(parts[0]) >= 0 ? parts[0] : 'market';
      if (target !== 'detail') {
        state.coin = null;
        state.detail = null;
        setScreen(target);
      }
    } finally {
      applyingHash = false;
    }
  }

  function openCoin(id) {
    const coin = Providers.coinById(id);
    if (!coin) return;
    navigate('#/coin/' + coin.id);
  }

  function goMarket() {
    navigate('#/market');
  }

  function setTf(tf) {
    state.tf = tf;
    document.querySelectorAll('.tf-btn').forEach(b => b.classList.toggle('active', b.getAttribute('data-tf') === tf));
    loadDetail();
    // به‌روزرسانی hash بدون رندر مجدد
    const coin = state.coin;
    if (coin && !applyingHash) {
      const h = '#/coin/' + coin.id + '/' + tf;
      if (location.hash !== h) {
        try { history.replaceState(null, '', h); } catch (e) { /* ignore */ }
      }
    }
  }

  /* ---------- ساعت و وضعیت ---------- */
  function updateClock() {
    const el = document.getElementById('clock');
    if (el) {
      const now = new Date();
      el.textContent = now.toLocaleTimeString('fa-IR', { hour: '2-digit', minute: '2-digit' });
    }
  }

  function setNetStatus(mode, text) {
    const el = document.getElementById('netStatus');
    if (!el) return;
    if (mode === 'loading' && state.loading) { el.className = 'dot loading'; el.title = text || 'در حال دریافت...'; return; }
    el.className = 'dot ' + mode;
    el.title = text || '';
  }

  function setLoading(on) {
    state.loading = on;
    const el = document.getElementById('netStatus');
    if (el) {
      if (on) { el.className = 'dot loading'; el.title = 'در حال دریافت...'; }
      else if (state.marketStale) { el.className = 'dot offline'; el.title = 'آفلاین — داده کش‌شده'; }
      else { el.className = 'dot online'; el.title = 'متصل'; }
    }
  }

  /* ============================================================
   * اعلان سیستمی پامپ/دامپ (از طریق پل جاوا)
   * ============================================================ */
  function notifyPumpAlert(coin, p) {
    try {
      if (!window.AndroidBridge) return;
      const key = coin.id + '_' + p.type;
      const last = state.lastNotifyByCoin[key];
      if (last && (Date.now() - last) < 30 * 60 * 1000) return; // هر ۳۰ دقیقه یک بار
      state.lastNotifyByCoin[key] = Date.now();
      const title = (p.type === 'pump' ? '🚨 پامپ ' : p.type === 'dump' ? '📉 دامپ ' : '⚠ آماده‌باش ') + coin.sym;
      // درخواست مجوز فقط یک بار در هر نشست
      if (!state.notifAsked) {
        state.notifAsked = true;
        window.AndroidBridge.requestPermission();
      }
      window.AndroidBridge.notify(title, p.msg + ' — ' + coin.name);
    } catch (e) { /* اعلان اختیاری است */ }
  }

  /* ============================================================
   * به‌روزرسانی بازار (با fallback به کش آفلاین)
   * ============================================================ */
  async function refreshMarket(showSpinner) {
    setLoading(true);
    try {
      const res = await Providers.getMarketList();
      const wasStale = state.marketStale;
      state.market = { list: res.list };
      state.marketAt = Date.now();
      state.marketSource = res.source;
      state.marketStale = false;
      Store.cacheMarket(res.list, res.source);
      setNetStatus('online', 'متصل — منبع: ' + res.source);
      if (state.screen === 'market') {
        // به‌روزرسانی درجا (حفظ اسکرول و جستجو) — اگر ردیفی نبود رندر کامل
        const listEl = document.getElementById('coinList');
        if (listEl && listEl.querySelector('.coin-row')) {
          UI.updateMarketRows(document.getElementById('screen'), state);
          if (wasStale) UI.renderMarket(document.getElementById('screen'), state);
        } else {
          UI.renderMarket(document.getElementById('screen'), state);
        }
        // سیگنال‌ها را برای ارزهای تازه به‌روز کن (با کش ۵ دقیقه‌ای)
        loadListSignals(false);
      }
      setLoading(false);
    } catch (e) {
      // fallback به کش
      const cached = Store.getMarketCache();
      if (cached && cached.list && cached.list.length) {
        state.market = { list: cached.list };
        state.marketAt = cached.at;
        state.marketSource = cached.source;
        state.marketStale = true;
        setNetStatus('offline', 'آفلاین — نمایش داده کش‌شده از ' + U.fmtDateTime(cached.at));
        if (state.screen === 'market') UI.renderMarket(document.getElementById('screen'), state);
      } else {
        setNetStatus('offline', 'خطا در دریافت داده');
        const container = document.getElementById('screen');
        if (state.screen === 'market' && !state.market) {
          U.clear(container);
          container.appendChild(U.el('div', { class: 'empty' }, [
            U.el('div', { text: 'خطا در دریافت داده بازار' }),
            U.el('div', { class: 'err-msg', text: e.message }),
            U.el('button', { class: 'btn', text: 'تلاش دوباره', onclick: () => refreshMarket(true) })
          ]));
        }
      }
      setLoading(false);
    }
  }

  /* ============================================================
   * جزئیات سکه — با لغو درخواست قبلی + کش هوشمند تایم‌فریم‌ها
   * ============================================================ */
  const TF_TTL = { '15m': 60 * 1000, '1h': 5 * 60 * 1000, '4h': 15 * 60 * 1000, '1d': 30 * 60 * 1000 };

  async function loadDetail() {
    const coin = state.coin;
    if (!coin) return;

    // لغو درخواست قبلی (dedup واقعی)
    if (state.detailAbort) state.detailAbort.abort();
    const abort = new AbortController();
    state.detailAbort = abort;

    const panel = document.getElementById('signalPanel');
    const indPanel = document.getElementById('indPanel');
    const pumpPanel = document.getElementById('pumpPanel');
    const priceEl = document.getElementById('detailPrice');
    const chgEl = document.getElementById('detailChg');

    const tfs = ['15m', '1h', '4h', '1d'];
    const results = await Promise.all(tfs.map(async tf => {
      if (abort.signal.aborted) return { tf, a: null, source: null, error: 'aborted' };
      // کش با TTL: تایم‌فریم‌های کند ابتدا از کش می‌آیند
      const cache = Store.getKlinesCache(coin.id, tf);
      const fresh = cache && (Date.now() - cache.at) < TF_TTL[tf];
      if (fresh && cache.candles.length >= 30) {
        return { tf, a: TA.analyze(cache.candles), source: cache.source + ' (کش)', cached: true };
      }
      try {
        const { candles, source } = await Providers.getKlines(coin, tf, 200, abort.signal);
        if (abort.signal.aborted) return { tf, a: null, source: null, error: 'aborted' };
        Store.cacheKlines(coin.id, tf, candles, source);
        return { tf, a: TA.analyze(candles), source };
      } catch (e) {
        if (abort.signal.aborted) return { tf, a: null, source: null, error: 'aborted' };
        // fallback به کش قدیمی
        if (cache && cache.candles.length >= 30) {
          return { tf, a: TA.analyze(cache.candles), source: cache.source + ' (کش قدیمی)', cached: true };
        }
        return { tf, a: null, source: null, error: e.message };
      }
    }));
    if (abort.signal.aborted) return;

    const okParts = results.filter(r => r.a && r.a.count >= 30);
    const multi = okParts.length ? Strategy.evaluateMulti(okParts) : null;
    const cur = results.find(r => r.tf === state.tf) || okParts[okParts.length - 1] || null;

    // پامپ/دامپ روی ۱۵ دقیقه و ۱ ساعت
    const pump = [];
    for (const tf of ['15m', '1h']) {
      const r = results.find(x => x.tf === tf);
      if (r && r.a) {
        const p = PumpDetect.check(r.a.candles, Store.load(), tf);
        if (p) pump.push(p);
      }
    }

    state.detail = { multi, analyses: results, pump, source: cur ? cur.source : null };

    // قیمت و تغییر ۲۴ ساعته (از کش یا کندل‌ها)
    if (cur && cur.a && cur.a.last) {
      const last = cur.a.last.c;
      if (priceEl) priceEl.textContent = '$' + U.fmtPrice(last);
    }
    const a15 = results.find(r => r.tf === '15m');
    if (a15 && a15.a && chgEl) {
      const closes = a15.a.candles.map(c => c.c);
      const last = closes[closes.length - 1];
      const prev = closes.length > 96 ? closes[closes.length - 97] : closes[0];
      const chg = prev > 0 ? (last - prev) / prev * 100 : 0;
      chgEl.textContent = U.fmtPct(chg, true);
      chgEl.className = 'chg big ' + (chg >= 0 ? 'pos' : 'neg');
    }

    // نمودار تعاملی (پنجره هم‌تراز — سری کامل + تاخیر)
    if (cur && cur.a) {
      const canvas = document.getElementById('chart');
      if (canvas) {
        const chartOpts = {
          maxBars: 120,
          ema9: cur.a.ema9Series ? { values: cur.a.ema9Series, lag: 9 } : null,
          ema21: cur.a.ema21Series ? { values: cur.a.ema21Series, lag: 21 } : null
        };
        if (state.chartSession) {
          state.chartSession.setData(cur.a.candles, chartOpts);
        } else {
          state.chartSession = Chart.createSession(canvas, cur.a.candles, chartOpts);
        }
      }
    }

    // بکتست سبک روی تایم‌فریم فعلی — دقت تجربی واقعی
    let backtest = null;
    if (cur && cur.a) {
      backtest = Strategy.backtest(cur.a.candles);
    }

    if (panel) UI.renderSignalPanel(panel, multi, cur && cur.a ? Strategy.evaluate(cur.a) : null, backtest);
    if (indPanel) UI.renderIndicators(indPanel, cur ? cur.a : null);
    if (pumpPanel) UI.renderPumpPanel(pumpPanel, pump);

    // چیپ‌های اطلاعاتی
    const chips = document.getElementById('detailChips');
    if (chips) {
      U.clear(chips);
      const a15 = results.find(r => r.tf === '15m');
      if (a15 && a15.a) {
        const closes = a15.a.candles.map(c => c.c);
        const last = closes[closes.length - 1];
        const chg1h = closes.length > 5 ? (last - closes[closes.length - 5]) / closes[closes.length - 5] * 100 : null;
        const chg24 = closes.length > 96 ? (last - closes[closes.length - 97]) / closes[closes.length - 97] * 100 : null;
        if (chg1h !== null) chips.appendChild(U.el('span', { class: 'chip-info ' + (chg1h >= 0 ? 'pos' : 'neg'), text: '۱ ساعت: ' + U.fmtPct(chg1h, true) }));
        if (chg24 !== null) chips.appendChild(U.el('span', { class: 'chip-info ' + (chg24 >= 0 ? 'pos' : 'neg'), text: '۲۴ ساعت: ' + U.fmtPct(chg24, true) }));
      }
      if (state.market && state.market.list) {
        const row = state.market.list.find(m => m.id === coin.id);
        if (row && row.mcap) {
          chips.appendChild(U.el('span', { class: 'chip-info', text: 'ارزش بازار: $' + U.fmtCompact(row.mcap) }));
          const rank = state.market.list.filter(m => m.mcap && m.mcap > row.mcap).length + 1;
          chips.appendChild(U.el('span', { class: 'chip-info', text: 'رتبه: ' + rank }));
        }
      }
    }

    // آمار ۲۴ ساعته
    const stats = document.getElementById('statsGrid');
    if (stats) {
      const cells = stats.querySelectorAll('.stat-value');
      if (cells.length >= 4) {
        try {
          const t = await Providers.getTicker24h(coin);
          if (t) {
            cells[0].textContent = '$' + U.fmtPrice(t.high);
            cells[1].textContent = '$' + U.fmtPrice(t.low);
            cells[2].textContent = U.fmtCompact(t.quoteVol) + ' $';
          }
        } catch (e) { /* آمار اختیاری است */ }
        cells[3].textContent = (cur && cur.source) ? cur.source : '—';
      }
    }
  }

  /* ============================================================
   * سیگنال سبک برای سکه‌های نشان‌شده در فهرست (C19)
   * ============================================================ */
  async function loadListSignals(force) {
    if (state.screen !== 'market') return;
    if (!state.market || !state.market.list) return;
    // سیگنال برای همه ارزهای فهرست (به‌جز استیبل‌کوین‌ها) با کش ۵ دقیقه‌ای
    const TTL = 5 * 60 * 1000;
    const q = U.makeQueue(4);
    let done = 0, total = 0;
    const countEl = document.querySelector('.market-count');
    for (const m of state.market.list) {
      if (Providers.isStablecoin(m.sym)) continue;
      const coin = Providers.coinById(m.id);
      if (!coin) continue;
      total++;
      // اگر سیگنال تازه داریم، فقط نشان را مطمئن کن
      if (!force && state.listSignals[m.id] && state.listSignalsAt[m.id] && (Date.now() - state.listSignalsAt[m.id]) < TTL) {
        const row = document.querySelector('.coin-row[data-coin="' + m.id + '"] .sig-cell');
        if (row && !row.children.length) UI.setRowSignal(row, state.listSignals[m.id]);
        continue;
      }
      q.add(async () => {
        try {
          // نسخه سریع: بایننس ← بای‌بیت (۸۰ کندل برای اندیکاتورها کافی است)
          const { candles } = await Providers.getKlinesFast(coin, '1h', 80);
          const a = TA.analyze(candles);
          if (!a || a.count < 40) return;
          const ev = Strategy.evaluate(a);
          ev.category = Strategy.categoryFromAnalysis(a);
          state.listSignals[m.id] = ev;
          state.listSignalsAt[m.id] = Date.now();
          const row = document.querySelector('.coin-row[data-coin="' + m.id + '"] .sig-cell');
          if (row) UI.setRowSignal(row, ev);
        } catch (e) { /* بدون سیگنال */ }
        done++;
        if (done % 10 === 0) UI.refreshFilterCounts(state);
      });
    }
    if (total) UI.refreshFilterCounts(state);
  }

  /* ============================================================
   * اسکنر پامپ/دامپ — با قابلیت لغو
   * ============================================================ */
  async function runScan(manual) {
    if (state.scanning) return;
    state.scanning = true;
    state.scanManual = !!manual;
    state.scanAbort = false;
    state.scanResults = null;
    const list = document.getElementById('scanList');
    const status = document.getElementById('scanStatus');
    const btn = document.getElementById('rescanBtn');
    if (btn) btn.textContent = 'در حال اسکن...';
    if (status) status.textContent = 'در حال دریافت فهرست بازار...';

    const abort = new AbortController();
    try {
      let market = state.market;
      if (!market) {
        try {
          const res = await Providers.getMarketList();
          market = { list: res.list };
        } catch (e) { /* از کش */ }
      }
      if (!market || !market.list) throw new Error('داده بازار در دسترس نیست');
      // همه ارزهای فهرست (به‌جز استیبل‌کوین‌ها) — تا سقف تنظیم‌شده
      const scanMax = Math.max(10, Math.min(100, Store.get('scanMax') || 60));
      const coins = market.list.map(m => Providers.coinBySymbol(m.sym))
        .filter(Boolean)
        .filter(c => !Providers.isStablecoin(c.sym))
        .slice(0, scanMax);
      const results = [];
      const q = U.makeQueue(4);
      const opts = Store.load();
      const cooldownMs = 10 * 60 * 1000; // هشدار تکراری برای ۱۰ دقیقه ثبت نمی‌شود
      let done = 0;

      for (const coin of coins) {
        q.add(async () => {
          if (state.scanAbort) { done++; return; }
          try {
            const { candles, source } = await Providers.getKlinesFast(coin, '15m', 60, abort.signal);
            if (candles.length) Providers.setPrice(coin.id, candles[candles.length - 1].c);
            const p = PumpDetect.check(candles, opts, '15m');
            if (p) {
              // dedupe: هشدار هم‌نوع تکراری اخیر را نادیده بگیر
              const prev = state.lastScanByCoin[coin.id];
              if (!prev || prev.type !== p.type || (Date.now() - prev.at) > cooldownMs) {
                const marketRow = market.list.find(m => m.sym === coin.sym);
                results.push(Object.assign({}, p, {
                  coin: coin,
                  price: marketRow ? marketRow.price : null,
                  chg24: marketRow ? marketRow.chg24 : null,
                  at: Date.now(),
                  source: source
                }));
                state.lastScanByCoin[coin.id] = { type: p.type, at: Date.now() };
                // اعلان سیستمی فقط برای هشدارهای شدید
                if ((p.severity === 'high' || p.severity === 'extreme') && Store.get('notifyEnabled')) {
                  notifyPumpAlert(coin, p);
                }
              }
            }
          } catch (e) { /* سکه‌ای که داده ندارد رد می‌شود */ }
          done++;
          state.scanProgress = done;
          if (status && !state.scanAbort) status.textContent = 'در حال اسکن ' + done + ' از ' + coins.length + ' سکه...';
        });
      }
      await q.drain();

      if (state.scanAbort) {
        if (status) status.textContent = 'اسکن لغو شد.';
      } else {
        results.sort((a, b) => {
          const sev = { extreme: 3, high: 2, mild: 1 };
          return (sev[b.severity] || 0) - (sev[a.severity] || 0);
        });
        state.scanResults = results;
        state.scanAt = Date.now();
        Store.cacheScan(results);
        if (status) status.textContent = 'اسکن کامل شد — ' + coins.length + ' سکه بررسی شد، ' + results.length + ' هشدار فعال.';
        // اسکن خودکار: ثبت زمان + اطلاع‌رسانی هشدارهای جدید
        if (!state.scanManual) {
          state.lastAutoScanAt = Date.now();
          const severe = results.filter(r => r.severity === 'high' || r.severity === 'extreme').length;
          state.lastAutoCount = severe;
          if (state.screen !== 'scanner' && severe > 0 && Store.get('notifyEnabled')) {
            UI.toast('🚨 اسکن خودکار: ' + severe + ' هشدار شدید پامپ/دامپ', 3500);
          }
        }
        // به‌روزرسانی قیمت سکه‌های سفارشی در فهرست بازار
        if (state.screen === 'market') {
          const listEl = document.getElementById('coinList');
          if (listEl && state.market) UI.updateMarketRows(document.getElementById('screen'), state);
        }
        const listEl = document.getElementById('scanList');
        if (listEl) UI.renderScanResults(listEl, results, state.scanAt);
      }
    } catch (e) {
      // fallback به کش اسکن قبلی
      const cached = Store.getScanCache();
      if (cached && cached.results) {
        state.scanResults = cached.results;
        state.scanAt = cached.at;
        const listEl = document.getElementById('scanList');
        if (listEl) UI.renderScanResults(listEl, cached.results, cached.at);
        if (status) status.textContent = 'آفلاین — نمایش اسکن قبلی (' + U.fmtDateTime(cached.at) + ')';
      } else if (status) {
        status.textContent = 'خطا در اسکن: ' + e.message;
      }
    } finally {
      state.scanning = false;
      if (btn) btn.textContent = 'اسکن دوباره';
    }
  }

  function cancelScan() {
    state.scanAbort = true;
  }

  /* ---------- تایمرها ---------- */
  function applySettings() {
    const s = Store.load();
    clearInterval(timers.market);
    clearInterval(timers.detail);
    clearInterval(timers.scan);
    timers.market = setInterval(() => refreshMarket(false), Math.max(10, s.refreshSec) * 1000);
    timers.detail = setInterval(() => { if (state.screen === 'detail') loadDetail(); }, Math.max(15, s.detailRefreshSec) * 1000);
    if (s.autoScan) {
      // اسکن خودکار: در هر صفحه‌ای اجرا می‌شود (در پس‌زمینه برنامه متوقف است)
      timers.scan = setInterval(() => runScan(false), Math.max(1, s.scanIntervalMin) * 60 * 1000);
    }
  }

  /* ---------- راه‌اندازی ---------- */
  function boot() {
    document.querySelectorAll('.nav-btn').forEach(b => {
      b.addEventListener('click', () => setScreen(b.getAttribute('data-screen')));
    });

    window.addEventListener('online', () => { setNetStatus('online', 'متصل'); refreshMarket(false); });
    window.addEventListener('offline', () => setNetStatus('offline', 'آفلاین'));

    // ساعت
    updateClock();
    clockTimer = setInterval(updateClock, 30 * 1000);

    // توقف به‌روزرسانی وقتی برنامه در پس‌زمینه است
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        clearInterval(timers.market); clearInterval(timers.detail); clearInterval(timers.scan);
      } else {
        applySettings();
        refreshMarket(false);
        if (state.screen === 'detail') loadDetail();
        else if (state.screen === 'market') loadListSignals();
      }
    });

    // مسیریابی hash (دکمه بازگشت اندروید + اشتراک‌گذاری)
    window.addEventListener('hashchange', () => { if (!applyingHash) applyHash(); });

    // بازیابی سکه‌های سفارشی
    const watchlist = Store.get('watchlist');
    for (const c of watchlist) {
      if (c && c.id && c.sym) Providers.addCoin(c);
    }

    applySettings();
    refreshMarket(true);
    applyHash();
    // سیگنال برای همه ارزهای فهرست
    setTimeout(() => loadListSignals(false), 1200);
    // اولین اسکن خودکار ~۳۰ ثانیه بعد از اجرا (اگر فعال باشد)
    if (Store.get('autoScan')) setTimeout(() => runScan(false), 30000);
  }

  document.addEventListener('DOMContentLoaded', boot);

  return { boot, setScreen, openCoin, goMarket, setTf, refreshMarket, loadDetail, runScan, cancelScan, applySettings, loadListSignals, state };
})();
