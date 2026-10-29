package dev.eltermino.app

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutsTest {
    private fun mac(key: Key, meta: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        Shortcuts.commandFor(key, meta, ctrl, alt, shift, macOs = true)

    private fun pc(key: Key, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        Shortcuts.commandFor(key, meta = false, ctrl = ctrl, alt = alt, shift = shift, macOs = false)

    @Test
    fun macOsUsesCommand() {
        assertEquals(AppCommand.NewTab, mac(Key.T, meta = true))
        assertEquals(AppCommand.Close, mac(Key.W, meta = true))
        assertEquals(AppCommand.NextTab, mac(Key.DirectionRight, meta = true, alt = true))
        assertEquals(AppCommand.PreviousTab, mac(Key.DirectionLeft, meta = true, alt = true))
        assertEquals(AppCommand.NextTab, mac(Key.Tab, ctrl = true))
        assertEquals(AppCommand.PreviousTab, mac(Key.Tab, ctrl = true, shift = true))
        assertEquals(AppCommand.NextTab, mac(Key.RightBracket, meta = true, shift = true))
        assertEquals(AppCommand.GoToTab(0), mac(Key.One, meta = true))
        assertEquals(AppCommand.GoToTab(7), mac(Key.Eight, meta = true))
        assertEquals(AppCommand.GoToTab(-1), mac(Key.Nine, meta = true), "⌘9 is the last tab, as in browsers")
        assertEquals(AppCommand.NewHost, mac(Key.N, meta = true))
        assertEquals(AppCommand.ToggleSidebar, mac(Key.S, meta = true, ctrl = true))
        assertEquals(AppCommand.Settings, mac(Key.Comma, meta = true))
    }

    @Test
    fun macOsLeavesTheTerminalItsKeys() {
        assertNull(mac(Key.C, meta = true), "⌘C copies in the terminal")
        assertNull(mac(Key.DirectionLeft, meta = true), "⌘← goes to the start of the line")
        assertNull(mac(Key.Backspace, meta = true), "⌘⌫ deletes the line")
        assertNull(mac(Key.W, ctrl = true), "Ctrl+W is the shell's")
        assertNull(mac(Key.T, meta = true, shift = true))
    }

    @Test
    fun elsewhereCtrlAloneStaysWithTheShell() {
        assertEquals(AppCommand.NewTab, pc(Key.T, ctrl = true, shift = true))
        assertEquals(AppCommand.Close, pc(Key.W, ctrl = true, shift = true))
        assertNull(pc(Key.W, ctrl = true), "Ctrl+W deletes a word in bash")
        assertNull(pc(Key.T, ctrl = true))
        assertEquals(AppCommand.NextTab, pc(Key.Tab, ctrl = true))
        assertEquals(AppCommand.PreviousTab, pc(Key.PageUp, ctrl = true))
        assertEquals(AppCommand.GoToTab(2), pc(Key.Three, alt = true))
        assertEquals(AppCommand.ToggleSidebar, pc(Key.B, ctrl = true, shift = true))
        assertNull(pc(Key.Three, ctrl = true, alt = true), "Ctrl+Alt is AltGr: it types a character")
    }

    @Test
    fun labelsFollowEachPlatform() {
        assertEquals("⌘T", Shortcuts.label(Shortcut(Key.T, meta = true), macOs = true))
        assertEquals("⌥⌘→", Shortcuts.label(Shortcut(Key.DirectionRight, meta = true, alt = true), macOs = true))
        assertEquals("⌃⌘S", Shortcuts.label(Shortcut(Key.S, meta = true, ctrl = true), macOs = true))
        assertEquals("⌘,", Shortcuts.label(Shortcut(Key.Comma, meta = true), macOs = true))
        assertEquals("Ctrl+Shift+T", Shortcuts.label(Shortcut(Key.T, ctrl = true, shift = true), macOs = false))
        assertEquals("⌘1", Shortcuts.tabLabel(1, macOs = true))
        assertEquals("Alt+9", Shortcuts.tabLabel(9, macOs = false))
    }
}
