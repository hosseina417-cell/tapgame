/* رابط کاربری برنامه — بدون هیچ وابستگی خارجی و کاملاً آفلاین */
(function () {
  'use strict';

  var M = window.CarModel;
  var J = window.Jalali;
  var storage = safeStorage();
  var store = new M.Store(storage);
  var FILTER_KEY = 'cardefect.filters';
  var SEVERITY_COLOR = { 'بحرانی': 'var(--danger)', 'زیاد': '#f0883e', 'متوسط': 'var(--warning)', 'کم': 'var(--ok)' };
  var STATUS_COLOR = {
    'باز': 'var(--info)', 'در دست اقدام': 'var(--purple)',
    'منتظر قطعه': 'var(--warning)', 'انجام‌شده': 'var(--ok)', 'به تعویق افتاده': 'var(--muted)'
  };

  var state = {
    screen: 'dashboard',
    filters: { vehicleId: '', status: '', severity: '', sort: 'شدت', search: '' },
    pendingUndo: null,
    undoIimer: null
  };

  // ---------- ابزارها ----------
  function $(id) { return document.getElementById(id); }

  // در برخی دستگاه‌ها localStorage روی file:// در دسترس نیست؛
  // در این صورت یک ذخیره‌سازِ موقت (در حافظه) جایگزین می‌شود تا برنامه از کار نیفتد.
  function safeStorage() {
    try {
      var probe = '__cardefect_probe__';
      localStorage.setItem(probe, '1');
      localStorage.removeItem(probe);
      return localStorage;
    } catch (e) {
      var mem = {}, warned = false;
      return {
        getItem: function (k) { return k in mem ? mem[k] : null; },
        setItem: function (k, v) { mem[k] = String(v); },
        removeItem: function (k) { delete mem[k]; },
        isMemoryOnly: true,
        _warn: function () { if (!warned) { warned = true; return e && e.message; } return null; }
      };
    }
  }

  function esc(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function faNum(n) {
    try { return J.persianDigits(Number(n || 0).toLocaleString('en-US')); }
    catch (e) { return String(n); }
  }

  function money(n) {
    n = Number(n || 0);
    return n > 0 ? faNum(n) + ' تومان' : '—';
  }

  function fmtDate(iso) { return iso ? J.persianDigits(J.format(iso)) : '—'; }

  function persistFilters() {
    try { localStorage.setItem(FILTER_KEY, JSON.stringify(state.filters)); } catch (e) { /* نادیده */ }
  }

  function restoreFilters() {
    try {
      var raw = localStorage.getItem(FILTER_KEY);
      if (raw) {
        var saved = JSON.parse(raw);
        Object.keys(state.filters).forEach(function (k) {
          if (saved[k] !== undefined) state.filters[k] = saved[k];
        });
      }
    } catch (e) { /* نادیده */ }
  }

  // ---------- پیام کوتاه ----------
  function toast(message, actionLabel, onAction) {
    var box = $('toast');
    $('toast-text').textContent = message;
    var btn = $('toast-action');
    if (actionLabel) {
      btn.hidden = false;
      btn.textContent = actionLabel;
      btn.onclick = function () { hideToast(); if (onAction) onAction(); };
    } else {
      btn.hidden = true;
      btn.onclick = null;
    }
    box.classList.add('show');
    clearTimeout(state.undoIimer);
    state.undoIimer = setTimeout(hideToast, actionLabel ? 7000 : 2600);
  }

  function hideToast() {
    $('toast').classList.remove('show');
    clearTimeout(state.undoIimer);
    state.pendingUndo = null;
  }

  // ---------- گفتگو ----------
  var dialogState = { onAccept: null };

  function openDialog(title, bodyHtml, footerHtml, onReady) {
    $('modal-title').textContent = title;
    $('modal-body').innerHTML = bodyHtml;
    $('modal-footer').innerHTML = footerHtml || '<button class="btn ghost" data-action="close-dialog">بستن</button>';
    $('backdrop').classList.add('open');
    if (onReady) onReady();
    var first = $('modal-body').querySelector('input, select, textarea');
    if (first && !('ontouchstart' in window)) setTimeout(function () { first.focus(); }, 60);
  }

  function closeDialog() { $('backdrop').classList.remove('open'); }

  function confirmDialog(title, message, acceptLabel, onAccept) {
    openDialog(title, '<p style="margin:0">' + esc(message) + '</p>',
      '<button class="btn ghost" data-action="close-dialog">انصراف</button>' +
      '<button class="btn danger" data-action="dialog-accept">' + esc(acceptLabel) + '</button>');
    dialogState.onAccept = onAccept;
  }

  function showErrors(formEl, errors) {
    Object.keys(errors || {}).forEach(function (field) {
      var input = formEl.querySelector('[name="' + field + '"]');
      var target = formEl.querySelector('[data-error="' + field + '"]');
      if (input) input.setAttribute('aria-invalid', 'true');
      if (target) target.textContent = errors[field];
      if (input && !target) {
        var span = document.createElement('span');
        span.className = 'error';
        span.textContent = errors[field];
        input.parentNode.appendChild(span);
      }
    });
    var firstError = formEl.querySelector('.error');
    if (firstError && firstError.scrollIntoView) firstError.scrollIntoView({ block: 'center' });
  }

  function clearErrors(formEl) {
    formEl.querySelectorAll('.error').forEach(function (n) { n.parentNode.removeChild(n); });
    formEl.querySelectorAll('[aria-invalid]').forEach(function (n) { n.removeAttribute('aria-invalid'); });
  }

  // ---------- فرم ایراد ----------
  function selectOptions(values, selected) {
    return values.map(function (v) {
      return '<option value="' + esc(v) + '"' + (v === selected ? ' selected' : '') + '>' + esc(v) + '</option>';
    }).join('');
  }

  function openDefectForm(defectId) {
    var vehicles = store.activeVehicles();
    if (!vehicles.length) {
      toast('ابتدا یک خودرو اضافه کنید');
      go('vehicles');
      return;
    }
    var d = defectId ? store.getDefect(defectId) : null;
    if (defectId && !d) { toast('این ایراد دیگر وجود ندارد'); renderAll(); return; }

    var defaultVehicle = state.filters.vehicleId || vehicles[0].id;
    var values = d || {
      vehicleId: defaultVehicle, title: '', category: 'موتور', severity: 'متوسط',
      status: 'باز', location: '', odometer: '', estimatedCost: '', actualCost: '',
      dueDate: '', description: '', reportedAt: J.todayIso()
    };

    var body =
      '<form id="defect-form" novalidate>' +
      '<label class="field"><span>خودرو</span><select name="vehicleId">' +
      vehicles.map(function (v) {
        return '<option value="' + esc(v.id) + '"' + (v.id === values.vehicleId ? ' selected' : '') + '>' + esc(v.name) + '</option>';
      }).join('') + '</select><span class="error" data-error="vehicleId"></span></label>' +

      '<label class="field"><span>عنوان ایراد *</span><input name="title" value="' + esc(values.title) +
      '" maxlength="80" placeholder="مثلاً صدای زوزه در دنده دو" autocomplete="off">' +
      '<span class="error" data-error="title"></span></label>' +

      '<div class="row wrap" style="gap:8px">' +
      '<label class="field" style="flex:1;min-width:140px"><span>دسته</span><select name="category">' +
      selectOptions(M.CATEGORIES, values.category) + '</select></label>' +
      '<label class="field" style="flex:1;min-width:120px"><span>شدت</span><select name="severity">' +
      selectOptions(M.SEVERITIES, values.severity) + '</select></label>' +
      '</div>' +

      '<label class="field"><span>وضعیت</span><select name="status">' +
      selectOptions(M.STATUSES, values.status) + '</select></label>' +

      '<label class="field"><span>محل / قطعه (اختیاری)</span><input name="location" value="' + esc(values.location) +
      '" maxlength="60" autocomplete="off"></label>' +

      '<div class="row wrap" style="gap:8px">' +
      '<label class="field" style="flex:1;min-width:120px"><span>کیلومتر</span><input name="odometer" inputmode="numeric" value="' +
      esc(values.odometer) + '" placeholder="۰"><span class="error" data-error="odometer"></span></label>' +
      '<label class="field" style="flex:1;min-width:130px"><span>هزینهٔ برآوردی (تومان)</span><input name="estimatedCost" inputmode="numeric" value="' +
      esc(values.estimatedCost) + '" placeholder="۰"><span class="error" data-error="estimatedCost"></span></label>' +
      '</div>' +

      '<label class="field"><span>هزینهٔ واقعی پس از تعمیر (تومان)</span><input name="actualCost" inputmode="numeric" value="' +
      esc(values.actualCost) + '" placeholder="۰"><span class="error" data-error="actualCost"></span></label>' +

      '<label class="field"><span>سررسید (شمسی)</span><input name="dueDate" inputmode="numeric" value="' +
      esc(values.dueDate ? J.format(values.dueDate) : '') + '" placeholder="1405/06/24" autocomplete="off">' +
      '<span class="error" data-error="dueDate"></span></label>' +

      '<label class="field"><span>توضیحات</span><textarea name="description" maxlength="2000" placeholder="شرایط بروز، صدا، نشانه‌ها…">' +
      esc(values.description) + '</textarea></label>' +
      '</form>';

    var footer = (d ? '<button class="btn danger" data-action="delete-defect" data-id="' + esc(d.id) + '">حذف</button>' : '') +
      '<button class="btn ghost" data-action="close-dialog">انصراف</button>' +
      '<button class="btn primary" data-action="save-defect" data-id="' + esc(d ? d.id : '') + '">ذخیره</button>';

    openDialog(d ? 'ویرایش ایراد' : 'ثبت ایراد جدید', body, footer);
  }

  var warnedDuplicate = null;

  function saveDefect(id) {
    var form = $('defect-form');
    clearErrors(form);
    var get = function (name) {
      var el = form.querySelector('[name="' + name + '"]');
      return el ? el.value : '';
    };
    var dueRaw = get('dueDate').trim();
    var dueIso = '';
    if (dueRaw) {
      var parts = dueRaw.split(/[\/\-\s]+/).filter(Boolean);
      if (parts.length === 3) dueIso = J.parseJalali(parts[0], parts[1], parts[2]);
      if (!dueIso) {
        showErrors(form, { dueDate: 'فرمت باید مانند 1405/06/24 باشد.' });
        return;
      }
    }
    var payload = {
      vehicleId: get('vehicleId'),
      title: get('title'),
      category: get('category'),
      severity: get('severity'),
      status: get('status'),
      location: get('location'),
      odometer: get('odometer'),
      estimatedCost: get('estimatedCost'),
      actualCost: get('actualCost'),
      dueDate: dueIso,
      description: get('description')
    };
    var errors = M.validateDefect(payload, store);
    if (Object.keys(errors).length) { showErrors(form, errors); return; }

    if (warnedDuplicate !== payload.title) {
      if (store.hasDuplicateTitle(payload.title, payload.vehicleId, id)) {
        warnedDuplicate = payload.title;
        toast('عنوان مشابهی برای این خودرو وجود دارد — در صورت تمایل دوباره ذخیره را بزنید');
        return;
      }
    }

    warnedDuplicate = null;
    if (id) { store.updateDefect(id, payload); toast('ایراد به‌روزرسانی شد'); }
    else { store.addDefect(payload); toast('ایراد ثبت شد'); }
    closeDialog();
    renderAll();
  }

  function askDeleteDefect(id) {
    var d = store.getDefect(id);
    if (!d) return;
    closeDialog();
    confirmDialog('حذف ایراد', '«' + d.title + '» حذف می‌شود.', 'حذف', function () {
      var snapshot = JSON.parse(JSON.stringify(d));
      store.deleteDefect(id);
      renderAll();
      toast('ایراد حذف شد', 'بازگردانی', function () {
        store.restoreDefect(snapshot);
        renderAll();
        toast('ایراد بازگردانده شد');
      });
    });
  }

  // ---------- فرم خودرو ----------
  function openVehicleForm(vehicleId) {
    var v = vehicleId ? store.getVehicle(vehicleId) : null;
    var values = v || { name: '', plate: '', brand: '', model: '', year: '', odometer: '', serviceIntervalKm: 10000, serviceIntervalDays: 180 };
    var body =
      '<form id="vehicle-form" novalidate>' +
      '<label class="field"><span>نام خودرو *</span><input name="name" value="' + esc(values.name) +
      '" maxlength="40" placeholder="مثلاً پژو ۴۰۵ نقره‌ای" autocomplete="off"><span class="error" data-error="name"></span></label>' +
      '<div class="row wrap" style="gap:8px">' +
      '<label class="field" style="flex:1;min-width:120px"><span>برند</span><input name="brand" value="' + esc(values.brand) + '" maxlength="30" autocomplete="off"></label>' +
      '<label class="field" style="flex:1;min-width:120px"><span>مدل</span><input name="model" value="' + esc(values.model) + '" maxlength="30" autocomplete="off"></label>' +
      '</div>' +
      '<div class="row wrap" style="gap:8px">' +
      '<label class="field" style="flex:1;min-width:110px"><span>پلاک</span><input name="plate" value="' + esc(values.plate) + '" maxlength="20" autocomplete="off"></label>' +
      '<label class="field" style="flex:1;min-width:110px"><span>سال ساخت</span><input name="year" inputmode="numeric" value="' + esc(values.year) + '" placeholder="۱۳۹۸"><span class="error" data-error="year"></span></label>' +
      '</div>' +
      '<div class="row wrap" style="gap:8px">' +
      '<label class="field" style="flex:1;min-width:130px"><span>کیلومتر فعلی</span><input name="odometer" inputmode="numeric" value="' + esc(values.odometer) + '" placeholder="۰"><span class="error" data-error="odometer"></span></label>' +
      '<label class="field" style="flex:1;min-width:130px"><span>بازهٔ سرویس (کیلومتر)</span><input name="serviceIntervalKm" inputmode="numeric" value="' + esc(values.serviceIntervalKm) + '" placeholder="۱۰۰۰۰"></label>' +
      '<label class="field" style="flex:1;min-width:130px"><span>بازهٔ سرویس (روز)</span><input name="serviceIntervalDays" inputmode="numeric" value="' + esc(values.serviceIntervalDays === undefined ? 180 : values.serviceIntervalDays) + '" placeholder="۱۸۰"></label>' +
      '</div>' +
      '</form>';
    var footer = (v ? '<button class="btn danger" data-action="delete-vehicle" data-id="' + esc(v.id) + '">حذف</button>' : '') +
      '<button class="btn ghost" data-action="close-dialog">انصراف</button>' +
      '<button class="btn primary" data-action="save-vehicle" data-id="' + esc(v ? v.id : '') + '">ذخیره</button>';
    openDialog(v ? 'ویرایش خودرو' : 'خودروی جدید', body, footer);
  }

  function saveVehicle(id) {
    var form = $('vehicle-form');
    clearErrors(form);
    var get = function (name) {
      var el = form.querySelector('[name="' + name + '"]');
      return el ? el.value : '';
    };
    var payload = {
      id: id || undefined,
      name: get('name'), brand: get('brand'), model: get('model'), plate: get('plate'),
      year: get('year'), odometer: get('odometer'),
      serviceIntervalKm: get('serviceIntervalKm'), serviceIntervalDays: get('serviceIntervalDays')
    };
    var errors = M.validateVehicle(payload, store);
    if (Object.keys(errors).length) { showErrors(form, errors); return; }
    if (id) { store.updateVehicle(id, payload); toast('خودرو به‌روزرسانی شد'); }
    else { store.addVehicle(payload); toast('خودرو اضافه شد'); }
    closeDialog();
    renderAll();
  }

  function askDeleteVehicle(id) {
    var v = store.getVehicle(id);
    if (!v) return;
    var count = store.defects.filter(function (d) { return d.vehicleId === id; }).length;
    closeDialog();
    confirmDialog('حذف خودرو',
      'خودروی «' + v.name + '» و ' + faNum(count) + ' ایرادِ ثبت‌شده برای آن حذف می‌شوند.',
      'حذف', function () {
        var snapshot = JSON.parse(JSON.stringify(store.getVehicle(id)));
        var related = JSON.parse(JSON.stringify(store.defects.filter(function (d) { return d.vehicleId === id; })));
        store.deleteVehicle(id);
        renderAll();
        toast('خودرو و ' + faNum(related.length) + ' ایرادِ آن حذف شد', 'بازگردانی', function () {
          store.restoreVehicle(snapshot, related);
          renderAll();
          toast('خودرو بازگردانده شد');
        });
      });
  }

  // ---------- رندر: داشبورد ----------
  function renderDashboard() {
    var stats = store.stats();
    var tiles = [
      { label: 'ایرادهای باز', value: faNum(stats.open), color: 'var(--fg)' },
      { label: 'بحرانیِ باز', value: faNum(stats.criticalOpen), color: stats.criticalOpen ? 'var(--danger)' : 'var(--muted)' },
      { label: 'برآورد هزینهٔ باز', value: money(stats.estimatedOpenCost), color: 'var(--warning)' },
      { label: 'سررسید گذشته', value: faNum(stats.overdue), color: stats.overdue ? 'var(--danger)' : 'var(--muted)' }
    ];
    $('stat-tiles').innerHTML = tiles.map(function (t) {
      return '<div class="tile"><div class="value num" style="color:' + t.color + '">' + t.value +
        '</div><div class="label">' + esc(t.label) + '</div></div>';
    }).join('');

    // موارد نیازمند توجه
    var attention = store.query({ openOnly: true, sort: 'شدت' }).filter(function (d) {
      if (d.severity === 'بحرانی') return true;
      if (!d.dueDate) return false;
      var diff = J.daysBetween(J.todayIso(), d.dueDate);
      return diff !== null && diff <= 7;
    }).slice(0, 6);

    var html = '';
    if (attention.length) {
      html += '<div class="section-title">نیازمند توجه</div>' + attention.map(defectCardHtml).join('');
    } else if (!stats.open) {
      html += '<div class="empty"><span class="big">✅</span>هیچ ایراد بازی ندارید.<br>' +
        '<span class="tiny">دکمهٔ + را بزنید تا اولین ایراد ثبت شود.</span></div>';
    } else {
      html += '<div class="empty"><span class="big">🗂</span>مورد فوری‌ای وجود ندارد.</div>';
    }
    $('attention-list').innerHTML = html;
    renderServiceReminders();
  }

  function renderServiceReminders() {
    var due = store.activeVehicles().filter(function (v) { return store.serviceStatus(v).needsService; });
    if (!due.length) { $('service-reminders').innerHTML = ''; return; }
    $('service-reminders').innerHTML = '<div class="section-title">سرویس‌های عقب‌افتاده</div>' +
      due.map(function (v) {
        var st = store.serviceStatus(v);
        return '<div class="card"><div class="row"><div><div class="title">' + esc(v.name) +
          '</div><div class="tiny">' + esc(st.reason) + ' سرویس گذشته است</div></div>' +
          '<button class="btn primary small" data-action="record-service" data-id="' + esc(v.id) + '">ثبت سرویس</button>' +
          '</div></div>';
      }).join('');
  }

  // ---------- رندر: ایرادات ----------
  function defectCardHtml(d) {
    var overdue = false, dueText = '';
    if (d.dueDate) {
      var diff = J.daysBetween(J.todayIso(), d.dueDate);
      if (diff !== null) {
        if (diff < 0) { overdue = true; dueText = '⚠ ' + faNum(-diff) + ' روز گذشته'; }
        else if (diff <= 7) dueText = '⏳ ' + faNum(diff) + ' روز مانده';
        else dueText = 'سررسید ' + fmtDate(d.dueDate);
      }
    }
    var sevColor = SEVERITY_COLOR[d.severity] || 'var(--muted)';
    return '<div class="card tappable" data-action="edit-defect" data-id="' + esc(d.id) + '" role="button" tabindex="0">' +
      '<div class="row"><div class="title" style="flex:1">' + esc(d.title) + '</div>' +
      '<span class="badge" style="color:' + sevColor + '">' + esc(d.severity) + '</span></div>' +
      '<div class="sub">' + esc(d.category) + ' • ' + esc(store.vehicleName(d.vehicleId)) +
      (d.odometer ? ' • ' + faNum(d.odometer) + ' km' : '') + '</div>' +
      '<div class="row" style="margin-top:6px"><div class="tiny">' +
      '<span class="dot" style="background:' + (STATUS_COLOR[d.status] || 'var(--muted)') + '"></span>' +
      esc(d.status) + ' • ثبت ' + fmtDate(d.reportedAt) + ' • ' + money(M.effectiveCost(d)) + '</div>' +
      (dueText ? '<span class="tiny" style="color:' + (overdue ? 'var(--danger)' : 'var(--warning)') + '">' + esc(dueText) + '</span>' : '') +
      '</div></div>';
  }

  function renderDefects() {
    // اگر خودرویِ انتخاب‌شده در فیلتر حذف شده بود، فیلتر را پاک می‌کنیم
    if (state.filters.vehicleId && !store.getVehicle(state.filters.vehicleId)) {
      state.filters.vehicleId = '';
      persistFilters();
    }
    // فیلترها
    var vehicles = store.activeVehicles();
    $('filter-vehicle').innerHTML = '<option value="">همهٔ خودروها</option>' +
      vehicles.map(function (v) {
        return '<option value="' + esc(v.id) + '"' + (v.id === state.filters.vehicleId ? ' selected' : '') + '>' +
          esc(v.name) + '</option>';
      }).join('');
    $('filter-sort').innerHTML = M.SORTS.map(function (s) {
      return '<option value="' + esc(s) + '"' + (s === state.filters.sort ? ' selected' : '') + '>' + esc(s) + '</option>';
    }).join('');

    var chip = function (label, active, dataAttr) {
      return '<button type="button" class="chip' + (active ? ' active' : '') + '" aria-pressed="' +
        (active ? 'true' : 'false') + '" ' + dataAttr + '>' + esc(label) + '</button>';
    };
    var chips = '';
    chips += chip('همه', !state.filters.status, 'data-filter="status" data-value=""');
    M.STATUSES.forEach(function (s) {
      if (!state.filters.status || state.filters.status === s) {
        chips += chip(s, state.filters.status === s, 'data-filter="status" data-value="' + esc(s) + '"');
      }
    });
    chips += '<span class="chip" style="border:0;background:none;padding:6px 2px">·</span>';
    chips += chip('همهٔ شدت‌ها', !state.filters.severity, 'data-filter="severity" data-value=""');
    M.SEVERITIES.forEach(function (s) {
      chips += chip(s, state.filters.severity === s, 'data-filter="severity" data-value="' + esc(s) + '"');
    });
    $('filter-chips').innerHTML = chips;

    var items = store.query({
      vehicleId: state.filters.vehicleId || undefined,
      status: state.filters.status || undefined,
      severity: state.filters.severity || undefined,
      sort: state.filters.sort,
      search: state.filters.search
    });

    var hasAny = store.defects.length > 0;
    if (!items.length) {
      $('defect-list').innerHTML = hasAny
        ? '<div class="empty"><span class="big">🔍</span>هیچ ایرادی با این فیلترها پیدا نشد.<br>' +
          '<span class="tiny">فیلترها را تغییر دهید یا جستجو را پاک کنید.</span></div>'
        : '<div class="empty"><span class="big">🔧</span>هنوز ایرادی ثبت نشده است.<br>' +
          '<span class="tiny">دکمهٔ + را بزنید تا شروع کنید.</span></div>';
      return;
    }
    $('defect-list').innerHTML = items.map(defectCardHtml).join('');
  }

  // ---------- رندر: خودروها ----------
  function renderVehicles() {
    var vehicles = store.vehicles;
    if (!vehicles.length) {
      $('vehicle-list').innerHTML = '<div class="empty"><span class="big">🚗</span>هنوز خودرویی ثبت نشده است.<br>' +
        '<span class="tiny">برای ثبت ایراد، ابتدا یک خودرو اضافه کنید.</span></div>';
      return;
    }
    $('vehicle-list').innerHTML = vehicles.map(function (v) {
      var total = store.defects.filter(function (d) { return d.vehicleId === v.id; }).length;
      var open = store.defects.filter(function (d) { return d.vehicleId === v.id && M.isOpen(d); }).length;
      var st = store.serviceStatus(v);
      var meta = [v.plate, v.brand, v.model].filter(Boolean).join(' • ');
      return '<div class="card tappable" data-action="edit-vehicle" data-id="' + esc(v.id) + '" role="button" tabindex="0">' +
        '<div class="row"><div class="title" style="flex:1">' + esc(v.name) +
        (v.archived ? ' <span class="tiny">(بایگانی)</span>' : '') + '</div>' +
        '<span class="badge" style="color:' + (open ? 'var(--info)' : 'var(--muted)') + '">' +
        faNum(open) + ' ایراد باز از ' + faNum(total) + '</span></div>' +
        (meta ? '<div class="sub">' + esc(meta) + '</div>' : '') +
        '<div class="btn-row" style="margin-top:10px">' +
        '<button class="btn ghost small" data-action="archive-vehicle" data-id="' + esc(v.id) + '">' +
        (v.archived ? 'خروج از بایگانی' : 'بایگانی') + '</button>' +
        '<button class="btn ghost small" data-action="edit-vehicle" data-id="' + esc(v.id) + '">ویرایش</button>' +
        '</div>' +
        '<div class="row" style="margin-top:6px"><span class="tiny">کیلومتر: ' + faNum(v.odometer) + ' km</span>' +
        '<span class="tiny" style="color:' + (st.needsService ? 'var(--danger)' : 'var(--muted)') + '">' +
        (st.needsService ? 'سرویس عقب‌افتاده'
          : (st.kmRemaining === null ? 'بازهٔ سرویس تنظیم نشده' : 'تا سرویس: ' + faNum(st.kmRemaining) + ' km')) +
        '</span></div></div>';
    }).join('');
  }

  // ---------- رندر: سرویس ----------
  function renderService() {
    var vehicles = store.activeVehicles();
    if (!vehicles.length) {
      $('service-list').innerHTML = '<div class="empty"><span class="big">🗓</span>خودرویی برای پیگیری سرویس وجود ندارد.</div>';
      return;
    }
    $('service-list').innerHTML = vehicles.map(function (v) {
      var st = store.serviceStatus(v);
      var pct = Math.round((st.kmProgress || 0) * 100);
      var cls = st.kmDue ? 'due' : (pct > 85 ? 'warn' : '');
      var parts = [];
      if (st.kmRemaining !== null) {
        parts.push(st.kmRemaining >= 0 ? faNum(st.kmRemaining) + ' کیلومتر مانده'
          : faNum(-st.kmRemaining) + ' کیلومتر گذشته');
      }
      if (st.daysRemaining !== null) {
        parts.push(st.daysRemaining >= 0 ? faNum(st.daysRemaining) + ' روز مانده'
          : faNum(-st.daysRemaining) + ' روز گذشته');
      }
      return '<div class="card">' +
        '<div class="row"><div class="title">' + esc(v.name) + '</div>' +
        '<span class="badge" style="color:' + (st.needsService ? 'var(--danger)' : 'var(--ok)') + '">' +
        (st.needsService ? 'نیاز به سرویس' : 'وضعیت خوب') + '</span></div>' +
        '<div class="bar ' + cls + '" style="margin:8px 0"><i style="width:' + pct + '%"></i></div>' +
        '<div class="row"><span class="tiny">' + esc(parts.join(' • ')) + '</span>' +
        '<span class="tiny">آخرین سرویس: ' + fmtDate(v.lastServiceDate) + '</span></div>' +
        '<div class="btn-row" style="margin-top:10px">' +
        '<button class="btn primary small" data-action="record-service" data-id="' + esc(v.id) + '">ثبت سرویس انجام‌شده</button>' +
        '<button class="btn ghost small" data-action="edit-vehicle" data-id="' + esc(v.id) + '">ویرایش خودرو</button>' +
        '</div></div>';
    }).join('');
  }

  // ---------- رندر: داده‌ها ----------
  function renderData() {
    var stats = store.stats();
    $('about-stats').textContent = 'تعداد خودروها: ' + store.vehicles.length +
      ' • تعداد ایرادها: ' + stats.total + ' • حجم داده: ' + faNum(store.exportPayload().length) + ' نویسه';
  }

  function buildCsv() {
    var header = ['خودرو', 'عنوان', 'دسته', 'شدت', 'وضعیت', 'محل', 'کیلومتر',
      'هزینه برآوردی', 'هزینه واقعی', 'تاریخ ثبت', 'سررسید', 'توضیحات'];
    function cell(value) {
      var s = String(value === null || value === undefined ? '' : value).replace(/\r?\n/g, ' ');
      // جلوگیری از اجرای فرمول در اکسل/شیت
      if (/^[=+\-@\t]/.test(s)) s = "'" + s;
      return '"' + s.replace(/"/g, '""') + '"';
    }
    var rows = [header.map(cell).join(',')];
    store.query({ sort: 'جدیدترین' }).forEach(function (d) {
      rows.push([store.vehicleName(d.vehicleId), d.title, d.category, d.severity, d.status,
        d.location, d.odometer, d.estimatedCost, d.actualCost,
        d.reportedAt, d.dueDate, d.description].map(cell).join(','));
    });
    return rows.join('\r\n');
  }

  function copyText(text, okMessage) {
    var done = function () { toast(okMessage || 'رونوشت شد'); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text, done); });
    } else {
      fallbackCopy(text, done);
    }
  }

  function fallbackCopy(text, done) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); done(); }
    catch (e) { toast('رونوشت خودکار نشد؛ متن را دستی انتخاب کنید'); }
    document.body.removeChild(ta);
  }

  function downloadText(filename, text) {
    try {
      var blob = new Blob(['\ufeff' + text], { type: 'text/plain;charset=utf-8' });
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
      toast('فایل آمادهٔ دانلود شد');
    } catch (e) {
      toast('دانلود در این محیط ممکن نیست؛ از دکمهٔ رونوشت استفاده کنید');
    }
  }

  // ---------- ناوبری ----------
  function go(screen) {
    state.screen = screen;
    document.querySelectorAll('.screen').forEach(function (s) { s.classList.remove('active'); });
    var el = $('screen-' + screen);
    if (el) el.classList.add('active');
    document.querySelectorAll('nav button').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-screen') === screen);
    });
    $('fab').style.display = (screen === 'defects' || screen === 'vehicles') ? 'block' : 'none';
    window.scrollTo(0, 0);
    var main = $('main');
    if (main) main.scrollTop = 0;
  }

  // ---------- رندر کامل ----------
  function renderAll() {
    var steps = [renderDashboard, renderDefects, renderVehicles, renderService, renderData];
    steps.forEach(function (fn) {
      try { fn(); }
      catch (err) { if (window.console) console.error('render error', err); }
    });
  }

  // ---------- رویدادها ----------
  function bindEvents() {
    document.querySelectorAll('nav button').forEach(function (btn) {
      btn.addEventListener('click', function () { go(btn.getAttribute('data-screen')); });
    });

    $('fab').addEventListener('click', function () {
      if (state.screen === 'vehicles') openVehicleForm(null);
      else openDefectForm(null);
    });

    $('search-input').addEventListener('input', function (e) {
      state.filters.search = e.target.value;
      persistFilters();
      renderDefects();
    });
    $('filter-vehicle').addEventListener('change', function (e) {
      state.filters.vehicleId = e.target.value;
      persistFilters();
      renderDefects();
    });
    $('filter-sort').addEventListener('change', function (e) {
      state.filters.sort = e.target.value;
      persistFilters();
      renderDefects();
    });

    $('filter-chips').addEventListener('click', function (e) {
      var chip = e.target.closest('.chip');
      if (!chip) return;
      var key = chip.getAttribute('data-filter');
      if (!key || key === 'none') return;
      var value = chip.getAttribute('data-value');
      state.filters[key] = (state.filters[key] === value) ? '' : value;
      persistFilters();
      renderDefects();
    });

    // کلیک‌هایِ درونِ فهرست‌ها و گفتگوها
    document.addEventListener('click', function (e) {
      var el = e.target.closest('[data-action]');
      if (!el) return;
      var action = el.getAttribute('data-action');
      var id = el.getAttribute('data-id');

      if (action === 'close-dialog') { closeDialog(); return; }
      if (action === 'dialog-accept') {
        var fn = dialogState.onAccept;
        dialogState.onAccept = null;
        closeDialog();
        if (fn) fn();
        return;
      }
      if (action === 'edit-defect') { openDefectForm(id); return; }
      if (action === 'delete-defect') { askDeleteDefect(id); return; }
      if (action === 'save-defect') { saveDefect(id); return; }
      if (action === 'edit-vehicle') { openVehicleForm(id); return; }
      if (action === 'delete-vehicle') { askDeleteVehicle(id); return; }
      if (action === 'save-vehicle') { saveVehicle(id); return; }
      if (action === 'archive-vehicle') {
        var av = store.getVehicle(id);
        if (!av) return;
        store.setArchived(id, !av.archived);
        renderAll();
        toast(av.archived ? 'خودرو از بایگانی خارج شد' : 'خودرو بایگانی شد');
        return;
      }
      if (action === 'clear-all') {
        confirmDialog('پاک‌کردن داده‌ها', 'همهٔ خودروها و ایرادها برای همیشه پاک می‌شوند. ادامه می‌دهید؟',
          'پاک‌کن', function () {
            store.clearAll();
            state.filters.vehicleId = '';
            persistFilters();
            renderAll();
            toast('همهٔ داده‌ها پاک شدند');
          });
        return;
      }
      if (action === 'record-service') {
        var v = store.getVehicle(id);
        if (!v) return;
        confirmDialog('ثبت سرویس',
          'سرویسِ «' + v.name + '» با کیلومترِ فعلی (' + faNum(v.odometer) + ' km) ثبت شود؟',
          'ثبت', function () {
            store.recordService(id, v.odometer);
            renderAll();
            toast('سرویس ثبت شد');
          });
        return;
      }
      if (action === 'backup-show') { $('backup-text').value = store.exportPayload(); toast('پشتیبان آماده شد'); return; }
      if (action === 'backup-copy') { copyText(store.exportPayload(), 'پشتیبان رونوشت شد'); return; }
      if (action === 'backup-download') { downloadText('cardefect-backup.json', store.exportPayload()); return; }
      if (action === 'csv-show') { $('csv-text').value = buildCsv(); toast('CSV ساخته شد'); return; }
      if (action === 'csv-copy') { copyText(buildCsv(), 'CSV رونوشت شد'); return; }
      if (action === 'import-merge' || action === 'import-replace') {
        var text = $('import-text').value.trim();
        if (!text) { toast('ابتدا متن پشتیبان را بچسبانید'); return; }
        var mode = action === 'import-replace' ? 'replace' : 'merge';
        var act = function () {
          var res = store.importPayload(text, mode);
          $('import-text').value = '';
          renderAll();
          toast(res.message);
        };
        if (mode === 'replace') {
          confirmDialog('جایگزینی داده‌ها', 'همهٔ داده‌های فعلی پاک و با پشتیبان جایگزین می‌شوند.', 'جایگزینی', act);
        } else {
          act();
        }
        return;
      }
    });

    // پشتیبانی از صفحه‌کلید برای کارت‌ها
    var importFile = $('import-file');
    if (importFile) {
      importFile.addEventListener('change', function (e) {
        var file = e.target.files && e.target.files[0];
        if (!file) return;
        var reader = new FileReader();
        reader.onload = function () { $('import-text').value = String(reader.result || ''); toast('فایل خوانده شد'); };
        reader.onerror = function () { toast('خواندن فایل ناموفق بود'); };
        reader.readAsText(file);
      });
    }

    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      var card = e.target.closest && e.target.closest('.card.tappable');
      if (!card) return;
      e.preventDefault();
      card.click();
    });

    $('backdrop').addEventListener('click', function (e) {
      if (e.target === $('backdrop')) closeDialog();
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closeDialog();
    });
  }

  // ---------- آغاز ----------
  function init() {
    restoreFilters();
    var loaded = store.load();
    if (!loaded && !store.vehicles.length) {
      // نخستین اجرا: یک خودروی پیش‌فرض تا کاربر سردرگم نشود
      store.addVehicle({ name: 'خودروی من' });
    }
    if (store.restoredFromBackup) {
      $('storage-warning').textContent = '⚠ داده از نسخهٔ پشتیبان بازیابی شد';
    }
    if (storage.isMemoryOnly) {
      $('storage-warning').textContent = '⚠ ذخیره‌ساز در دسترس نیست؛ داده‌ها فقط تا بستن برنامه می‌مانند';
    }
    $('today-label').textContent = J.persianDigits(J.format(J.todayIso())) + ' — ' + J.weekday(J.todayIso());
    $('search-input').value = state.filters.search || '';
    bindEvents();
    go('dashboard');
    renderAll();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
