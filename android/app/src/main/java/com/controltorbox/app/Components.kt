package com.controltorbox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ES = Locale("es", "ES")
private val SHORT = DateTimeFormatter.ofPattern("d MMM", ES)
private val SHORT_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", ES)

object Dates {
    fun short(i: Instant?, now: Instant = Instant.now()): String? {
        i ?: return null
        val z = i.atZone(ZoneId.systemDefault())
        val sameYear = z.year == now.atZone(ZoneId.systemDefault()).year
        return (if (sameYear) SHORT else SHORT_YEAR).format(z).replace(".", "")
    }

    fun relative(i: Instant?, now: Instant = Instant.now()): String? {
        i ?: return null
        val d = Duration.between(i, now)
        return when {
            d.toHours() < 1 -> "hace un momento"
            d.toHours() < 24 -> "hace ${d.toHours()} h"
            d.toDays() == 1L -> "ayer"
            d.toDays() < 7 -> "hace ${d.toDays()} días"
            d.toDays() < 14 -> "hace 1 semana"
            d.toDays() < 60 -> "hace ${d.toDays() / 7} semanas"
            else -> short(i, now)
        }
    }
}

@Composable
fun StatusBadge(status: AudioStatus, analyzedOrManual: Boolean = true) {
    val c = C.c
    val (bg, fg, text) = when (status) {
        AudioStatus.ES -> Triple(c.greenSoft, c.green, "Español")
        AudioStatus.NO_ES -> Triple(c.orangeSoft, c.orange, "Sin español")
        AudioStatus.UNKNOWN -> Triple(c.chip, c.chipText, "Sin comprobar")
    }
    Box(Modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, radius: Dp = 18.dp, border: BorderStroke? = null, content: @Composable () -> Unit) {
    Surface(modifier = modifier, color = C.c.surface, shape = RoundedCornerShape(radius), border = border, content = content)
}

@Composable
fun IconTile(icon: ImageVector, tint: Color, bg: Color, size: Dp = 44.dp) {
    Box(Modifier.size(size).background(bg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Ic.Back, "Volver", tint = C.c.text) }
            }
            Text(
                title, fontSize = if (onBack != null) 20.sp else 26.sp, fontWeight = FontWeight.Bold, color = C.c.text,
                modifier = Modifier.weight(1f).padding(vertical = if (onBack != null) 0.dp else 8.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        if (subtitle != null) {
            Text(subtitle, fontSize = 13.sp, lineHeight = 19.sp, color = C.c.muted, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.c.text, modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))
}

@Composable
fun SettingsGroupTitle(text: String) {
    Text(
        text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.c.chipText,
        letterSpacing = 0.5.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = C.c.accent,
    filled: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 40.dp,
    horizontalPadding: Dp = 14.dp,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.size(5.dp))
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    if (filled) {
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = height),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (color == C.c.accent) C.c.onAccent else Color.White),
            contentPadding = PaddingValues(horizontal = horizontalPadding),
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = height),
            border = BorderStroke(1.dp, C.c.borderStrong),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = C.c.surface, contentColor = C.c.text),
            contentPadding = PaddingValues(horizontal = horizontalPadding),
        ) { content() }
    }
}

/** Barra inferior fija con el resumen y la acción principal («Borrar 2»). */
@Composable
fun BottomActionBar(title: String, subtitle: String, button: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(color = C.c.surface) {
        Column {
            HorizontalDivider(color = C.c.border)
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.c.text)
                    Text(subtitle, fontSize = 12.sp, color = C.c.muted)
                }
                PillButton(button, onClick, color = C.c.orange, enabled = enabled, height = 48.dp)
            }
        }
    }
}

@Composable
fun ConfirmDeleteDialog(rows: List<ItemRow>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Borrar de TorBox?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Se borrarán ${rows.size} elemento(s) y liberarás ${Format.size(rows.sumOf { it.size })}:")
                Spacer(Modifier.height(4.dp))
                rows.take(8).forEach { Text("• ${it.title} · ${Format.size(it.size)}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (rows.size > 8) Text("… y ${rows.size - 8} más", fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("No se puede deshacer.", color = C.c.orange, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = C.c.orange, contentColor = Color.White)) {
                Text("Borrar ${rows.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Fila de la lista (Biblioteca, Inicio). */
@Composable
fun ItemLine(row: ItemRow, showIcon: Boolean = true, trailingDup: Boolean = true, metaOverride: String? = null) {
    val c = C.c
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showIcon) {
            IconTile(if (Duplicates.parseName(row.item.name).isSeries) Ic.Tv else Ic.Movie, c.chipText, c.chip, size = 40.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(row.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = metaOverride ?: listOfNotNull(
                row.quality.takeIf { it.isNotBlank() } ?: if (row.item.files.size > 1) "${row.item.files.size} archivos" else null,
                Format.size(row.size),
                Dates.short(row.createdAt),
            ).joinToString(" · ")
            Text(meta, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (row.hasVideo) StatusBadge(row.status)
            if (trailingDup && row.duplicate != null) Text("Duplicado", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
        }
    }
}
