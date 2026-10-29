package dev.eltermino.core.terminal

/**
 * Something the user typed, pressed or pasted, before it's encoded. Mirroring input as
 * events instead of bytes lets every receiving session encode it for its own modes: an
 * arrow key reaches vim in application mode and a shell in normal mode correctly.
 */
sealed interface TerminalInput {
    /** Typed text, newlines already turned into CR. */
    data class Text(val text: String) : TerminalInput

    data class Key(val key: TerminalKey, val modifiers: KeyModifiers = KeyModifiers.None) : TerminalInput

    data class Char(val codePoint: Int, val modifiers: KeyModifiers = KeyModifiers.None) : TerminalInput

    /** Bracketed by each receiver when its program asked for it. */
    data class Paste(val text: String) : TerminalInput

    class Bytes(val bytes: ByteArray) : TerminalInput
}
