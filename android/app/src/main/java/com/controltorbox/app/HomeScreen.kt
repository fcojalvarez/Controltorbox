package com.controltorbox.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(vm: MainViewModel, state: UiState, nav: Nav) {
    val c = C.c
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Controltorbox", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted)
                Text("Tu TorBox", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.text)
            }
            IconButton(onClick = vm::refresh, modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(c.surface)) {
                Icon(Ic.Refresh, "Recargar", tint = c.text, modifier = Modifier.size(20.dp))
            }
        }

        StorageCard(state)

        Column {
            SectionTitle("Para limpiar")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val review = state.reviewRows
                val unknown = state.unknownRows.size
                TaskCard(
                    icon = Ic.NoAudio, tint = c.orange, tileBg = c.orangeSoft,
                    title = "Sin audio en español",
                    subtitle = if (review.isEmpty()) "Todo revisado" else {
                        val sure = state.noEsCount
                        val parts = listOfNotNull(
                            if (sure > 0) "$sure seguro${if (sure > 1) "s" else ""}" else null,
                            if (unknown > 0) "$unknown por comprobar" else null,
                        )
                        parts.joinToString(" + ") + " · hasta ${Format.size(review.sumOf { it.size })}"
                    },
                    action = "Revisar", actionColor = c.orange, enabled = review.isNotEmpty(),
                    onClick = { nav.open(Route.Review) },
                )
                TaskCard(
                    icon = Ic.Copy, tint = c.accent, tileBg = c.accentSoft,
                    title = "Duplicados",
                    subtitle = if (state.duplicateGroups.isEmpty()) "No hay títulos repetidos"
                    else "${state.duplicateGroups.size} título${if (state.duplicateGroups.size > 1) "s" else ""} repetido${if (state.duplicateGroups.size > 1) "s" else ""} · libera ${Format.size(state.duplicateFreeable)}",
                    action = "Revisar", actionColor = c.accent, enabled = state.duplicateGroups.isNotEmpty(),
                    onClick = { nav.open(Route.Dups) },
                )
                AppCard(radius = 16.dp) {
                    Row(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconTile(Ic.Wave, c.chipText, c.chip)
                        Column(Modifier.weight(1f)) {
                            Text("Idioma sin comprobar", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                            Text(
                                state.analyzing ?: if (unknown == 0) "Todo comprobado" else "$unknown elemento${if (unknown > 1) "s" else ""} · el nombre no lo dice",
                                fontSize = 13.sp, color = c.muted,
                            )
                        }
                        if (unknown > 0) PillButton("Analizar", vm::analyzeUnknown, filled = false, enabled = state.analyzing == null, height = 36.dp)
                    }
                }
            }
        }

        val recent = state.recent
        if (recent.isNotEmpty()) {
            Column {
                SectionTitle("Añadido hace poco")
                AppCard(radius = 16.dp) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        recent.forEachIndexed { i, r ->
                            Box(Modifier.fillMaxWidth().clickable { nav.open(Route.Detail(r.key)) }) {
                                ItemLine(
                                    r, showIcon = false, trailingDup = false,
                                    metaOverride = listOfNotNull(Dates.relative(r.createdAt), Format.size(r.size)).joinToString(" · "),
                                )
                            }
                            if (i < recent.lastIndex) HorizontalDivider(color = c.chip)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageCard(state: UiState) {
    val c = C.c
    val used = state.totalSize
    val account = state.accountLimit
    val alert = state.alertLimit
    // La barra llega hasta el límite de la cuenta; si no se conoce, hasta tu aviso.
    val scale = maxOf(account ?: alert ?: used, used, 1L)
    AppCard(radius = 20.dp) {
        Column(Modifier.padding(18.dp)) {
            Row {
                Text("Espacio ocupado", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted, modifier = Modifier.weight(1f))
                state.planName?.let { Text("Plan $it", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.chipText) }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                val (num, unit) = Format.size(used).split(" ").let { it[0] to it.getOrElse(1) { "" } }
                Text(num, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.text)
                Text(" $unit", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.chipText, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Text("${state.rows.size} elementos", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
            }

            val parts = listOf(Kind.TORRENT to c.accent, Kind.USENET to c.accent.copy(alpha = 0.55f), Kind.WEBDL to c.accent.copy(alpha = 0.3f))
                .map { (k, col) -> Triple(k, col, state.sizeOf(k)) }.filter { it.third > 0 }
            val over = alert != null && used > alert
            LimitsBar(parts, scale, used, alert, account, over)

            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                parts.forEach { (k, col, size) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(col))
                        Text("  ${k.label} ${Format.size(size)}", fontSize = 12.sp, color = c.chipText)
                    }
                }
            }
        }
    }
}

/**
 * Barra de espacio con los límites escritos dentro: el final es el límite de la cuenta
 * (o tu aviso si no se conoce) y una marca vertical señala tu aviso.
 */
@Composable
private fun LimitsBar(parts: List<Triple<Kind, Color, Long>>, scale: Long, used: Long, alert: Long?, account: Long?, over: Boolean) {
    val c = C.c
    val usedFraction = (used.toFloat() / scale).coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.padding(top = 14.dp).fillMaxWidth().height(24.dp).clip(RoundedCornerShape(8.dp)).background(c.chip)) {
        val w = maxWidth
        // Relleno por tipo de descarga.
        Row(Modifier.fillMaxSize()) {
            var filled = 0f
            parts.forEach { (_, col, size) ->
                val f = (size.toFloat() / scale).coerceIn(0f, 1f - filled)
                if (f > 0.001f) {
                    Box(Modifier.fillMaxHeight().weight(f).background(if (over) c.orange else col))
                    filled += f
                }
            }
            if (1f - filled > 0.001f) Spacer(Modifier.weight(1f - filled))
        }
        val endLabel = when {
            account != null -> Format.size(account)
            alert != null -> Format.size(alert)
            else -> null
        }
        val endLabelWidth = endLabel?.let { (it.length * 6.2f + 16).dp } ?: 0.dp
        endLabel?.let {
            Text(
                it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = if (usedFraction > 0.97f) Color.White else c.chipText,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            )
        }
        // Marca de tu aviso (solo si la barra llega hasta la cuenta).
        if (account != null && alert != null && alert < scale) {
            val x = w * (alert.toFloat() / scale)
            Box(Modifier.offset(x = x - 1.dp).fillMaxHeight().width(2.dp).background(c.text))
            val label = Format.size(alert)
            val labelWidth = (label.length * 6.2f + 8).dp
            // A la derecha de la marca si cabe antes de la etiqueta final; si no, a la izquierda.
            // Si el relleno pasa de la marca, el texto empieza donde acaba el relleno para que se lea.
            val rightStart = maxOf(x + 5.dp, w * usedFraction + 6.dp)
            val labelX = if (w - rightStart - endLabelWidth >= labelWidth) rightStart else x - labelWidth - 1.dp
            val onFill = (labelX + labelWidth / 2) < w * usedFraction
            if (labelX >= 4.dp) {
                Text(
                    label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    color = if (onFill) Color.White else c.text,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = labelX),
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    icon: ImageVector, tint: Color, tileBg: Color, title: String, subtitle: String,
    action: String, actionColor: Color, enabled: Boolean, onClick: () -> Unit,
) {
    val c = C.c
    AppCard(radius = 16.dp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(icon, tint, tileBg)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(subtitle, fontSize = 13.sp, color = c.muted)
            }
            if (enabled) {
                Text(action, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = actionColor)
                Icon(Ic.Chevron, null, tint = c.faint, modifier = Modifier.size(18.dp))
            }
        }
    }
}
