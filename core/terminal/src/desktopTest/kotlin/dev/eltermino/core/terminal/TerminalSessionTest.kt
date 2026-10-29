package dev.eltermino.core.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shell = FakeShellChannel()
    private val session = TerminalSession(shell, scope, TerminalSessionConfig(cols = 80, rows = 24))

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun outputReachesTheScreen() {
        shell.emit("hello \u001B[31mred\u001B[0m world\r\nsecond line")
        eventually({ session.screenState.value.dump() }) { session.screenState.value.line(1) == "second line" }
        assertEquals("hello red world", session.screenState.value.line(0))
    }

    @Test
    fun emulatorRepliesGoBackToTheShell() {
        shell.emit("abc\u001B[6n")
        eventually({ shell.writtenText().visible() }) { "\u001B[1;4R" in shell.writtenText() }
        shell.emit("\u001B[c")
        eventually({ shell.writtenText().visible() }) { "\u001B[?" in shell.writtenText() }
    }

    @Test
    fun rapidResizesAreCoalesced() {
        session.resize(100, 30)
        session.resize(110, 32)
        session.resize(120, 40)
        eventually({ "resizes=${shell.resizes}" }) { shell.resizes.isNotEmpty() }
        Thread.sleep(200)
        assertEquals(listOf(TerminalSize(120, 40)), shell.resizes.toList())
        assertEquals(TerminalSize(120, 40), session.size.value)
        eventually { session.screenState.value.cols == 120 && session.screenState.value.terminalRows == 40 }
    }

    @Test
    fun resizeToTheSameSizeIsNotSent() {
        session.resize(80, 24)
        Thread.sleep(200)
        assertTrue(shell.resizes.isEmpty())
    }

    @Test
    fun closedWhenOutputEnds() {
        assertFalse(session.closed.value)
        shell.endOutput()
        eventually { session.closed.value }
    }

    @Test
    fun closeClosesTheShell() = runBlocking {
        session.close()
        assertTrue(session.closed.value)
        assertTrue(shell.closed)
    }

    @Test
    fun typedTextTurnsNewlinesIntoEnter() {
        session.type("ls -la\n")
        eventually({ shell.writtenText().visible() }) { shell.writtenText() == "ls -la\r" }
    }

    @Test
    fun stickyCtrlAppliesToTheNextCharacterOnly() {
        session.toggleStickyCtrl()
        assertTrue(session.stickyModifiers.value.ctrl)
        session.type("cd")
        eventually({ shell.writtenText().visible() }) { shell.writtenText() == "\u0003d" }
        assertTrue(session.stickyModifiers.value.isEmpty)
    }

    @Test
    fun stickyAltAppliesToKeys() {
        session.toggleStickyAlt()
        session.sendKey(TerminalKey.Left)
        eventually({ shell.writtenText().visible() }) { shell.writtenText() == "\u001B[1;3D" }
    }

    @Test
    fun cursorKeysFollowTheRemoteMode() {
        shell.emit("\u001B[?1h")
        eventually { session.keyboardMode.applicationCursorKeys }
        session.sendKey(TerminalKey.Up)
        eventually({ shell.writtenText().visible() }) { shell.writtenText() == "\u001BOA" }
    }

    @Test
    fun pasteIsBracketedWhenTheProgramAsks() {
        session.paste("plain\n")
        eventually({ shell.writtenText().visible() }) { shell.writtenText() == "plain\r" }
        shell.written.clear()

        shell.emit("\u001B[?2004h")
        eventually { session.screenState.value.bracketedPasteModeEnabled }
        session.paste("echo a\necho b\u001B[201~rm -rf x")
        eventually({ shell.writtenText().visible() }) { shell.writtenText().endsWith("\u001B[201~") }
        assertEquals("\u001B[200~echo a\recho brm -rf x\u001B[201~", shell.writtenText())
    }

    @Test
    fun attachKeepsTheScreenAndTalksToTheNewShell() = runBlocking {
        shell.emit("before the drop\r\n\u001B[?1049h\u001B[?1hfull screen app")
        eventually { session.keyboardMode.applicationCursorKeys }
        shell.endOutput()
        eventually { session.closed.value }

        val next = FakeShellChannel()
        session.attach(next, notice = "-- reconnected --")
        assertFalse(session.closed.value)
        eventually({ "the old shell is closed" }) { shell.closed }
        assertFalse(session.keyboardMode.applicationCursorKeys, "modes left by the old program are reset")
        val screen = session.screenState.value.dump()
        assertTrue("before the drop" in screen, screen)
        assertTrue("-- reconnected --" in screen, screen)
        assertFalse("full screen app" in screen, "back on the main screen: $screen")

        session.type("ls\n")
        eventually({ next.writtenText().visible() }) { next.writtenText() == "ls\r" }
        next.emit("after")
        eventually({ session.screenState.value.dump() }) { "after" in session.screenState.value.dump() }
    }

    @Test
    fun inputIsMirroredAsEventsButReceivedInputIsNot() = runBlocking {
        // Subscribes before anything is typed.
        val events = async(start = CoroutineStart.UNDISPATCHED) { session.input.take(3).toList() }
        session.type("ab")
        session.sendKey(TerminalKey.Up)
        session.receive(TerminalInput.Text("not mirrored"))
        session.paste("p")
        assertEquals(
            listOf(TerminalInput.Text("ab"), TerminalInput.Key(TerminalKey.Up), TerminalInput.Paste("p")),
            events.await(),
        )
        eventually({ shell.writtenText().visible() }) { shell.writtenText() == "ab\u001B[Anot mirroredp" }
    }

    @Test
    fun receivedKeysUseTheReceiversMode() {
        shell.emit("\u001B[?1h")
        eventually { session.keyboardMode.applicationCursorKeys }
        session.receive(TerminalInput.Key(TerminalKey.Up))
        eventually({ shell.writtenText().visible() }) { shell.writtenText() == "\u001BOA" }
    }
}
