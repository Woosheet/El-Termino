package dev.eltermino.core.terminal

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyActionTest {
    private fun decide(
        key: Key,
        codePoint: Int = 0,
        mods: KeyModifiers = KeyModifiers.None,
        macOs: Boolean = false,
        inputFieldActive: Boolean = true,
    ) = decideKeyAction(key, codePoint, mods, macOs, inputFieldActive)

    @Test
    fun specialKeysAreHandledByUs() {
        assertEquals(KeyAction.SendKey(TerminalKey.Enter, KeyModifiers.None), decide(Key.Enter, '\r'.code))
        assertEquals(KeyAction.SendKey(TerminalKey.Enter, KeyModifiers.None), decide(Key.NumPadEnter))
        assertEquals(KeyAction.SendKey(TerminalKey.Tab, KeyModifiers.None), decide(Key.Tab))
        assertEquals(KeyAction.SendKey(TerminalKey.Tab, KeyModifiers.Shift), decide(Key.Tab, mods = KeyModifiers.Shift))
        assertEquals(KeyAction.SendKey(TerminalKey.Escape, KeyModifiers.None), decide(Key.Escape))
        assertEquals(KeyAction.SendKey(TerminalKey.F10, KeyModifiers.None), decide(Key.F10))
        assertEquals(KeyAction.SendKey(TerminalKey.Left, KeyModifiers.Alt), decide(Key.DirectionLeft, mods = KeyModifiers.Alt, macOs = true))
    }

    @Test
    fun ctrlLettersBecomeControlCodes() {
        assertEquals(KeyAction.SendChar('c'.code, KeyModifiers.Ctrl), decide(Key.C, 3, KeyModifiers.Ctrl))
        assertEquals(KeyAction.SendChar('c'.code, KeyModifiers.Ctrl), decide(Key.C, 3, KeyModifiers.Ctrl, macOs = true))
        assertEquals(KeyAction.SendChar('['.code, KeyModifiers.Ctrl), decide(Key.LeftBracket, mods = KeyModifiers.Ctrl))
        assertEquals(KeyAction.SendChar(' '.code, KeyModifiers.Ctrl), decide(Key.Spacebar, mods = KeyModifiers.Ctrl))
    }

    @Test
    fun copyAndPasteShortcutsNeverReachTheShell() {
        val cmd = KeyModifiers(meta = true)
        val ctrlShift = KeyModifiers(ctrl = true, shift = true)
        assertEquals(KeyAction.Copy, decide(Key.C, 'c'.code, cmd, macOs = true))
        assertEquals(KeyAction.Paste, decide(Key.V, 'v'.code, cmd, macOs = true))
        assertEquals(KeyAction.Copy, decide(Key.C, 'C'.code, ctrlShift))
        assertEquals(KeyAction.Paste, decide(Key.V, 'V'.code, ctrlShift))
        assertEquals(KeyAction.Paste, decide(Key.Insert, mods = KeyModifiers.Shift))
        // Other Cmd shortcuts belong to the app (new tab, close window, ...).
        assertEquals(KeyAction.PassThrough, decide(Key.T, 't'.code, cmd, macOs = true))
    }

    @Test
    fun plainTextIsLeftToTheInputField() {
        assertEquals(KeyAction.PassThrough, decide(Key.A, 'a'.code))
        assertEquals(KeyAction.PassThrough, decide(Key.A, 'A'.code, KeyModifiers.Shift))
        assertEquals(KeyAction.SendChar('a'.code, KeyModifiers.None), decide(Key.A, 'a'.code, inputFieldActive = false))
    }

    @Test
    fun altIsMetaExceptOnMacOs() {
        assertEquals(KeyAction.SendChar('b'.code, KeyModifiers.Alt), decide(Key.B, 'b'.code, KeyModifiers.Alt))
        // Option+ò types @ on an Italian Mac keyboard.
        assertEquals(KeyAction.PassThrough, decide(Key.Unknown, '@'.code, KeyModifiers.Alt, macOs = true))
    }

    @Test
    fun altGrTypesCharacters() {
        // AltGr+ò on an Italian Windows keyboard arrives as Ctrl+Alt with '@'.
        assertEquals(KeyAction.PassThrough, decide(Key.Unknown, '@'.code, KeyModifiers(ctrl = true, alt = true)))
    }
}
