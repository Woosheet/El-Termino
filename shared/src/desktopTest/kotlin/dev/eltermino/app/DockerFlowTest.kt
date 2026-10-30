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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Docker tab against the fake docker CLI of the testenv hosts (see testenv/README.md). */
class DockerFlowTest {
    private fun withApp(block: suspend (AppState) -> Unit) {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        try {
            runBlocking { block(state) }
        } finally {
            state.scope.cancel()
        }
    }

    private suspend fun DockerTab.awaitContainer(name: String, condition: (DockerContainer) -> Boolean): DockerContainer {
        val list = kotlinx.coroutines.withTimeoutOrNull(30_000) { containers.filterNotNull().first { list -> list.any { it.name == name && condition(it) } } }
        return assertNotNull(list, "$name never got there; error: ${error.value}; containers: ${containers.value}").single { it.name == name }
    }

    @Test
    fun startStopAndFollowTheLog() = withApp { state ->
        val host = state.actions.testHost("basic", TestEnv.BASIC_PORT, state.actions.keyIdentity())
        val tab = state.sessions.openDocker(host)
        var job = tab.awaitContainer("job") { true }
        // The fake keeps its state between runs: start from a stopped job.
        if (job.running) {
            state.sessions.dockerAction(tab, job, DockerAction.Stop)
            job = tab.awaitContainer("job") { !it.running }
        }

        state.sessions.dockerAction(tab, job, DockerAction.Start)
        job = tab.awaitContainer("job") { it.running && it.cpu != null }
        assertTrue(job.status.startsWith("Up"), job.status)
        assertEquals(null, tab.error.value)
        assertEquals(null, tab.actionError.value)

        state.sessions.dockerAction(tab, job, DockerAction.Stop)
        job = tab.awaitContainer("job") { !it.running }
        assertEquals(null, job.cpu)

        // A failed action stays on screen through the refreshes that follow.
        state.sessions.dockerAction(tab, DockerContainer("zzz", "fantasma", "img", "exited", "", ""), DockerAction.Start)
        val failure = withTimeout(30_000) { tab.actionError.filterNotNull().first() }
        assertEquals("fantasma: Error response from daemon: No such container: zzz", failure)
        // The action asks for a refresh, and one more round comes 5 seconds later.
        delay(6_000)
        assertEquals(null, tab.error.value)
        assertEquals(failure, tab.actionError.value)

        // Logs open in a new terminal on the same host and keep following.
        state.sessions.dockerLogs(tab, tab.containers.value!!.first { it.name == "web" })
        val terminal = state.sessions.tabs.value.filterIsInstance<SessionTab>().single()
        val open = withTimeout(30_000) { terminal.status.first { it is TabStatus.Open } }
        val text = withTimeout(30_000) {
            while ("web | 10:03" !in assertIs<TabStatus.Open>(open).terminal.snapshotText()) delay(200)
            open.terminal.snapshotText()
        }
        assertTrue("docker logs -f --tail 200 'web'" in text, text)
    }

    @Test
    fun aUserOutsideTheDockerGroupIsToldSo() = withApp { state ->
        val host = state.actions.testHost("bastion", TestEnv.BASTION_PORT, state.actions.keyIdentity())
        val tab = state.sessions.openDocker(host)
        val error = withTimeout(30_000) { tab.error.filterNotNull().first() }
        assertEquals("L'utente non può usare Docker su bastion: va aggiunto al gruppo docker", error)
        assertNotNull(tab.lease.value)
        assertEquals(null, tab.containers.value)
    }
}
