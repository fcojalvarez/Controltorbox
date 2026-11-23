package com.controltorbox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Velocidad legible: «4,2 MB/s». */
fun speedLabel(bytesPerSecond: Long) = "${Format.size(bytesPerSecond)}/s"

/** «quedan 12 min», «quedan 2 h 5 min». */
fun etaLabel(seconds: Long?): String? {
    seconds ?: return null
    val m = seconds / 60
    return when {
        seconds < 60 -> "menos de 1 min"
        m < 60 -> "quedan $m min"
        else -> "quedan ${m / 60} h ${m % 60} min"
    }
}

/** Una línea con el estado de la descarga: velocidad, tiempo restante, fuentes. */
fun downloadLine(it: TorboxItem): String = when {
    it.paused -> "En pausa · ${(it.progress * 100).toInt()} %"
    it.stalled -> listOfNotNull(
        "Atascada",
        it.state.takeIf { s -> s.isNotBlank() && "stalled" !in s.lowercase() }?.let { s -> "«$s»" },
        it.seeds?.takeIf { _ -> it.kind == Kind.TORRENT }?.let { s -> "$s fuentes" },
        it.availability?.takeIf { _ -> it.kind == Kind.TORRENT }?.let { a -> "disponibilidad ${"%.1f".format(a)}" },
    ).joinToString(" · ")
    else -> listOfNotNull(
        "${(it.progress * 100).toInt()} %",
        speedLabel(it.downloadSpeed).takeIf { _ -> it.downloadSpeed > 0 },
        etaLabel(it.eta),
        it.seeds?.takeIf { _ -> it.kind == Kind.TORRENT }?.let { s -> "$s fuentes" + (it.peers?.let { p -> " · $p pares" } ?: "") },
    ).joinToString(" · ")
}

@Composable
fun DownloadsScreen(vm: MainViewModel, state: UiState, nav: Nav) {
    val c = C.c
    val rows = state.downloading
    val stalled = rows.filter { it.item.stalled }
    val active = rows.filterNot { it.item.stalled }
    var confirm by remember { mutableStateOf<ItemRow?>(null) }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                ScreenHeader("Descargas", onBack = nav::back, subtitle = "Lo que TorBox está descargando, lo que se ha atascado y lo que está en cola.")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rows.isEmpty() && state.queued.isEmpty()) {
                item { Text("No hay nada descargándose ni en cola.", color = c.muted, modifier = Modifier.padding(16.dp)) }
            }
            if (stalled.isNotEmpty()) {
                item { SectionTitle("Atascadas · ${stalled.size}") }
                items(stalled, key = { it.key }) { r -> DownloadCard(r, state, vm, nav, onDelete = { confirm = r }) }
            }
            if (active.isNotEmpty()) {
                item { SectionTitle("Descargando · ${active.size}") }
                items(active, key = { it.key }) { r -> DownloadCard(r, state, vm, nav, onDelete = { confirm = r }) }
            }
            if (state.queued.isNotEmpty()) {
                item { SectionTitle("En cola · ${state.queued.size}") }
                items(state.queued, key = { it.key }) { q -> QueuedCard(q, busy = q.key in state.busy, vm) }
            }
        }
    }
    confirm?.let { r ->
        ConfirmDeleteDialog(listOf(r), onConfirm = { confirm = null; vm.delete(listOf(r.key)) }, onDismiss = { confirm = null })
    }
}

@Composable
private fun DownloadCard(r: ItemRow, state: UiState, vm: MainViewModel, nav: Nav, onDelete: () -> Unit) {
    val c = C.c
    val it = r.item
    val busy = r.key in state.busy
    AppCard(
        radius = 16.dp, border = if (it.stalled) BorderStroke(2.dp, c.orange) else null,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { nav.open(Route.Detail(r.key)) },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${it.kind.label} · ${Format.size(r.size)}", fontSize = 12.sp, color = c.muted)
                }
            }
            LinearProgressIndicator(
                progress = { it.progress }, modifier = Modifier.fillMaxWidth(),
                color = if (it.stalled) c.orange else c.accent, trackColor = c.chip,
            )
            Text(downloadLine(it), fontSize = 12.sp, color = if (it.stalled) c.orange else c.chipText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (it.kind == Kind.TORRENT && !it.paused) {
                    PillButton("Reanunciar", { vm.reannounce(it) }, filled = it.stalled, enabled = !busy, height = 36.dp)
                }
                if (it.paused) PillButton("Reanudar", { vm.resume(it) }, enabled = !busy, height = 36.dp)
                else if (it.kind == Kind.USENET) PillButton("Pausar", { vm.pause(it) }, filled = false, enabled = !busy, height = 36.dp)
                PillButton("Borrar", onDelete, filled = false, enabled = !busy, height = 36.dp, icon = Ic.Trash)
            }
        }
    }
}

@Composable
private fun QueuedCard(q: QueuedItem, busy: Boolean, vm: MainViewModel) {
    val c = C.c
    AppCard(radius = 16.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Titles.clean(q.name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(q.kind.label, Dates.relative(UiState.parseDate(q.createdAt))?.let { "en cola $it" }).joinToString(" · "),
                    fontSize = 12.sp, color = c.muted,
                )
            }
            PillButton("Iniciar", { vm.startQueued(q) }, enabled = !busy, height = 36.dp)
            PillButton("Quitar", { vm.deleteQueued(q) }, filled = false, enabled = !busy, height = 36.dp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
