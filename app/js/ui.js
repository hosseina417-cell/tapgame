/* ============================================================
 * لایه رابط کاربری — رندر صفحات
 * ============================================================ */
'use strict';

const UI = (function () {

  /* ---------- نشان سیگنال ---------- */
  function signalBadge(signal, probability) {
    if (signal === 'BUY') {
      return U.el('span', { class: 'badge badge-buy', text: 'خرید' + (probability ? '  ' + Math.round(probability) + '٪' : '') });
    }
    if (signal === 'SELL') {
      return U.el('span', { class: 'badge badge-sell', text: 'فروش' + (probability ? '  ' + Math.round(probability) + '٪' : '') });
    }
    return U.el('span', { class: 'badge badge-neutral', text: 'خنثی' });
  }

  function pumpBadge(p) {
    if (!p) return null;
    if (p.type === 'pump') {
      return U.el('span', { class: 'badge badge-pump', text: 'پامپ ' + severityLabel(p.severity) });
    }
    if (p.type === 'dump') {
      return U.el('span', { class: 'badge badge-dump', text: 'دامپ ' + severityLabel(p.severity) });
    }
    return U.el('span', { class: 'badge badge-watch', text: 'آماده‌باش' });
  }

  function severityLabel(s) {
    return { mild: 'ملایم', high: 'شدید', extreme: 'بسیار شدید' }[s] || s;
  }

  /* قرار دادن نشان سیگنال در ردیف فهرست */
  function setRowSignal(cell, ev) {
    if (!cell) return;
    U.clear(cell);
    cell.appendChild(signalBadge(ev.signal, ev.probability));
  }

  /* توست ساده */
  function toast(msg, ms) {
    let t = document.getElementById('toast');
    if (!t) {
      t = U.el('div', { id: 'toast', class: 'toast' });
      document.body.appendChild(t);
    }
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.remove('show'), ms || 1800);
  }

  /* ============================================================
   * صفحه ۱: بازار
   * ============================================================ */
  function renderMarket(container, state) {
    U.clear(container);
    const market = state.market;

    container.appendChild(U.el('div', { class: 'search-wrap' }, [
      U.el('input', { class: 'search', type: 'search', placeholder: 'جستجوی سکه (مثلاً BTC)...', id: 'marketSearch' })
    ]));

    if (state.marketStale) {
      container.appendChild(U.el('div', { class: 'stale-banner', text: '⚠ آفلاین — داده کش‌شده از ' + U.fmtDateTime(state.marketAt) + ' نمایش داده می‌شود' }));
    }

    if (market && market.list) {
      const wlCount = Store.get('watchlist').length;
      container.appendChild(U.el('div', { class: 'market-count', text: market.list.length + ' سکه — ' + (wlCount ? wlCount + ' سکه سفارشی' : 'برای افزودن سکه به تنظیمات بروید') }));
    }

    const listWrap = U.el('div', { class: 'coin-list', id: 'coinList' });
    container.appendChild(listWrap);

    if (!market) {
      listWrap.appendChild(U.el('div', { class: 'empty', text: 'در حال دریافت داده‌های بازار...' }));
      return;
    }

    const rows = [];
    for (const coin of market.list) {
      const sig = state.listSignals[coin.id];
      const row = U.el('div', { class: 'coin-row' }, [
        U.el('span', { class: 'star' + (Store.isFavorite(coin.id) ? ' on' : ''), 'data-fav': coin.id, text: Store.isFavorite(coin.id) ? '★' : '☆' }),
        coin.image ? U.el('img', { class: 'coin-img', src: coin.image, alt: '' }) : U.el('span', { class: 'coin-img coin-img-ph', text: (coin.sym || '?').slice(0, 1) }),
        U.el('div', { class: 'coin-main' }, [
          U.el('div', { class: 'coin-name', text: coin.name }),
          U.el('div', { class: 'coin-sym', text: coin.sym })
        ]),
        U.el('div', { class: 'coin-price' }, [
          U.el('div', { class: 'coin-price-val', text: '$' + U.fmtPrice(coin.price) }),
          U.el('span', { class: 'chg ' + (coin.chg24 >= 0 ? 'pos' : 'neg'), text: U.fmtPct(coin.chg24, true) })
        ]),
        U.el('div', { class: 'sig-cell' }, sig ? signalBadge(sig.signal, sig.probability) : null)
      ]);
      row.setAttribute('data-coin', coin.id);
      rows.push(row);
    }
    rows.sort((a, b) => {
      const fa = a.querySelector('.star').classList.contains('on') ? 1 : 0;
      const fb = b.querySelector('.star').classList.contains('on') ? 1 : 0;
      return fb - fa;
    });
    for (const r of rows) listWrap.appendChild(r);

    // اگر سکه‌ای سیگنال آماده دارد، نشانش را بگذار
    for (const coin of market.list) {
      const sig = state.listSignals[coin.id];
      if (sig) {
        const cell = listWrap.querySelector('.coin-row[data-coin="' + coin.id + '"] .sig-cell');
        if (cell) setRowSignal(cell, sig);
      }
    }

    // رویدادها
    const search = container.querySelector('#marketSearch');
    if (search) {
      search.addEventListener('input', U.debounce(function () {
        const q = this.value.trim().toLowerCase();
        for (const r of rows) {
          r.style.display = (r.textContent.toLowerCase().indexOf(q) >= 0) ? '' : 'none';
        }
      }, 150));
    }
    listWrap.addEventListener('click', function (e) {
      const fav = e.target.closest('[data-fav]');
      if (fav) {
        e.stopPropagation();
        Store.toggleFavorite(fav.getAttribute('data-fav'));
        fav.classList.toggle('on');
        fav.textContent = fav.classList.contains('on') ? '★' : '☆';
        return;
      }
      const row = e.target.closest('[data-coin]');
      if (row) App.openCoin(row.getAttribute('data-coin'));
    });
  }

  /* به‌روزرسانی درجای ردیف‌های بازار (حفظ اسکرول و جستجو) */
  function updateMarketRows(container, state) {
    const market = state.market;
    if (!market) return;
    const listEl = document.getElementById('coinList');
    if (!listEl) return;
    for (const coin of market.list) {
      const row = listEl.querySelector('.coin-row[data-coin="' + coin.id + '"]');
      if (!row) continue;
      const priceEl = row.querySelector('.coin-price-val');
      if (priceEl) priceEl.textContent = '$' + U.fmtPrice(coin.price);
      const chgEl = row.querySelector('.chg');
      if (chgEl) {
        chgEl.textContent = U.fmtPct(coin.chg24, true);
        chgEl.className = 'chg ' + (coin.chg24 >= 0 ? 'pos' : 'neg');
      }
    }
  }

  /* ============================================================
   * صفحه ۲: جزئیات سکه
   * ============================================================ */
  function renderDetail(container, state) {
    U.clear(container);
    const coin = state.coin;
    if (!coin) { container.appendChild(U.el('div', { class: 'empty', text: 'سکه‌ای انتخاب نشده' })); return; }

    container.appendChild(U.el('div', { class: 'detail-head' }, [
      U.el('button', { class: 'back', id: 'backBtn', text: '→ بازگشت' }),
      U.el('div', { class: 'detail-title' }, [
        coin.image ? U.el('img', { class: 'coin-img', src: coin.image, alt: '' }) : null,
        U.el('div', {}, [
          U.el('div', { class: 'coin-name', text: coin.name }),
          U.el('div', { class: 'coin-sym', text: coin.sym + ' / USD' })
        ])
      ]),
      U.el('span', { id: 'detailFav', class: 'star large' + (Store.isFavorite(coin.id) ? ' on' : ''), text: Store.isFavorite(coin.id) ? '★' : '☆' })
    ]));

    // قیمت و تغییرات
    const priceBox = U.el('div', { class: 'price-box' }, [
      U.el('div', { class: 'price-big', id: 'detailPrice', text: '—' }),
      U.el('span', { class: 'chg big', id: 'detailChg', text: '—' })
    ]);
    container.appendChild(priceBox);

    // چیپ‌های اطلاعاتی (۱ ساعت، ۲۴ ساعت، ارزش بازار، رتبه)
    container.appendChild(U.el('div', { class: 'chips-row', id: 'detailChips' }));

    // تب‌های تایم‌فریم
    const tfBar = U.el('div', { class: 'tf-bar' }, ['15m', '1h', '4h', '1d'].map(tf =>
      U.el('button', { class: 'tf-btn' + (state.tf === tf ? ' active' : ''), 'data-tf': tf, text: tf })
    ));
    container.appendChild(tfBar);

    // نمودار
    const chartBox = U.el('div', { class: 'chart-box' }, [
      U.el('canvas', { id: 'chart', class: 'chart' })
    ]);
    container.appendChild(chartBox);

    // پنل سیگنال
    container.appendChild(U.el('div', { class: 'panel', id: 'signalPanel' }, [
      U.el('div', { class: 'panel-title', text: 'سیگنال و احتمال' }),
      U.el('div', { class: 'sig-loading', text: 'در حال محاسبه...' })
    ]));

    // کارت‌های اندیکاتور
    container.appendChild(U.el('div', { class: 'panel', id: 'indPanel' }, [
      U.el('div', { class: 'panel-title', text: 'اندیکاتورها' }),
      U.el('div', { class: 'ind-loading', text: 'در حال محاسبه...' })
    ]));

    // پامپ/دامپ
    container.appendChild(U.el('div', { class: 'panel', id: 'pumpPanel' }, [
      U.el('div', { class: 'panel-title', text: 'تشخیص پامپ / دامپ' }),
      U.el('div', { class: 'pump-loading', text: 'در حال بررسی...' })
    ]));

    // آمار ۲۴ ساعته
    container.appendChild(U.el('div', { class: 'panel' }, [
      U.el('div', { class: 'panel-title', text: 'آمار ۲۴ ساعت' }),
      U.el('div', { class: 'stats-grid', id: 'statsGrid' }, [
        statCell('بیشینه', '—'), statCell('کمینه', '—'), statCell('حجم', '—'), statCell('منبع داده', '—')
      ])
    ]));

    container.appendChild(U.el('div', { class: 'disclaimer', text: '⚠ تحلیل خودکار آزمایشی است و توصیه مالی نیست.' }));

    // رویدادها
    container.querySelector('#backBtn').addEventListener('click', () => App.goMarket());
    container.querySelector('#detailFav').addEventListener('click', function () {
      Store.toggleFavorite(coin.id);
      this.classList.toggle('on');
      this.textContent = this.classList.contains('on') ? '★' : '☆';
    });
    tfBar.addEventListener('click', e => {
      const btn = e.target.closest('[data-tf]');
      if (btn) App.setTf(btn.getAttribute('data-tf'));
    });

    App.loadDetail();
  }

  function statCell(label, value) {
    return U.el('div', { class: 'stat' }, [
      U.el('div', { class: 'stat-label', text: label }),
      U.el('div', { class: 'stat-value', id: label, text: value })
    ]);
  }

  /* پنل سیگنال با احتمال + دقت تجربی (بکتست) */
  function renderSignalPanel(panel, multi, selectedEv, backtest) {
    U.clear(panel);
    if (!multi) {
      panel.appendChild(U.el('div', { class: 'empty', text: 'داده کافی برای تحلیل نیست' }));
      return;
    }
    const top = multi.parts && multi.parts.length ? multi.parts[multi.parts.length - 1].ev : null;
    const ev = selectedEv || top;

    const gauge = U.el('div', { class: 'gauge ' + multi.signal.toLowerCase() }, [
      U.el('div', { class: 'gauge-label', text: multi.signal === 'BUY' ? 'خرید' : multi.signal === 'SELL' ? 'فروش' : 'خنثی' }),
      U.el('div', { class: 'gauge-prob', text: Math.round(multi.probability) + '٪' }),
      U.el('div', { class: 'gauge-sub', text: 'شاخص احتمال (برآورد آزمایشی — نه تضمین)' }),
      U.el('div', { class: 'gauge-bar' }, [U.el('div', { class: 'gauge-fill', style: 'width:' + Math.max(2, Math.abs(multi.score)) + '%;background:' + (multi.score >= 0 ? 'var(--green)' : 'var(--red)') })])
    ]);
    panel.appendChild(gauge);

    // دقت تجربی حاصل از بکتست روی داده‌های همین سکه
    if (backtest && (backtest.buyRate !== null || backtest.sellRate !== null)) {
      const bt = U.el('div', { class: 'backtest-box' }, [
        U.el('div', { class: 'backtest-title', text: 'دقت تجربی سیگنال‌ها (بازده ۴ کندل بعد — داده همین سکه)' })
      ]);
      if (backtest.buyRate !== null) {
        bt.appendChild(U.el('div', { class: 'backtest-row' }, [
          U.el('span', { class: 'badge badge-buy', text: 'خرید' }),
          U.el('span', { text: 'موفق در ' + backtest.buyRate.toFixed(0) + '٪ موارد (از ' + backtest.buy + ' سیگنال)' })
        ]));
      }
      if (backtest.sellRate !== null) {
        bt.appendChild(U.el('div', { class: 'backtest-row' }, [
          U.el('span', { class: 'badge badge-sell', text: 'فروش' }),
          U.el('span', { text: 'موفق در ' + backtest.sellRate.toFixed(0) + '٪ موارد (از ' + backtest.sell + ' سیگنال)' })
        ]));
      }
      if (!backtest.buy && !backtest.sell) {
        bt.appendChild(U.el('div', { class: 'backtest-row', text: 'سیگنالی در داده تاریخی پیدا نشد' }));
      }
      panel.appendChild(bt);
    }

    panel.appendChild(U.el('div', { class: 'sig-score', text: 'امتیاز: ' + (multi.score >= 0 ? '+' : '') + multi.score.toFixed(1) + ' (از 100- تا 100+)' }));

    // عوامل موثر
    if (ev) {
      const factorRows = U.el('div', { class: 'factor-list' });
      const names = { trend: 'روند', macd: 'مکدی', rsi: 'آر‌اس‌آی', stoch: 'استوکاستیک', bollinger: 'بولینگر', volume: 'حجم' };
      for (const k in ev.factors) {
        const f = ev.factors[k];
        const row = U.el('div', { class: 'factor-row' }, [
          U.el('span', { class: 'factor-name', text: names[k] || k }),
          U.el('div', { class: 'factor-bar' }, [
            U.el('div', { class: 'factor-fill ' + (f.score >= 0 ? 'pos' : 'neg'), style: 'width:' + Math.abs(f.score) * 100 + '%' })
          ]),
          U.el('span', { class: 'factor-val', text: (f.score >= 0 ? '+' : '') + f.score.toFixed(2) })
        ]);
        factorRows.appendChild(row);
      }
      panel.appendChild(factorRows);
      panel.appendChild(U.el('div', { class: 'factor-notes' }, ev.factors.rsi.notes.concat(
        ev.factors.macd.notes).slice(0, 4).map(n => U.el('div', { class: 'note', text: '• ' + n }))));
    }
  }

  /* کارت‌های اندیکاتور */
  function renderIndicators(panel, a) {
    U.clear(panel);
    if (!a) { panel.appendChild(U.el('div', { class: 'empty', text: 'داده کافی نیست' })); return; }
    const cards = [
      { label: 'RSI (14)', value: a.rsi !== null ? a.rsi.toFixed(1) : '—', tone: a.rsi !== null && a.rsi > 70 ? 'neg' : a.rsi !== null && a.rsi < 30 ? 'pos' : '' },
      { label: 'MACD', value: a.macd ? a.macd.macd.toFixed(4) : '—', tone: a.macd && a.macd.hist >= 0 ? 'pos' : 'neg' },
      { label: 'هیستوگرام', value: a.macd ? a.macd.hist.toFixed(4) : '—', tone: a.macd && a.macd.hist >= 0 ? 'pos' : 'neg' },
      { label: 'استوکاستیک', value: a.stoch ? a.stoch.k.toFixed(0) + '/' + a.stoch.d.toFixed(0) : '—', tone: a.stoch && a.stoch.k > 80 ? 'neg' : a.stoch && a.stoch.k < 20 ? 'pos' : '' },
      { label: 'ATR', value: a.atr !== null ? '$' + U.fmtPrice(a.atr, 4) : '—' },
      { label: 'حجم (نسبت)', value: a.vol ? a.vol.ratio.toFixed(1) + 'x' : '—', tone: a.vol && a.vol.ratio > 1.5 ? 'pos' : '' },
      { label: 'EMA9', value: a.ema9 !== null ? '$' + U.fmtPrice(a.ema9) : '—' },
      { label: 'EMA21', value: a.ema21 !== null ? '$' + U.fmtPrice(a.ema21) : '—' },
      { label: 'EMA50', value: a.ema50 !== null ? '$' + U.fmtPrice(a.ema50) : '—' },
      { label: 'باند بالا', value: a.bb ? '$' + U.fmtPrice(a.bb.upper) : '—' },
      { label: 'باند پایین', value: a.bb ? '$' + U.fmtPrice(a.bb.lower) : '—' },
      { label: 'موقعیت در باند', value: a.bbPct !== null && a.bbPct !== undefined ? (a.bbPct * 100).toFixed(0) + '٪' : '—' }
    ];
    const grid = U.el('div', { class: 'ind-grid' });
    for (const c of cards) {
      grid.appendChild(U.el('div', { class: 'ind-card ' + c.tone }, [
        U.el('div', { class: 'ind-label', text: c.label }),
        U.el('div', { class: 'ind-value', text: c.value })
      ]));
    }
    panel.appendChild(grid);
  }

  /* پنل پامپ/دامپ */
  function renderPumpPanel(panel, pumpResults) {
    U.clear(panel);
    if (!pumpResults || !pumpResults.length) {
      panel.appendChild(U.el('div', { class: 'empty', text: 'هشدار پامپ/دامپ فعالی وجود ندارد' }));
      return;
    }
    for (const p of pumpResults) {
      const tone = p.type === 'pump' ? 'pos' : p.type === 'dump' ? 'neg' : '';
      panel.appendChild(U.el('div', { class: 'pump-row ' + tone }, [
        U.el('span', { class: 'badge ' + (p.type === 'pump' ? 'badge-pump' : p.type === 'dump' ? 'badge-dump' : 'badge-watch'), text: p.type === 'pump' ? 'پامپ' : p.type === 'dump' ? 'دامپ' : 'آماده‌باش' }),
        U.el('span', { class: 'pump-msg', text: p.msg }),
        U.el('span', { class: 'pump-tf', text: p.tf })
      ]));
    }
  }

  /* ============================================================
   * صفحه ۳: اسکنر پامپ/دامپ
   * ============================================================ */
  function renderScanner(container, state) {
    U.clear(container);
    container.appendChild(U.el('div', { class: 'scan-head' }, [
      U.el('div', { class: 'panel-title', text: 'اسکنر پامپ و دامپ' }),
      U.el('div', { class: 'scan-btns' }, [
        state.scanning ? U.el('button', { class: 'btn btn-danger', id: 'cancelScanBtn', text: 'لغو اسکن' }) : null,
        U.el('button', { class: 'btn', id: 'rescanBtn', text: state.scanning ? 'در حال اسکن...' : 'اسکن دوباره' })
      ])
    ]));

    const status = U.el('div', { class: 'scan-status', id: 'scanStatus' });
    container.appendChild(status);

    const list = U.el('div', { class: 'scan-list', id: 'scanList' });
    container.appendChild(list);

    if (state.scanning) {
      status.textContent = 'در حال اسکن ' + (state.scanProgress || 0) + ' سکه...';
    } else if (!state.scanResults) {
      status.textContent = 'هنوز اسکنی انجام نشده. دکمه «اسکن دوباره» را بزنید.';
    }

    if (state.scanResults) renderScanResults(list, state.scanResults, state.scanAt);

    container.querySelector('#rescanBtn').addEventListener('click', () => App.runScan(true));
    const cancelBtn = container.querySelector('#cancelScanBtn');
    if (cancelBtn) cancelBtn.addEventListener('click', () => App.cancelScan());

    container.appendChild(U.el('div', { class: 'disclaimer', text: '⚠ تشخیص پامپ/دامپ بر اساس جهش قیمت و حجم است؛ هیچ‌وقت به‌تنهایی برای معامله کافی نیست.' }));
  }

  function renderScanResults(list, results, scanAt) {
    U.clear(list);
    const events = results.filter(r => r.type === 'pump' || r.type === 'dump');
    const watches = results.filter(r => r.type === 'watch');
    if (!events.length && !watches.length) {
      list.appendChild(U.el('div', { class: 'empty', text: 'پامپ/دامپ فعالی در بین سکه‌های محبوب پیدا نشد.' }));
      return;
    }
    if (events.length) {
      list.appendChild(U.el('div', { class: 'scan-section', text: '🚨 پامپ و دامپ فعال' }));
    }
    for (const r of events) {
      const row = U.el('div', { class: 'scan-row', 'data-coin': r.coin.id }, [
        U.el('span', { class: 'badge ' + (r.type === 'pump' ? 'badge-pump' : r.type === 'dump' ? 'badge-dump' : 'badge-watch'),
          text: r.type === 'pump' ? 'پامپ' : r.type === 'dump' ? 'دامپ' : 'آماده‌باش' }),
        U.el('div', { class: 'scan-main' }, [
          U.el('div', { class: 'coin-name', text: r.coin.name + ' (' + r.coin.sym + ')' }),
          U.el('div', { class: 'scan-msg', text: r.msg })
        ]),
        U.el('div', { class: 'scan-meta' }, [
          U.el('div', { class: 'scan-sev', text: severityLabel(r.severity) }),
          U.el('div', { class: 'scan-time', text: U.fmtTime(r.at) })
        ])
      ]);
      list.appendChild(row);
    }
    if (watches.length) {
      list.appendChild(U.el('div', { class: 'scan-section', text: '👀 آماده‌باش (فشردگی + حجم غیرعادی)' }));
    }
    for (const r of watches) {
      const row = U.el('div', { class: 'scan-row watch', 'data-coin': r.coin.id }, [
        U.el('span', { class: 'badge badge-watch', text: 'آماده‌باش' }),
        U.el('div', { class: 'scan-main' }, [
          U.el('div', { class: 'coin-name', text: r.coin.name + ' (' + r.coin.sym + ')' }),
          U.el('div', { class: 'scan-msg', text: r.msg })
        ]),
        U.el('div', { class: 'scan-meta' }, [
          U.el('div', { class: 'scan-time', text: U.fmtTime(r.at) })
        ])
      ]);
      list.appendChild(row);
    }
    if (scanAt) {
      list.appendChild(U.el('div', { class: 'scan-foot', text: 'آخرین اسکن: ' + U.fmtDateTime(scanAt) }));
    }
    list.addEventListener('click', e => {
      const row = e.target.closest('[data-coin]');
      if (row) App.openCoin(row.getAttribute('data-coin'));
    });
  }

  /* ============================================================
   * صفحه ۴: تنظیمات
   * ============================================================ */
  function renderSettings(container, state) {
    U.clear(container);
    container.appendChild(U.el('div', { class: 'panel-title', text: 'تنظیمات' }));

    const s = Store.load();
    const form = U.el('div', { class: 'settings-form' });

    function numSetting(label, key, min, max, step, unit) {
      const wrap = U.el('div', { class: 'setting' }, [
        U.el('label', { class: 'setting-label', text: label }),
        U.el('input', { class: 'setting-input', type: 'number', min: min, max: max, step: step, value: s[key], 'data-key': key }),
        unit ? U.el('span', { class: 'setting-unit', text: unit }) : null
      ]);
      form.appendChild(wrap);
    }

    numSetting('فاصله به‌روزرسانی بازار (ثانیه)', 'refreshSec', 10, 600, 5);
    numSetting('فاصله به‌روزرسانی جزئیات (ثانیه)', 'detailRefreshSec', 15, 900, 5);
    numSetting('فاصله اسکن خودکار (دقیقه)', 'scanIntervalMin', 1, 60, 1);
    numSetting('آستانه پامپ ۱۵ دقیقه (٪)', 'pumpPct15m', 0.5, 20, 0.1);
    numSetting('آستانه پامپ ۱ ساعت (٪)', 'pumpPct1h', 1, 50, 0.5);
    numSetting('آستانه دامپ ۱۵ دقیقه (٪-)', 'dumpPct15m', -20, -0.5, 0.1);
    numSetting('آستانه دامپ ۱ ساعت (٪-)', 'dumpPct1h', -50, -1, 0.5);
    numSetting('نسبت حجم به میانگین', 'volRatio', 1, 10, 0.1, 'x');
    numSetting('تعداد ارزهای اسکن‌شده', 'scanMax', 10, 100, 5, 'سکه');

    // اعلان سیستمی
    const notifSetting = U.el('div', { class: 'setting' }, [
      U.el('label', { class: 'setting-label', text: 'اعلان سیستمی پامپ/دامپ شدید' }),
      U.el('button', { class: 'btn ' + (s.notifyEnabled ? 'btn-on' : ''), id: 'notifToggle', text: s.notifyEnabled ? '✔ فعال' : 'غیرفعال' })
    ]);
    form.appendChild(notifSetting);

    // سکه سفارشی
    const customPanel = U.el('div', { class: 'setting col' }, [
      U.el('label', { class: 'setting-label', text: 'افزودن سکه سفارشی (نماد، مثل WIF یا BONK)' }),
      U.el('div', { class: 'custom-add' }, [
        U.el('input', { class: 'setting-input grow', type: 'text', id: 'customSym', placeholder: 'نماد سکه' }),
        U.el('button', { class: 'btn', id: 'customAddBtn', text: 'جستجو و افزودن' })
      ]),
      U.el('div', { class: 'custom-results', id: 'customResults' })
    ]);
    form.appendChild(customPanel);

    const favPanel = U.el('div', { class: 'setting' }, [
      U.el('label', { class: 'setting-label', text: 'سکه‌های نشان‌شده' }),
      U.el('div', { class: 'fav-chips', id: 'favChips' })
    ]);
    form.appendChild(favPanel);
    const chips = favPanel.querySelector('#favChips');
    for (const c of Providers.allCoins()) {
      if (Store.isFavorite(c.id)) {
        chips.appendChild(U.el('span', { class: 'chip', 'data-id': c.id, text: c.sym + ' ✕' }));
      }
    }
    if (!chips.children.length) chips.appendChild(U.el('span', { class: 'chip dim', text: 'هیچ‌کدام' }));

    // سکه‌های سفارشی (با امکان حذف)
    const wl = Store.get('watchlist');
    if (wl.length) {
      const wlPanel = U.el('div', { class: 'setting' }, [
        U.el('label', { class: 'setting-label', text: 'سکه‌های سفارشی (برای حذف کلیک کنید)' }),
        U.el('div', { class: 'fav-chips', id: 'wlChips' })
      ]);
      form.appendChild(wlPanel);
      const wlChips = wlPanel.querySelector('#wlChips');
      for (const c of wl) {
        wlChips.appendChild(U.el('span', { class: 'chip', 'data-wl': c.id, text: c.sym + ' ✕' }));
      }
      wlChips.addEventListener('click', function (e) {
        const chip = e.target.closest('[data-wl]');
        if (!chip) return;
        const id = chip.getAttribute('data-wl');
        Providers.removeCoin(id);
        const newWl = Store.get('watchlist').filter(c => c.id !== id);
        Store.set('watchlist', newWl);
        if (Store.isFavorite(id)) Store.toggleFavorite(id);
        toast('سکه سفارشی حذف شد');
        App.refreshMarket(false);
        renderSettings(container, state);
      });
    }

    form.appendChild(U.el('div', { class: 'btn-row' }, [
      U.el('button', { class: 'btn', id: 'saveBtn', text: 'ذخیره تنظیمات' }),
      U.el('button', { class: 'btn btn-danger', id: 'resetBtn', text: 'بازنشانی' })
    ]));

    container.appendChild(form);

    // درباره
    container.appendChild(U.el('div', { class: 'panel about' }, [
      U.el('div', { class: 'panel-title', text: 'درباره برنامه' }),
      U.el('div', { class: 'about-text', html:
        'اسکنر کریپتو v' + APP_VERSION + ' — ابزار تحلیل تکنیکال خودکار.<br>' +
        'منابع داده: بایننس، بای‌بیت، اوکی‌ایکس، کراکن، کریپتوکامپیر، کوین‌گکو، کوین‌کپ.<br><br>' +
        '<b>سلب مسئولیت:</b> سیگنال‌ها، درصد احتمال و هشدارهای پامپ/دامپ، تخمین‌های آماری ساده بر پایه اندیکاتورها هستند و ' +
        'به هیچ‌وجه توصیه خرید یا فروش نیستند. بازار رمزارز پرریسک است و ممکن است تمام سرمایه شما را از دست بدهید.' })
    ]));

    // رویدادها
    form.addEventListener('input', function (e) {
      const inp = e.target.closest('[data-key]');
      if (inp) { /* تغییر زنده در ذخیره نهایی */ }
    });
    container.querySelector('#saveBtn').addEventListener('click', function () {
      const inputs = form.querySelectorAll('[data-key]');
      for (const inp of inputs) {
        const key = inp.getAttribute('data-key');
        let v = parseFloat(inp.value);
        if (!isFinite(v)) { toast('مقدار نامعتبر برای «' + key + '»'); return; }
        Store.set(key, v);
      }
      this.textContent = '✔ ذخیره شد';
      toast('تنظیمات ذخیره شد');
      setTimeout(() => { this.textContent = 'ذخیره تنظیمات'; }, 1200);
      App.applySettings();
    });
    // اعلان سیستمی: تغییر وضعیت + تست
    const notifBtn = container.querySelector('#notifToggle');
    if (notifBtn) {
      notifBtn.addEventListener('click', function () {
        const on = !Store.get('notifyEnabled');
        Store.set('notifyEnabled', on);
        this.textContent = on ? '✔ فعال' : 'غیرفعال';
        this.classList.toggle('btn-on', on);
        if (on && window.AndroidBridge) {
          window.AndroidBridge.requestPermission();
          window.AndroidBridge.notify('اسکنر کریپتو', 'اعلان هشدار فعال شد ✅');
        }
      });
    }

    // سکه سفارشی
    const customBtn = container.querySelector('#customAddBtn');
    const customSym = container.querySelector('#customSym');
    const customResults = container.querySelector('#customResults');
    if (customBtn) {
      customBtn.addEventListener('click', async function () {
        const q = (customSym.value || '').trim();
        if (!q) { toast('نماد سکه را وارد کنید'); return; }
        customResults.innerHTML = '<div class="empty">در حال جستجو...</div>';
        try {
          const found = await Providers.searchCoin(q);
          U.clear(customResults);
          if (!found.length) {
            customResults.appendChild(U.el('div', { class: 'empty', text: 'سکه‌ای پیدا نشد' }));
            return;
          }
          for (const c of found) {
            const row = U.el('div', { class: 'custom-row' }, [
              U.el('span', { class: 'coin-sym', text: c.sym + ' — ' + c.name }),
              U.el('button', { class: 'btn small', text: 'افزودن', onclick: async (ev) => {
                const btn = ev.target;
                btn.disabled = true;
                btn.textContent = 'در حال بررسی...';
                try {
                  // ساخت سکه کامل از نتیجه جستجو (نمادهای صرافی خودکار ساخته می‌شوند)
                  const full = Providers.buildCoin(c);
                  // اعتبارسنجی سریع: بایننس ← بای‌بیت
                  const res = await Providers.getKlinesFast(full, '15m', 60);
                  if (res.candles.length) Providers.setPrice(full.id, res.candles[res.candles.length - 1].c);
                  if (Providers.addCoin(full)) {
                    const wl = Store.get('watchlist');
                    wl.push(full);
                    Store.set('watchlist', wl);
                    toast('✔ ' + full.sym + ' اضافه شد');
                    U.clear(customResults);
                    App.refreshMarket(false).then(() => App.setScreen('market'));
                  } else {
                    toast(full.sym + ' از قبل موجود است');
                    btn.disabled = false;
                    btn.textContent = 'افزودن';
                  }
                } catch (e) {
                  btn.disabled = false;
                  btn.textContent = 'افزودن';
                  toast('داده‌ای برای ' + c.sym + ' در بایننس/بای‌بیت پیدا نشد');
                }
              } })
            ]);
            customResults.appendChild(row);
          }
        } catch (e) {
          U.clear(customResults);
          customResults.appendChild(U.el('div', { class: 'empty', text: 'خطا در جستجو: ' + e.message }));
          // جایگزین: افزودن دستی با همان نماد تایپ‌شده
          customResults.appendChild(U.el('div', { class: 'custom-row' }, [
            U.el('span', { class: 'coin-sym', text: 'افزودن دستی نماد ' + q.toUpperCase() }),
            U.el('button', { class: 'btn small', text: 'تلاش', onclick: async (ev) => {
              const btn = ev.target;
              btn.disabled = true;
              btn.textContent = '...';
              const manual = Providers.buildCoin({ id: q.toLowerCase() + '-coin', sym: q.toUpperCase(), name: q.toUpperCase() });
              try {
                await Providers.getKlinesFast(manual, '15m', 60);
                Providers.addCoin(manual);
                const wl = Store.get('watchlist');
                wl.push(manual);
                Store.set('watchlist', wl);
                toast('✔ ' + manual.sym + ' اضافه شد');
                U.clear(customResults);
                App.refreshMarket(false).then(() => App.setScreen('market'));
              } catch (e2) {
                btn.disabled = false;
                btn.textContent = 'تلاش';
                toast('این نماد در بایننس/بای‌بیت پیدا نشد');
              }
            } })
          ]));
        }
      });
    }

    container.querySelector('#resetBtn').addEventListener('click', function () {
      // تایید دو مرحله‌ای بدون دیالوگ بومی
      if (this.getAttribute('data-arm') === '1') {
        Store.reset();
        App.applySettings();
        toast('همه چیز بازنشانی شد');
        renderSettings(container, state);
        return;
      }
      this.setAttribute('data-arm', '1');
      this.textContent = 'مطمئن هستید؟ دوباره بزنید';
      setTimeout(() => { this.removeAttribute('data-arm'); this.textContent = 'بازنشانی'; }, 2500);
    });
  }

  return { signalBadge, pumpBadge, severityLabel, renderMarket, updateMarketRows, renderDetail, renderScanner, renderSettings, renderSignalPanel, renderIndicators, renderPumpPanel, renderScanResults, toast };
})();
