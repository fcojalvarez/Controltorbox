package com.controltorbox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: MainViewModel, state: UiState, nav: Nav) {
    val c = C.c
    val rows = state.libraryRows
    val selecting = state.selected.isNotEmpty()
    var confirm by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (selecting) 96.dp else 24.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    if (selecting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = vm::clearSelection) { Icon(Ic.Close, "Cancelar selección", tint = c.text) }
                            Text("${state.selected.size} seleccionados", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
                            TextButton(onClick = { rows.forEach { if (it.key !in state.selected) vm.toggleSelected(it.key) } }) { Text("Todos") }
                        }
                    } else {
                        Text("Biblioteca", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    OutlinedTextField(
                        value = state.search, onValueChange = vm::setSearch,
                        placeholder = { Text("Buscar película o serie") },
                        leadingIcon = { Icon(Ic.Search, null, tint = c.muted, modifier = Modifier.size(20.dp)) },
                        trailingIcon = {
                            if (state.search.isNotEmpty()) IconButton(onClick = { vm.setSearch("") }) { Icon(Ic.Close, "Borrar búsqueda", modifier = Modifier.size(18.dp)) }
                        },
                        singleLine = true, shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = c.surface, focusedContainerColor = c.surface,
                            unfocusedBorderColor = c.border, focusedBorderColor = c.accent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // El filtro «Caduca pronto», si está activo, va primero para que se vea.
                        LibraryFilter.entries.sortedBy { if (it == LibraryFilter.EXPIRING && state.filter == it) 0 else 1 }.forEach { f ->
                            val n = state.countFor(f)
                            // «Caduca pronto» solo aparece si hay algo que caduca.
                            if (f == LibraryFilter.EXPIRING && n == 0 && state.filter != f) return@forEach
                            val sel = state.filter == f
                            FilterChip(
                                selected = sel, onClick = { vm.setFilter(f) },
                                label = { Text(if (f == LibraryFilter.DUPS) f.label else "${f.label} · $n", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) },
                                shape = RoundedCornerShape(18.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = c.surface, labelColor = c.text,
                                    selectedContainerColor = c.text, selectedLabelColor = c.surface,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = sel, borderColor = c.borderStrong, selectedBorderColor = c.text,
                                ),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${rows.size} elementos · ${Format.size(rows.sumOf { it.size })}", fontSize = 13.sp, color = c.chipText, modifier = Modifier.weight(1f))
                        SortMenu(state.sort, vm::setSort)
                    }
                }
            }
            if (rows.isEmpty()) {
                item { Text("No hay nada con estos filtros.", color = c.muted, modifier = Modifier.padding(24.dp)) }
            }
            itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                val first = i == 0
                val last = i == rows.lastIndex
                val shape = RoundedCornerShape(
                    topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
                    bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp,
                )
                val selected = r.key in state.selected
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(if (selected) c.accentSoft else c.surface)
                        .combinedClickable(
                            onClick = { if (selecting) vm.toggleSelected(r.key) else nav.open(Route.Detail(r.key)) },
                            onLongClick = { vm.toggleSelected(r.key) },
                        )
                        .padding(horizontal = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) {
                            Checkbox(
                                checked = selected, onCheckedChange = { vm.toggleSelected(r.key) },
                                colors = CheckboxDefaults.colors(checkedColor = c.accent),
                            )
                        }
                        Box(Modifier.weight(1f)) { ItemLine(r, showIcon = !selecting) }
                    }
                    if (!last) HorizontalDivider(color = c.chip)
                }
            }
            if (rows.isNotEmpty() && !selecting) {
                item {
                    Text(
                        "Mantén pulsado un elemento para seleccionar varios", fontSize = 12.sp, color = c.muted,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        if (selecting) {
            Surface(
                color = c.surface, shadowElevation = 8.dp, shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, c.border),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${state.selected.size} seleccionados", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
                        Text(Format.size(state.selectedRows.sumOf { it.size }), fontSize = 12.sp, color = c.muted)
                    }
                    PillButton("Borrar", { confirm = true }, color = c.orange, icon = Ic.Trash, height = 44.dp)
                }
            }
        }
    }

    if (confirm) {
        ConfirmDeleteDialog(state.selectedRows, onConfirm = { confirm = false; vm.deleteSelected() }, onDismiss = { confirm = false })
    }
}

@Composable
private fun SortMenu(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    val c = C.c
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Ic.Sort, null, tint = c.text, modifier = Modifier.size(16.dp))
            Text("  ${sort.label}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { onSort(s); open = false }) }
        }
    }
}
