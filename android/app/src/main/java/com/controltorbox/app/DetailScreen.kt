package com.controltorbox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Estado de la reproducción que se pinta en la ficha. */
data class PlaybackUi(
    val position: Float = 0.15f,
    val durationMs: Long? = null,
    val playing: Int = -1,
    val currentMs: Long = 0,
    val clipStartMs: Long = 0,
)

/** Duración de cada fragmento que se escucha. */
const val CLIP_MS = 30_000L

@Composable
fun DetailScreen(vm: MainViewModel, row: ItemRow, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item = row.item
    val preview = remember { AudioProbe.Preview(context) }
    var loadingTracks by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableFloatStateOf(0.15f) }
    var durationMs by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableIntStateOf(-1) }
    var currentMs by remember { mutableLongStateOf(0L) }
    var clipStart by remember { mutableLongStateOf(0L) }
    var link by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val d = preview.player.duration
                if (d > 0) durationMs = d
                if (state == Player.STATE_ENDED) playing = -1
            }

            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = "No se pudo reproducir: ${e.errorCodeName}"
                playing = -1
            }
        }
        preview.player.addListener(listener)
        onDispose { preview.release() }
    }
    LaunchedEffect(playing) {
        while (playing >= 0) {
            currentMs = preview.player.currentPosition
            if (currentMs - clipStart >= CLIP_MS) { preview.stop(); playing = -1 }
            delay(300)
        }
    }

    fun loadTracks() {
        loadingTracks = true; error = null
        scope.launch {
            try { vm.analyze(item) } catch (e: Exception) { error = e.message } finally { loadingTracks = false }
        }
    }

    fun start(index: Int) {
        error = null
        scope.launch {
            try {
                val url = link ?: vm.linkFor(item).also { link = it }
                val start = durationMs?.let { (it * position).toLong() } ?: (position * 3_600_000).toLong().coerceAtMost(600_000)
                clipStart = start
                currentMs = start
                preview.play(url, index, start)
                playing = index
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun toggle(index: Int) { if (playing == index) { preview.stop(); playing = -1 } else start(index) }

    DetailContent(
        row = row,
        loadingTracks = loadingTracks,
        error = error,
        playback = PlaybackUi(position, durationMs, playing, currentMs, clipStart),
        onBack = { preview.stop(); nav.back() },
        onLoadTracks = ::loadTracks,
        onPosition = { position = it },
        onPositionDone = { if (playing >= 0) start(playing) },
        onToggle = ::toggle,
        onManual = { vm.setManual(item.key, it) },
        onDelete = { confirm = true },
    )

    if (confirm) {
        ConfirmDeleteDialog(listOf(row), onConfirm = { confirm = false; preview.stop(); vm.delete(listOf(row.key)); nav.back() }, onDismiss = { confirm = false })
    }
}

/** Contenido de la ficha, sin estado (para poder previsualizarlo). */
@Composable
fun DetailContent(
    row: ItemRow,
    loadingTracks: Boolean,
    error: String?,
    playback: PlaybackUi,
    onBack: () -> Unit,
    onLoadTracks: () -> Unit,
    onPosition: (Float) -> Unit,
    onPositionDone: () -> Unit,
    onToggle: (Int) -> Unit,
    onManual: (AudioStatus?) -> Unit,
    onDelete: () -> Unit,
) {
    val c = C.c
    val item = row.item
    val tracks = row.analysis?.tracks?.takeIf { it.isNotEmpty() }
    var showFiles by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) { Icon(Ic.Back, "Volver", tint = c.text) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(row.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.text)
                Text(
                    listOfNotNull(row.quality.takeIf { it.isNotBlank() }, item.kind.label, Format.size(row.size), Dates.short(row.createdAt)?.let { "añadido el $it" }).joinToString(" · "),
                    fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp),
                )
                Text(item.name, fontSize = 12.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                row.duplicate?.let { Text("Tienes otra copia de «${it.label}»", fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
            }

            StatusBanner(row)

            if (item.mainVideo != null) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Pistas de audio", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
                            if (tracks != null) Text("${tracks.size} pista${if (tracks.size != 1) "s" else ""}", fontSize = 12.sp, color = c.muted)
                        }
                        when {
                            loadingTracks -> Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("  Leyendo el archivo…", fontSize = 13.sp, color = c.chipText)
                            }
                            tracks == null -> Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Lee las pistas del archivo para saber qué idiomas tiene y escucharlas.", fontSize = 13.sp, color = c.chipText)
                                PillButton("Leer pistas de audio", onLoadTracks, icon = Ic.Wave)
                            }
                            else -> {
                                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Escuchar desde ", fontSize = 12.sp, color = c.chipText)
                                    Text(
                                        playback.durationMs?.let { Format.time((it * playback.position / 1000).toLong()) } ?: "${(playback.position * 100).toInt()} %",
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.text,
                                    )
                                    Slider(
                                        value = playback.position, onValueChange = onPosition, onValueChangeFinished = onPositionDone,
                                        valueRange = 0f..0.95f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.chip),
                                    )
                                    playback.durationMs?.let { Text(Format.time(it / 1000), fontSize = 12.sp, color = c.chipText) }
                                }
                                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    tracks.forEach { t -> TrackRow(t, playback, onToggle) }
                                }
                                TextButton(onClick = onLoadTracks, modifier = Modifier.padding(top = 4.dp)) { Text("Volver a leer las pistas", fontSize = 13.sp) }
                            }
                        }
                        error?.let { Text(it, color = c.orange, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }

            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("¿Es correcto?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    val manual = if (row.source == AudioSource.MANUAL) row.status else null
                    Row(
                        Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).border(1.dp, c.borderStrong, RoundedCornerShape(22.dp)),
                    ) {
                        listOf(AudioStatus.ES to "Con español", AudioStatus.NO_ES to "Sin español", null to "Automático").forEachIndexed { i, (s, label) ->
                            val sel = manual == s
                            if (i > 0) Box(Modifier.size(width = 1.dp, height = 40.dp).background(c.borderStrong))
                            Box(
                                Modifier.weight(1f).height(40.dp)
                                    .background(if (sel) (if (s == AudioStatus.NO_ES) c.orangeSoft else if (s == null) c.chip else c.greenSoft) else c.surface)
                                    .clickable(role = Role.RadioButton) { onManual(s) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (sel) (if (s == AudioStatus.NO_ES) c.orange else if (s == null) c.text else c.greenText) else c.text,
                                )
                            }
                        }
                    }
                }
            }

            if (item.files.size > 1) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        TextButton(onClick = { showFiles = !showFiles }) {
                            Text("${if (showFiles) "Ocultar" else "Ver"} los ${item.files.size} archivos", fontSize = 13.sp)
                        }
                        if (showFiles) {
                            item.files.sortedByDescending { it.size }.forEach { f ->
                                Row(Modifier.padding(vertical = 3.dp)) {
                                    Text(
                                        f.displayName, Modifier.weight(1f), fontSize = 12.sp, color = c.text,
                                        fontWeight = if (f.id == item.mainVideo?.id) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    Text(Format.size(f.size), fontSize = 12.sp, color = c.muted)
                                }
                            }
                            Text(
                                "TorBox no permite borrar archivos sueltos: se borra el elemento completo.",
                                fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.background(c.bg).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)) {
            OutlinedButton(
                onClick = onDelete, modifier = Modifier.fillMaxWidth().height(52.dp),
                border = BorderStroke(1.5.dp, c.orange),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = c.orange),
            ) {
                Icon(Ic.Trash, null, modifier = Modifier.size(18.dp))
                Text("  Borrar de TorBox · ${Format.size(row.size)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatusBanner(row: ItemRow) {
    val c = C.c
    val (bg, fg, title) = when (row.status) {
        AudioStatus.ES -> Triple(c.greenSoft, c.greenText, "Tiene audio en español")
        AudioStatus.NO_ES -> Triple(c.orangeSoft, c.orange, "No tiene audio en español")
        AudioStatus.UNKNOWN -> Triple(c.chip, c.chipText, "Idioma sin comprobar")
    }
    val sub = when (row.source) {
        AudioSource.ANALYZED -> "Comprobado leyendo las pistas del archivo"
        AudioSource.MANUAL -> "Lo marcaste tú"
        AudioSource.NAME -> if (row.status == AudioStatus.UNKNOWN) "El nombre no lo dice: lee las pistas o escúchalo" else "Según el nombre del archivo"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            when (row.status) { AudioStatus.ES -> Ic.Check; AudioStatus.NO_ES -> Ic.NoAudio; AudioStatus.UNKNOWN -> Ic.Wave },
            null, tint = fg, modifier = Modifier.size(22.dp),
        )
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg)
            Text(sub, fontSize = 12.sp, color = c.chipText)
        }
    }
}

@Composable
private fun TrackRow(t: AudioTrack, playback: PlaybackUi, onToggle: (Int) -> Unit) {
    val c = C.c
    val isPlaying = playback.playing == t.index
    val isEs = Language.isSpanishCode(t.language)
    val ch = when (t.channels) { 1 -> "mono"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; 0 -> null; else -> "${t.channels} canales" }
    val detail = listOfNotNull(t.codec.takeIf { it.isNotBlank() }, ch, t.label.takeIf { it.isNotBlank() }?.let { "«$it»" }).joinToString(" · ") +
        if (!t.supported) " · este móvil no puede reproducirla" else ""
    val border = when {
        isPlaying -> BorderStroke(2.dp, c.green)
        isEs -> BorderStroke(1.dp, c.green)
        else -> BorderStroke(1.dp, c.border)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(border, RoundedCornerShape(14.dp))
            .background(if (isPlaying) c.greenSoft.copy(alpha = 0.5f) else Color.Transparent)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Language.displayName(t.language), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    if (isPlaying) {
                        Text(
                            "Sonando", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.green).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(detail, fontSize = 12.sp, color = if (isPlaying) c.chipText else c.muted)
            }
            IconButton(
                onClick = { onToggle(t.index) }, enabled = t.supported,
                modifier = Modifier.size(44.dp).then(if (!isPlaying) Modifier.border(1.dp, c.borderStrong, CircleShape) else Modifier),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isPlaying) c.green else c.surface,
                    contentColor = if (isPlaying) Color.White else c.text,
                    disabledContainerColor = c.bg, disabledContentColor = c.faint,
                ),
            ) {
                Icon(if (!t.supported) Ic.Muted else if (isPlaying) Ic.Pause else Ic.Play, if (isPlaying) "Pausar" else "Escuchar", modifier = Modifier.size(16.dp))
            }
        }
        if (isPlaying) {
            val startMs = playback.clipStartMs
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Format.time(playback.currentMs / 1000), fontSize = 11.sp, color = c.chipText)
                LinearProgressIndicator(
                    progress = { ((playback.currentMs - startMs).toFloat() / CLIP_MS).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp), color = c.green, trackColor = c.greenSoft,
                )
                Text(Format.time((startMs + CLIP_MS) / 1000), fontSize = 11.sp, color = c.chipText)
            }
        }
    }
}
