package dev.eltermino.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Design tokens of the "Carta" style. Components read these instead of raw colors, so a
 * theme is just another [CartaColors] instance; see [Themes].
 */
@Immutable
data class CartaColors(
    val background: Color,
    val chrome: Color,
    val text: Color,
    val textDim: Color,
    val line: Color,
    /** Borders that must stay visible on [chrome], such as secondary buttons. */
    val lineStrong: Color,
    /** Background of rows and buttons under the pointer. */
    val hover: Color,
    val pressed: Color,
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val accentBackground: Color,
    val danger: Color,
    val dangerBackground: Color,
    val scrim: Color,
    val online: Color,
    val offline: Color,
    val terminalBackground: Color,
    val terminalText: Color,
    /** Syntax highlighting in the remote editor. */
    val syntaxKey: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
    val syntaxComment: Color,
    val syntaxKeyword: Color,
    /** Tab group colours, strong (text, underline) and soft (label background), in `GroupColor` order. */
    val groupStrong: List<Color>,
    val groupSoft: List<Color>,
)

val CartaLight = CartaColors(
    background = Color(0xFFFFFFFF),
    chrome = Color(0xFFF4F3EF),
    text = Color(0xFF2C2C2A),
    textDim = Color(0xFF6B6A65),
    line = Color(0xFFE2E0D8),
    lineStrong = Color(0xFFD3D1C7),
    hover = Color(0xFFECEAE4),
    pressed = Color(0xFFE2E0D8),
    accent = Color(0xFF185FA5),
    accentHover = Color(0xFF14518C),
    accentPressed = Color(0xFF0C447C),
    accentBackground = Color(0xFFE6F1FB),
    danger = Color(0xFFA32D2D),
    dangerBackground = Color(0xFFFCEBEB),
    scrim = Color(0x662C2C2A),
    online = Color(0xFF3B6D11),
    offline = Color(0xFFB4B2A9),
    terminalBackground = Color(0xFFFBFAF7),
    terminalText = Color(0xFF2C2C2A),
    syntaxKey = Color(0xFF185FA5),
    syntaxString = Color(0xFF3B6D11),
    syntaxNumber = Color(0xFF854F0B),
    syntaxComment = Color(0xFF8A8880),
    syntaxKeyword = Color(0xFF534AB7),
    // Blu, Verde, Viola, Corallo, Ambra, Rosa, Grigio
    groupStrong = listOf(Color(0xFF185FA5), Color(0xFF3B6D11), Color(0xFF534AB7), Color(0xFF993C1D), Color(0xFF854F0B), Color(0xFF993556), Color(0xFF5F5E5A)),
    groupSoft = listOf(Color(0xFFE6F1FB), Color(0xFFEAF3DE), Color(0xFFEEEDFE), Color(0xFFFAECE7), Color(0xFFFAEEDA), Color(0xFFFBEAF0), Color(0xFFF1EFE8)),
)

/**
 * The few text sizes the app uses (12, 13, 14, 16, 18, 22); components pick from these,
 * never ad-hoc sizes.
 */
@Immutable
data class CartaTypography(
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val small: TextStyle,
    val caption: TextStyle,
    /** A screen's title: the one h1 of a tool screen. */
    val title: TextStyle,
    /** Dialog titles and the product's name. */
    val dialogTitle: TextStyle,
    /** Section and card titles. */
    val heading: TextStyle,
    val label: TextStyle,
)

/** Figures that line up digit for digit, for numbers in columns and anything that updates. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private val Sans = FontFamily.Default

val CartaType = CartaTypography(
    body = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodyStrong = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    small = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp),
    caption = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    title = TextStyle(fontFamily = Sans, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    dialogTitle = TextStyle(fontFamily = Sans, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    heading = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    label = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
)

private val LocalCartaColors = staticCompositionLocalOf { CartaLight }
private val LocalCartaType = staticCompositionLocalOf { CartaType }
private val LocalAppTheme = staticCompositionLocalOf { Themes.Carta }

/** Text style and color inherited by [dev.eltermino.ui.components.Text] and icons. */
val LocalTextStyle = compositionLocalOf { CartaType.body }
val LocalContentColor = compositionLocalOf { CartaLight.text }

object Carta {
    val colors: CartaColors
        @Composable @ReadOnlyComposable get() = LocalCartaColors.current

    val type: CartaTypography
        @Composable @ReadOnlyComposable get() = LocalCartaType.current

    /** The whole theme, for the terminal's colours and light or dark system chrome. */
    val theme: AppTheme
        @Composable @ReadOnlyComposable get() = LocalAppTheme.current

    // Replaced by a bundled JetBrains Mono once compose resources are set up.
    val monospace: FontFamily = FontFamily.Monospace
}

@Composable
fun CartaTheme(theme: AppTheme = Themes.Carta, content: @Composable () -> Unit) {
    val colors = theme.colors
    CompositionLocalProvider(
        LocalAppTheme provides theme,
        LocalCartaColors provides colors,
        LocalCartaType provides CartaType,
        LocalTextStyle provides CartaType.body,
        LocalContentColor provides colors.text,
        LocalTextSelectionColors provides TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accentBackground),
        content = content,
    )
}
