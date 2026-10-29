package dev.eltermino.core.terminal

import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ESC = "\u001B"

class TerminalViewTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shell = FakeShellChannel()
    private val session = TerminalSession(shell, scope)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /** Lets the view measure itself and resize the session, then returns the settled size. */
    private fun HeadlessView.settle(): TerminalSize {
        frames(5)
        eventually({ "size stayed ${session.size.value}" }) { session.size.value != TerminalSize(80, 24) }
        Thread.sleep(150)
        frames(3)
        return session.size.value
    }

    @Test
    fun viewSizesTheTerminalToFit() {
        HeadlessView(session, 1600, 900).use { view ->
            val size = view.settle()
            assertTrue(size.cols > 80 && size.rows > 24, "got $size")
            assertEquals(size, shell.resizes.last())
        }
    }

    @Test
    fun keysPressedInTheViewReachTheShell() {
        HeadlessView(session, 1200, 700).use { view ->
            view.settle()
            assertTrue(view.press(Key.C, 3, ctrl = true), "Ctrl+C was not consumed")
            view.press(Key.Enter, '\r'.code)
            view.press(Key.Tab, '\t'.code)
            view.press(Key.Escape, 27)
            view.press(Key.DirectionUp)
            view.press(Key.F10)
            view.press(Key.Backspace, 8)
            eventually({ shell.writtenText().visible() }) {
                shell.writtenText() == "\u0003\r\t$ESC$ESC[A$ESC[21~\u007F"
            }
            // The copy shortcut never reaches the shell, even with nothing selected.
            val copyConsumed = if (isMacOs) view.press(Key.C, 'c'.code, meta = true) else view.press(Key.C, 'C'.code, ctrl = true, shift = true)
            assertTrue(copyConsumed)
            Thread.sleep(100)
            assertEquals("\u0003\r\t$ESC$ESC[A$ESC[21~\u007F", shell.writtenText())
        }
    }

    /** On macOS ⌘ and ⌥ edit the line as in iTerm2: these are the bytes the shell gets. */
    @Test
    fun macOsLineEditingReachesTheShell() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isMacOs)
        HeadlessView(session, 1200, 700).use { view ->
            view.settle()
            view.press(Key.Backspace, 8, meta = true)
            view.press(Key.DirectionLeft, meta = true)
            view.press(Key.DirectionRight, meta = true)
            view.press(Key.DirectionLeft, alt = true)
            view.press(Key.DirectionRight, alt = true)
            view.press(Key.Backspace, 8, alt = true)
            val expected = "\u0015\u0001\u0005${ESC}b${ESC}f$ESC\u007F"
            eventually({ shell.writtenText().visible() }) { shell.writtenText() == expected }
        }
    }

    @Test
    fun rendersColorsAndUnicode() {
        HeadlessView(session, 1800, 760).use { view ->
            view.settle()
            shell.emit(colorSample())
            eventually({ session.screenState.value.dump() }) { "fine." in session.screenState.value.dump() }
            view.savePng(File(snapshotDir, "view-colors.png"))
        }
    }

    @Test
    fun rendersAFullScreenApp() {
        HeadlessView(session, 1600, 760).use { view ->
            val size = view.settle()
            shell.emit(fullScreenSample(size.cols, size.rows))
            eventually({ session.screenState.value.dump() }) { "-- INSERT --" in session.screenState.value.dump() }
            view.savePng(File(snapshotDir, "view-fullscreen.png"))
        }
    }

    private fun colorSample() = buildString {
        append("${ESC}[32mdev@basic$ESC[0m:$ESC[34m~$ESC[0m$ ls --color\r\n")
        append("$ESC[01;34mdocs$ESC[0m  $ESC[01;32mgen-keys.sh$ESC[0m  $ESC[01;36mlink$ESC[0m  $ESC[01;31marchive.tgz$ESC[0m  README.md\r\n\r\n")
        append("16 colori: ")
        for (i in 0..7) append("$ESC[3${i}m██$ESC[0m")
        append(" ")
        for (i in 0..7) append("$ESC[9${i}m██$ESC[0m")
        append("  sfondi: ")
        for (i in 0..7) append("$ESC[4${i}m  $ESC[0m")
        append("\r\n256 colori: ")
        for (i in 16 until 88) append("$ESC[48;5;${i}m $ESC[0m")
        append("\r\ntruecolor:  ")
        for (i in 0 until 72) {
            val r = 255 - i * 255 / 71
            val g = (i * 510 / 71).let { if (it > 255) 510 - it else it }
            val b = i * 255 / 71
            append("$ESC[48;2;$r;$g;${b}m $ESC[0m")
        }
        append("\r\n\r\nPerché così? Città, però, più | 😀 🚀 ✓ | 中文字符 日本語 한국어 |\r\n")
        append("$ESC[1mbold$ESC[0m $ESC[4munderline$ESC[0m $ESC[7mreverse$ESC[0m $ESC[9mstrike$ESC[0m $ESC[3mitalic$ESC[0m $ESC[2mdim$ESC[0m\r\n")
        append("box: ┌──────┬──────┐\r\n     │ uno  │ due  │\r\n     └──────┴──────┘\r\n")
        append("${ESC}[32mdev@basic$ESC[0m:$ESC[34m~$ESC[0m$ echo fine.\r\nfine.\r\n$ESC[32mdev@basic$ESC[0m:$ESC[34m~$ESC[0m$ ")
    }

    /** An alternate-screen layout like a TUI draws it, padded explicitly (the emulator lacks BCE). */
    private fun fullScreenSample(cols: Int, rows: Int) = buildString {
        append("$ESC[?1049h$ESC[H$ESC[2J")
        append("$ESC[1;1H$ESC[30;46m" + " File  Command  Options".padEnd(cols) + "$ESC[0m")
        append("$ESC[2;1H$ESC[37;44m┌" + "─".repeat(cols - 2) + "┐")
        for (r in 3 until rows - 2) append("$ESC[$r;1H│" + " ".repeat(cols - 2) + "│")
        append("$ESC[${rows - 2};1H└" + "─".repeat(cols - 2) + "┘$ESC[0m")
        append("$ESC[4;4H$ESC[1;33;44mProcesses$ESC[22;37m   CPU [$ESC[32m||||||||$ESC[37m        ] 42%")
        append("$ESC[5;4H$ESC[37;44m  PID USER   COMMAND")
        append("$ESC[6;4H$ESC[30;46m  812 www    nginx: worker process".padEnd(40) + "$ESC[37;44m")
        append("$ESC[7;4H  901 app    node server.js")
        append("$ESC[0m$ESC[${rows - 1};1H$ESC[7m" + " -- INSERT --".padEnd(cols) + "$ESC[0m")
        append("$ESC[$rows;1H F1 Help  F2 Setup  F10 Quit")
        append("$ESC[7;30H")
    }
}
