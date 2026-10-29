package dev.eltermino.core.terminal

enum class TerminalKey {
    Enter, Backspace, Tab, Escape,
    Up, Down, Left, Right,
    Home, End, PageUp, PageDown, Insert, Delete,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

data class KeyModifiers(
    val shift: Boolean = false,
    val alt: Boolean = false,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
) {
    val isEmpty: Boolean get() = !shift && !alt && !ctrl && !meta

    operator fun plus(other: KeyModifiers) = KeyModifiers(
        shift = shift || other.shift,
        alt = alt || other.alt,
        ctrl = ctrl || other.ctrl,
        meta = meta || other.meta,
    )

    /** The xterm modifier parameter: 1 + shift(1) + alt(2) + ctrl(4) + meta(8). */
    internal val xtermParameter: Int
        get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0) + (if (meta) 8 else 0)

    companion object {
        val None = KeyModifiers()
        val Ctrl = KeyModifiers(ctrl = true)
        val Alt = KeyModifiers(alt = true)
        val Shift = KeyModifiers(shift = true)
    }
}

/** Terminal modes set by the remote program that change what keys send (DECCKM, DECKPAM). */
data class KeyboardMode(
    val applicationCursorKeys: Boolean = false,
    val applicationKeypad: Boolean = false,
)

/** Translates keys into the byte sequences xterm sends, which is what `TERM=xterm-256color` promises. */
object KeyEncoder {
    private const val ESC = "\u001B"

    fun encode(key: TerminalKey, modifiers: KeyModifiers = KeyModifiers.None, mode: KeyboardMode = KeyboardMode()): ByteArray =
        encodeToString(key, modifiers, mode).encodeToByteArray()

    /**
     * A typed character with modifiers: Ctrl maps to the C0 control codes, Alt prefixes ESC.
     * Shift is expected to be folded into [codePoint] already ('a' vs 'A').
     */
    fun encodeChar(codePoint: Int, modifiers: KeyModifiers = KeyModifiers.None): ByteArray {
        val base = if (modifiers.ctrl) controlCode(codePoint) ?: codePointToString(codePoint) else codePointToString(codePoint)
        return (if (modifiers.alt) ESC + base else base).encodeToByteArray()
    }

    fun encodeChar(char: Char, modifiers: KeyModifiers = KeyModifiers.None): ByteArray = encodeChar(char.code, modifiers)

    internal fun encodeToString(key: TerminalKey, modifiers: KeyModifiers, mode: KeyboardMode): String {
        val m = modifiers.xtermParameter
        val modified = m > 1
        val altPrefix = if (modifiers.alt) ESC else ""
        return when (key) {
            TerminalKey.Enter -> altPrefix + "\r"
            TerminalKey.Backspace -> altPrefix + if (modifiers.ctrl) "\u0008" else "\u007F"
            TerminalKey.Tab -> if (modifiers.shift) "$ESC[Z" else altPrefix + "\t"
            TerminalKey.Escape -> altPrefix + ESC

            TerminalKey.Up -> cursor('A', m, mode)
            TerminalKey.Down -> cursor('B', m, mode)
            TerminalKey.Right -> cursor('C', m, mode)
            TerminalKey.Left -> cursor('D', m, mode)
            TerminalKey.Home -> cursor('H', m, mode)
            TerminalKey.End -> cursor('F', m, mode)

            TerminalKey.Insert -> tilde(2, m)
            TerminalKey.Delete -> tilde(3, m)
            TerminalKey.PageUp -> tilde(5, m)
            TerminalKey.PageDown -> tilde(6, m)

            TerminalKey.F1 -> if (modified) "$ESC[1;${m}P" else "${ESC}OP"
            TerminalKey.F2 -> if (modified) "$ESC[1;${m}Q" else "${ESC}OQ"
            TerminalKey.F3 -> if (modified) "$ESC[1;${m}R" else "${ESC}OR"
            TerminalKey.F4 -> if (modified) "$ESC[1;${m}S" else "${ESC}OS"
            TerminalKey.F5 -> tilde(15, m)
            TerminalKey.F6 -> tilde(17, m)
            TerminalKey.F7 -> tilde(18, m)
            TerminalKey.F8 -> tilde(19, m)
            TerminalKey.F9 -> tilde(20, m)
            TerminalKey.F10 -> tilde(21, m)
            TerminalKey.F11 -> tilde(23, m)
            TerminalKey.F12 -> tilde(24, m)
        }
    }

    // Unmodified cursor keys switch to SS3 in application mode; modified ones always use CSI 1;m.
    private fun cursor(final: Char, m: Int, mode: KeyboardMode): String = when {
        m > 1 -> "$ESC[1;$m$final"
        mode.applicationCursorKeys -> "${ESC}O$final"
        else -> "$ESC[$final"
    }

    private fun tilde(code: Int, m: Int): String = if (m > 1) "$ESC[$code;$m~" else "$ESC[$code~"

    private fun controlCode(codePoint: Int): String? {
        val code = when (codePoint) {
            in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
            in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
            ' '.code, '@'.code, '2'.code -> 0
            '['.code, '3'.code -> 27
            '\\'.code, '4'.code -> 28
            ']'.code, '5'.code -> 29
            '^'.code, '6'.code, '~'.code -> 30
            '_'.code, '7'.code, '/'.code, '-'.code -> 31
            '8'.code, '?'.code -> 127
            else -> return null
        }
        return code.toChar().toString()
    }

    private fun codePointToString(codePoint: Int): String =
        if (codePoint < 0x10000) {
            codePoint.toChar().toString()
        } else {
            val offset = codePoint - 0x10000
            charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
        }
}
