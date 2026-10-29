package dev.eltermino.app

import androidx.compose.ui.input.key.Key

/** What a keyboard shortcut, or the menu bar on macOS, asks the app to do. */
sealed interface AppCommand {
    /** Opens the "+" menu after the tabs: a shell like the focused one, files, a recent host. */
    data object NewTab : AppCommand

    /** Closes the focused pane, or its tab when it's alone there; on another screen, goes back to the sessions. */
    data object Close : AppCommand

    data object NextTab : AppCommand
    data object PreviousTab : AppCommand

    /** The tab at [index] from the left; -1 is the last one. */
    data class GoToTab(val index: Int) : AppCommand

    data object NewHost : AppCommand
    data object ToggleSidebar : AppCommand
    data object Settings : AppCommand
}

/** Keys pressed together; [meta] is ⌘ on macOS. */
data class Shortcut(val key: Key, val meta: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false)

/**
 * The app's shortcuts. On macOS they use ⌘, which the shell never sees. Elsewhere Ctrl and a
 * letter belong to the shell (Ctrl+W deletes a word), so they add Shift, as GNOME Terminal and
 * Windows Terminal do. Next and previous tab avoid [ and ], which need Option on Italian keyboards.
 */
object Shortcuts {
    private val digits = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

    /** The command a key press means, or null when it isn't one of ours. */
    fun commandFor(key: Key, meta: Boolean, ctrl: Boolean, alt: Boolean, shift: Boolean, macOs: Boolean): AppCommand? {
        val pressed = Shortcut(key, meta, ctrl, alt, shift)
        all(macOs).firstOrNull { (_, shortcuts) -> pressed in shortcuts }?.let { return it.first }
        val digit = digits.indexOf(key)
        if (digit >= 0 && pressed == (if (macOs) Shortcut(key, meta = true) else Shortcut(key, alt = true))) {
            return AppCommand.GoToTab(if (digit == 8) -1 else digit)
        }
        return null
    }

    /** Each command with its shortcuts, the first one being the one menus show. */
    fun all(macOs: Boolean): List<Pair<AppCommand, List<Shortcut>>> = if (macOs) {
        listOf(
            AppCommand.NewTab to listOf(Shortcut(Key.T, meta = true)),
            AppCommand.Close to listOf(Shortcut(Key.W, meta = true)),
            AppCommand.NextTab to listOf(Shortcut(Key.DirectionRight, meta = true, alt = true), Shortcut(Key.Tab, ctrl = true), Shortcut(Key.RightBracket, meta = true, shift = true)),
            AppCommand.PreviousTab to listOf(Shortcut(Key.DirectionLeft, meta = true, alt = true), Shortcut(Key.Tab, ctrl = true, shift = true), Shortcut(Key.LeftBracket, meta = true, shift = true)),
            AppCommand.NewHost to listOf(Shortcut(Key.N, meta = true)),
            AppCommand.ToggleSidebar to listOf(Shortcut(Key.S, meta = true, ctrl = true)),
            AppCommand.Settings to listOf(Shortcut(Key.Comma, meta = true)),
        )
    } else {
        listOf(
            AppCommand.NewTab to listOf(Shortcut(Key.T, ctrl = true, shift = true)),
            AppCommand.Close to listOf(Shortcut(Key.W, ctrl = true, shift = true)),
            AppCommand.NextTab to listOf(Shortcut(Key.Tab, ctrl = true), Shortcut(Key.PageDown, ctrl = true)),
            AppCommand.PreviousTab to listOf(Shortcut(Key.Tab, ctrl = true, shift = true), Shortcut(Key.PageUp, ctrl = true)),
            AppCommand.NewHost to listOf(Shortcut(Key.N, ctrl = true, shift = true)),
            AppCommand.ToggleSidebar to listOf(Shortcut(Key.B, ctrl = true, shift = true)),
            AppCommand.Settings to listOf(Shortcut(Key.Comma, ctrl = true)),
        )
    }

    /** "⌘T" or "⌥⌘→" on macOS, "Ctrl+Shift+T" elsewhere. */
    fun label(shortcut: Shortcut, macOs: Boolean): String {
        val key = when (shortcut.key) {
            Key.DirectionRight -> "→"
            Key.DirectionLeft -> "←"
            Key.Tab -> if (macOs) "⇥" else "Tab"
            Key.PageDown -> "PagGiù"
            Key.PageUp -> "PagSu"
            Key.Comma -> ","
            Key.RightBracket -> "]"
            Key.LeftBracket -> "["
            else -> digits.indexOf(shortcut.key).takeIf { it >= 0 }?.let { "${it + 1}" } ?: shortcut.key.toString().removePrefix("Key: ")
        }
        return if (macOs) {
            // The order macOS menus use: Control, Option, Shift, Command.
            buildString {
                if (shortcut.ctrl) append('⌃')
                if (shortcut.alt) append('⌥')
                if (shortcut.shift) append('⇧')
                if (shortcut.meta) append('⌘')
                append(key)
            }
        } else {
            (listOfNotNull("Ctrl".takeIf { shortcut.ctrl }, "Alt".takeIf { shortcut.alt }, "Shift".takeIf { shortcut.shift }) + key).joinToString("+")
        }
    }

    /** What the shortcut for tab [n] (1 to 9) looks like. */
    fun tabLabel(n: Int, macOs: Boolean) = label(Shortcut(digits[n - 1], meta = macOs, alt = !macOs), macOs)
}
