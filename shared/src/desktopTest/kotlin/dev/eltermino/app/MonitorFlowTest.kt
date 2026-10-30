package dev.eltermino.app

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Resources tab reads a testenv host over its link, and stops when the tab closes. */
class MonitorFlowTest {
    @Test
    fun processesFromRealHost() {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        try {
            runBlocking {
                val host = state.actions.testHost("basic", TestEnv.BASIC_PORT, state.actions.keyIdentity())
                val tab = state.sessions.openMonitor(host)
                val sample = withTimeout(60_000) { tab.samples.first { it.isNotEmpty() } }.last()
                assertTrue(sample.processes.isNotEmpty(), "ps on a Debian host")
                assertTrue(sample.processes.size <= 10)
                assertTrue(sample.processes.all { it.pid > 0 && it.user.isNotEmpty() && it.command.isNotEmpty() }, sample.processes.toString())
                state.sessions.close(tab)
            }
        } finally {
            state.scope.cancel()
        }
    }

    @Test
    fun aResourcesTabReadsTheHostUntilItCloses() {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        try {
            runBlocking {
                val host = state.actions.testHost("basic", TestEnv.BASIC_PORT, state.actions.keyIdentity())
                val tab = state.sessions.openMonitor(host)
                // Two samples: the second has the CPU and network rates.
                val samples = withTimeout(60_000) { tab.samples.first { it.size >= 2 } }
                assertNull(tab.error.value)
                val latest = samples.last()
                assertNotNull(latest.cpuPercent)
                assertTrue(latest.memTotalKb > 0 && latest.memUsedKb in 0..latest.memTotalKb)
                assertTrue(latest.disks.any { it.mount == "/" }, latest.disks.toString())
                assertNotNull(latest.rxBytesPerSecond)

                state.sessions.close(tab)
                withTimeout(10_000) { tab.job!!.join() }
                val count = tab.samples.value.size
                Thread.sleep(3_500)
                assertEquals(count, tab.samples.value.size, "nothing is read once the tab is closed")
            }
        } finally {
            state.scope.cancel()
        }
    }
}
