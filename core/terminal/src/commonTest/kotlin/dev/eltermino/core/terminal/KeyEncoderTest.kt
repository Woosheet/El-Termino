package dev.eltermino.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyEncoderTest {
    private fun enc(key: TerminalKey, mods: KeyModifiers = KeyModifiers.None, mode: KeyboardMode = KeyboardMode()) =
        KeyEncoder.encode(key, mods, mode).decodeToString().visible()

    private fun char(c: Char, mods: KeyModifiers = KeyModifiers.None) = KeyEncoder.encodeChar(c, mods).decodeToString().visible()

    private fun String.visible() = replace("\u001B", "ESC").map { if (it.code < 0x20 || it.code == 0x7F) "^${it.code}" else "$it" }
        .joinToString("")

    private val app = KeyboardMode(applicationCursorKeys = true)

    @Test
    fun basicKeys() {
        assertEquals("^13", enc(TerminalKey.Enter))
        assertEquals("^127", enc(TerminalKey.Backspace))
        assertEquals("^8", enc(TerminalKey.Backspace, KeyModifiers.Ctrl))
        assertEquals("^9", enc(TerminalKey.Tab))
        assertEquals("ESC[Z", enc(TerminalKey.Tab, KeyModifiers.Shift))
        assertEquals("ESC", enc(TerminalKey.Escape))
        assertEquals("ESC^13", enc(TerminalKey.Enter, KeyModifiers.Alt))
        assertEquals("ESC^127", enc(TerminalKey.Backspace, KeyModifiers.Alt))
        assertEquals("ESCESC", enc(TerminalKey.Escape, KeyModifiers.Alt))
    }

    @Test
    fun cursorKeysFollowApplicationMode() {
        assertEquals("ESC[A", enc(TerminalKey.Up))
        assertEquals("ESC[B", enc(TerminalKey.Down))
        assertEquals("ESC[C", enc(TerminalKey.Right))
        assertEquals("ESC[D", enc(TerminalKey.Left))
        assertEquals("ESCOA", enc(TerminalKey.Up, mode = app))
        assertEquals("ESCOD", enc(TerminalKey.Left, mode = app))
        assertEquals("ESC[H", enc(TerminalKey.Home))
        assertEquals("ESC[F", enc(TerminalKey.End))
        assertEquals("ESCOH", enc(TerminalKey.Home, mode = app))
        assertEquals("ESCOF", enc(TerminalKey.End, mode = app))
    }

    @Test
    fun modifiedCursorKeysUseCsiParameters() {
        assertEquals("ESC[1;2A", enc(TerminalKey.Up, KeyModifiers.Shift))
        assertEquals("ESC[1;3D", enc(TerminalKey.Left, KeyModifiers.Alt))
        assertEquals("ESC[1;5C", enc(TerminalKey.Right, KeyModifiers.Ctrl))
        assertEquals("ESC[1;5C", enc(TerminalKey.Right, KeyModifiers.Ctrl, app))
        assertEquals("ESC[1;6B", enc(TerminalKey.Down, KeyModifiers(shift = true, ctrl = true)))
        assertEquals("ESC[1;8H", enc(TerminalKey.Home, KeyModifiers(shift = true, alt = true, ctrl = true)))
    }

    @Test
    fun editingKeys() {
        assertEquals("ESC[2~", enc(TerminalKey.Insert))
        assertEquals("ESC[3~", enc(TerminalKey.Delete))
        assertEquals("ESC[5~", enc(TerminalKey.PageUp))
        assertEquals("ESC[6~", enc(TerminalKey.PageDown))
        assertEquals("ESC[3;5~", enc(TerminalKey.Delete, KeyModifiers.Ctrl))
        assertEquals("ESC[5;2~", enc(TerminalKey.PageUp, KeyModifiers.Shift))
    }

    @Test
    fun functionKeys() {
        assertEquals("ESCOP", enc(TerminalKey.F1))
        assertEquals("ESCOQ", enc(TerminalKey.F2))
        assertEquals("ESCOR", enc(TerminalKey.F3))
        assertEquals("ESCOS", enc(TerminalKey.F4))
        assertEquals("ESC[15~", enc(TerminalKey.F5))
        assertEquals("ESC[17~", enc(TerminalKey.F6))
        assertEquals("ESC[18~", enc(TerminalKey.F7))
        assertEquals("ESC[19~", enc(TerminalKey.F8))
        assertEquals("ESC[20~", enc(TerminalKey.F9))
        assertEquals("ESC[21~", enc(TerminalKey.F10))
        assertEquals("ESC[23~", enc(TerminalKey.F11))
        assertEquals("ESC[24~", enc(TerminalKey.F12))
        assertEquals("ESC[1;2P", enc(TerminalKey.F1, KeyModifiers.Shift))
        assertEquals("ESC[1;5S", enc(TerminalKey.F4, KeyModifiers.Ctrl))
        assertEquals("ESC[15;2~", enc(TerminalKey.F5, KeyModifiers.Shift))
        assertEquals("ESC[24;5~", enc(TerminalKey.F12, KeyModifiers.Ctrl))
    }

    @Test
    fun controlCharacters() {
        assertEquals("^1", char('a', KeyModifiers.Ctrl))
        assertEquals("^3", char('c', KeyModifiers.Ctrl))
        assertEquals("^3", char('C', KeyModifiers.Ctrl))
        assertEquals("^4", char('d', KeyModifiers.Ctrl))
        assertEquals("^26", char('z', KeyModifiers.Ctrl))
        assertEquals("^0", char(' ', KeyModifiers.Ctrl))
        assertEquals("^0", char('@', KeyModifiers.Ctrl))
        assertEquals("ESC", char('[', KeyModifiers.Ctrl))
        assertEquals("^28", char('\\', KeyModifiers.Ctrl))
        assertEquals("^29", char(']', KeyModifiers.Ctrl))
        assertEquals("^30", char('^', KeyModifiers.Ctrl))
        assertEquals("^31", char('_', KeyModifiers.Ctrl))
        assertEquals("^31", char('/', KeyModifiers.Ctrl))
        assertEquals("^127", char('?', KeyModifiers.Ctrl))
        assertEquals("1", char('1', KeyModifiers.Ctrl))
    }

    @Test
    fun altPrefixesEscape() {
        assertEquals("ESCb", char('b', KeyModifiers.Alt))
        assertEquals("ESC.", char('.', KeyModifiers.Alt))
        assertEquals("ESC^24", char('x', KeyModifiers(alt = true, ctrl = true)))
    }

    @Test
    fun nonAsciiCharactersAreUtf8() {
        assertEquals("è".encodeToByteArray().toList(), KeyEncoder.encodeChar('è').toList())
        assertEquals("😀".encodeToByteArray().toList(), KeyEncoder.encodeChar(0x1F600).toList())
        assertEquals(("\u001B" + "é").encodeToByteArray().toList(), KeyEncoder.encodeChar('é', KeyModifiers.Alt).toList())
    }
}
