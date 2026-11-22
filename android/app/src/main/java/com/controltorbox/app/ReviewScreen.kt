package com.controltorbox.app

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ReviewScreen(vm: MainViewModel, state: UiState, nav: Nav) {
    val c = C.c
    // La lista se fija al entrar: lo que decides no desaparece mientras revisas.
    val keys = remember { state.reviewRows.map { it.key } }
    val rows = keys.mapNotNull { state.row(it) }
    val toDelete = rows.filter { state.decisions[it.key] == Decision.DELETE }
    val kept = rows.count { state.decisions[it.key] == Decision.KEEP }
    val pending = rows.size - toDelete.size - kept
    var confirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                ScreenHeader("Sin audio en español", onBack = nav::back)
                Text(
                    buildAnnotatedString {
                        append("Escucha si dudas y decide. ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("No se borra nada") }
                        append(" hasta que pulses el botón de abajo.")
                    },
                    fontSize = 13.sp, lineHeight = 19.sp, color = c.chipText, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                )
            }
        },
        bottomBar = {
            if (rows.isNotEmpty()) {
                val title = when {
                    toDelete.isNotEmpty() -> "${toDelete.size} para borrar"
                    kept > 0 -> "$kept para conservar"
                    else -> "Nada decidido aún"
                }
                val sub = listOfNotNull(
                    if (toDelete.isNotEmpty()) "Liberarás ${Format.size(toDelete.sumOf { it.size })}" else null,
                    if (kept > 0) "$kept conservado${if (kept > 1) "s" else ""}" else null,
                    if (pending > 0) "$pending pendiente${if (pending > 1) "s" else ""}" else null,
                ).joinToString(" · ")
                BottomActionBar(
                    title, sub,
                    button = if (toDelete.isNotEmpty()) "Borrar ${toDelete.size}" else "Guardar",
                    enabled = toDelete.isNotEmpty() || kept > 0,
                    onClick = { if (toDelete.isNotEmpty()) confirm = true else { vm.applyReview(); nav.back() } },
                )
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Text(
                "No queda nada por revisar.", color = c.muted,
                modifier = Modifier.padding(padding).fillMaxWidth().padding(32.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.key }) { r ->
                    ReviewCard(r, state.decisions[r.key], state,
                        onListen = { nav.open(Route.Detail(r.key)) },
                        onKeep = { vm.decide(r.key, Decision.KEEP) },
                        onDelete = { vm.decide(r.key, Decision.DELETE) })
                }
            }
        }
    }

    if (confirm) {
        ConfirmDeleteDialog(toDelete, onConfirm = { confirm = false; vm.applyReview(); nav.back() }, onDismiss = { confirm = false })
    }
}

/** Por qué está en la lista, en una frase. */
private fun reason(r: ItemRow, state: UiState): String {
    r.analysis?.episodeSummary?.let { return it }
    val tracks = r.analysis?.tracks.orEmpty()
    if (tracks.isNotEmpty()) {
        return "Pistas: " + tracks.joinToString(" · ") { t ->
            Language.displayName(t.language) + when (t.channels) { 6 -> " 5.1"; 8 -> " 7.1"; 2 -> " 2.0"; else -> "" }
        }
    }
    if (Regex("\\bvose?\\b", RegexOption.IGNORE_CASE).containsMatchIn(r.item.name.replace('.', ' '))) {
        return "VOSE: audio original con subtítulos"
    }
    r.duplicate?.let { g ->
        val other = state.rows.firstOrNull { it.duplicate?.id == g.id && it.key != r.key && it.status == AudioStatus.ES }
        if (other != null) return "Tienes otra copia${other.quality.takeIf { it.isNotBlank() }?.let { " en $it" } ?: ""} con español"
    }
    return "El nombre no indica el idioma"
}

@Composable
private fun ReviewCard(
    r: ItemRow, decision: Decision?, state: UiState,
    onListen: () -> Unit, onKeep: () -> Unit, onDelete: () -> Unit,
) {
    val c = C.c
    val border = when (decision) {
        Decision.DELETE -> BorderStroke(2.dp, c.orange)
        Decision.KEEP -> BorderStroke(2.dp, c.green)
        null -> BorderStroke(1.dp, c.border)
    }
    AppCard(radius = 16.dp, border = border, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${r.item.name} · ${Format.size(r.size)}", fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusBadge(r.status)
            }
            Text(reason(r, state), fontSize = 12.sp, color = c.chipText, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Escuchar", onListen, filled = false, icon = Ic.Play, modifier = Modifier.weight(1f), horizontalPadding = 4.dp)
                PillButton(
                    "Conservar", onKeep, filled = decision == Decision.KEEP, color = c.green,
                    icon = if (decision == Decision.KEEP) Ic.Check else null, modifier = Modifier.weight(1f), horizontalPadding = 4.dp,
                )
                PillButton(
                    "Borrar", onDelete, filled = decision == Decision.DELETE, color = c.orange,
                    icon = if (decision == Decision.DELETE) Ic.Check else null, modifier = Modifier.weight(1f), horizontalPadding = 4.dp,
                )
            }
        }
    }
}
