package dev.eltermino.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalThemesTest {
    @Test
    fun theTerminalFollowsTheAppUntilChosenApart() {
        assertEquals(Themes.Carta.terminal, Themes.terminalFor(FOLLOW_APP_TERMINAL, Themes.Carta))
        assertEquals(Themes.Notte.terminal, Themes.terminalFor(FOLLOW_APP_TERMINAL, Themes.Notte))
        assertEquals("Gruvbox scuro", Themes.terminalFor("gruvbox-scuro", Themes.Carta).name)
        assertEquals(Themes.Dracula.terminal, Themes.terminalFor("dracula", Themes.Carta), "an app theme's colours, on another app theme")
        assertEquals(Themes.Carta.terminal, Themes.terminalFor("non-esiste", Themes.Carta), "an id from a newer version")
    }

    @Test
    fun everyTerminalHasItsOwnIdAndName() {
        assertEquals(Themes.terminals.size, Themes.terminals.map { it.first }.toSet().size)
        assertEquals(Themes.terminals.size, Themes.terminals.map { it.second.name }.toSet().size)
        assertTrue(FOLLOW_APP_TERMINAL !in Themes.terminals.map { it.first })
    }

    /** Plain text must read on every background, whatever the palette's taste. */
    @Test
    fun everyTerminalHasReadableText() {
        fun contrast(a: Color, b: Color): Double {
            val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
            return (light + 0.05) / (dark + 0.05)
        }
        Themes.terminals.forEach { (_, theme) ->
            val ratio = contrast(theme.foreground, theme.background)
            assertTrue(ratio >= 4.5, "${theme.name}: text at ${"%.2f".format(ratio)}:1 on its background")
        }
    }
}
