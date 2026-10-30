package dev.eltermino.app

import dev.eltermino.core.terminal.TerminalSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Records a terminal on the basic testenv host, then plays it back. */
class RecordingFlowTest {
    private fun play(vararg events: AsciicastEvent, check: (ReplayTab) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cast = Asciicast("t", 80, 24, 0, events.toList())
            val tab = ReplayTab("r", "t", cast, scope)
            tab.speed.value = 8f
            runBlocking {
                tab.terminal.attach(ReplayChannel(tab))
                withTimeout(30_000) { tab.finished.first { it } }
            }
            check(tab)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun replayAppliesResizeEvents() = play(AsciicastEvent(0.1, "r", "100x30")) { tab ->
        assertEquals(TerminalSize(100, 30), tab.terminal.size.value)
    }

    @Test
    fun resizeEventOrderKept() {
        val line = "x".repeat(90)
        play(AsciicastEvent(0.1, "r", "100x30"), AsciicastEvent(0.2, "o", line), AsciicastEvent(0.3, "r", "120x30")) { tab ->
            // Written at 100 columns: at the 80 of the header it would have wrapped.
            assertTrue(tab.terminal.snapshotText().lines().any { it == line }, tab.terminal.snapshotText())
            assertEquals(TerminalSize(120, 30), tab.terminal.size.value)
        }
    }

    @Test
    fun malformedResizeIgnored() = play(
        AsciicastEvent(0.1, "r", "largo"),
        AsciicastEvent(0.2, "r", "0x0"),
        AsciicastEvent(0.3, "r", "99999999999x1"),
        AsciicastEvent(0.4, "o", "fine"),
    ) { tab ->
        assertEquals(TerminalSize(80, 24), tab.terminal.size.value)
        assertTrue("fine" in tab.terminal.snapshotText())
    }

    @Test
    fun recordAndReplayATerminal() {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        try {
            runBlocking {
                val host = state.actions.testHost("basic", TestEnv.BASIC_PORT, state.actions.keyIdentity())
                val tab = state.sessions.connect(host)
                val open = assertIs<TabStatus.Open>(withTimeout(30_000) { tab.status.first { it is TabStatus.Open } })
                state.sessions.startRecording(tab)
                assertTrue(tab.recording.value)
                open.terminal.sendText("echo registrato-\$((40+2))\r")
                withTimeout(30_000) { while ("registrato-42" !in open.terminal.snapshotText()) delay(100) }
                state.sessions.stopRecording(tab)
                assertFalse(tab.recording.value)

                val store = assertNotNull(state.services.recordings)
                val summary = assertNotNull(store.list().single().summary())
                assertEquals("basic", summary.title)
                val text = store.read(summary.id)!!.decodeToString()
                // Output only: the typed command comes back as the shell's echo, the expansion as its result.
                assertTrue("registrato-42" in text, text)

                val replay = assertNotNull(state.sessions.openReplay(summary))
                replay.speed.value = 8f
                withTimeout(30_000) { replay.finished.first { it } }
                assertTrue("registrato-42" in replay.terminal.snapshotText(), replay.terminal.snapshotText())
                state.sessions.closeAll()
            }
        } finally {
            state.scope.cancel()
        }
    }
}
