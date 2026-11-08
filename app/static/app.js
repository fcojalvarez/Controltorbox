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

async function api(path, body, method = "POST") {
  const opts = body
    ? { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
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
    badge.title = {
      ffprobe: `Analizado: ${it.audio_languages.join(", ") || "sin pistas"}`,
      manual: "Marcado a mano tras escucharlo",
    }[it.audio_source] || "Estimado por el nombre del archivo";
    tdAudio.appendChild(badge);
    const src = document.createElement("small");
    src.textContent = {
      ffprobe: it.audio_languages.join(", ") || "sin etiquetas",
      manual: "marcado a mano",
    }[it.audio_source] || "por nombre";
    if (it.analysis_error) src.textContent += ` · ${it.analysis_error}`;
    tdAudio.appendChild(src);
    if (it.has_video) {
      const play = document.createElement("button");
      play.className = "secondary small";
      play.textContent = "▶ Escuchar";
      play.title = "Escuchar un fragmento de cada pista de audio";
      play.disabled = !state.ffprobe;
      play.addEventListener("click", () => openPlayer(it));
      tdAudio.appendChild(play);
    }

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
      banner("No se encuentra ffmpeg: el idioma solo se estima por el nombre. Ejecuta «pip install -r requirements.txt» (lo instala automáticamente) y reinicia la app.", "info");
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
// Escuchar el audio
// ---------------------------------------------------------------------------
const LANG_NAMES = {
  spa: "Español", es: "Español", eng: "Inglés", en: "Inglés", jpn: "Japonés", ja: "Japonés",
  fre: "Francés", fra: "Francés", fr: "Francés", ger: "Alemán", deu: "Alemán", de: "Alemán",
  ita: "Italiano", it: "Italiano", por: "Portugués", pt: "Portugués", kor: "Coreano", ko: "Coreano",
  chi: "Chino", zho: "Chino", rus: "Ruso", cat: "Catalán", und: "Sin etiqueta",
};
const player = { item: null, duration: null, playing: null };

function fmtTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const mm = String(m).padStart(h ? 2 : 1, "0"), ss = String(s).padStart(2, "0");
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

function channelsLabel(n) {
  return { 1: "mono", 2: "estéreo", 6: "5.1", 8: "7.1" }[n] || (n ? `${n} canales` : "");
}

function playerStart() {
  const pct = Number($("player-pos").value) / 100;
  return player.duration ? Math.floor(player.duration * pct) : Number($("player-pos").value) * 6;
}

function updatePosLabel() {
  $("player-pos-label").textContent = player.duration
    ? `${fmtTime(playerStart())} de ${fmtTime(player.duration)}`
    : fmtTime(playerStart());
}

function stopPlayer() {
  const audio = $("player-audio");
  audio.pause();
  audio.removeAttribute("src");
  audio.load();
  player.playing = null;
  for (const b of document.querySelectorAll("#player-tracks button")) b.textContent = "▶ Escuchar";
}

function renderManual(manual) {
  for (const b of document.querySelectorAll("#player-manual button")) {
    b.classList.toggle("active", (b.dataset.status || null) === (manual || null));
  }
}

async function openPlayer(it) {
  player.item = it;
  player.duration = null;
  $("player-title").textContent = it.name;
  $("player-error").classList.add("hidden");
  $("player-tracks").innerHTML = '<li class="hint">Leyendo las pistas de audio…</li>';
  $("player-pos").value = 15;
  updatePosLabel();
  renderManual(it.audio_source === "manual" ? it.audio_status : null);
  const dlg = $("player");
  if (!dlg.open) dlg.showModal();
  try {
    const info = await api(`/api/items/${it.kind}/${it.id}/tracks`);
    if (player.item !== it) return;
    player.duration = info.duration;
    updatePosLabel();
    renderManual(info.manual);
    const list = $("player-tracks");
    list.textContent = "";
    if (!info.tracks.length) {
      list.innerHTML = `<li class="hint">${info.error || "No se encontraron pistas de audio."}</li>`;
    }
    for (const t of info.tracks) {
      const li = document.createElement("li");
      const lang = (t.language || "und").toLowerCase();
      const label = document.createElement("div");
      const strong = document.createElement("strong");
      strong.textContent = `Pista ${t.n + 1} · ${LANG_NAMES[lang] || lang.toUpperCase()}`;
      if (lang === "spa" || lang.startsWith("es")) li.classList.add("is-es");
      const meta = document.createElement("small");
      meta.textContent = [t.codec.toUpperCase(), channelsLabel(t.channels), t.title && `«${t.title}»`]
        .filter(Boolean).join(" · ");
      label.append(strong, meta);
      const btn = document.createElement("button");
      btn.className = "secondary small";
      btn.textContent = "▶ Escuchar";
      btn.disabled = !info.can_play;
      if (!info.can_play) btn.title = "No se encuentra ffmpeg";
      btn.addEventListener("click", () => playTrack(t.n, btn));
      li.append(label, btn);
      list.appendChild(li);
    }
  } catch (err) {
    $("player-tracks").textContent = "";
    showPlayerError(err.message);
  }
}

function showPlayerError(msg) {
  const el = $("player-error");
  el.textContent = msg;
  el.classList.remove("hidden");
}

async function playTrack(n, btn) {
  const it = player.item;
  const audio = $("player-audio");
  if (player.playing === n && !audio.paused) { stopPlayer(); return; }
  stopPlayer();
  $("player-error").classList.add("hidden");
  const params = new URLSearchParams({
    track: n, start: playerStart(), duration: $("player-len").value,
  });
  const url = `/api/items/${it.kind}/${it.id}/clip?${params}`;
  btn.textContent = "Cargando…";
  btn.disabled = true;
  try {
    // Primero comprobamos que el servidor puede generar el fragmento para mostrar el error real.
    const res = await fetch(url);
    if (!res.ok) await parseResponse(res);
    const blob = await res.blob();
    if (player.item !== it) return;
    audio.src = URL.createObjectURL(blob);
    await audio.play();
    player.playing = n;
    btn.textContent = "■ Parar";
  } catch (err) {
    btn.textContent = "▶ Escuchar";
    showPlayerError(err.message);
  } finally {
    btn.disabled = false;
  }
}

async function setManual(status) {
  const it = player.item;
  try {
    await api(`/api/items/${it.kind}/${it.id}/audio_status`, { status }, "PUT");
    renderManual(status);
    await load();
  } catch (err) {
    showPlayerError(err.message);
  }
}

$("player-pos").addEventListener("input", updatePosLabel);
$("player-pos").addEventListener("change", stopPlayer);
$("player-len").addEventListener("change", stopPlayer);
$("player-close").addEventListener("click", () => $("player").close());
$("player").addEventListener("close", () => { stopPlayer(); player.item = null; });
$("player-audio").addEventListener("ended", stopPlayer);
for (const b of document.querySelectorAll("#player-manual button")) {
  b.addEventListener("click", () => setManual(b.dataset.status || null));
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
  if (s.ffprobe_found || s.ffmpeg_found) {
    const parts = [];
    if (s.ffmpeg_bundled) parts.push("✓ Usando el ffmpeg incluido con la app");
    else if (s.ffmpeg_found) parts.push(`✓ ffmpeg: ${s.ffmpeg_found}`);
    if (s.ffprobe_found) parts.push(`✓ ffprobe: ${s.ffprobe_found}`);
    if (!s.ffmpeg_found) parts.push("⚠ Sin ffmpeg no se puede escuchar el audio");
    status.textContent = parts.join("\n");
    status.className = "hint ok";
    help.classList.add("hidden");
  } else {
    status.textContent = "✗ No se encuentra ffmpeg.";
    status.className = "hint missing";
    help.textContent = s.install_help;
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
