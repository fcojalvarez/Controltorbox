package com.controltorbox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es", "ES"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, state: UiState, onOpenSettings: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Controltorbox") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Recargar") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Ajustes") }
                },
            )
        },
        bottomBar = {
            if (state.selected.isNotEmpty() || state.filter == AudioFilter.DUPS) {
                SelectionBar(state, vm, onDelete = { confirmDelete = true })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.analyzing != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.update?.let { u -> item(key = "update") { UpdateBanner(u, state.updateProgress, vm::installUpdate) } }
                item { Header(state, vm) }
                var lastGroup: String? = null
                val rows = state.visible
                if (rows.isEmpty() && !state.loading) {
                    item {
                        Text(
                            if (state.filter == AudioFilter.DUPS) "No se han encontrado duplicados." else "No hay elementos con estos filtros.",
                            Modifier.fillMaxWidth().padding(32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                for (row in rows) {
                    if (state.filter == AudioFilter.DUPS && row.duplicate?.id != lastGroup) {
                        lastGroup = row.duplicate?.id
                        val members = rows.filter { it.duplicate?.id == lastGroup }
                        item(key = "g-$lastGroup") {
                            Text(
                                "${row.duplicate?.label} · ${members.size} copias · ${Format.size(members.sumOf { it.item.totalSize })}",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    item(key = row.item.key) {
                        ItemCard(
                            row = row,
                            selected = row.item.key in state.selected,
                            showDupTag = state.filter != AudioFilter.DUPS,
                            onToggle = { vm.toggle(row.item.key) },
                            onOpen = { detail = row.item.key },
                            onDupTag = { vm.setFilter(AudioFilter.DUPS) },
                        )
                    }
                }
            }
        }
    }

    state.rows.firstOrNull { it.item.key == detail }?.let { row ->
        ItemSheet(row = row, vm = vm, onDismiss = { detail = null })
    }

    if (confirmDelete) {
        val sel = state.selectedRows
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar de TorBox?") },
            text = {
                Column {
                    Text("Vas a eliminar ${sel.size} elemento(s) y liberar ${Format.size(sel.sumOf { it.item.totalSize })}:")
                    Spacer(Modifier.height(8.dp))
                    sel.take(8).forEach { Text("• ${it.item.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }
                    if (sel.size > 8) Text("… y ${sel.size - 8} más", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Esta acción no se puede deshacer.", color = Red, fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; vm.deleteSelected() },
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                ) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    state.error?.let { err ->
        AlertDialog(
            onDismissRequest = { vm.clearMessage() },
            title = { Text("Aviso") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { vm.clearMessage() }) { Text("Aceptar") } },
        )
    }
}

@Composable
private fun UpdateBanner(info: UpdateInfo, progress: Float?, onUpdate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nueva versión disponible", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    progress?.let { "Descargando… ${(it * 100).toInt()} %" } ?: "Versión ${info.versionName}",
                    fontSize = 12.sp,
                )
            }
            Button(onClick = onUpdate, enabled = progress == null) { Text("Actualizar") }
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun Header(state: UiState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("${state.rows.size}", "Elementos", Modifier.weight(1f))
            Stat(Format.size(state.totalSize), "Ocupado", Modifier.weight(1.3f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("${state.rows.count { it.status == AudioStatus.NO_ES }}", "Sin español", Modifier.weight(1f), Amber) {
                vm.setFilter(AudioFilter.NO_ES)
            }
            Stat("${state.rows.count { it.status == AudioStatus.UNKNOWN }}", "Desconocido", Modifier.weight(1f)) {
                vm.setFilter(AudioFilter.UNKNOWN)
            }
            Stat("${state.duplicateGroups}", "Duplicados", Modifier.weight(1f), MaterialTheme.colorScheme.primary,
                active = state.filter == AudioFilter.DUPS) { vm.setFilter(AudioFilter.DUPS) }
        }
        OutlinedTextField(
            value = state.search,
            onValueChange = vm::setSearch,
            placeholder = { Text("Buscar por nombre…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (state.search.isNotEmpty()) IconButton(onClick = { vm.setSearch("") }) { Icon(Icons.Default.Clear, "Borrar") }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(AudioFilter.NOT_ES, AudioFilter.UNKNOWN, AudioFilter.ES, AudioFilter.ALL, AudioFilter.DUPS).forEach { f ->
                FilterChip(selected = state.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SortMenu(state.sort, vm::setSort)
            Spacer(Modifier.weight(1f))
            if (state.filter != AudioFilter.DUPS) {
                Text("Solo vídeo", fontSize = 13.sp)
                Checkbox(checked = state.onlyVideo, onCheckedChange = vm::setOnlyVideo)
            }
        }
        val visibleKeys = state.visible.map { it.item.key }
        if (visibleKeys.isNotEmpty()) {
            val all = visibleKeys.all { it in state.selected }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = all, onCheckedChange = { vm.selectAllVisible(it) })
                Text("Seleccionar los ${visibleKeys.size} visibles", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Stat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun SortMenu(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(sort.label, fontSize = 13.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = { onSort(s); open = false })
            }
        }
    }
}

@Composable
fun AudioBadge(status: AudioStatus) {
    val color = when (status) {
        AudioStatus.ES -> Green
        AudioStatus.NO_ES -> Red
        AudioStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
        Text(
            status.label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

fun ItemRow.dateLabel(): String? = createdAt?.let { DATE_FMT.format(it.atZone(ZoneId.systemDefault())) }

@Composable
private fun ItemCard(
    row: ItemRow,
    selected: Boolean,
    showDupTag: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDupTag: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Text(row.item.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val meta = listOfNotNull(row.item.kind.label, Format.size(row.item.totalSize), row.dateLabel()?.let { "añadido el $it" })
                Text(meta.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioBadge(row.status)
                    val src = if (row.source == AudioSource.ANALYZED) row.analysis?.languages?.joinToString(", ") ?: "" else row.source.label
                    Text(src, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    if (showDupTag && row.duplicate != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.clickable(onClick = onDupTag),
                        ) {
                            Text("Duplicado", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(state: UiState, vm: MainViewModel, onDelete: () -> Unit) {
    BottomAppBar {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            val sel = state.selectedRows
            Text(
                state.analyzing ?: if (sel.isEmpty()) "Ninguno seleccionado"
                else "${sel.size} seleccionados · ${Format.size(sel.sumOf { it.item.totalSize })} a liberar",
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.filter == AudioFilter.DUPS) {
                    OutlinedButton(onClick = vm::selectExtraCopies) { Text("Sobrantes", fontSize = 13.sp) }
                }
                OutlinedButton(onClick = vm::analyzeSelected, enabled = sel.isNotEmpty() && state.analyzing == null) {
                    Text("Analizar", fontSize = 13.sp)
                }
                Spacer(Modifier.width(0.dp).weight(1f))
                Button(
                    onClick = onDelete,
                    enabled = sel.isNotEmpty() && !state.loading,
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                ) { Text("Eliminar", fontSize = 13.sp) }
            }
        }
    }
}
