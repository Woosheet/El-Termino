package dev.eltermino.core.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

internal sealed interface KeyAction {
    data class SendKey(val key: TerminalKey, val modifiers: KeyModifiers) : KeyAction
    data class SendChar(val codePoint: Int, val modifiers: KeyModifiers) : KeyAction
    data object Copy : KeyAction
    data object Paste : KeyAction

    /** Not ours: an app shortcut, or plain text the input field will type. */
    data object PassThrough : KeyAction
}

/**
 * Decides what a hardware key press means for the terminal. Plain printable text is left
 * to the input field (so IMEs and AltGr/Option compositions keep working) unless
 * [inputFieldActive] is false, as during a selection.
 */
internal fun decideKeyAction(
    key: Key,
    codePoint: Int,
    modifiers: KeyModifiers,
    macOs: Boolean,
    inputFieldActive: Boolean,
): KeyAction {
    val (shift, alt, ctrl, meta) = modifiers
    if (macOs && meta) {
        return when (key) {
            Key.C -> KeyAction.Copy
            Key.V -> KeyAction.Paste
            else -> KeyAction.PassThrough
        }
    }
    if (!macOs && ctrl && shift && !alt) {
        when (key) {
            Key.C -> return KeyAction.Copy
            Key.V -> return KeyAction.Paste
        }
    }
    if (shift && !ctrl && !alt && key == Key.Insert) return KeyAction.Paste

    specialKeys[key]?.let { return KeyAction.SendKey(it, modifiers.copy(meta = false)) }

    // Ctrl+Alt together is AltGr on Windows and Linux: it types a character, so leave it alone.
    val altGr = ctrl && alt
    if (ctrl && !altGr) {
        val char = controlChars[key] ?: return KeyAction.PassThrough
        return KeyAction.SendChar(char.code, KeyModifiers(ctrl = true))
    }
    // On macOS Option composes characters (@, #, [ ] on Italian layouts), so it isn't Meta.
    if (alt && !altGr && !macOs) {
        val char = codePoint.takeIf(::isPrintable) ?: controlChars[key]?.code ?: return KeyAction.PassThrough
        return KeyAction.SendChar(char, KeyModifiers(alt = true))
    }
    if (!inputFieldActive && isPrintable(codePoint)) return KeyAction.SendChar(codePoint, KeyModifiers.None)
    return KeyAction.PassThrough
}

private fun isPrintable(codePoint: Int) = codePoint >= 0x20 && codePoint != 0x7F

private val specialKeys: Map<Key, TerminalKey> = mapOf(
    Key.Enter to TerminalKey.Enter,
    Key.NumPadEnter to TerminalKey.Enter,
    Key.Backspace to TerminalKey.Backspace,
    Key.Tab to TerminalKey.Tab,
    Key.Escape to TerminalKey.Escape,
    Key.DirectionUp to TerminalKey.Up,
    Key.DirectionDown to TerminalKey.Down,
    Key.DirectionLeft to TerminalKey.Left,
    Key.DirectionRight to TerminalKey.Right,
    Key.MoveHome to TerminalKey.Home,
    Key.MoveEnd to TerminalKey.End,
    Key.PageUp to TerminalKey.PageUp,
    Key.PageDown to TerminalKey.PageDown,
    Key.Insert to TerminalKey.Insert,
    Key.Delete to TerminalKey.Delete,
    Key.F1 to TerminalKey.F1,
    Key.F2 to TerminalKey.F2,
    Key.F3 to TerminalKey.F3,
    Key.F4 to TerminalKey.F4,
    Key.F5 to TerminalKey.F5,
    Key.F6 to TerminalKey.F6,
    Key.F7 to TerminalKey.F7,
    Key.F8 to TerminalKey.F8,
    Key.F9 to TerminalKey.F9,
    Key.F10 to TerminalKey.F10,
    Key.F11 to TerminalKey.F11,
    Key.F12 to TerminalKey.F12,
)

/** Keys that combine with Ctrl into a control code, by their unshifted character. */
private val controlChars: Map<Key, Char> = buildMap {
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { i, key -> put(key, 'a' + i) }
    val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    digits.forEachIndexed { i, key -> put(key, '0' + i) }
    put(Key.Spacebar, ' ')
    put(Key.LeftBracket, '[')
    put(Key.RightBracket, ']')
    put(Key.Backslash, '\\')
    put(Key.Slash, '/')
    put(Key.Minus, '-')
}

/**
 * Applies [decideKeyAction] to Compose key events. A key press we handle also swallows the
 * typed-character event that may follow it, so nothing reaches the shell twice.
 */
internal class TerminalKeyHandler(private val macOs: Boolean) {
    private var swallowTyped = false

    fun onKeyEvent(
        event: KeyEvent,
        inputFieldActive: Boolean,
        perform: (KeyAction) -> Unit,
    ): Boolean = when (event.type) {
        KeyEventType.KeyDown -> {
            val modifiers = KeyModifiers(
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
                ctrl = event.isCtrlPressed,
                meta = event.isMetaPressed,
            )
            val action = decideKeyAction(event.key, event.utf16CodePoint, modifiers, macOs, inputFieldActive)
            if (action == KeyAction.PassThrough) {
                swallowTyped = false
                false
            } else {
                perform(action)
                swallowTyped = true
                true
            }
        }
        KeyEventType.KeyUp -> {
            swallowTyped = false
            false
        }
        else -> swallowTyped
    }
}
