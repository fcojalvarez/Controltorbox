package com.controltorbox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(row: ItemRow, vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item = row.item
    val preview = remember { AudioProbe.Preview(context) }
    var tracks by remember { mutableStateOf(row.analysis?.tracks?.takeIf { it.isNotEmpty() }) }
    var loadingTracks by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableFloatStateOf(0.15f) }
    var durationMs by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableIntStateOf(-1) }
    var link by remember { mutableStateOf<String?>(null) }

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

    suspend fun ensureLink(): String = link ?: vm.linkFor(item).also { link = it }

    fun loadTracks() {
        loadingTracks = true; error = null
        scope.launch {
            try {
                tracks = vm.analyze(item).tracks
            } catch (e: Exception) {
                error = e.message
            } finally {
                loadingTracks = false
            }
        }
    }

    fun start(index: Int) {
        error = null
        scope.launch {
            try {
                val url = ensureLink()
                val start = durationMs?.let { (it * position).toLong() } ?: (position * 60 * 60 * 1000).toLong().coerceAtMost(10 * 60 * 1000)
                preview.play(url, index, start)
                playing = index
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun toggle(index: Int) {
        if (playing == index) { preview.stop(); playing = -1 } else start(index)
    }

    ModalBottomSheet(onDismissRequest = { preview.stop(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ItemDetail(
            row = row, tracks = tracks, loadingTracks = loadingTracks, error = error, position = position,
            durationMs = durationMs, playing = playing,
            onLoadTracks = ::loadTracks, onPosition = { position = it },
            onPositionDone = { if (playing >= 0) start(playing) },
            onToggle = ::toggle, onManual = { vm.setManual(item.key, it) },
            onClose = { preview.stop(); onDismiss() },
        )
    }
}

/** Contenido de la ficha (separado para poder previsualizarlo y probarlo). */
@Composable
fun ItemDetail(
    row: ItemRow,
    tracks: List<AudioTrack>?,
    loadingTracks: Boolean,
    error: String?,
    position: Float,
    durationMs: Long?,
    playing: Int,
    onLoadTracks: () -> Unit,
    onPosition: (Float) -> Unit,
    onPositionDone: () -> Unit,
    onToggle: (Int) -> Unit,
    onManual: (AudioStatus?) -> Unit,
    onClose: () -> Unit,
) {
    val item = row.item
    var showFiles by remember { mutableStateOf(false) }
    run {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                listOfNotNull(item.kind.label, Format.size(item.totalSize), row.dateLabel()?.let { "añadido el $it" }).joinToString(" · "),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioBadge(row.status)
                Text(row.source.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row.duplicate?.let { Text("Duplicado de «${it.label}»", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }

            if (item.files.size > 1) {
                TextButton(onClick = { showFiles = !showFiles }) { Text("${if (showFiles) "▾" else "▸"} ${item.files.size} archivos") }
                if (showFiles) {
                    item.files.sortedByDescending { it.size }.forEach { f ->
                        Row {
                            Text(f.displayName, Modifier.weight(1f), fontSize = 12.sp,
                                fontWeight = if (f.id == item.mainVideo?.id) FontWeight.SemiBold else FontWeight.Normal)
                            Text(Format.size(f.size), fontSize = 12.sp)
                        }
                    }
                    Text("TorBox no permite borrar archivos sueltos: al eliminar se borra el elemento completo.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider()
            Text("Pistas de audio", fontWeight = FontWeight.SemiBold)
            if (item.mainVideo == null) {
                Text("Este elemento no tiene archivo de vídeo.", fontSize = 13.sp)
            } else {
                if (tracks == null && !loadingTracks) {
                    OutlinedButton(onClick = onLoadTracks) { Text("Leer pistas de audio") }
                }
                if (loadingTracks) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(20.dp)); Text("  Leyendo el archivo…", fontSize = 13.sp)
                }
                tracks?.let { list ->
                    Text(
                        "Escuchar desde " + (durationMs?.let { Format.time((it * position / 1000).toLong()) + " de " + Format.time(it / 1000) }
                            ?: "el ${(position * 100).toInt()} %"),
                        fontSize = 13.sp,
                    )
                    Slider(value = position, onValueChange = onPosition, valueRange = 0f..0.95f,
                        onValueChangeFinished = onPositionDone)
                    if (list.isEmpty()) Text("No se encontraron pistas de audio.", fontSize = 13.sp)
                    list.forEach { t ->
                        val isEs = Language.isSpanishCode(t.language)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            border = if (isEs) BorderStroke(1.dp, Green) else null,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text("Pista ${t.index + 1} · ${Language.displayName(t.language)}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    val ch = when (t.channels) { 1 -> "mono"; 2 -> "estéreo"; 6 -> "5.1"; 8 -> "7.1"; 0 -> ""; else -> "${t.channels} canales" }
                                    Text(
                                        listOf(t.codec, ch, t.label.takeIf { it.isNotBlank() }?.let { "«$it»" } ?: "")
                                            .filter { it.isNotBlank() }.joinToString(" · ") +
                                            if (!t.supported) " · no se puede reproducir en este móvil" else "",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onToggle(t.index) }, enabled = t.supported) {
                                    if (playing == t.index) Icon(Icons.Default.Close, "Parar")
                                    else Icon(Icons.Default.PlayArrow, "Escuchar")
                                }
                            }
                        }
                    }
                    TextButton(onClick = onLoadTracks, enabled = !loadingTracks) { Text("Volver a leer las pistas") }
                }
            }
            error?.let { Text(it, color = Red, fontSize = 13.sp) }

            HorizontalDivider()
            Text("Tras escucharlo, ¿tiene audio en español?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            val manual = if (row.source == AudioSource.MANUAL) row.status else null
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = manual == AudioStatus.ES, onClick = { onManual(AudioStatus.ES) }, label = { Text("Sí") })
                FilterChip(selected = manual == AudioStatus.NO_ES, onClick = { onManual(AudioStatus.NO_ES) }, label = { Text("No") })
                FilterChip(selected = manual == null, onClick = { onManual(null) }, label = { Text("Automático") })
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Cerrar") }
            }
        }
    }
}
