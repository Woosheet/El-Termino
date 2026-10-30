package dev.eltermino.app

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
