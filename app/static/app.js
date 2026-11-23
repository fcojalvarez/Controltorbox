"use strict";

/* ==========================================================================
   Controltorbox (web) — misma estructura que la app Android:
   Inicio · Biblioteca · Ajustes, revisión guiada, duplicados y ficha.
   ========================================================================== */

const $ = (sel, el = document) => el.querySelector(sel);
const GB = 1024 ** 3;
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const keyOf = (it) => `${it.kind}:${it.id}`;
const KIND_LABEL = { torrent: "Torrent", usenet: "Usenet", webdl: "Web" };
const STATUS_LABEL = { es: "Español", no_es: "Sin español", unknown: "Sin comprobar" };
const LANG = {
  spa: "Español", es: "Español", eng: "Inglés", en: "Inglés", jpn: "Japonés", ja: "Japonés", fre: "Francés", fra: "Francés",
  fr: "Francés", ger: "Alemán", deu: "Alemán", de: "Alemán", ita: "Italiano", it: "Italiano", por: "Portugués", pt: "Portugués",
  kor: "Coreano", ko: "Coreano", chi: "Chino", zho: "Chino", rus: "Ruso", ru: "Ruso", cat: "Catalán", ca: "Catalán", und: "Sin etiqueta",
};
const langName = (c) => { const l = String(c || "und").toLowerCase(); return l.startsWith("es") ? "Español" : (LANG[l] || LANG[l.split("-")[0]] || l.toUpperCase()); };
const isEsCode = (c) => { const l = String(c || "").toLowerCase(); return ["spa", "es", "esp", "spanish", "castellano", "latino"].includes(l) || l.startsWith("es-"); };

const ICON = {
  back: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M15 6l-6 6 6 6"/></svg>',
  chev: '<svg class="chev" aria-hidden="true" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg>',
  refresh: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 3v6h-6"/></svg>',
  noaudio: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M11 5L6 9H2v6h4l5 4V5z"/><path d="M22 9l-6 6"/><path d="M16 9l6 6"/></svg>',
  copy: '<svg aria-hidden="true" viewBox="0 0 24 24"><rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/></svg>',
  wave: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M3 10v4"/><path d="M7 6v12"/><path d="M11 3v18"/><path d="M15 7v10"/><path d="M19 10v4"/></svg>',
  search: '<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/></svg>',
  check: '<svg aria-hidden="true" viewBox="0 0 24 24" style="stroke-width:3"><path d="M5 12l5 5 9-10"/></svg>',
  trash: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M6 6l1 14h10l1-14"/></svg>',
  info: '<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 8v5"/><path d="M12 16h.01"/></svg>',
  play: '<svg class="fill" aria-hidden="true" viewBox="0 0 24 24"><path d="M7 4l13 8-13 8z"/></svg>',
  pause: '<svg class="fill" aria-hidden="true" viewBox="0 0 24 24"><path d="M6 5h4v14H6z"/><path d="M14 5h4v14h-4z"/></svg>',
  muted: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M5 5l14 14"/><path d="M7 4l13 8-4 2.5"/><path d="M7 11v9l5-3"/></svg>',
  movie: '<svg aria-hidden="true" viewBox="0 0 24 24"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="M7 4v16"/><path d="M17 4v16"/></svg>',
  tv: '<svg aria-hidden="true" viewBox="0 0 24 24"><rect x="3" y="6" width="18" height="12" rx="2"/><path d="M8 21h8"/></svg>',
  clock: '<svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
  download: '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 3v12"/><path d="M7 10l5 5 5-5"/><path d="M5 21h14"/></svg>',
  library: '<svg aria-hidden="true" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18"/><path d="M8 4v5"/><path d="M16 4v5"/></svg>',
};

/* -------------------------- Formato ------------------------------------- */
function fmtSize(bytes) {
  if (!bytes || bytes <= 0) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, n = bytes;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1).replace(".", ",")} ${u[i]}`;
}
function fmtTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}
const parseDate = (s) => { const t = s ? Date.parse(s) : NaN; return Number.isNaN(t) ? null : t; };
const SHORT = new Intl.DateTimeFormat("es-ES", { day: "numeric", month: "short" });
const SHORT_Y = new Intl.DateTimeFormat("es-ES", { day: "numeric", month: "short", year: "numeric" });
function shortDate(t) {
  if (t == null) return null;
  const d = new Date(t);
  return (d.getFullYear() === new Date().getFullYear() ? SHORT : SHORT_Y).format(d).replace(".", "");
}
function relDate(t) {
  if (t == null) return null;
  const h = (Date.now() - t) / 3.6e6, d = Math.floor(h / 24);
  if (h < 1) return "hace un momento";
  if (h < 24) return `hace ${Math.floor(h)} h`;
  if (d === 1) return "ayer";
  if (d < 7) return `hace ${d} días`;
  if (d < 14) return "hace 1 semana";
  if (d < 60) return `hace ${Math.floor(d / 7)} semanas`;
  return shortDate(t);
}

/* -------------------------- Almacenamiento local ------------------------ */
const TOKEN_KEY = "controltorbox.torboxToken";
function lsGet(k, def) { try { const v = localStorage.getItem(k); return v === null ? def : v; } catch (_) { return def; } }
function lsSet(k, v) { try { v === null ? localStorage.removeItem(k) : localStorage.setItem(k, v); return true; } catch (_) { return false; } }
const getToken = () => lsGet(TOKEN_KEY, "");
const setToken = (t) => lsSet(TOKEN_KEY, t || null);
const SORTS = {
  date_desc: "Más recientes", date_asc: "Más antiguos", size_desc: "Más grandes", size_asc: "Más pequeños", name_asc: "Nombre (A-Z)",
};
const prefs = {
  get alertOn() { return lsGet("controltorbox.alertOn", "1") === "1"; },
  set alertOn(v) { lsSet("controltorbox.alertOn", v ? "1" : "0"); },
  get alertGb() { return Number(lsGet("controltorbox.alertGb", "500")) || 500; },
  set alertGb(v) { lsSet("controltorbox.alertGb", String(v)); },
  get accountGb() { return Number(lsGet("controltorbox.accountGb", "0")) || 0; },
  set accountGb(v) { lsSet("controltorbox.accountGb", String(v)); },
  get sort() { const s = lsGet("controltorbox.sort", "date_desc"); return SORTS[s] ? s : "date_desc"; },
  set sort(v) { lsSet("controltorbox.sort", v); },
};

/* -------------------------- API ----------------------------------------- */
function authFetch(url, opts = {}) {
  const headers = new Headers(opts.headers || {});
  const t = getToken();
  if (t) headers.set("X-Torbox-Token", t);
  return fetch(url, { ...opts, headers });
}
async function parseResponse(res) {
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (_) { data = { detail: text.slice(0, 300) }; }
  if (!res.ok) {
    let d = data.detail;
    if (Array.isArray(d)) d = d.map((x) => x.msg).join("; ");
    throw new Error(d ? `${d} (HTTP ${res.status})` : `Error HTTP ${res.status}`);
  }
  return data;
}
async function api(path, body, method = "POST") {
  const opts = body !== undefined ? { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : {};
  return parseResponse(await authFetch(path, opts));
}

/* -------------------------- Estado -------------------------------------- */
const FILTERS = [["all", "Todo"], ["no_es", "Sin español"], ["unknown", "Sin comprobar"], ["dups", "Duplicados"], ["es", "Con español"], ["expiring", "Caduca pronto"]];

/* ---- Caducidad y descargas ---- */
const DAY = 864e5;
const expTime = (it) => parseDate(it.expires_at);
const isSoon = (t) => t != null && t - Date.now() < 7 * DAY;
function expiryLabel(t) {
  const h = (t - Date.now()) / 3.6e6;
  if (h < 0) return "caducado";
  if (h < 24) return "caduca hoy";
  if (h < 48) return "caduca mañana";
  return `caduca en ${Math.floor(h / 24)} días`;
}
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const expiringRows = () => state.items.filter((it) => isSoon(expTime(it))).sort((a, b) => expTime(a) - expTime(b));
function etaLabel(sec) {
  if (!sec) return null;
  const m = Math.floor(sec / 60);
  if (sec < 60) return "menos de 1 min";
  return m < 60 ? `quedan ${m} min` : `quedan ${Math.floor(m / 60)} h ${m % 60} min`;
}
function downloadLine(it) {
  const pct = `${Math.round((it.progress || 0) * 100)} %`;
  const tor = it.kind === "torrent";
  if (it.paused) return `En pausa · ${pct}`;
  if (it.stalled) {
    return ["Atascada", it.state && !/stalled/i.test(it.state) ? `«${it.state}»` : null,
      tor && it.seeds != null ? `${it.seeds} fuentes` : null,
      tor && it.availability != null ? `disponibilidad ${Number(it.availability).toFixed(1).replace(".", ",")}` : null].filter(Boolean).join(" · ");
  }
  return [pct, it.download_speed > 0 ? `${fmtSize(it.download_speed)}/s` : null, etaLabel(it.eta),
    tor && it.seeds != null ? `${it.seeds} fuentes${it.peers != null ? ` · ${it.peers} pares` : ""}` : null].filter(Boolean).join(" · ");
}
const downloadingRows = () => state.items.filter((it) => it.in_progress).sort((a, b) => (b.stalled - a.stalled) || (b.progress - a.progress));
const RANK = { es: 2, unknown: 1, no_es: 0 };

const state = {
  status: null, account: null, items: [], queued: [], busy: new Set(), loaded: false, analyzing: null,
  filter: "all", search: "", selecting: false, selected: new Set(),
  reviewKeys: null, decisions: new Map(), dupKeep: {},
  detail: null,
};

const byKey = (k) => state.items.find((it) => keyOf(it) === k);
const timeOf = (it) => parseDate(it.created_at);

function sortItems(list, how) {
  const byDate = (dir) => (a, b) => {
    const ta = timeOf(a), tb = timeOf(b);
    if (ta === null && tb === null) return 0;
    if (ta === null) return 1;
    if (tb === null) return -1;
    return dir * (ta - tb);
  };
  const cmp = {
    size_desc: (a, b) => (b.size || 0) - (a.size || 0),
    size_asc: (a, b) => (a.size || 0) - (b.size || 0),
    date_desc: byDate(-1), date_asc: byDate(1),
    name_asc: (a, b) => a.title.localeCompare(b.title, "es", { sensitivity: "base", numeric: true }),
  }[how];
  return [...list].sort(cmp);
}

const reviewRows = () => sortItems(
  state.items.filter((it) => it.has_video && it.audio_status !== "es" && !it.kept), "size_desc",
).sort((a, b) => (a.audio_status === "no_es" ? 0 : 1) - (b.audio_status === "no_es" ? 0 : 1));

function dupGroups() {
  const map = new Map();
  for (const it of state.items) if (it.duplicate_group) {
    if (!map.has(it.duplicate_group)) map.set(it.duplicate_group, []);
    map.get(it.duplicate_group).push(it);
  }
  return [...map.entries()].map(([id, members]) => {
    const ranked = [...members].sort((a, b) => (RANK[b.audio_status] ?? 1) - (RANK[a.audio_status] ?? 1) || (b.size || 0) - (a.size || 0));
    const keep = members.some((m) => keyOf(m) === state.dupKeep[id]) ? state.dupKeep[id] : keyOf(ranked[0]);
    const toDelete = ranked.filter((m) => keyOf(m) !== keep);
    return { id, label: members[0].duplicate_label, members: ranked, keep, toDelete, freeable: toDelete.reduce((s, m) => s + (m.size || 0), 0) };
  }).sort((a, b) => b.members.reduce((s, m) => s + m.size, 0) - a.members.reduce((s, m) => s + m.size, 0));
}

function matchFilter(it, f) {
  return { all: true, no_es: it.audio_status === "no_es", unknown: it.audio_status === "unknown" && it.has_video,
    dups: !!it.duplicate_group, es: it.audio_status === "es", expiring: isSoon(expTime(it)) }[f];
}
function libraryRows() {
  const q = state.search.trim().toLowerCase();
  const rows = sortItems(state.items.filter((it) =>
    (!q || it.title.toLowerCase().includes(q) || it.name.toLowerCase().includes(q)) && matchFilter(it, state.filter)), prefs.sort);
  return state.filter === "expiring" ? rows.sort((a, b) => expTime(a) - expTime(b)) : rows;
}

const limits = () => {
  const account = state.account?.storage_limit || (prefs.accountGb > 0 ? prefs.accountGb * GB : null);
  const alert = prefs.alertOn && prefs.alertGb > 0 ? prefs.alertGb * GB : null;
  return { account, alert, fromTorbox: !!state.account?.storage_limit };
};

/* -------------------------- Utilidades de interfaz ---------------------- */
let toastTimer = null;
function toast(msg) {
  const el = $("#toast");
  el.textContent = msg;
  el.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add("hidden"), 4000);
}
function setLoading(on) {
  let el = $(".loading-bar");
  if (on && !el) { el = document.createElement("div"); el.className = "loading-bar"; document.body.appendChild(el); }
  if (!on && el) el.remove();
}
function dialog(html, buttons) {
  return new Promise((resolve) => {
    const dlg = $("#dialog");
    $("#dialog-form").innerHTML = `${html}<menu>${buttons.map((b) =>
      `<button value="${b.value}" class="btn ${b.cls || "outline"}">${esc(b.label)}</button>`).join("")}</menu>`;
    dlg.returnValue = "";
    dlg.addEventListener("close", () => resolve(dlg.returnValue), { once: true });
    dlg.showModal();
  });
}
const showError = (msg) => dialog(`<h2>Aviso</h2><p style="white-space:pre-line">${esc(msg)}</p>`, [{ value: "ok", label: "Aceptar", cls: "" }]);
async function confirmDelete(rows) {
  const size = rows.reduce((s, r) => s + (r.size || 0), 0);
  const list = rows.slice(0, 8).map((r) => `<li>${esc(r.title)} · ${fmtSize(r.size)}</li>`).join("") + (rows.length > 8 ? `<li>… y ${rows.length - 8} más</li>` : "");
  const v = await dialog(
    `<h2>¿Borrar de TorBox?</h2><p>Se borrarán ${rows.length} elemento(s) y liberarás ${fmtSize(size)}:</p><ul>${list}</ul><p class="error"><strong>No se puede deshacer.</strong></p>`,
    [{ value: "cancel", label: "Cancelar" }, { value: "ok", label: `Borrar ${rows.length}`, cls: "orange" }],
  );
  return v === "ok";
}
const badge = (it) => it.has_video ? `<span class="badge ${it.audio_status}">${STATUS_LABEL[it.audio_status]}</span>` : "";
const isSeries = (it) => / · T\d/.test(it.title);

/* -------------------------- Acciones ------------------------------------ */
async function load() {
  setLoading(true);
  try {
    state.status = await api("/api/status");
    if (!state.status.api_key_configured) { state.loaded = true; render(); return; }
    try { Object.assign(state.status, await api("/api/settings")); } catch (_) { /* opcional */ }
    const data = await api("/api/items");
    state.items = data.items;
    state.loaded = true;
    const keys = new Set(state.items.map(keyOf));
    for (const k of [...state.selected]) if (!keys.has(k)) state.selected.delete(k);
    render();
    api("/api/account").then((a) => { state.account = a; render(); }).catch(() => {});
    api("/api/queued").then((q) => { state.queued = q.queued; render(); }).catch(() => {});
  } catch (err) {
    state.loaded = true;
    render();
    showError(err.message);
  } finally {
    setLoading(false);
  }
}

async function deleteKeys(keys) {
  const items = keys.map(byKey).filter(Boolean);
  if (!items.length) return;
  setLoading(true);
  try {
    const data = await api("/api/delete", { items: items.map((it) => ({ kind: it.kind, id: it.id })) });
    const ok = data.results.filter((r) => r.ok);
    const failed = data.results.filter((r) => !r.ok);
    const freed = ok.reduce((s, r) => s + (byKey(`${r.kind}:${r.id}`)?.size || 0), 0);
    for (const r of ok) state.selected.delete(`${r.kind}:${r.id}`);
    await load();
    if (failed.length) showError(`No se pudieron borrar ${failed.length}:\n${failed[0].error}`);
    else toast(`Borrados ${ok.length} · liberados ${fmtSize(freed)}`);
  } catch (err) {
    showError(err.message);
  } finally {
    setLoading(false);
  }
}

async function analyzeUnknown() {
  const targets = reviewRows().filter((it) => it.audio_status === "unknown");
  if (!targets.length || state.analyzing) return;
  state.analyzing = `Analizando ${targets.length}…`;
  render();
  try {
    const data = await api("/api/analyze", { items: targets.map((it) => ({ kind: it.kind, id: it.id })) });
    const errors = data.results.filter((r) => r.error && !r.status).length;
    const withEs = data.results.filter((r) => r.status === "es").length;
    state.analyzing = null;
    await load();
    toast(`Analizados ${targets.length - errors}: ${withEs} con español${errors ? ` · ${errors} no se pudieron leer` : ""}`);
  } catch (err) {
    state.analyzing = null;
    render();
    showError(err.message);
  }
}

/* -------------------------- Enrutado ------------------------------------ */
function route() {
  const parts = (location.hash.replace(/^#\/?/, "") || "inicio").split("/");
  return { name: parts[0] || "inicio", args: parts.slice(1) };
}
const go = (h) => { location.hash = h; };

window.addEventListener("hashchange", () => {
  const r = route();
  if (r.name !== "revisar" && r.name !== "item") { state.reviewKeys = null; state.decisions.clear(); }
  if (r.name !== "biblioteca") { state.selecting = false; state.selected.clear(); }
  if (r.name !== "item") { stopAudio(); state.detail = null; }
  if (r.name === "item") openDetail(`${r.args[0]}:${r.args[1]}`);
  render();
  window.scrollTo(0, 0);
});

/* -------------------------- Render -------------------------------------- */
function render() {
  const r = route();
  const view = $("#view");
  const tabs = $("#tabs");
  if (state.status && !state.status.api_key_configured) {
    tabs.classList.add("hidden");
    view.innerHTML = pageWelcome();
    return;
  }
  if (!state.loaded) { view.innerHTML = '<p class="empty">Cargando…</p>'; return; }
  const isTab = ["inicio", "biblioteca", "ajustes"].includes(r.name);
  tabs.classList.toggle("hidden", !isTab);
  for (const a of tabs.querySelectorAll("a")) {
    if (a.dataset.tab === r.name) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
  }
  const page = {
    inicio: pageHome, biblioteca: pageLibrary, ajustes: pageSettings,
    revisar: pageReview, duplicados: pageDups, item: pageDetail, descargas: pageDownloads,
  }[r.name] || pageHome;
  view.innerHTML = page();
  $(".shell").className = "shell" + (isTab ? "" : " no-tabs");
  if (r.name === "biblioteca") bindLibrary();
}

/* ---- Inicio ---- */
function storageBar() {
  const used = state.items.reduce((s, it) => s + (it.size || 0), 0);
  const { account, alert } = limits();
  const scale = Math.max(account ?? alert ?? used, used, 1);
  const over = alert != null && used > alert;
  const colors = { torrent: "var(--accent)", usenet: "color-mix(in srgb, var(--accent) 55%, transparent)", webdl: "color-mix(in srgb, var(--accent) 30%, transparent)" };
  const parts = ["torrent", "usenet", "webdl"].map((k) => [k, state.items.filter((it) => it.kind === k).reduce((s, it) => s + it.size, 0)]).filter(([, s]) => s > 0);
  const usedPct = Math.min(100, (used / scale) * 100);
  let segs = "";
  for (const [k, size] of parts) segs += `<div class="seg" style="width:${(size / scale) * 100}%;background:${over ? "var(--orange)" : colors[k]}"></div>`;
  const end = account ? fmtSize(account) : alert ? fmtSize(alert) : "";
  let mark = "";
  if (account && alert && alert < scale) {
    const x = (alert / scale) * 100;
    // El texto va a la derecha de la marca (o del relleno, si lo pasa); si no cabe, a la izquierda.
    const start = Math.max(x, usedPct);
    const pos = start < 62 ? `left:calc(${start}% + 6px)` : `right:calc(${100 - x}% + 6px)`;
    mark = `<div class="mark" style="left:calc(${x}% - 1px)"></div><span class="lbl" style="${pos}">${fmtSize(alert)}</span>`;
  }
  const [num, unit] = fmtSize(used).split(" ");
  return `
    <section class="card pad storage">
      <div class="row"><span class="grow muted" style="font-size:13px;font-weight:500">Espacio ocupado</span>
        ${state.account?.plan_name ? `<span style="font-size:13px;font-weight:600;color:var(--chip-text)">Plan ${esc(state.account.plan_name)}</span>` : ""}</div>
      <div class="row" style="align-items:baseline;gap:6px;margin-top:6px">
        <span class="big-num">${num}</span><span class="unit">${unit}</span>
        <span class="grow"></span><span class="muted" style="font-size:13px">${state.items.length} elementos</span></div>
      <div class="bar" role="img" aria-label="Ocupado ${fmtSize(used)}${alert ? `, tu límite ${fmtSize(alert)}` : ""}${account ? `, cuenta ${fmtSize(account)}` : ""}">
        ${segs}${mark}${end ? `<span class="lbl end">${end}</span>` : ""}</div>
      <div class="legend">${parts.map(([k, s]) => `<span><span class="dot" style="background:${colors[k]}"></span>${KIND_LABEL[k]} ${fmtSize(s)}</span>`).join("")}</div>
    </section>`;
}

function pageHome() {
  const review = reviewRows();
  const unknown = review.filter((it) => it.audio_status === "unknown").length;
  const sure = review.length - unknown;
  const groups = dupGroups();
  const freeable = groups.reduce((s, g) => s + g.freeable, 0);
  const recent = sortItems(state.items, "date_desc").filter((it) => timeOf(it) !== null).slice(0, 3);
  const reviewSub = review.length
    ? [sure ? `${sure} seguro${sure > 1 ? "s" : ""}` : null, unknown ? `${unknown} por comprobar` : null].filter(Boolean).join(" + ") + ` · hasta ${fmtSize(review.reduce((s, it) => s + it.size, 0))}`
    : "Todo revisado";
  const task = (href, enabled, tile, icon, title, sub, color) => enabled
    ? `<a class="task" href="${href}"><span class="tile ${tile}">${icon}</span><span class="grow"><div class="t">${title}</div><div class="s">${sub}</div></span><span class="act" style="color:${color}">Revisar</span>${ICON.chev}</a>`
    : `<div class="task"><span class="tile ${tile}">${icon}</span><span class="grow"><div class="t">${title}</div><div class="s">${sub}</div></span></div>`;
  return `
    <header class="top">
      <div class="grow"><div class="over">Controltorbox</div><h1>Tu TorBox</h1></div>
      <button class="icon-btn" data-act="reload" aria-label="Recargar">${ICON.refresh}</button>
    </header>
    ${storageBar()}
    ${downloadsCard()}
    <h2 class="section-title">Para limpiar</h2>
    <div class="stack">
      ${task("#/revisar", review.length > 0, "orange", ICON.noaudio, "Sin audio en español", reviewSub, "var(--orange)")}
      ${task("#/duplicados", groups.length > 0, "blue", ICON.copy, "Duplicados", groups.length
        ? `${groups.length} título${groups.length > 1 ? "s" : ""} repetido${groups.length > 1 ? "s" : ""} · libera ${fmtSize(freeable)}` : "No hay títulos repetidos", "var(--accent)")}
      ${(() => { const exp = expiringRows(); return exp.length ? `<a class="task" href="#/biblioteca" data-act="filter-expiring">
        <span class="tile orange">${ICON.clock}</span><span class="grow"><div class="t">Caduca pronto</div>
        <div class="s">${exp.length} elemento${exp.length > 1 ? "s" : ""} · el primero ${expiryLabel(expTime(exp[0]))}</div></span>
        <span class="act" style="color:var(--orange)">Ver</span>${ICON.chev}</a>` : ""; })()}
      <div class="task">
        <span class="tile gray">${ICON.wave}</span>
        <span class="grow"><div class="t">Idioma sin comprobar</div><div class="s">${esc(state.analyzing ||
          (unknown ? `${unknown} elemento${unknown > 1 ? "s" : ""} · el nombre no lo dice` : "Todo comprobado"))}</div></span>
        ${unknown ? `<button class="btn outline" data-act="analyze-unknown" ${state.analyzing ? "disabled" : ""}>Analizar</button>` : ""}
      </div>
    </div>
    ${recent.length ? `<h2 class="section-title">Añadido hace poco</h2>
      <div class="list">${recent.map((it) => `
        <a class="line" href="#/item/${it.kind}/${it.id}">
          <span class="grow"><div class="t ellipsis">${esc(it.title)}</div><div class="small muted">${[relDate(timeOf(it)), fmtSize(it.size)].filter(Boolean).join(" · ")}</div></span>
          ${badge(it)}
        </a>`).join("")}</div>` : ""}`;
}

function downloadsCard() {
  const dl = downloadingRows();
  if (!dl.length && !state.queued.length) return "";
  const stalled = dl.filter((it) => it.stalled).length;
  const active = dl.length - stalled;
  const sub = [active ? `${active} descargando` : null, stalled ? `${stalled} atascada${stalled > 1 ? "s" : ""}` : null,
    state.queued.length ? `${state.queued.length} en cola` : null].filter(Boolean).join(" · ");
  const color = stalled ? "var(--orange)" : "var(--accent)";
  return `<a class="task" href="#/descargas" style="margin-top:14px">
    <span class="tile ${stalled ? "orange" : "blue"}">${ICON.download}</span>
    <span class="grow"><div class="t">Descargas</div><div class="s">${sub}</div></span>
    <span class="act" style="color:${color}">Ver</span>${ICON.chev}</a>`;
}

/* ---- Descargas y cola ---- */
function downloadActions(it) {
  const busy = state.busy.has(keyOf(it)) ? "disabled" : "";
  return [
    it.kind === "torrent" && !it.paused ? `<button class="btn ${it.stalled ? "" : "outline"}" data-act="control" data-key="${keyOf(it)}" data-op="reannounce" ${busy}>Reanunciar</button>` : "",
    it.paused ? `<button class="btn" data-act="control" data-key="${keyOf(it)}" data-op="resume" ${busy}>Reanudar</button>`
      : it.kind === "usenet" ? `<button class="btn outline" data-act="control" data-key="${keyOf(it)}" data-op="pause" ${busy}>Pausar</button>` : "",
  ].join("");
}
function downloadCard(it) {
  return `<article class="review ${it.stalled ? "del" : ""}">
    <a href="#/item/${it.kind}/${it.id}" style="text-decoration:none;color:inherit">
      <div class="t">${esc(it.title)}</div><div class="small muted">${KIND_LABEL[it.kind]} · ${fmtSize(it.size)}</div></a>
    <div class="dl-bar"><div style="width:${Math.round((it.progress || 0) * 100)}%;${it.stalled ? "background:var(--orange)" : ""}"></div></div>
    <div class="small" style="color:${it.stalled ? "var(--orange)" : "var(--chip-text)"}">${esc(downloadLine(it))}</div>
    <div class="row" style="gap:8px;margin-top:10px;flex-wrap:wrap">${downloadActions(it)}
      <button class="btn outline" data-act="delete-key" data-key="${keyOf(it)}">${ICON.trash}Borrar</button></div>
  </article>`;
}
function pageDownloads() {
  const dl = downloadingRows();
  const stalled = dl.filter((it) => it.stalled);
  const active = dl.filter((it) => !it.stalled);
  return `
    <header class="sub-top"><a class="icon-btn plain" href="#/inicio" aria-label="Volver">${ICON.back}</a><h1>Descargas</h1></header>
    <p class="lead">Lo que TorBox está descargando, lo que se ha atascado y lo que está en cola.</p>
    ${!dl.length && !state.queued.length ? '<p class="empty">No hay nada descargándose ni en cola.</p>' : ""}
    ${stalled.length ? `<h2 class="section-title">Atascadas · ${stalled.length}</h2><div class="stack">${stalled.map(downloadCard).join("")}</div>` : ""}
    ${active.length ? `<h2 class="section-title">Descargando · ${active.length}</h2><div class="stack">${active.map(downloadCard).join("")}</div>` : ""}
    ${state.queued.length ? `<h2 class="section-title">En cola · ${state.queued.length}</h2><div class="stack">${state.queued.map((q) => {
      const k = `queued:${q.kind}:${q.id}`, busy = state.busy.has(k) ? "disabled" : "";
      return `<div class="task"><span class="grow"><div class="t">${esc(q.title || q.name)}</div>
        <div class="s">${[KIND_LABEL[q.kind], relDate(parseDate(q.created_at)) ? `en cola ${relDate(parseDate(q.created_at))}` : null].filter(Boolean).join(" · ")}</div></span>
        <button class="btn" data-act="queued" data-kind="${q.kind}" data-id="${q.id}" data-op="start" ${busy}>Iniciar</button>
        <button class="btn outline" data-act="queued" data-kind="${q.kind}" data-id="${q.id}" data-op="delete" ${busy}>Quitar</button></div>`;
    }).join("")}</div>` : ""}`;
}

async function busyAction(key, fn, done) {
  if (state.busy.has(key)) return;
  state.busy.add(key);
  render();
  try { await fn(); toast(done); await load(); } catch (err) { showError(err.message); } finally { state.busy.delete(key); render(); }
}

/* ---- Revisar sin español ---- */
function reviewReason(it) {
  if (it.episode_summary) return it.episode_summary;
  if (it.audio_languages?.length) return "Pistas: " + it.audio_languages.map(langName).join(" · ");
  if (/\bvose?\b/i.test(it.name.replace(/\./g, " "))) return "VOSE: audio original con subtítulos";
  if (it.duplicate_group) {
    const other = state.items.find((o) => o.duplicate_group === it.duplicate_group && keyOf(o) !== keyOf(it) && o.audio_status === "es");
    if (other) return `Tienes otra copia${other.quality ? ` en ${other.quality}` : ""} con español`;
  }
  return "El nombre no indica el idioma";
}

function pageReview() {
  if (!state.reviewKeys) state.reviewKeys = reviewRows().map(keyOf);
  const rows = state.reviewKeys.map(byKey).filter(Boolean);
  const del = rows.filter((it) => state.decisions.get(keyOf(it)) === "del");
  const kept = rows.filter((it) => state.decisions.get(keyOf(it)) === "keep").length;
  const pending = rows.length - del.length - kept;
  const title = del.length ? `${del.length} para borrar` : kept ? `${kept} para conservar` : "Nada decidido aún";
  const sub = [del.length ? `Liberarás ${fmtSize(del.reduce((s, it) => s + it.size, 0))}` : null,
    kept ? `${kept} conservado${kept > 1 ? "s" : ""}` : null, pending ? `${pending} pendiente${pending > 1 ? "s" : ""}` : null].filter(Boolean).join(" · ");
  return `
    <header class="sub-top"><a class="icon-btn plain" href="#/inicio" aria-label="Volver">${ICON.back}</a><h1>Sin audio en español</h1></header>
    <p class="lead">Escucha si dudas y decide. <strong>No se borra nada</strong> hasta que pulses el botón de abajo.</p>
    <div class="stack">${rows.length ? rows.map((it) => {
      const d = state.decisions.get(keyOf(it));
      return `
      <article class="review ${d === "del" ? "del" : d === "keep" ? "keep" : ""}">
        <div class="row" style="align-items:flex-start">
          <div class="grow"><div class="t">${esc(it.title)}</div><div class="small muted ellipsis">${esc(it.name)} · ${fmtSize(it.size)}</div></div>
          ${badge(it)}
        </div>
        <div class="small" style="margin-top:8px;color:var(--chip-text)">${esc(reviewReason(it))}</div>
        <div class="three">
          <a class="btn outline" href="#/item/${it.kind}/${it.id}">${ICON.play}Escuchar</a>
          <button class="btn ${d === "keep" ? "green" : "outline"}" data-act="decide" data-key="${keyOf(it)}" data-d="keep" aria-pressed="${d === "keep"}">${d === "keep" ? ICON.check : ""}Conservar</button>
          <button class="btn ${d === "del" ? "orange" : "outline"}" data-act="decide" data-key="${keyOf(it)}" data-d="del" aria-pressed="${d === "del"}">${d === "del" ? ICON.check : ""}Borrar</button>
        </div>
      </article>`;
    }).join("") : '<p class="empty">No queda nada por revisar.</p>'}</div>
    ${rows.length ? `<div class="actionbar"><div>
      <div class="grow"><div class="t">${title}</div><div class="small muted">${sub}</div></div>
      <button class="btn orange big" data-act="apply-review" ${del.length || kept ? "" : "disabled"}>${del.length ? `Borrar ${del.length}` : "Guardar"}</button>
    </div></div>` : ""}`;
}

async function applyReview() {
  const keep = [...state.decisions].filter(([, d]) => d === "keep").map(([k]) => k);
  const del = [...state.decisions].filter(([, d]) => d === "del").map(([k]) => k);
  if (del.length && !(await confirmDelete(del.map(byKey).filter(Boolean)))) return;
  try {
    for (const k of keep) { const it = byKey(k); if (it) await api(`/api/items/${it.kind}/${it.id}/kept`, { kept: true }, "PUT"); }
  } catch (err) { showError(err.message); return; }
  go("#/inicio");
  if (del.length) await deleteKeys(del);
  else { await load(); toast(`Guardado: ${keep.length} conservado(s).`); }
}

/* ---- Duplicados ---- */
function copyTitle(it) {
  const m = it.title.match(/ · T(\d+)(?: E(\d+))?/);
  const what = m ? (m[2] ? `Episodio ${m[2]} suelto` : "Temporada completa") : null;
  return [what, it.quality || null, KIND_LABEL[it.kind]].filter(Boolean).join(" · ");
}
function copyDetail(it) {
  const langs = [...new Set((it.audio_languages || []).map(langName))];
  const lang = langs.length ? langs.join(" + ") : { es: "Con español", no_es: "Sin español" }[it.audio_status] || "idioma sin comprobar";
  return `${fmtSize(it.size)} · ${it.video_count > 1 ? `${it.video_count} episodios · ` : ""}${lang}`;
}
function pageDups() {
  const groups = dupGroups();
  const del = groups.flatMap((g) => g.toDelete);
  return `
    <header class="sub-top"><a class="icon-btn plain" href="#/inicio" aria-label="Volver">${ICON.back}</a><h1>Duplicados</h1></header>
    <p class="lead">Elige qué copia conservar. Te sugerimos la que tiene español o, si empatan, la más grande.</p>
    <div class="stack" style="gap:14px">${groups.length ? groups.map((g) => `
      <section class="group" role="radiogroup" aria-label="${esc(g.label)}">
        <div class="head"><h2>${esc(g.label)}</h2><span class="small muted">${g.members.length} copias · ${fmtSize(g.members.reduce((s, m) => s + m.size, 0))}</span></div>
        ${g.members.map((m) => { const keep = keyOf(m) === g.keep; return `
          <button class="copy ${keep ? "keep" : ""}" role="radio" aria-checked="${keep}" data-act="keep-copy" data-group="${esc(g.id)}" data-key="${keyOf(m)}">
            <span class="radio"></span>
            <span class="grow"><div style="font-size:14px;font-weight:600">${esc(copyTitle(m))}</div><div class="small muted">${esc(copyDetail(m))}</div></span>
            ${keep ? '<span class="state" style="color:var(--green)">Conservar</span>' : `<span class="state" style="color:var(--orange)">${ICON.trash}Se borrará</span>`}
          </button>`; }).join("")}
      </section>`).join("") : '<p class="empty">No hay duplicados.</p>'}
      ${groups.length ? `<div class="info">${ICON.info}<span>Toca otra copia para conservarla en su lugar. Se detectan por título y año; si una tiene el título en español y otra en inglés, no se emparejan.</span></div>` : ""}
    </div>
    ${groups.length ? `<div class="actionbar"><div>
      <div class="grow"><div class="t">Liberar ${fmtSize(del.reduce((s, m) => s + m.size, 0))}</div>
      <div class="small muted">Se borrarán ${del.length} copia${del.length !== 1 ? "s" : ""}, se conservan ${groups.length}</div></div>
      <button class="btn orange big" data-act="delete-dups" ${del.length ? "" : "disabled"}>Borrar ${del.length}</button>
    </div></div>` : ""}`;
}

/* ---- Biblioteca ---- */
function libraryLine(it) {
  const sel = state.selected.has(keyOf(it));
  const exp = isSoon(expTime(it)) ? expTime(it) : null;
  const meta = [exp ? cap(expiryLabel(exp)) : null, it.in_progress ? (it.stalled ? "Atascada" : `${Math.round(it.progress * 100)} % descargado`) : null,
    it.quality || (it.files > 1 ? `${it.files} archivos` : null), fmtSize(it.size), exp ? null : shortDate(timeOf(it))].filter(Boolean).join(" · ");
  const warn = exp || it.stalled;
  const inner = `
    ${state.selecting ? `<input type="checkbox" ${sel ? "checked" : ""} aria-hidden="true" tabindex="-1">` : `<span class="tile gray small">${isSeries(it) ? ICON.tv : ICON.movie}</span>`}
    <span class="grow"><div class="t ellipsis">${esc(it.title)}</div><div class="small ellipsis ${warn ? "" : "muted"}" ${warn ? 'style="color:var(--orange)"' : ""}>${esc(meta)}</div></span>
    <span class="end">${badge(it)}${it.duplicate_group ? '<span class="dup">Duplicado</span>' : ""}</span>`;
  return state.selecting
    ? `<div class="line ${sel ? "selected" : ""}" role="checkbox" aria-checked="${sel}" aria-label="${esc(it.title)}" tabindex="0" data-act="toggle-sel" data-key="${keyOf(it)}">${inner}</div>`
    : `<a class="line" href="#/item/${it.kind}/${it.id}">${inner}</a>`;
}
function libraryList() {
  const rows = libraryRows();
  return `
    <div class="toolbar">
      <span>${rows.length} elementos · ${fmtSize(rows.reduce((s, it) => s + it.size, 0))}</span>
      <span class="row" style="gap:4px">
        <label class="small muted" for="sort">Orden</label>
        <select id="sort" data-act="sort">${Object.entries(SORTS).map(([k, v]) => `<option value="${k}" ${k === prefs.sort ? "selected" : ""}>${v}</option>`).join("")}</select>
        <button class="link" data-act="selecting">${state.selecting ? "Cancelar" : "Seleccionar"}</button>
      </span>
    </div>
    ${rows.length ? `<div class="list">${rows.map(libraryLine).join("")}</div>` : '<p class="empty">No hay nada con estos filtros.</p>'}`;
}
function pageLibrary() {
  const sel = state.items.filter((it) => state.selected.has(keyOf(it)));
  return `
    <header class="top"><h1>${state.selecting ? `${sel.length} seleccionados` : "Biblioteca"}</h1></header>
    <label class="search">${ICON.search}<span class="hidden">Buscar</span>
      <input id="search" type="search" placeholder="Buscar película o serie" value="${esc(state.search)}" autocomplete="off"></label>
    <div class="chips" role="group" aria-label="Filtros">${[...FILTERS].sort(([a], [b]) => (b === state.filter && b === "expiring") - (a === state.filter && a === "expiring")).map(([k, label]) => {
      const n = state.items.filter((it) => matchFilter(it, k)).length;
      if (k === "expiring" && !n && state.filter !== k) return "";
      return `<button class="chip" data-act="filter" data-f="${k}" aria-pressed="${state.filter === k}">${label}${k === "dups" ? "" : ` · ${n}`}</button>`;
    }).join("")}</div>
    <div id="lib-list">${libraryList()}</div>
    ${state.selecting && sel.length ? `<div class="selbar"><div>
      <div class="grow"><div style="font-size:14px;font-weight:700">${sel.length} seleccionados</div><div class="small muted">${fmtSize(sel.reduce((s, it) => s + it.size, 0))}</div></div>
      <button class="btn orange" data-act="delete-selected">${ICON.trash}Borrar</button>
    </div></div>` : ""}`;
}
function bindLibrary() {
  const input = $("#search");
  if (!input) return;
  input.addEventListener("input", () => { state.search = input.value; $("#lib-list").innerHTML = libraryList(); });
}

/* ---- Ficha ---- */
function openDetail(key) {
  if (state.detail?.key === key) return;
  stopAudio();
  state.detail = { key, info: null, loading: false, loadingText: null, error: null, ep: null, position: 0.15, playing: -1, clipStart: 0, current: 0, showFiles: false };
  const it = byKey(key);
  if (it?.analyzed) loadTracks(false, true);
}

async function loadTracks(refresh, quiet = false) {
  const d = state.detail;
  const it = d && byKey(d.key);
  if (!it) return;
  d.loading = !quiet; d.error = null;
  if (!quiet && it.video_count > 1) d.loadingText = `Leyendo los ${it.video_count} episodios…`;
  render();
  try {
    d.info = await api(`/api/items/${it.kind}/${it.id}/tracks${refresh ? "?refresh=true" : ""}`);
    if (!quiet) await load();
  } catch (err) {
    d.error = err.message;
  } finally {
    d.loading = false; d.loadingText = null;
    render();
  }
}

function currentEpisode() {
  const eps = state.detail?.info?.episodes || [];
  return eps.find((e) => e.file_id === state.detail.ep) || eps[0] || null;
}

function trackRow(t, i, playable) {
  const d = state.detail;
  const on = d.playing === i;
  const ch = { 1: "mono", 2: "2.0", 6: "5.1", 8: "7.1" }[t.channels] || (t.channels ? `${t.channels} canales` : null);
  const detail = [String(t.codec || "").toUpperCase() || null, ch, t.title ? `«${t.title}»` : null].filter(Boolean).join(" · ");
  const prog = on ? Math.min(100, ((d.current - d.clipStart) / 30) * 100) : 0;
  return `
    <div class="track ${on ? "playing" : isEsCode(t.language) ? "es" : ""}">
      <div class="row">
        <div class="grow"><div class="t">${esc(langName(t.language))}${on ? '<span class="now">Sonando</span>' : ""}</div><div class="small muted">${esc(detail)}</div></div>
        <button class="play ${on ? "on" : ""}" data-act="play" data-i="${i}" aria-label="${on ? "Parar" : `Escuchar ${esc(langName(t.language))}`}" ${playable ? "" : "disabled"}>${playable ? (on ? ICON.pause : ICON.play) : ICON.muted}</button>
      </div>
      ${on ? `<div class="progress"><span>${fmtTime(d.current)}</span><div class="p"><div style="width:${prog}%"></div></div><span>${fmtTime(d.clipStart + 30)}</span></div>` : ""}
    </div>`;
}

function pageDetail() {
  const d = state.detail;
  const it = d && byKey(d.key);
  if (!it) return `<header class="sub-top"><a class="icon-btn plain" href="#/biblioteca" aria-label="Volver">${ICON.back}</a><h1>No encontrado</h1></header>`;
  const info = d.info;
  const eps = info?.episodes || [];
  const ep = currentEpisode();
  const tracks = ep ? ep.tracks : info?.tracks;
  const duration = ep ? ep.duration : info?.duration;
  const canPlay = info?.can_play !== false;
  const statusSub = it.audio_source === "manual" ? "Lo marcaste tú"
    : it.audio_source === "ffprobe" ? (it.episode_summary ? `Comprobado episodio a episodio: ${it.episode_summary.charAt(0).toLowerCase()}${it.episode_summary.slice(1)}` : "Comprobado leyendo las pistas del archivo")
    : it.audio_status === "unknown" ? "El nombre no lo dice: lee las pistas o escúchalo" : "Según el nombre del archivo";
  const bannerTitle = { es: "Tiene audio en español", no_es: "No tiene audio en español", unknown: "Idioma sin comprobar" }[it.audio_status];
  const bannerIcon = { es: ICON.check, no_es: ICON.noaudio, unknown: ICON.wave }[it.audio_status];

  let tracksHtml;
  if (!it.has_video) {
    tracksHtml = `<p class="small muted">${it.files ? `Ninguno de sus ${it.files} archivos es un vídeo que se pueda leer.` : "TorBox no ha enviado la lista de archivos de este elemento."}</p>`;
  } else if (d.loading) {
    tracksHtml = `<div class="row" style="margin-top:12px"><span class="spinner"></span><span class="small" style="color:var(--chip-text)">${esc(d.loadingText || "Leyendo el archivo…")}</span></div>`;
  } else if (!info) {
    tracksHtml = `<div class="stack" style="margin-top:10px">
      <p class="small" style="margin:0;color:var(--chip-text)">${it.video_count > 1 ? "Lee las pistas de cada episodio para saber si todos tienen español." : "Lee las pistas del archivo para saber qué idiomas tiene y escucharlas."}</p>
      <div><button class="btn" data-act="load-tracks">${ICON.wave}${it.video_count > 1 ? `Leer los ${it.video_count} episodios` : "Leer pistas de audio"}</button></div></div>`;
  } else {
    tracksHtml = `
      ${!eps.length && it.video_count > 1 ? `<div class="callout"><span>Solo se comprobó un episodio. Comprueba los ${it.video_count} para saber si todos tienen español.</span>
        <button class="btn" data-act="refresh-tracks">${ICON.wave}Comprobar los ${it.video_count} episodios</button></div>` : ""}
      ${eps.length ? `<div class="eps" role="tablist" aria-label="Episodios">${eps.map((e) => `
        <button class="ep" role="tab" data-act="ep" data-id="${e.file_id}" aria-pressed="${!!ep && e.file_id === ep.file_id}" aria-label="${esc(e.label)}: ${STATUS_LABEL[e.status] || ""}">
          <span class="d" style="background:${{ es: "var(--green)", no_es: "var(--orange)" }[e.status] || "var(--faint)"}"></span>${esc(e.label)}</button>`).join("")}</div>` : ""}
      ${ep?.error ? `<p class="error">No se pudo leer este episodio: ${esc(ep.error)}</p>` : ""}
      <div class="pos"><label for="pos">Escuchar desde</label>
        <strong>${duration ? fmtTime(duration * d.position) : `${Math.round(d.position * 100)} %`}</strong>
        <input id="pos" type="range" min="0" max="95" value="${Math.round(d.position * 100)}" data-act="pos">
        ${duration ? `<span>${fmtTime(duration)}</span>` : ""}</div>
      <div class="stack" style="margin-top:8px;gap:8px">${(tracks || []).map((t, i) => trackRow(t, i, canPlay)).join("") || '<p class="small muted">No se encontraron pistas de audio.</p>'}</div>
      <button class="link" data-act="refresh-tracks">Volver a leer las pistas</button>`;
  }
  const manual = it.audio_source === "manual" ? it.audio_status : null;
  const meta = [it.quality || null, KIND_LABEL[it.kind], fmtSize(it.size), shortDate(timeOf(it)) ? `añadido el ${shortDate(timeOf(it))}` : null].filter(Boolean).join(" · ");
  return `
    <header class="sub-top"><button class="icon-btn plain" data-act="back" aria-label="Volver">${ICON.back}</button><span class="grow"></span></header>
    <div style="padding:0 4px 14px">
      <h1 style="margin:0;font-size:24px;font-weight:700;letter-spacing:-0.02em">${esc(it.title)}</h1>
      <div class="muted" style="font-size:13px;margin-top:4px">${esc(meta)}</div>
      <div class="small muted" style="margin-top:2px">${esc(it.name)}</div>
      ${it.duplicate_group ? `<div class="small" style="margin-top:6px;color:var(--accent);font-weight:600">Tienes otra copia de «${esc(it.duplicate_label)}»</div>` : ""}
      ${expTime(it) != null ? `<div class="small" style="margin-top:6px;${isSoon(expTime(it)) ? "color:var(--orange);font-weight:600" : "color:var(--muted)"}">TorBox lo quitará el ${shortDate(expTime(it))} · ${expiryLabel(expTime(it))}</div>` : ""}
    </div>
    <div class="stack" style="gap:14px">
      ${it.in_progress ? `<section class="card pad" ${it.stalled ? 'style="border:2px solid var(--orange)"' : ""}>
        <h2 style="margin:0;font-size:16px;${it.stalled ? "color:var(--orange)" : ""}">${it.stalled ? "Descarga atascada" : it.paused ? "Descarga en pausa" : "Descargando"}</h2>
        <div class="dl-bar"><div style="width:${Math.round(it.progress * 100)}%;${it.stalled ? "background:var(--orange)" : ""}"></div></div>
        <div class="small" style="color:var(--chip-text)">${esc(downloadLine(it))}</div>
        ${it.stalled ? '<p class="small muted">No encuentra fuentes. Reanunciar pide a TorBox que las busque otra vez; si sigue igual, quizá convenga borrarla.</p>' : ""}
        <div class="row" style="gap:8px;margin-top:10px">${downloadActions(it)}</div></section>` : ""}
      <div class="banner ${it.audio_status}">${bannerIcon}<div><div class="t">${bannerTitle}</div><div class="s">${esc(statusSub)}</div></div></div>
      <section class="card pad">
        <div class="row" style="align-items:baseline"><h2 class="grow" style="margin:0;font-size:16px">Pistas de audio</h2>
          ${tracks ? `<span class="small muted">${tracks.length} pista${tracks.length !== 1 ? "s" : ""}</span>` : ""}</div>
        ${tracksHtml}
        ${d.error ? `<p class="error">${esc(d.error)}</p>` : ""}
      </section>
      <section class="card pad">
        <div style="font-size:14px;font-weight:600">¿Es correcto?</div>
        <div class="seg3" role="radiogroup" aria-label="Idioma del audio">
          <button class="es" data-act="manual" data-s="es" aria-pressed="${manual === "es"}">Con español</button>
          <button class="no_es" data-act="manual" data-s="no_es" aria-pressed="${manual === "no_es"}">Sin español</button>
          <button class="auto" data-act="manual" data-s="" aria-pressed="${!manual}">Automático</button>
        </div>
      </section>
      ${it.files > 1 ? `<section class="card pad">
        <button class="link" data-act="files">${d.showFiles ? "Ocultar" : "Ver"} los ${it.files} archivos</button>
        ${d.showFiles ? `<div class="files">${it.file_list.map((f) => `<div><span>${esc(f.name)}</span><span class="muted">${fmtSize(f.size)}</span></div>`).join("")}
          <p class="small muted">TorBox no permite borrar archivos sueltos: se borra el elemento completo.</p></div>` : ""}
      </section>` : ""}
    </div>
    <div class="actionbar" style="background:var(--bg);border-top:0"><div>
      <button class="btn danger-outline" data-act="delete-one">${ICON.trash}Borrar de TorBox · ${fmtSize(it.size)}</button>
    </div></div>`;
}

/* ---- Audio ---- */
const audio = $("#audio");
let audioUrl = null;
function stopAudio() {
  audio.pause();
  if (audioUrl) { URL.revokeObjectURL(audioUrl); audioUrl = null; }
  audio.removeAttribute("src");
  if (state.detail) state.detail.playing = -1;
}
audio.addEventListener("timeupdate", () => {
  const d = state.detail;
  if (!d || d.playing < 0) return;
  d.current = d.clipStart + audio.currentTime;
  const p = document.querySelector(".track.playing .progress");
  if (p) {
    p.firstElementChild.textContent = fmtTime(d.current);
    p.querySelector(".p > div").style.width = `${Math.min(100, (audio.currentTime / 30) * 100)}%`;
  }
});
audio.addEventListener("ended", () => { stopAudio(); render(); });

async function playTrack(i) {
  const d = state.detail;
  const it = byKey(d.key);
  if (d.playing === i) { stopAudio(); render(); return; }
  stopAudio();
  const ep = currentEpisode();
  const duration = (ep ? ep.duration : d.info?.duration) || 3600;
  const start = Math.floor(duration * d.position);
  const params = new URLSearchParams({ track: i, start, duration: 30 });
  if (ep) params.set("file_id", ep.file_id);
  d.error = null;
  const btn = document.querySelector(`[data-act="play"][data-i="${i}"]`);
  if (btn) btn.innerHTML = '<span class="spinner"></span>';
  try {
    const res = await authFetch(`/api/items/${it.kind}/${it.id}/clip?${params}`);
    if (!res.ok) await parseResponse(res);
    audioUrl = URL.createObjectURL(await res.blob());
    audio.src = audioUrl;
    await audio.play();
    d.playing = i; d.clipStart = start; d.current = start;
  } catch (err) {
    d.error = err.message;
  }
  render();
}

/* ---- Ajustes ---- */
function stepper(act, label) {
  return `<div class="stepper"><button data-act="${act}" data-dir="-1" aria-label="Menos">−</button><span>${label}</span><button data-act="${act}" data-dir="1" aria-label="Más">+</button></div>`;
}
function pageSettings() {
  const st = state.status || {};
  const { account, fromTorbox } = limits();
  const used = state.items.reduce((s, it) => s + it.size, 0);
  const plan = state.account?.plan_name;
  return `
    <header class="top"><h1>Ajustes</h1></header>
    <h2 class="group-title">Cuenta de TorBox</h2>
    <section class="card" style="padding:0 16px">
      <div class="setting">
        <span class="tile green small">${ICON.check}</span>
        <div class="grow"><div class="t" style="font-weight:600">Conectado</div><div class="small muted">Token ${esc(st.api_key_masked || "••••••••")}${st.api_key_source === "env" ? " (desde .env)" : ""}</div></div>
        <button class="btn outline" data-act="change-token">Cambiar</button>
      </div>
      <div class="setting">
        <div class="grow"><div class="t">Almacenamiento de la cuenta</div>
          <div class="small muted">${fromTorbox ? `Leído de TorBox${plan ? ` · plan ${esc(plan)}` : ""}` : `${plan ? `Plan ${esc(plan)} · ` : ""}TorBox no lo indica: ponlo tú`}</div></div>
        ${fromTorbox ? `<strong>${fmtSize(account)}</strong>` : stepper("account-gb", prefs.accountGb ? fmtSize(prefs.accountGb * GB) : "—")}
      </div>
    </section>
    <h2 class="group-title">Tu límite de espacio</h2>
    <section class="card" style="padding:0 16px">
      <div class="setting">
        <div class="grow"><div class="t">Marcar en la barra</div><div class="small muted">${prefs.alertOn ? `Ahora ocupas ${fmtSize(used)}` : "Desactivado"}</div></div>
        ${prefs.alertOn ? stepper("alert-gb", fmtSize(prefs.alertGb * GB)) : ""}
        <label class="switch"><input type="checkbox" data-act="alert-on" ${prefs.alertOn ? "checked" : ""} aria-label="Marcar tu límite en la barra"><span></span></label>
      </div>
    </section>
    <p class="small muted" style="margin:8px 4px 0">Los avisos diarios con notificaciones están en la app Android.</p>
    <h2 class="group-title">Aplicación</h2>
    <section class="card" style="padding:0 16px">
      <div class="setting"><div class="grow"><div class="t">Versión ${esc(window.APP_VERSION || st.version || "")}</div>
        <div class="small muted">Se actualiza con «git pull» y reiniciando la app</div></div></div>
      <div class="setting"><div class="grow"><div class="t">Análisis y escucha del audio</div>
        <div class="small muted">${st.ffprobe ? "ffmpeg disponible" : "No se encuentra ffmpeg: ejecuta «pip install -r requirements.txt»"}</div></div></div>
    </section>`;
}

async function changeToken() {
  const v = await dialog(`<h2>Cambiar token</h2>
    <label class="field"><input id="new-token" type="password" placeholder="Nuevo API token" autocomplete="off" aria-label="Nuevo API token"></label>
    <p class="small muted">Se guarda solo en este navegador.</p>`,
  [{ value: "logout", label: "Desconectar", cls: "outline" }, { value: "cancel", label: "Cancelar" }, { value: "save", label: "Guardar", cls: "" }]);
  if (v === "logout") {
    setToken(null);
    try { await authFetch("/api/settings/api_key", { method: "DELETE" }); } catch (_) { /* nada */ }
    state.items = []; state.account = null;
    return load();
  }
  if (v !== "save") return;
  const t = ($("#new-token")?.value || "").trim();
  if (!t) return;
  try {
    await api("/api/settings", { api_key: t }, "PUT");
    setToken(t);
    await load();
    toast("Token guardado en este navegador.");
  } catch (err) {
    showError(`El token no es válido: ${err.message}`);
  }
}

/* ---- Bienvenida ---- */
function pageWelcome() {
  return `<div class="welcome">
    <span class="tile blue" style="width:56px;height:56px">${ICON.library}</span>
    <h1 style="margin:0;font-size:28px">Controltorbox</h1>
    <p class="muted" style="margin:0;font-size:15px">Encuentra lo que no tiene audio en español y los duplicados de tu TorBox, y libera espacio.</p>
    <label class="field"><input id="welcome-token" type="password" placeholder="API token de TorBox" autocomplete="off" aria-label="API token de TorBox"></label>
    <p class="small muted" style="margin:0">Lo encontrarás en torbox.app → Settings → API Key. Se guarda solo en este navegador.</p>
    <p id="welcome-error" class="error"></p>
    <button class="btn big" data-act="connect">Conectar</button>
  </div>`;
}
async function connect(btn) {
  const t = ($("#welcome-token")?.value || "").trim();
  if (!t) return;
  btn.disabled = true; btn.textContent = "Comprobando…";
  try {
    await api("/api/settings", { api_key: t }, "PUT");
    setToken(t);
    go("#/inicio");
    await load();
  } catch (err) {
    $("#welcome-error").textContent = `El token no es válido: ${err.message}`;
    btn.disabled = false; btn.textContent = "Conectar";
  }
}

/* -------------------------- Eventos ------------------------------------- */
document.addEventListener("click", async (e) => {
  const el = e.target.closest("[data-act]");
  if (!el || el.tagName === "SELECT" || el.tagName === "INPUT") return;
  if (el.closest("dialog")) return;
  const act = el.dataset.act;
  const d = state.detail;
  switch (act) {
    case "reload": return load();
    case "back": return history.length > 1 ? history.back() : go("#/biblioteca");
    case "analyze-unknown": return analyzeUnknown();
    case "decide": {
      const cur = state.decisions.get(el.dataset.key);
      if (cur === el.dataset.d) state.decisions.delete(el.dataset.key); else state.decisions.set(el.dataset.key, el.dataset.d);
      return render();
    }
    case "apply-review": return applyReview();
    case "keep-copy": state.dupKeep[el.dataset.group] = el.dataset.key; return render();
    case "delete-dups": {
      const keys = dupGroups().flatMap((g) => g.toDelete.map(keyOf));
      if (await confirmDelete(keys.map(byKey))) { state.dupKeep = {}; go("#/inicio"); await deleteKeys(keys); }
      return;
    }
    case "filter": state.filter = el.dataset.f; return render();
    case "selecting": state.selecting = !state.selecting; state.selected.clear(); return render();
    case "toggle-sel": {
      const k = el.dataset.key;
      if (state.selected.has(k)) state.selected.delete(k); else state.selected.add(k);
      return render();
    }
    case "delete-selected": {
      const rows = [...state.selected].map(byKey).filter(Boolean);
      if (await confirmDelete(rows)) { state.selecting = false; await deleteKeys(rows.map(keyOf)); }
      return;
    }
    case "load-tracks": return loadTracks(false);
    case "refresh-tracks": stopAudio(); return loadTracks(true);
    case "ep": stopAudio(); d.ep = Number(el.dataset.id); return render();
    case "play": return playTrack(Number(el.dataset.i));
    case "files": d.showFiles = !d.showFiles; return render();
    case "manual": {
      const it = byKey(d.key);
      try { await api(`/api/items/${it.kind}/${it.id}/audio_status`, { status: el.dataset.s || null }, "PUT"); await load(); }
      catch (err) { showError(err.message); }
      return;
    }
    case "delete-one": {
      const it = byKey(d.key);
      if (await confirmDelete([it])) { stopAudio(); go("#/biblioteca"); await deleteKeys([keyOf(it)]); }
      return;
    }
    case "change-token": return changeToken();
    case "filter-expiring": state.filter = "expiring"; return;
    case "control": {
      const it = byKey(el.dataset.key);
      const done = { reannounce: "Reanunciado: TorBox buscará fuentes de nuevo.", pause: "Pausado.", resume: "Reanudado." }[el.dataset.op];
      return busyAction(keyOf(it), () => api(`/api/items/${it.kind}/${it.id}/control`, { operation: el.dataset.op }), done);
    }
    case "queued": {
      const k = `queued:${el.dataset.kind}:${el.dataset.id}`;
      return busyAction(k, () => api(`/api/queued/${el.dataset.kind}/${el.dataset.id}`, { operation: el.dataset.op }),
        el.dataset.op === "start" ? "Iniciado: pasa a descargarse." : "Quitado de la cola.");
    }
    case "delete-key": {
      const it = byKey(el.dataset.key);
      if (it && await confirmDelete([it])) await deleteKeys([keyOf(it)]);
      return;
    }
    case "account-gb": {
      const v = prefs.accountGb, dir = Number(el.dataset.dir);
      const step = (dir > 0 ? v >= 1000 : v > 1000) ? 500 : 100;
      prefs.accountGb = Math.max(0, v + dir * step);
      return render();
    }
    case "alert-gb": {
      const v = prefs.alertGb, dir = Number(el.dataset.dir);
      const step = (dir > 0 ? v >= 1000 : v > 1000) ? 250 : 50;
      prefs.alertGb = Math.max(50, v + dir * step);
      return render();
    }
    case "connect": return connect(el);
  }
});
document.addEventListener("change", (e) => {
  const el = e.target;
  if (el.dataset.act === "sort") { prefs.sort = el.value; $("#lib-list").innerHTML = libraryList(); }
  if (el.dataset.act === "alert-on") { prefs.alertOn = el.checked; render(); }
  if (el.dataset.act === "pos" && state.detail) {
    state.detail.position = Number(el.value) / 100;
    const wasPlaying = state.detail.playing;
    stopAudio();
    if (wasPlaying >= 0) playTrack(wasPlaying); else render();
  }
});
document.addEventListener("keydown", (e) => {
  if ((e.key === "Enter" || e.key === " ") && e.target.matches('[data-act="toggle-sel"]')) { e.preventDefault(); e.target.click(); }
  if (e.key === "Enter" && e.target.id === "welcome-token") $('[data-act="connect"]').click();
});

/* -------------------------- Arranque ------------------------------------ */
(async () => {
  render();
  await load();
  const r = route();
  if (r.name === "item") { openDetail(`${r.args[0]}:${r.args[1]}`); render(); }
})();
