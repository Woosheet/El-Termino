package dev.eltermino.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Design tokens of the "Carta" style. Components read these instead of raw colors,
 * so a dark variant only needs a second [CartaColors] instance.
 */
@Immutable
data class CartaColors(
    val background: Color,
    val chrome: Color,
    val text: Color,
    val textDim: Color,
    val line: Color,
    val accent: Color,
    val accentBackground: Color,
    val online: Color,
    val offline: Color,
    val terminalBackground: Color,
    val terminalText: Color,
)

val CartaLight = CartaColors(
    background = Color(0xFFFFFFFF),
    chrome = Color(0xFFF4F3EF),
    text = Color(0xFF2C2C2A),
    textDim = Color(0xFF6B6A65),
    line = Color(0xFFE2E0D8),
    accent = Color(0xFF185FA5),
    accentBackground = Color(0xFFE6F1FB),
    online = Color(0xFF3B6D11),
    offline = Color(0xFFB4B2A9),
    terminalBackground = Color(0xFFFBFAF7),
    terminalText = Color(0xFF2C2C2A),
)

private val LocalCartaColors = staticCompositionLocalOf { CartaLight }

object Carta {
    val colors: CartaColors
        @Composable @ReadOnlyComposable get() = LocalCartaColors.current

    // Replaced by a bundled JetBrains Mono once compose resources are set up.
    val monospace: FontFamily = FontFamily.Monospace
}

@Composable
fun CartaTheme(colors: CartaColors = CartaLight, content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = colors.accent,
        onPrimary = Color.White,
        primaryContainer = colors.accentBackground,
        onPrimaryContainer = colors.accent,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.background,
        onSurface = colors.text,
        surfaceVariant = colors.chrome,
        onSurfaceVariant = colors.textDim,
        outline = colors.line,
        outlineVariant = colors.line,
    )
    CompositionLocalProvider(LocalCartaColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
