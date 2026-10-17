package io.github.alexandrelam.photos.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The Darkroom palette, taken from the design file. */
@Immutable
data class DarkroomColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    /** Amber used for fills: primary buttons, toggles, progress. */
    val accent: Color,
    /** Text and icons drawn on top of [accent]. */
    val onAccent: Color,
    /** Amber used for text on [background] (darker in light mode for contrast). */
    val accentText: Color,
    /** Background of the selected bottom-bar item. */
    val accentSoft: Color,
    val success: Color,
    /** Destructive actions such as Delete. */
    val danger: Color,
    val isDark: Boolean,
)

private val Dark = DarkroomColors(
    background = Color(0xFF0E0F11),
    surface = Color(0xFF17181B),
    surfaceRaised = Color(0xFF22252B),
    border = Color(0xFF33363D),
    text = Color(0xFFF2F1EE),
    textSecondary = Color(0xFFA4A7AE),
    textMuted = Color(0xFF7D818A),
    accent = Color(0xFFFFB547),
    onAccent = Color(0xFF241703),
    accentText = Color(0xFFFFB547),
    accentSoft = Color(0xFF3A2C14),
    success = Color(0xFF6FD39A),
    danger = Color(0xFFFF6B5E),
    isDark = true,
)

private val Light = DarkroomColors(
    background = Color(0xFFF7F6F3),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEDEBE6),
    border = Color(0xFFD6D4CF),
    text = Color(0xFF17181B),
    textSecondary = Color(0xFF5F636B),
    textMuted = Color(0xFF6B6F77),
    accent = Color(0xFFFFB547),
    onAccent = Color(0xFF241703),
    accentText = Color(0xFFA15C00),
    accentSoft = Color(0xFFFFE7BF),
    success = Color(0xFF2E9D5E),
    danger = Color(0xFFC8382B),
    isDark = false,
)

private val LocalDarkroomColors = staticCompositionLocalOf { Dark }

/** Access the palette with `Darkroom.colors` and text styles with `Darkroom.type`. */
object Darkroom {
    val colors: DarkroomColors
        @Composable get() = LocalDarkroomColors.current

    val type = DarkroomType
}

object DarkroomType {
    val largeTitle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val section = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val bodyRegular = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
fun DarkroomTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) Dark else Light
    val material = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            surface = colors.background,
            surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surface,
            onSurface = colors.text,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
        )
    } else {
        lightColorScheme(
            primary = colors.accentText,
            onPrimary = Color.White,
            background = colors.background,
            surface = colors.background,
            surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surface,
            onSurface = colors.text,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
        )
    }
    CompositionLocalProvider(LocalDarkroomColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
