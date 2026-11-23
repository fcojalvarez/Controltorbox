package com.controltorbox.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Paleta del rediseño (la del mock), con variante oscura. */
@Immutable
data class AppColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val border: Color,
    val borderStrong: Color,
    val chip: Color,
    val chipText: Color,
    val accent: Color,
    val accentDark: Color,
    val accentSoft: Color,
    val orange: Color,
    val orangeSoft: Color,
    val green: Color,
    val greenSoft: Color,
    val greenText: Color,
    val onAccent: Color,
)

val LightColors = AppColors(
    bg = Color(0xFFF4F6FA), surface = Color.White, text = Color(0xFF14171F), muted = Color(0xFF5B6474),
    faint = Color(0xFF8A93A3), border = Color(0xFFE3E6EC), borderStrong = Color(0xFFC9CFDA),
    chip = Color(0xFFEEF0F4), chipText = Color(0xFF4A5262),
    accent = Color(0xFF2457C5), accentDark = Color(0xFF1A3F91), accentSoft = Color(0xFFE6EEFD),
    orange = Color(0xFFB4500E), orangeSoft = Color(0xFFFDEFE4),
    green = Color(0xFF17703F), greenSoft = Color(0xFFE3F4EA), greenText = Color(0xFF145F35),
    onAccent = Color.White,
)

val DarkColors = AppColors(
    bg = Color(0xFF101318), surface = Color(0xFF1A1E26), text = Color(0xFFE8EAEF), muted = Color(0xFFA3ABBA),
    faint = Color(0xFF7D8596), border = Color(0xFF2A303B), borderStrong = Color(0xFF3B4352),
    chip = Color(0xFF262B35), chipText = Color(0xFFC3C9D4),
    accent = Color(0xFF8AB0F5), accentDark = Color(0xFFB7CEF9), accentSoft = Color(0xFF1E2C47),
    orange = Color(0xFFF39A5B), orangeSoft = Color(0xFF3A2416),
    green = Color(0xFF5CCB8C), greenSoft = Color(0xFF173226), greenText = Color(0xFF8FE0B1),
    onAccent = Color(0xFF0D1524),
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

object C {
    val c: AppColors @Composable get() = LocalAppColors.current
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.bg, surface = colors.surface,
            onSurface = colors.text, onBackground = colors.text, surfaceVariant = colors.chip, onSurfaceVariant = colors.muted,
            outline = colors.borderStrong, outlineVariant = colors.border,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.bg, surface = colors.surface,
            onSurface = colors.text, onBackground = colors.text, surfaceVariant = colors.chip, onSurfaceVariant = colors.muted,
            outline = colors.borderStrong, outlineVariant = colors.border,
        )
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Iconos de trazo (mismos dibujos que el mock, viewBox 24). */
object Ic {
    private fun stroke(name: String, vararg paths: String, width: Float = 2f) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    val Home = stroke("home", "M3 11l9-8 9 8", "M5 10v10h14V10")
    val Library = stroke("library", "M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M3 9h18", "M8 4v5", "M16 4v5")
    /** Engranaje de Material (el dibujo anterior parecía un sol). */
    val Settings: ImageVector = Icons.Outlined.Settings
    val Refresh = stroke("refresh", "M21 12a9 9 0 1 1-3-6.7", "M21 3v6h-6")
    val Back = stroke("back", "M15 6l-6 6 6 6")
    val Chevron = stroke("chevron", "M9 6l6 6-6 6")
    val Download = stroke("download", "M12 3v12", "M7 10l5 5 5-5", "M5 21h14")
    val NoAudio = stroke("noaudio", "M11 5L6 9H2v6h4l5 4V5z", "M22 9l-6 6", "M16 9l6 6")
    val Copy = stroke("copy", "M10 8h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", "M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3")
    val Wave = stroke("wave", "M3 10v4", "M7 6v12", "M11 3v18", "M15 7v10", "M19 10v4")
    val Search = stroke("search", "M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0z", "M20 20l-4-4")
    val Sort = stroke("sort", "M7 4v16", "M3 16l4 4 4-4", "M17 20V4", "M13 8l4-4 4 4")
    val Check = stroke("check", "M5 12l5 5 9-10", width = 3f)
    val Trash = stroke("trash", "M3 6h18", "M8 6V4h8v2", "M6 6l1 14h10l1-14")
    val Info = stroke("info", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z", "M12 8v5", "M12 16h.01")
    val Close = stroke("close", "M6 6l12 12", "M18 6L6 18")
    val Movie = stroke("movie", "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 4v16", "M17 4v16")
    val Tv = stroke("tv", "M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M8 21h8")
    val Clock = stroke("clock", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z", "M12 7v5l3 2")
    val Muted = stroke("muted", "M5 5l14 14", "M7 4l13 8-4 2.5", "M7 11v9l5-3")
    val Play: ImageVector = ImageVector.Builder("play", 24.dp, 24.dp, 24f, 24f)
        .apply { addPath(addPathNodes("M7 4l13 8-13 8z"), fill = SolidColor(Color.Black)) }.build()
    val Pause: ImageVector = ImageVector.Builder("pause", 24.dp, 24.dp, 24f, 24f)
        .apply {
            addPath(addPathNodes("M6 5h4v14h-4z"), fill = SolidColor(Color.Black))
            addPath(addPathNodes("M14 5h4v14h-4z"), fill = SolidColor(Color.Black))
        }.build()
}
