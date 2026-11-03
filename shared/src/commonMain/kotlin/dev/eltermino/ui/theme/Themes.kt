package dev.eltermino.ui.theme

import androidx.compose.ui.graphics.Color
import dev.eltermino.core.terminal.TerminalTheme

/** A complete look: the app's colours and the terminal's, chosen together. */
data class AppTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val colors: CartaColors,
    val terminal: TerminalTheme,
)

/** "system" follows the OS: Carta by day, Notte when the system is dark. */
const val SYSTEM_THEME = "system"

private val lightGroups = CartaLight.groupStrong to CartaLight.groupSoft

// Lighter tones on dark tints, so group labels read on every dark theme.
private val darkGroupStrong = listOf(Color(0xFF7DB8F5), Color(0xFF9DD58A), Color(0xFFB59CF2), Color(0xFFF2A48A), Color(0xFFE8B86B), Color(0xFFF29CC0), Color(0xFFA9B6C8))
private val darkGroupSoft = listOf(Color(0xFF17375C), Color(0xFF1F3A2A), Color(0xFF2D2A52), Color(0xFF43291F), Color(0xFF3F3219), Color(0xFF43243A), Color(0xFF26344A))

private fun ansi(vararg hex: Long) = hex.map { Color(it) }

val CartaNight = CartaColors(
    background = Color(0xFF0F1B2D),
    chrome = Color(0xFF14233A),
    text = Color(0xFFE3EAF4),
    textDim = Color(0xFF8FA3BF),
    line = Color(0xFF22344F),
    lineStrong = Color(0xFF2E4566),
    hover = Color(0xFF1B2C47),
    pressed = Color(0xFF22385A),
    accent = Color(0xFF5AA9F0),
    accentHover = Color(0xFF74B8F4),
    accentPressed = Color(0xFF4A97DD),
    accentBackground = Color(0xFF17375C),
    danger = Color(0xFFF08A8A),
    dangerBackground = Color(0xFF3A1E28),
    scrim = Color(0x99020814),
    online = Color(0xFF7BC96F),
    offline = Color(0xFF4E617D),
    terminalBackground = Color(0xFF0B1626),
    terminalText = Color(0xFFDCE6F2),
    syntaxKey = Color(0xFF7DB8F5),
    syntaxString = Color(0xFF9DD58A),
    syntaxNumber = Color(0xFFE8B86B),
    syntaxComment = Color(0xFF6E83A3),
    syntaxKeyword = Color(0xFFB59CF2),
    groupStrong = darkGroupStrong,
    groupSoft = darkGroupSoft,
)

private val NightTerminal = TerminalTheme(
    name = "Notte",
    background = Color(0xFF0B1626),
    foreground = Color(0xFFDCE6F2),
    cursor = Color(0xFF5AA9F0),
    ansi = ansi(
        0xFF1B2A40, 0xFFF07178, 0xFFA5D97D, 0xFFF2C46D, 0xFF6CB6FF, 0xFFD69CF2, 0xFF67D4D9, 0xFFC8D3E3,
        0xFF5B6F8C, 0xFFFF8C93, 0xFFBDEB98, 0xFFFFD98A, 0xFF91CAFF, 0xFFE6B5FF, 0xFF8BE6EA, 0xFFF2F6FB,
    ),
)

/** Arctic Ice Studio's Nord: polar night background, frost blue accent. */
val NordColors = CartaColors(
    background = Color(0xFF2E3440),
    chrome = Color(0xFF3B4252),
    text = Color(0xFFECEFF4),
    textDim = Color(0xFF9AA5B8),
    line = Color(0xFF434C5E),
    lineStrong = Color(0xFF4C566A),
    hover = Color(0xFF434C5E),
    pressed = Color(0xFF4C566A),
    accent = Color(0xFF88C0D0),
    accentHover = Color(0xFF8FBCBB),
    accentPressed = Color(0xFF81A1C1),
    accentBackground = Color(0xFF384A5C),
    danger = Color(0xFFBF616A),
    dangerBackground = Color(0xFF4A3138),
    scrim = Color(0x99151A21),
    online = Color(0xFFA3BE8C),
    offline = Color(0xFF616E88),
    terminalBackground = Color(0xFF2E3440),
    terminalText = Color(0xFFD8DEE9),
    syntaxKey = Color(0xFF81A1C1),
    syntaxString = Color(0xFFA3BE8C),
    syntaxNumber = Color(0xFFD08770),
    syntaxComment = Color(0xFF616E88),
    syntaxKeyword = Color(0xFFB48EAD),
    groupStrong = darkGroupStrong,
    groupSoft = darkGroupSoft,
)

private val NordTerminal = TerminalTheme(
    name = "Nord",
    background = Color(0xFF2E3440),
    foreground = Color(0xFFD8DEE9),
    cursor = Color(0xFF88C0D0),
    ansi = ansi(
        0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
        0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4,
    ),
)

/** Ethan Schoonover's Solarized, light variant. */
val SolarizedLightColors = CartaColors(
    background = Color(0xFFFDF6E3),
    chrome = Color(0xFFEEE8D5),
    text = Color(0xFF3B4F57),
    textDim = Color(0xFF839496),
    line = Color(0xFFE4DDC8),
    lineStrong = Color(0xFFD6CEB5),
    hover = Color(0xFFEEE8D5),
    pressed = Color(0xFFE4DDC8),
    accent = Color(0xFF268BD2),
    accentHover = Color(0xFF1F78B8),
    accentPressed = Color(0xFF1A6AA3),
    accentBackground = Color(0xFFDFE9EE),
    danger = Color(0xFFDC322F),
    dangerBackground = Color(0xFFF8E4DC),
    scrim = Color(0x66002B36),
    online = Color(0xFF859900),
    offline = Color(0xFF93A1A1),
    terminalBackground = Color(0xFFFDF6E3),
    terminalText = Color(0xFF586E75),
    syntaxKey = Color(0xFF268BD2),
    syntaxString = Color(0xFF2AA198),
    syntaxNumber = Color(0xFFD33682),
    syntaxComment = Color(0xFF93A1A1),
    syntaxKeyword = Color(0xFF859900),
    groupStrong = lightGroups.first,
    groupSoft = lightGroups.second,
)

private val SolarizedLightTerminal = TerminalTheme(
    name = "Solarized chiaro",
    background = Color(0xFFFDF6E3),
    foreground = Color(0xFF586E75),
    cursor = Color(0xFF586E75),
    ansi = ansi(
        0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
        0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83, 0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
    ),
)

/** Zeno Rocha's Dracula. */
val DraculaColors = CartaColors(
    background = Color(0xFF282A36),
    chrome = Color(0xFF21222C),
    text = Color(0xFFF8F8F2),
    textDim = Color(0xFFA4A8C4),
    line = Color(0xFF3A3C4E),
    lineStrong = Color(0xFF44475A),
    hover = Color(0xFF343746),
    pressed = Color(0xFF44475A),
    accent = Color(0xFFBD93F9),
    accentHover = Color(0xFFCAA9FA),
    accentPressed = Color(0xFFA87EF5),
    accentBackground = Color(0xFF3D3655),
    danger = Color(0xFFFF5555),
    dangerBackground = Color(0xFF4A2A33),
    scrim = Color(0x99000000),
    online = Color(0xFF50FA7B),
    offline = Color(0xFF6272A4),
    terminalBackground = Color(0xFF282A36),
    terminalText = Color(0xFFF8F8F2),
    syntaxKey = Color(0xFF8BE9FD),
    syntaxString = Color(0xFFF1FA8C),
    syntaxNumber = Color(0xFFBD93F9),
    syntaxComment = Color(0xFF6272A4),
    syntaxKeyword = Color(0xFFFF79C6),
    groupStrong = darkGroupStrong,
    groupSoft = darkGroupSoft,
)

private val DraculaTerminal = TerminalTheme(
    name = "Dracula",
    background = Color(0xFF282A36),
    foreground = Color(0xFFF8F8F2),
    cursor = Color(0xFFF8F8F2),
    ansi = ansi(
        0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C, 0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
        0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5, 0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
    ),
)

/** enkia's Tokyo Night. */
val TokyoNightColors = CartaColors(
    background = Color(0xFF1A1B26),
    chrome = Color(0xFF16161E),
    text = Color(0xFFC0CAF5),
    textDim = Color(0xFF8A93BE),
    line = Color(0xFF292E42),
    lineStrong = Color(0xFF3B4261),
    hover = Color(0xFF24283B),
    pressed = Color(0xFF292E42),
    accent = Color(0xFF7AA2F7),
    accentHover = Color(0xFF89B4FA),
    accentPressed = Color(0xFF6A8FE0),
    accentBackground = Color(0xFF283457),
    danger = Color(0xFFF7768E),
    dangerBackground = Color(0xFF3F2430),
    scrim = Color(0x99000000),
    online = Color(0xFF9ECE6A),
    offline = Color(0xFF565F89),
    terminalBackground = Color(0xFF1A1B26),
    terminalText = Color(0xFFC0CAF5),
    syntaxKey = Color(0xFF7AA2F7),
    syntaxString = Color(0xFF9ECE6A),
    syntaxNumber = Color(0xFFFF9E64),
    syntaxComment = Color(0xFF565F89),
    syntaxKeyword = Color(0xFFBB9AF7),
    groupStrong = darkGroupStrong,
    groupSoft = darkGroupSoft,
)

private val TokyoNightTerminal = TerminalTheme(
    name = "Tokyo Night",
    background = Color(0xFF1A1B26),
    foreground = Color(0xFFC0CAF5),
    cursor = Color(0xFFC0CAF5),
    ansi = ansi(
        0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
        0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
    ),
)

// Terminal colours on their own, from each scheme's published palette, for any app theme.

/** Catppuccin, the dark Mocha flavour. */
private val CatppuccinMocha = TerminalTheme(
    name = "Catppuccin Mocha",
    background = Color(0xFF1E1E2E),
    foreground = Color(0xFFCDD6F4),
    cursor = Color(0xFFF5E0DC),
    ansi = ansi(
        0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF, 0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFBAC2DE,
        0xFF585B70, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF, 0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFA6ADC8,
    ),
)

/** Catppuccin, the light Latte flavour. */
private val CatppuccinLatte = TerminalTheme(
    name = "Catppuccin Latte",
    background = Color(0xFFEFF1F5),
    foreground = Color(0xFF4C4F69),
    cursor = Color(0xFFDC8A78),
    ansi = ansi(
        0xFF5C5F77, 0xFFD20F39, 0xFF40A02B, 0xFFDF8E1D, 0xFF1E66F5, 0xFFEA76CB, 0xFF179299, 0xFFACB0BE,
        0xFF6C6F85, 0xFFD20F39, 0xFF40A02B, 0xFFDF8E1D, 0xFF1E66F5, 0xFFEA76CB, 0xFF179299, 0xFFBCC0CC,
    ),
)

/** Pavel Pertsev's Gruvbox, dark with medium contrast. */
private val GruvboxDark = TerminalTheme(
    name = "Gruvbox scuro",
    background = Color(0xFF282828),
    foreground = Color(0xFFEBDBB2),
    cursor = Color(0xFFEBDBB2),
    ansi = ansi(
        0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921, 0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
        0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F, 0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2,
    ),
)

/** Gruvbox, light with medium contrast. */
private val GruvboxLight = TerminalTheme(
    name = "Gruvbox chiaro",
    background = Color(0xFFFBF1C7),
    foreground = Color(0xFF3C3836),
    cursor = Color(0xFF3C3836),
    ansi = ansi(
        0xFFFBF1C7, 0xFFCC241D, 0xFF98971A, 0xFFD79921, 0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFF7C6F64,
        0xFF928374, 0xFF9D0006, 0xFF79740E, 0xFFB57614, 0xFF076678, 0xFF8F3F71, 0xFF427B58, 0xFF3C3836,
    ),
)

/** Atom's One Dark. Black is a step off the background, so black text still shows. */
private val OneDark = TerminalTheme(
    name = "One Dark",
    background = Color(0xFF282C34),
    foreground = Color(0xFFABB2BF),
    cursor = Color(0xFF528BFF),
    ansi = ansi(
        0xFF3F4451, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
        0xFF5C6370, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFFFFFFF,
    ),
)

/** Atom's One Light. */
private val OneLight = TerminalTheme(
    name = "One Light",
    background = Color(0xFFFAFAFA),
    foreground = Color(0xFF383A42),
    cursor = Color(0xFF526FFF),
    ansi = ansi(
        0xFF383A42, 0xFFE45649, 0xFF50A14F, 0xFFC18401, 0xFF4078F2, 0xFFA626A4, 0xFF0184BC, 0xFFA0A1A7,
        0xFF696C77, 0xFFE45649, 0xFF50A14F, 0xFFC18401, 0xFF4078F2, 0xFFA626A4, 0xFF0184BC, 0xFFCACACC,
    ),
)

/** Ethan Schoonover's Solarized, dark variant, with the usual bright slots for the base tones. */
private val SolarizedDark = TerminalTheme(
    name = "Solarized scuro",
    background = Color(0xFF002B36),
    foreground = Color(0xFF839496),
    cursor = Color(0xFF93A1A1),
    ansi = ansi(
        0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
        0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83, 0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
    ),
)

/** Wimer Hazenberg's Monokai. */
private val Monokai = TerminalTheme(
    name = "Monokai",
    background = Color(0xFF272822),
    foreground = Color(0xFFF8F8F2),
    cursor = Color(0xFFF8F8F0),
    ansi = ansi(
        0xFF272822, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75, 0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF8F8F2,
        0xFF75715E, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75, 0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF9F8F5,
    ),
)

/** The terminal setting that keeps the colours of the app's theme. */
const val FOLLOW_APP_TERMINAL = "app"

object Themes {
    val Carta = AppTheme("carta", "Carta", dark = false, colors = CartaLight, terminal = TerminalTheme.CartaLight)
    val Notte = AppTheme("notte", "Notte", dark = true, colors = CartaNight, terminal = NightTerminal)
    val Nord = AppTheme("nord", "Nord", dark = true, colors = NordColors, terminal = NordTerminal)
    val SolarizedLight = AppTheme("solarized-chiaro", "Solarized chiaro", dark = false, colors = SolarizedLightColors, terminal = SolarizedLightTerminal)
    val Dracula = AppTheme("dracula", "Dracula", dark = true, colors = DraculaColors, terminal = DraculaTerminal)
    val TokyoNight = AppTheme("tokyo-night", "Tokyo Night", dark = true, colors = TokyoNightColors, terminal = TokyoNightTerminal)

    val all = listOf(Carta, Notte, Nord, SolarizedLight, Dracula, TokyoNight)

    /** The theme for setting [id]; [SYSTEM_THEME] or an unknown id follows [systemDark]. */
    fun resolve(id: String, systemDark: Boolean): AppTheme =
        all.firstOrNull { it.id == id } ?: if (systemDark) Notte else Carta

    /** Every terminal palette by id: the app themes' own, under the same ids, then the extra ones. */
    val terminals: List<Pair<String, TerminalTheme>> = all.map { it.id to it.terminal } + listOf(
        "catppuccin-mocha" to CatppuccinMocha,
        "catppuccin-latte" to CatppuccinLatte,
        "gruvbox-scuro" to GruvboxDark,
        "gruvbox-chiaro" to GruvboxLight,
        "one-dark" to OneDark,
        "one-light" to OneLight,
        "solarized-scuro" to SolarizedDark,
        "monokai" to Monokai,
    )

    /** The terminal colours for setting [id]; [FOLLOW_APP_TERMINAL] or an unknown id keeps [app]'s. */
    fun terminalFor(id: String, app: AppTheme): TerminalTheme = terminals.firstOrNull { it.first == id }?.second ?: app.terminal
}
