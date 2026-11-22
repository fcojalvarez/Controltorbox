package com.controltorbox.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DuplicatesScreen(vm: MainViewModel, state: UiState, nav: Nav) {
    val c = C.c
    val groups = state.duplicateGroups
    val toDelete = groups.flatMap { it.toDelete }
    var confirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                ScreenHeader(
                    "Duplicados", onBack = nav::back,
                    subtitle = "Elige qué copia conservar. Te sugerimos la que tiene español o, si empatan, la más grande.",
                )
            }
        },
        bottomBar = {
            if (groups.isNotEmpty()) {
                BottomActionBar(
                    "Liberar ${Format.size(toDelete.sumOf { it.size })}",
                    "Se borrarán ${toDelete.size} copia${if (toDelete.size != 1) "s" else ""}, se conservan ${groups.size}",
                    button = "Borrar ${toDelete.size}", enabled = toDelete.isNotEmpty(), onClick = { confirm = true },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (groups.isEmpty()) item { Text("No hay duplicados.", color = c.muted, modifier = Modifier.padding(16.dp)) }
            items(groups, key = { it.id }) { g -> GroupCard(g, onKeep = { vm.keepCopy(g.id, it) }, onOpen = { nav.open(Route.Detail(it)) }) }
            if (groups.isNotEmpty()) item {
                Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Ic.Info, null, tint = c.chipText, modifier = Modifier.size(18.dp))
                    Text(
                        "Toca otra copia para conservarla en su lugar. Se detectan por título y año; si una tiene el título en español y otra en inglés, no se emparejan.",
                        fontSize = 12.sp, lineHeight = 17.sp, color = c.chipText,
                    )
                }
            }
        }
    }

    if (confirm) {
        ConfirmDeleteDialog(toDelete, onConfirm = { confirm = false; vm.deleteDuplicateCopies(); nav.back() }, onDismiss = { confirm = false })
    }
}

/** Descripción corta de una copia: «4K HDR · Usenet» / «Temporada completa · 1080p». */
private fun copyTitle(r: ItemRow): String {
    val p = Duplicates.parseName(r.item.name)
    val what = when {
        p.isSeries && p.episode == null -> "Temporada completa"
        p.isSeries -> "Episodio ${p.episode} suelto"
        else -> null
    }
    return listOfNotNull(what, r.quality.takeIf { it.isNotBlank() }, r.item.kind.label).joinToString(" · ")
}

private fun copyDetail(r: ItemRow): String {
    val langs = r.analysis?.tracks?.map { Language.displayName(it.language) }?.distinct()
    val lang = when {
        !langs.isNullOrEmpty() -> langs.joinToString(" + ")
        r.status == AudioStatus.ES -> "Con español"
        r.status == AudioStatus.NO_ES -> "Sin español"
        else -> "idioma sin comprobar"
    }
    val files = if (r.item.files.size > 1) "${r.item.files.size} archivos · " else ""
    return "${Format.size(r.size)} · $files$lang"
}

@Composable
private fun GroupCard(g: DupGroup, onKeep: (String) -> Unit, onOpen: (String) -> Unit) {
    val c = C.c
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text(g.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
                Text("${g.members.size} copias · ${Format.size(g.members.sumOf { it.size })}", fontSize = 12.sp, color = c.muted)
            }
            g.members.forEach { r ->
                val keep = r.key == g.keepKey
                HorizontalDivider(color = if (keep) c.greenSoft else c.chip)
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (keep) c.greenSoft else c.surface)
                        .clickable(role = Role.RadioButton) { onKeep(r.key) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(22.dp).then(
                            if (keep) Modifier.border(7.dp, c.green, CircleShape).background(c.surface, CircleShape)
                            else Modifier.border(2.dp, c.faint, CircleShape),
                        ),
                    )
                    Column(Modifier.weight(1f).clickable { onOpen(r.key) }) {
                        Text(copyTitle(r), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        Text(copyDetail(r), fontSize = 12.sp, color = if (keep) c.chipText else c.muted)
                    }
                    if (keep) {
                        Text("Conservar", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.green)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ic.Trash, null, tint = c.orange, modifier = Modifier.size(14.dp))
                            Text(" Se borrará", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.orange)
                        }
                    }
                }
            }
        }
    }
}
