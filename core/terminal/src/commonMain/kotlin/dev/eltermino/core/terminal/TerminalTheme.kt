package dev.eltermino.core.terminal

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.johnan.terminal.core.TerminalColorScheme

/**
 * Colors for the terminal surface. [ansi] holds the 16 base colors (0–7 normal, 8–15 bright);
 * the 256-color cube and truecolor pass through unchanged.
 */
@Immutable
data class TerminalTheme(
    val name: String,
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val ansi: List<Color>,
) {
    init {
        require(ansi.size == 16) { "A terminal theme needs 16 ANSI colors, got ${ansi.size}" }
    }

    internal fun toColorScheme() = TerminalColorScheme(
        id = name,
        name = name,
        background = background,
        foreground = foreground,
        cursor = cursor,
        black = ansi[0],
        red = ansi[1],
        green = ansi[2],
        yellow = ansi[3],
        blue = ansi[4],
        magenta = ansi[5],
        cyan = ansi[6],
        white = ansi[7],
        brightBlack = ansi[8],
        brightRed = ansi[9],
        brightGreen = ansi[10],
        brightYellow = ansi[11],
        brightBlue = ansi[12],
        brightMagenta = ansi[13],
        brightCyan = ansi[14],
        brightWhite = ansi[15],
    )

    companion object {
        /**
         * The "Carta" light style. Mid tones, so the colors read both as text on the paper
         * background and as backgrounds behind black text (mc, htop, tmux status bars).
         * White stays light because TUIs draw it on blue.
         */
        val CartaLight = TerminalTheme(
            name = "Carta",
            background = Color(0xFFFBFAF7),
            foreground = Color(0xFF2C2C2A),
            cursor = Color(0xFF185FA5),
            ansi = listOf(
                Color(0xFF2C2C2A), // black
                Color(0xFFB8352F), // red
                Color(0xFF4A8418), // green
                Color(0xFFA0710E), // yellow
                Color(0xFF2563A8), // blue
                Color(0xFF9A3F7A), // magenta
                Color(0xFF138487), // cyan
                Color(0xFFC9C7BD), // white
                Color(0xFF7A7972), // bright black
                Color(0xFFE0483F), // bright red
                Color(0xFF6BA52B), // bright green
                Color(0xFFC8961F), // bright yellow
                Color(0xFF4A8BDB), // bright blue
                Color(0xFFC9589A), // bright magenta
                Color(0xFF22A5A0), // bright cyan
                Color(0xFFE6E4DB), // bright white
            ),
        )
    }
}
