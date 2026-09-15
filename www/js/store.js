/* لایهٔ داده: مدل، اعتبارسنجی، ذخیره‌سازی، کوئری و آمار */
(function (global) {
  'use strict';

  var Jalali = global.Jalali || (typeof require !== 'undefined' ? require('./jalali.js') : null);

  var SCHEMA_VERSION = 3;
  var CATEGORIES = ['موتور', 'گیربکس و انتقال قدرت', 'ترمز', 'برق و سنسور', 'بدنه و رنگ',
    'جلوبندی و تعلیق', 'تایر و چرخ', 'تهویه و کولر', 'سوخت و اگزوز',
    'کابین و تجهیزات', 'سرویس دوره‌ای', 'سایر'];
  var SEVERITIES = ['بحرانی', 'زیاد', 'متوسط', 'کم'];
  var SEVERITY_RANK = {};
  SEVERITIES.forEach(function (s, i) { SEVERITY_RANK[s] = i; });
  var STATUSES = ['باز', 'در دست اقدام', 'منتظر قطعه', 'انجام‌شده', 'به تعویق افتاده'];
  var OPEN_STATUSES = ['باز', 'در دست اقدام', 'منتظر قطعه'];
  var DONE = 'انجام‌شده';
  var SORTS = ['شدت', 'جدیدترین', 'قدیمی‌ترین', 'سررسید', 'هزینه', 'کیلومتر'];
  var LIMITS = { title: [3, 80], description: [0, 2000], location: [0, 60], name: [2, 40] };
  var MAX_COST = 100000000000;
  var MAX_ODO = 10000000;
  var STORAGE_KEY = 'cardefect.data.v3';
  var BACKUP_KEY = 'cardefect.data.backup';

  // ---------- ابزارهای متن و عدد ----------
  var AR_TO_FA = { 'ي': 'ی', 'ى': 'ی', 'ك': 'ک', 'ۀ': 'ه', 'ة': 'ه', 'ؤ': 'و', 'إ': 'ا', 'أ': 'ا' };

  function normalizeFa(text) {
    if (text === null || text === undefined) return '';
    var s = String(text)
      .replace(/[\u064B-\u065F\u0670\u0640\u200C\u200D\u200E\u200F]/g, '')
      .replace(/[يىكۀةؤإأ]/g, function (c) { return AR_TO_FA[c] || c; })
      .replace(/[۰-۹]/g, function (d) { return String('۰۱۲۳۴۵۶۷۸۹'.indexOf(d)); })
      .replace(/[٠-٩]/g, function (d) { return String('٠١٢٣٤٥٦٧٨٩'.indexOf(d)); });
    return s.replace(/\s+/g, ' ').trim().toLowerCase();
  }

  function cleanText(value, maxLen) {
    if (value === null || value === undefined) return '';
    var s = String(value).replace(/\u0000/g, '').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
    s = s.trim();
    return s.length > maxLen ? s.slice(0, maxLen).trim() : s;
  }

  function toInt(value, def, min, max) {
    if (value === null || value === undefined || value === '') return def === undefined ? 0 : def;
    var n = typeof value === 'number' ? value
      : parseInt(String(value).replace(/[۰-۹]/g, function (d) { return String('۰۱۲۳۴۵۶۷۸۹'.indexOf(d)); })
        .replace(/[,،\s]/g, '').replace(/[^\d-]/g, ''), 10);
    if (isNaN(n)) return def === undefined ? 0 : def;
    if (min !== undefined && n < min) return min;
    if (max !== undefined && n > max) return max;
    return n;
  }

  function isValidIso(value) {
    if (!value) return true;
    return /^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2})?$/.test(String(value).trim());
  }

  function newId() {
    return Math.random().toString(36).slice(2, 8) + Date.now().toString(36).slice(-6);
  }

  // ---------- مدل‌ها ----------
  function normalizeVehicle(data) {
    data = data || {};
    return {
      id: data.id || newId(),
      name: cleanText(data.name, LIMITS.name[1]),
      plate: cleanText(data.plate, 20),
      brand: cleanText(data.brand, 30),
      model: cleanText(data.model, 30),
      year: toInt(data.year, 0, 0, 2100),
      vin: cleanText(data.vin, 17),
      odometer: toInt(data.odometer, 0, 0, MAX_ODO),
      serviceIntervalKm: toInt(data.serviceIntervalKm, 10000, 0, MAX_ODO),
      serviceIntervalDays: toInt(data.serviceIntervalDays, 180, 0, 3650),
      lastServiceKm: toInt(data.lastServiceKm, 0, 0, MAX_ODO),
      lastServiceDate: isValidIso(data.lastServiceDate) ? (data.lastServiceDate || '') : '',
      createdAt: data.createdAt || Jalali.nowIso(),
      updatedAt: data.updatedAt || Jalali.nowIso(),
      archived: !!data.archived
    };
  }

  function normalizeDefect(data) {
    data = data || {};
    var d = {
      id: data.id || newId(),
      vehicleId: data.vehicleId || '',
      title: cleanText(data.title, LIMITS.title[1]),
      category: CATEGORIES.indexOf(data.category) >= 0 ? data.category : CATEGORIES[CATEGORIES.length - 1],
      severity: SEVERITIES.indexOf(data.severity) >= 0 ? data.severity : 'متوسط',
      status: STATUSES.indexOf(data.status) >= 0 ? data.status : 'باز',
      location: cleanText(data.location, LIMITS.location[1]),
      description: cleanText(data.description, LIMITS.description[1]),
      odometer: toInt(data.odometer, 0, 0, MAX_ODO),
      estimatedCost: toInt(data.estimatedCost, 0, 0, MAX_COST),
      actualCost: toInt(data.actualCost, 0, 0, MAX_COST),
      dueDate: isValidIso(data.dueDate) ? (data.dueDate || '') : '',
      reportedAt: isValidIso(data.reportedAt) ? (data.reportedAt || Jalali.todayIso()) : Jalali.todayIso(),
      resolvedAt: isValidIso(data.resolvedAt) ? (data.resolvedAt || '') : '',
      createdAt: data.createdAt || Jalali.nowIso(),
      updatedAt: data.updatedAt || Jalali.nowIso()
    };
    if (d.status === DONE && !d.resolvedAt) d.resolvedAt = Jalali.todayIso();
    if (d.status !== DONE) d.resolvedAt = '';
    return d;
  }

  function isOpen(d) { return OPEN_STATUSES.indexOf(d.status) >= 0; }
  function effectiveCost(d) { return d.actualCost || d.estimatedCost; }

  // ---------- اعتبارسنجی ----------
  function validateVehicle(data, store) {
    var errors = {};
    var name = cleanText(data.name, LIMITS.name[1]);
    if (name.length < LIMITS.name[0]) errors.name = 'نام خودرو باید حداقل ' + LIMITS.name[0] + ' حرف باشد.';
    if (store && store.vehicles.some(function (v) {
      return !v.archived && normalizeFa(v.name) === normalizeFa(name) && v.id !== data.id;
    })) errors.name = 'خودرویی با این نام وجود دارد.';
    var year = toInt(data.year, 0, 0, 2100);
    if (year && year < 1300) errors.year = 'سال ساخت معتبر نیست.';
    var odo = toInt(data.odometer, 0, 0, MAX_ODO);
    if (odo && odo < toInt(data.lastServiceKm, 0, 0, MAX_ODO)) {
      errors.odometer = 'کیلومتر فعلی نمی‌تواند کمتر از کیلومتر آخرین سرویس باشد.';
    }
    if (!isValidIso(data.lastServiceDate)) errors.lastServiceDate = 'تاریخ نامعتبر است.';
    return errors;
  }

  function validateDefect(data, store) {
    var errors = {};
    var title = cleanText(data.title, LIMITS.title[1]);
    if (title.length < LIMITS.title[0]) errors.title = 'عنوان باید حداقل ' + LIMITS.title[0] + ' حرف باشد.';
    if (!data.vehicleId) errors.vehicleId = 'ابتدا یک خودرو انتخاب کنید.';
    else if (store && !store.getVehicle(data.vehicleId)) errors.vehicleId = 'خودروی انتخاب‌شده وجود ندارد.';
    if (SEVERITIES.indexOf(data.severity) < 0) errors.severity = 'شدت نامعتبر است.';
    if (STATUSES.indexOf(data.status) < 0) errors.status = 'وضعیت نامعتبر است.';
    if (CATEGORIES.indexOf(data.category) < 0) errors.category = 'دسته نامعتبر است.';
    if (!isValidIso(data.dueDate)) errors.dueDate = 'تاریخ سررسید نامعتبر است (مثل 1405/06/24).';
    return errors;
  }

  // ---------- مخزن ----------
  function Store(storage) {
    this.storage = storage;
    this.vehicles = [];
    this.defects = [];
    this.lastError = '';
    this.restoredFromBackup = false;
  }

  Store.prototype.load = function () {
    var raw = null;
    try { raw = this.storage.getItem(STORAGE_KEY); } catch (e) { raw = null; }
    if (!raw) return this.loadLegacy();
    var payload = null;
    try { payload = JSON.parse(raw); } catch (e) { payload = null; }
    if (!payload) {
      this.lastError = 'دادهٔ ذخیره‌شده قابل خواندن نبود.';
      var backup = null;
      try { backup = this.storage.getItem(BACKUP_KEY); } catch (e) { backup = null; }
      if (backup) {
        try {
          payload = JSON.parse(backup);
          this.restoredFromBackup = true;
        } catch (e2) { payload = null; }
      }
      if (!payload) return this.loadLegacy();
    }
    this.applyPayload(payload);
    return true;
  };

  Store.prototype.loadLegacy = function () {
    // مهاجرت از نسخه‌های قدیمی‌تر (کلیدهای پیشین)
    var legacyKeys = ['cardefect.data', 'cardefect.data.v2', 'defects'];
    for (var i = 0; i < legacyKeys.length; i++) {
      var raw = null;
      try { raw = this.storage.getItem(legacyKeys[i]); } catch (e) { raw = null; }
      if (!raw) continue;
      try {
        this.applyPayload(JSON.parse(raw));
        return true;
      } catch (e2) { /* ادامه */ }
    }
    this.vehicles = [];
    this.defects = [];
    return false;
  };

  Store.prototype.applyPayload = function (payload) {
    if (!payload) { this.vehicles = []; this.defects = []; return; }
    if (Array.isArray(payload)) {
      // نسخهٔ ۱: فقط فهرستی از ایرادها
      var v = normalizeVehicle({ name: 'خودروی من' });
      this.vehicles = [v];
      this.defects = payload.filter(function (x) { return x && typeof x === 'object'; })
        .map(function (x) {
          return normalizeDefect({
            vehicleId: v.id, title: x.title || 'بدون عنوان', category: x.category,
            severity: x.severity, description: x.description || x.desc, reportedAt: x.date
          });
        });
    } else {
      this.vehicles = (payload.vehicles || []).map(normalizeVehicle);
      this.defects = (payload.defects || []).map(function (d) {
        var copy = {};
        Object.keys(d || {}).forEach(function (k) { copy[k] = d[k]; });
        if (!copy.vehicleId && copy.vehicle) {
          // نسخهٔ ۲: ارتباط با نام خودرو
          var target = null;
          this.vehicles.forEach(function (v2) {
            if (normalizeFa(v2.name) === normalizeFa(copy.vehicle)) target = v2;
          });
          if (!target && this.vehicles.length) target = this.vehicles[0];
          copy.vehicleId = target ? target.id : '';
          delete copy.vehicle;
        }
        if (copy.cost !== undefined && copy.estimatedCost === undefined) {
          copy.estimatedCost = copy.cost;
          delete copy.cost;
        }
        return normalizeDefect(copy);
      }, this);
    }
    this.repairLinks();
  };

  Store.prototype.repairLinks = function () {
    var seenV = {}, uniq = [];
    this.vehicles.forEach(function (v) {
      if (!v.id || seenV[v.id]) v.id = newId();
      seenV[v.id] = true;
      uniq.push(v);
    });
    this.vehicles = uniq;
    if (!this.vehicles.length) { this.defects = []; return; }
    var valid = {};
    this.vehicles.forEach(function (v) { valid[v.id] = true; });
    var seenD = {}, out = [];
    this.defects.forEach(function (d) {
      if (!d.id || seenD[d.id]) d.id = newId();
      seenD[d.id] = true;
      if (!valid[d.vehicleId]) d.vehicleId = this.vehicles[0].id;
      out.push(d);
    }, this);
    this.defects = out;
  };

  Store.prototype.save = function () {
    var payload = JSON.stringify({
      schemaVersion: SCHEMA_VERSION,
      exportedAt: Jalali.nowIso(),
      vehicles: this.vehicles,
      defects: this.defects
    });
    try {
      var previous = this.storage.getItem(STORAGE_KEY);
      if (previous) this.storage.setItem(BACKUP_KEY, previous);   // یک نسخهٔ پشتیبانِ خودکار
      this.storage.setItem(STORAGE_KEY, payload);
      this.lastError = '';
      return true;
    } catch (e) {
      this.lastError = 'ذخیره انجام نشد: ' + e.message;
      return false;
    }
  };

  // --- خودرو ---
  Store.prototype.getVehicle = function (id) {
    for (var i = 0; i < this.vehicles.length; i++) if (this.vehicles[i].id === id) return this.vehicles[i];
    return null;
  };
  Store.prototype.activeVehicles = function () {
    return this.vehicles.filter(function (v) { return !v.archived; });
  };
  Store.prototype.addVehicle = function (data, autoSave) {
    var v = normalizeVehicle(data);
    v.createdAt = Jalali.nowIso();
    v.updatedAt = v.createdAt;
    this.vehicles.push(v);
    if (autoSave !== false) this.save();
    return v;
  };
  Store.prototype.updateVehicle = function (id, patch, autoSave) {
    var v = this.getVehicle(id);
    if (!v) return null;
    var merged = {};
    Object.keys(v).forEach(function (k) { merged[k] = v[k]; });
    Object.keys(patch || {}).forEach(function (k) { merged[k] = patch[k]; });
    merged.id = id;
    merged.createdAt = v.createdAt;
    merged.updatedAt = Jalali.nowIso();
    var updated = normalizeVehicle(merged);
    this.vehicles[this.vehicles.indexOf(v)] = updated;
    if (autoSave !== false) this.save();
    return updated;
  };
  Store.prototype.deleteVehicle = function (id, autoSave) {
    var v = this.getVehicle(id);
    if (!v) return false;
    this.vehicles.splice(this.vehicles.indexOf(v), 1);
    this.defects = this.defects.filter(function (d) { return d.vehicleId !== id; });
    if (autoSave !== false) this.save();
    return true;
  };
  Store.prototype.restoreVehicle = function (vehicle, defects, autoSave) {
    if (!vehicle || this.getVehicle(vehicle.id)) return false;
    this.vehicles.push(normalizeVehicle(vehicle));
    (defects || []).forEach(function (d) {
      if (!this.getDefect(d.id)) this.defects.push(normalizeDefect(d));
    }, this);
    this.repairLinks();
    if (autoSave !== false) this.save();
    return true;
  };
  Store.prototype.setArchived = function (id, archived, autoSave) {
    return this.updateVehicle(id, { archived: !!archived }, autoSave);
  };
  Store.prototype.hasDuplicateTitle = function (title, vehicleId, excludeId) {
    var norm = normalizeFa(title);
    return this.defects.some(function (d) {
      return d.vehicleId === vehicleId && d.id !== excludeId && normalizeFa(d.title) === norm;
    });
  };
  Store.prototype.clearAll = function () {
    this.vehicles = [];
    this.defects = [];
    this.save();
  };
  Store.prototype.vehicleName = function (id) {
    var v = this.getVehicle(id);
    return v ? v.name : '—';
  };

  // --- ایراد ---
  Store.prototype.getDefect = function (id) {
    for (var i = 0; i < this.defects.length; i++) if (this.defects[i].id === id) return this.defects[i];
    return null;
  };
  Store.prototype.addDefect = function (data, autoSave) {
    var d = normalizeDefect(data);
    d.createdAt = Jalali.nowIso();
    d.updatedAt = d.createdAt;
    this.defects.push(d);
    if (autoSave !== false) this.save();
    return d;
  };
  Store.prototype.updateDefect = function (id, patch, autoSave) {
    var d = this.getDefect(id);
    if (!d) return null;
    var merged = {};
    Object.keys(d).forEach(function (k) { merged[k] = d[k]; });
    Object.keys(patch || {}).forEach(function (k) { merged[k] = patch[k]; });
    merged.id = id;
    merged.createdAt = d.createdAt;
    merged.updatedAt = Jalali.nowIso();
    var updated = normalizeDefect(merged);
    this.defects[this.defects.indexOf(d)] = updated;
    if (autoSave !== false) this.save();
    return updated;
  };
  Store.prototype.deleteDefect = function (id, autoSave) {
    var d = this.getDefect(id);
    if (!d) return false;
    this.defects.splice(this.defects.indexOf(d), 1);
    if (autoSave !== false) this.save();
    return true;
  };
  Store.prototype.restoreDefect = function (snapshot, autoSave) {
    if (!snapshot || this.getDefect(snapshot.id)) return false;
    this.defects.push(normalizeDefect(snapshot));
    if (autoSave !== false) this.save();
    return true;
  };

  // --- کوئری ---
  Store.prototype.query = function (opts) {
    opts = opts || {};
    var term = opts.search ? normalizeFa(opts.search) : '';
    var self = this;
    var items = this.defects.filter(function (d) {
      if (opts.vehicleId && d.vehicleId !== opts.vehicleId) return false;
      if (opts.status && d.status !== opts.status) return false;
      if (opts.severity && d.severity !== opts.severity) return false;
      if (opts.category && d.category !== opts.category) return false;
      if (opts.openOnly && !isOpen(d)) return false;
      if (term) {
        var hay = normalizeFa([d.title, d.description, d.location, d.category, d.status].join(' '));
        if (hay.indexOf(term) < 0) return false;
      }
      return true;
    });
    var sort = opts.sort || 'شدت';
    var byDate = function (a, b) { return (a.reportedAt || '') < (b.reportedAt || '') ? -1 : 1; };
    if (sort === 'جدیدترین') return items.sort(function (a, b) { return -byDate(a, b); });
    if (sort === 'قدیمی‌ترین') return items.sort(byDate);
    if (sort === 'هزینه') return items.sort(function (a, b) { return effectiveCost(b) - effectiveCost(a); });
    if (sort === 'کیلومتر') return items.sort(function (a, b) { return b.odometer - a.odometer; });
    if (sort === 'سررسید') {
      // سررسیددارها اول (نزدیک‌ترین)، سپس مواردِ بدون سررسید
      return items.sort(function (a, b) {
        if (!a.dueDate && !b.dueDate) return byDate(a, b);
        if (!a.dueDate) return 1;
        if (!b.dueDate) return -1;
        return a.dueDate < b.dueDate ? -1 : (a.dueDate > b.dueDate ? 1 : 0);
      });
    }
    return items.sort(function (a, b) {
      var d = SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity];
      if (d) return d;
      if (isOpen(a) !== isOpen(b)) return isOpen(a) ? -1 : 1;
      var c = effectiveCost(b) - effectiveCost(a);
      if (c) return c;
      return -byDate(a, b);
    });
  };

  // --- آمار ---
  Store.prototype.stats = function (vehicleId) {
    var items = this.defects.filter(function (d) { return !vehicleId || d.vehicleId === vehicleId; });
    var bySeverity = {}, byStatus = {}, byCategory = {};
    SEVERITIES.forEach(function (s) { bySeverity[s] = 0; });
    STATUSES.forEach(function (s) { byStatus[s] = 0; });
    var openItems = items.filter(isOpen);
    var today = Jalali.todayIso();
    items.forEach(function (d) {
      bySeverity[d.severity] = (bySeverity[d.severity] || 0) + 1;
      byStatus[d.status] = (byStatus[d.status] || 0) + 1;
      byCategory[d.category] = (byCategory[d.category] || 0) + 1;
    });
    return {
      total: items.length,
      open: openItems.length,
      done: byStatus[DONE] || 0,
      criticalOpen: openItems.filter(function (d) { return d.severity === 'بحرانی'; }).length,
      bySeverity: bySeverity,
      byStatus: byStatus,
      byCategory: byCategory,
      estimatedOpenCost: openItems.reduce(function (s, d) { return s + d.estimatedCost; }, 0),
      actualCost: items.reduce(function (s, d) { return s + d.actualCost; }, 0),
      overdue: openItems.filter(function (d) { return d.dueDate && d.dueDate < today; }).length,
      dueSoon: openItems.filter(function (d) {
        if (!d.dueDate) return false;
        var diff = Jalali.daysBetween(today, d.dueDate);
        return diff !== null && diff >= 0 && diff <= 7;
      }).length
    };
  };

  // --- وضعیت سرویس ---
  Store.prototype.serviceStatus = function (vehicle) {
    var out = { kmDue: false, kmRemaining: null, dateDue: false, daysRemaining: null, reason: '' };
    if (!vehicle) return out;
    var interval = toInt(vehicle.serviceIntervalKm, 0, 0, MAX_ODO);
    if (interval > 0) {
      var driven = Math.max(0, toInt(vehicle.odometer, 0) - toInt(vehicle.lastServiceKm, 0));
      out.kmRemaining = interval - driven;
      out.kmProgress = Math.min(1, Math.max(0, driven / interval));
      out.kmDue = out.kmRemaining <= 0;
    }
    if (vehicle.lastServiceDate && toInt(vehicle.serviceIntervalDays, 0, 0, 3650) > 0) {
      var elapsed = Jalali.daysBetween(vehicle.lastServiceDate, Jalali.todayIso());
      if (elapsed !== null) {
        out.daysRemaining = toInt(vehicle.serviceIntervalDays, 0) - elapsed;
        out.dateDue = out.daysRemaining <= 0;
      }
    }
    var reasons = [];
    if (out.kmDue) reasons.push('موعد کیلومتری');
    if (out.dateDue) reasons.push('موعد زمانی');
    out.needsService = !!(out.kmDue || out.dateDue);
    out.reason = reasons.join(' و ');
    return out;
  };

  Store.prototype.recordService = function (vehicleId, odometer, dateIso, autoSave) {
    var v = this.getVehicle(vehicleId);
    if (!v) return null;
    var km = toInt(odometer === undefined ? v.odometer : odometer, 0, 0, MAX_ODO);
    return this.updateVehicle(vehicleId, {
      lastServiceKm: km,
      lastServiceDate: dateIso || Jalali.todayIso(),
      odometer: Math.max(km, v.odometer)
    }, autoSave);
  };

  // --- پشتیبان‌گیری ---
  Store.prototype.exportPayload = function () {
    return JSON.stringify({
      schemaVersion: SCHEMA_VERSION,
      exportedAt: Jalali.nowIso(),
      vehicles: this.vehicles,
      defects: this.defects
    }, null, 1);
  };

  Store.prototype.importPayload = function (text, mode) {
    var payload = null;
    try { payload = JSON.parse(text); } catch (e) { return { ok: false, message: 'فایل/متن معتبر نیست (JSON خراب).' }; }
    if (!payload || (!payload.vehicles && !payload.defects)) {
      return { ok: false, message: 'ساختار پشتیبان شناخته نشد.' };
    }
    var incomingVehicles = (payload.vehicles || []).map(normalizeVehicle);
    var incomingDefects = [];
    var self = this;
    (payload.defects || []).forEach(function (d) {
      var copy = {};
      Object.keys(d || {}).forEach(function (k) { copy[k] = d[k]; });
      if (!copy.vehicleId && copy.vehicle) {
        var t = null;
        self.vehicles.concat(incomingVehicles).forEach(function (v) {
          if (normalizeFa(v.name) === normalizeFa(copy.vehicle)) t = v;
        });
        copy.vehicleId = t ? t.id : '';
      }
      incomingDefects.push(normalizeDefect(copy));
    });
    if (mode === 'replace') {
      this.vehicles = incomingVehicles;
      this.defects = incomingDefects;
      this.repairLinks();
      this.save();
      return { ok: true, added: incomingVehicles.length, message: 'داده‌ها جایگزین شدند.' };
    }
    var existingV = {}, existingD = {}, addedV = 0, addedD = 0;
    this.vehicles.forEach(function (v) { existingV[v.id] = true; });
    this.defects.forEach(function (d) { existingD[d.id] = true; });
    incomingVehicles.forEach(function (v) { if (!existingV[v.id]) { self.vehicles.push(v); addedV++; } });
    incomingDefects.forEach(function (d) { if (!existingD[d.id]) { self.defects.push(d); addedD++; } });
    this.repairLinks();
    this.save();
    return { ok: true, added: addedV + addedD, message: 'ادغام انجام شد (' + addedV + ' خودرو، ' + addedD + ' ایراد جدید).' };
  };

  var api = {
    SCHEMA_VERSION: SCHEMA_VERSION,
    CATEGORIES: CATEGORIES,
    SEVERITIES: SEVERITIES,
    STATUSES: STATUSES,
    OPEN_STATUSES: OPEN_STATUSES,
    DONE: DONE,
    SORTS: SORTS,
    LIMITS: LIMITS,
    MAX_COST: MAX_COST,
    MAX_ODO: MAX_ODO,
    STORAGE_KEY: STORAGE_KEY,
    normalizeFa: normalizeFa,
    cleanText: cleanText,
    toInt: toInt,
    isValidIso: isValidIso,
    newId: newId,
    normalizeVehicle: normalizeVehicle,
    normalizeDefect: normalizeDefect,
    isOpen: isOpen,
    effectiveCost: effectiveCost,
    validateVehicle: validateVehicle,
    validateDefect: validateDefect,
    Store: Store
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.CarModel = api;
})(typeof window !== 'undefined' ? window : globalThis);
