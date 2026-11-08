"use strict";

const state = { items: [], selected: new Set(), ffprobe: false };
const $ = (id) => document.getElementById(id);
const keyOf = (it) => `${it.kind}:${it.id}`;

const KIND_LABEL = { torrent: "Torrent", usenet: "Usenet", webdl: "Web" };
const AUDIO_LABEL = { es: "Español", no_es: "Sin español", unknown: "Desconocido" };

function fmtSize(bytes) {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, n = bytes;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${units[i]}`;
}

function banner(msg, kind = "error") {
  const el = $("banner");
  if (!msg) { el.className = "banner hidden"; return; }
  el.textContent = msg;
  el.className = `banner ${kind}`;
}

async function api(path, body) {
  const opts = body
    ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
    : {};
  const res = await fetch(path, opts);
  return parseResponse(res);
}

async function parseResponse(res) {
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (_) { data = { detail: text.slice(0, 300) }; }
  if (!res.ok) {
    let detail = data.detail;
    if (Array.isArray(detail)) detail = detail.map((d) => d.msg).join("; ");
    throw new Error(detail ? `${detail} (HTTP ${res.status})` : `Error HTTP ${res.status}`);
  }
  return data;
}

function visibleItems() {
  const q = $("search").value.trim().toLowerCase();
  const audio = $("filter-audio").value;
  const kind = $("filter-kind").value;
  const onlyVideo = $("only-video").checked;
  return state.items.filter((it) => {
    if (q && !it.name.toLowerCase().includes(q)) return false;
    if (kind !== "all" && it.kind !== kind) return false;
    if (onlyVideo && !it.has_video) return false;
    if (audio === "not_es") return it.audio_status !== "es";
    if (audio !== "all" && it.audio_status !== audio) return false;
    return true;
  });
}

function render() {
  const items = visibleItems();
  const tbody = $("rows");
  tbody.textContent = "";

  if (!items.length) {
    const tr = document.createElement("tr");
    tr.innerHTML = '<td colspan="5" class="empty">No hay elementos con estos filtros.</td>';
    tbody.appendChild(tr);
  }

  for (const it of items) {
    const tr = document.createElement("tr");
    const k = keyOf(it);
    if (state.selected.has(k)) tr.classList.add("selected");

    const tdCheck = document.createElement("td");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = state.selected.has(k);
    cb.addEventListener("change", () => {
      cb.checked ? state.selected.add(k) : state.selected.delete(k);
      tr.classList.toggle("selected", cb.checked);
      updateSelection();
    });
    tdCheck.appendChild(cb);

    const tdName = document.createElement("td");
    tdName.className = "name";
    tdName.textContent = it.name;
    if (it.main_file && it.main_file !== it.name) {
      const small = document.createElement("small");
      small.textContent = it.main_file;
      tdName.appendChild(small);
    }

    const tdKind = document.createElement("td");
    tdKind.textContent = KIND_LABEL[it.kind] || it.kind;

    const tdSize = document.createElement("td");
    tdSize.className = "num";
    tdSize.textContent = fmtSize(it.size);

    const tdAudio = document.createElement("td");
    const badge = document.createElement("span");
    badge.className = `badge ${it.audio_status}`;
    badge.textContent = AUDIO_LABEL[it.audio_status] || it.audio_status;
    badge.title = it.audio_source === "ffprobe"
      ? `Analizado: ${it.audio_languages.join(", ") || "sin pistas"}`
      : "Estimado por el nombre del archivo";
    tdAudio.appendChild(badge);
    const src = document.createElement("small");
    src.textContent = it.audio_source === "ffprobe"
      ? (it.audio_languages.join(", ") || "sin etiquetas")
      : "por nombre";
    if (it.analysis_error) src.textContent += ` · ${it.analysis_error}`;
    tdAudio.appendChild(src);

    tr.append(tdCheck, tdName, tdKind, tdSize, tdAudio);
    tbody.appendChild(tr);
  }

  const allSel = items.length > 0 && items.every((it) => state.selected.has(keyOf(it)));
  $("select-all").checked = allSel;
  updateSelection();
}

function selectedItems() {
  return state.items.filter((it) => state.selected.has(keyOf(it)));
}

function updateSelection() {
  const sel = selectedItems();
  const size = sel.reduce((a, it) => a + (it.size || 0), 0);
  $("selection-info").textContent = sel.length
    ? `${sel.length} seleccionados · ${fmtSize(size)} a liberar`
    : "0 seleccionados";
  $("btn-delete").disabled = sel.length === 0;
  $("btn-analyze").disabled = sel.length === 0 || !state.ffprobe;
}

function updateStats() {
  const total = state.items.reduce((a, it) => a + (it.size || 0), 0);
  $("stat-total").textContent = state.items.length;
  $("stat-size").textContent = fmtSize(total);
  $("stat-noes").textContent = state.items.filter((i) => i.audio_status === "no_es").length;
  $("stat-unknown").textContent = state.items.filter((i) => i.audio_status === "unknown").length;
}

async function load() {
  banner(null);
  $("rows").innerHTML = '<tr><td colspan="5" class="empty">Cargando…</td></tr>';
  try {
    const st = await api("/api/status");
    state.ffprobe = st.ffprobe;
    if (!st.api_key_configured) {
      banner("Configura tu API token de TorBox en ⚙ Ajustes para empezar.", "info");
      $("rows").innerHTML = '<tr><td colspan="5" class="empty">Sin API token.</td></tr>';
      openSettings();
      return;
    }
    if (!st.ffprobe) {
      banner("No se encuentra ffprobe (parte de ffmpeg): el idioma solo se estima por el nombre. Instala ffmpeg o indica su ruta en ⚙ Ajustes para analizar las pistas reales.", "info");
    }
    const data = await api("/api/items");
    state.items = data.items;
    const keys = new Set(state.items.map(keyOf));
    for (const k of [...state.selected]) if (!keys.has(k)) state.selected.delete(k);
    updateStats();
    render();
  } catch (err) {
    banner(err.message);
    $("rows").innerHTML = '<tr><td colspan="5" class="empty">No se pudo cargar.</td></tr>';
  }
}

async function analyzeSelected() {
  const sel = selectedItems();
  const btn = $("btn-analyze");
  btn.disabled = true;
  btn.textContent = `Analizando ${sel.length}…`;
  try {
    const data = await api("/api/analyze", { items: sel.map((it) => ({ kind: it.kind, id: it.id })) });
    const errors = data.results.filter((r) => r.error && !r.status);
    // Lo que resulta tener audio en español deja de estar seleccionado
    for (const r of data.results) if (r.status === "es") state.selected.delete(r.key);
    await load();
    if (errors.length) banner(`${errors.length} elemento(s) no se pudieron analizar: ${errors[0].error}`);
  } catch (err) {
    banner(err.message);
  } finally {
    btn.textContent = "Analizar audio";
    updateSelection();
  }
}

async function deleteSelected() {
  const sel = selectedItems();
  const size = sel.reduce((a, it) => a + (it.size || 0), 0);
  $("confirm-text").textContent = `Vas a eliminar ${sel.length} elemento(s) y liberar ${fmtSize(size)}:`;
  const list = $("confirm-list");
  list.textContent = "";
  for (const it of sel.slice(0, 15)) {
    const li = document.createElement("li");
    li.textContent = `${it.name} (${fmtSize(it.size)})`;
    list.appendChild(li);
  }
  if (sel.length > 15) {
    const li = document.createElement("li");
    li.textContent = `… y ${sel.length - 15} más`;
    list.appendChild(li);
  }

  const dlg = $("confirm");
  dlg.returnValue = "";
  dlg.showModal();
  await new Promise((r) => dlg.addEventListener("close", r, { once: true }));
  if (dlg.returnValue !== "ok") return;

  const btn = $("btn-delete");
  btn.disabled = true;
  btn.textContent = "Eliminando…";
  try {
    const data = await api("/api/delete", { items: sel.map((it) => ({ kind: it.kind, id: it.id })) });
    const failed = data.results.filter((r) => !r.ok);
    for (const r of data.results) if (r.ok) state.selected.delete(`${r.kind}:${r.id}`);
    await load();
    if (failed.length) banner(`No se pudieron eliminar ${failed.length}: ${failed[0].error}`);
    else banner(`Eliminados ${data.results.length} elemento(s).`, "ok");
  } catch (err) {
    banner(err.message);
  } finally {
    btn.textContent = "Eliminar seleccionados";
    updateSelection();
  }
}

// ---------------------------------------------------------------------------
// Ajustes
// ---------------------------------------------------------------------------
function settingsError(msg) {
  const el = $("settings-error");
  el.textContent = msg || "";
  el.classList.toggle("hidden", !msg);
}

async function openSettings() {
  settingsError(null);
  $("api-key").value = "";
  $("api-key").type = "password";
  const dlg = $("settings");
  if (!dlg.open) dlg.showModal();
  try {
    const s = await api("/api/settings");
    $("api-key-current").textContent = s.api_key_configured
      ? `Token actual: ${s.api_key_masked}` + (s.api_key_source === "env" ? " (desde .env)" : "")
      : "No hay ningún token configurado.";
    $("btn-clear-key").disabled = s.api_key_source !== "ajustes";
    renderFfprobe(s);
  } catch (err) {
    settingsError(err.message);
  }
}

function renderFfprobe(s) {
  const status = $("ffprobe-status");
  const help = $("ffprobe-help");
  if (s.ffprobe_found) {
    status.textContent = `✓ Encontrado: ${s.ffprobe_found}`;
    status.className = "hint ok";
    help.classList.add("hidden");
  } else {
    status.textContent = "✗ No se encuentra ffprobe en este equipo.";
    status.className = "hint missing";
    help.textContent = `Para instalarlo: ${s.install_help}. Después pulsa «Recargar» o, si ya lo tienes, pega aquí su ruta.`;
    help.classList.remove("hidden");
  }
  $("ffprobe-path").value = s.ffprobe_custom || "";
}

async function saveFfprobe() {
  const btn = $("btn-save-ffprobe");
  btn.disabled = true;
  settingsError(null);
  try {
    const res = await fetch("/api/settings/ffprobe", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path: $("ffprobe-path").value.trim() }),
    });
    renderFfprobe(await parseResponse(res));
    await load();
  } catch (err) {
    settingsError(err.message);
  } finally {
    btn.disabled = false;
  }
}

async function saveSettings(e) {
  e.preventDefault();
  const key = $("api-key").value.trim();
  if (!key) { settingsError("Introduce el API token."); return; }
  const btn = $("btn-save-key");
  btn.disabled = true;
  btn.textContent = "Comprobando…";
  settingsError(null);
  try {
    const res = await fetch("/api/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ api_key: key }),
    });
    await parseResponse(res);
    $("settings").close();
    await load();
    banner("API token guardado correctamente.", "ok");
  } catch (err) {
    settingsError(err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "Guardar";
  }
}

async function clearKey() {
  if (!confirm("¿Borrar el API token guardado?")) return;
  try {
    const res = await fetch("/api/settings/api_key", { method: "DELETE" });
    if (!res.ok) throw new Error(`Error HTTP ${res.status}`);
    $("settings").close();
    state.items = [];
    state.selected.clear();
    updateStats();
    await load();
  } catch (err) {
    settingsError(err.message);
  }
}

$("btn-settings").addEventListener("click", openSettings);
$("settings-form").addEventListener("submit", saveSettings);
$("btn-clear-key").addEventListener("click", clearKey);
$("btn-close-settings").addEventListener("click", () => $("settings").close());
$("btn-save-ffprobe").addEventListener("click", saveFfprobe);
$("ffprobe-path").addEventListener("keydown", (e) => {
  if (e.key === "Enter") { e.preventDefault(); saveFfprobe(); }
});
$("btn-toggle-key").addEventListener("click", () => {
  const i = $("api-key");
  i.type = i.type === "password" ? "text" : "password";
});

$("search").addEventListener("input", render);
$("filter-audio").addEventListener("change", render);
$("filter-kind").addEventListener("change", render);
$("only-video").addEventListener("change", render);
$("btn-reload").addEventListener("click", load);
$("btn-analyze").addEventListener("click", analyzeSelected);
$("btn-delete").addEventListener("click", deleteSelected);
$("select-all").addEventListener("change", (e) => {
  for (const it of visibleItems()) {
    e.target.checked ? state.selected.add(keyOf(it)) : state.selected.delete(keyOf(it));
  }
  render();
});

load();
