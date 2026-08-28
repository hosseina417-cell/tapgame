const state = { data: null, q: "", proto: "", source: "" };

const $ = (id) => document.getElementById(id);

function toast(msg) {
  const t = document.createElement("div");
  t.textContent = msg;
  Object.assign(t.style, {
    position: "fixed", bottom: "20px", left: "50%", transform: "translateX(-50%)",
    background: "#0f766e", color: "#fff", padding: "10px 16px", borderRadius: "12px",
    zIndex: 9, fontFamily: "inherit",
  });
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 1800);
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast("کپی شد");
  } catch {
    toast("کپی ناموفق بود");
  }
}

function filtered() {
  if (!state.data) return [];
  const q = state.q.trim().toLowerCase();
  return state.data.configs.filter((c) => {
    if (state.proto && c.protocol !== state.proto) return false;
    if (state.source && c.source !== state.source) return false;
    if (!q) return true;
    const hay = `${c.remark} ${c.host} ${c.flag} ${c.protocol}`.toLowerCase();
    return hay.includes(q);
  });
}

function render() {
  const stats = $("stats");
  const list = $("list");
  if (!state.data) {
    stats.innerHTML = "";
    list.innerHTML = '<div class="empty">در حال دریافت کانفیگ‌ها…</div>';
    return;
  }
  const items = filtered();
  const okSources = state.data.sources.filter((s) => s.ok).length;
  stats.innerHTML = `
    <div class="stat"><b>${state.data.total}</b><span>کل لینک‌های یکتا</span></div>
    <div class="stat"><b>${items.length}</b><span>نمایش داده‌شده</span></div>
    <div class="stat"><b>${okSources}/${state.data.sources.length}</b><span>منبع سالم</span></div>
    <div class="stat"><b>${new Date(state.data.updated * 1000).toLocaleTimeString("fa-IR")}</b><span>آخرین بروزرسانی</span></div>
  `;
  const srcSel = $("source");
  if (srcSel.options.length <= 1) {
    for (const s of state.data.sources) {
      const o = document.createElement("option");
      o.value = s.id;
      o.textContent = `${s.name} (${s.count})`;
      srcSel.appendChild(o);
    }
  }
  if (!items.length) {
    list.innerHTML = '<div class="empty">موردی با این فیلتر پیدا نشد.</div>';
    return;
  }
  list.innerHTML = items.slice(0, 400).map((c) => `
    <article class="card">
      <div class="flag">${c.flag}</div>
      <div class="meta">
        <h3>${escapeHtml(c.remark)}</h3>
        <p>
          <span class="badge ${c.protocol}">${c.protocol}</span>
          ${escapeHtml(c.host)}${c.port ? ":" + c.port : ""} · ${escapeHtml(c.source)}
        </p>
      </div>
      <div class="row-actions">
        <button class="btn primary" data-copy="${encodeURIComponent(c.link)}">کپی برای V2RayNG</button>
      </div>
    </article>
  `).join("");
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[ch]));
}

async function load(refresh = false) {
  $("list").innerHTML = '<div class="empty">در حال دریافت کانفیگ‌ها…</div>';
  try {
    const res = await fetch(refresh ? "/api/refresh" : "/api/configs");
    if (!res.ok) throw new Error("HTTP " + res.status);
    state.data = await res.json();
    render();
  } catch (e) {
    $("list").innerHTML = `<div class="empty">خطا در دریافت: ${escapeHtml(e.message)}</div>`;
  }
}

$("search").addEventListener("input", (e) => { state.q = e.target.value; render(); });
$("proto").addEventListener("change", (e) => { state.proto = e.target.value; render(); });
$("source").addEventListener("change", (e) => { state.source = e.target.value; render(); });
$("refreshBtn").addEventListener("click", () => load(true));
$("copyAllBtn").addEventListener("click", () => {
  const links = filtered().map((c) => c.link).join("\n");
  if (!links) return toast("چیزی برای کپی نیست");
  copyText(links);
});
document.addEventListener("click", (e) => {
  const btn = e.target.closest("[data-copy]");
  if (btn) copyText(decodeURIComponent(btn.getAttribute("data-copy")));
});

load(false);
