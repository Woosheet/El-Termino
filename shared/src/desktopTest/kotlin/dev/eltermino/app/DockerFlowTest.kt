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
        // The fake keeps its state between runs: this starts it over, with a stopped job.
        state.sessions.connections.run(host, "rm -rf /tmp/fake-docker")
        val tab = state.sessions.openDocker(host)
        var job = tab.awaitContainer("job") { !it.running }

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

    private suspend fun AppState.freshDocker(): DockerTab {
        val host = actions.testHost("basic", TestEnv.BASIC_PORT, actions.keyIdentity())
        sessions.connections.run(host, "rm -rf /tmp/fake-docker")
        return sessions.openDocker(host).also { it.awaitContainer("web") { true } }
    }

    private suspend fun DockerTab.resource(kind: DockerKind, name: String?): List<DockerResource> =
        withTimeout(30_000) {
            resources.first { all -> all[kind] != null && (name == null || all.getValue(kind).any { it.name == name }) }.getValue(kind)
        }

    @Test
    fun removeContainerAsksConfirmation() = withApp { state ->
        val tab = state.freshDocker()
        val job = tab.awaitContainer("job") { !it.running }
        state.sessions.dockerRemove(tab, DockerKind.Container, job.id, job.name)
        val asked = assertNotNull(tab.confirmation.value)
        assertTrue("job" in asked.title, asked.title)
        delay(1_000)
        tab.awaitContainer("job") { true }

        state.sessions.dockerCancel(tab)
        assertEquals(null, tab.confirmation.value)
        tab.awaitContainer("job") { true }

        state.sessions.dockerRemove(tab, DockerKind.Container, job.id, job.name)
        state.sessions.dockerConfirm(tab)
        assertEquals(null, tab.confirmation.value)
        withTimeout(30_000) { tab.containers.first { list -> list != null && list.none { it.name == "job" } } }
    }

    @Test
    fun theBusyImageExplainsWhyItStays() = withApp { state ->
        val tab = state.freshDocker()
        tab.select(DockerSection.Images)
        val image = tab.resource(DockerKind.Image, "nginx:1.27").single { it.name == "nginx:1.27" }
        state.sessions.dockerRemove(tab, DockerKind.Image, image.id, image.name)
        state.sessions.dockerConfirm(tab)
        assertEquals("nginx:1.27: È ancora in uso: ferma e rimuovi prima ciò che lo usa", withTimeout(30_000) { tab.actionError.filterNotNull().first() })
    }

    @Test
    fun volumesAndNetworksAreListedAndRemoved() = withApp { state ->
        val tab = state.freshDocker()
        tab.select(DockerSection.Volumes)
        tab.resource(DockerKind.Volume, "dati")
        state.sessions.dockerRemove(tab, DockerKind.Volume, "dati", "dati")
        state.sessions.dockerConfirm(tab)
        withTimeout(30_000) { tab.resources.first { all -> all[DockerKind.Volume]?.none { it.name == "dati" } == true } }

        tab.select(DockerSection.Networks)
        assertEquals(listOf(false, true), tab.resource(DockerKind.Network, "shop_net").map { it.removable })
    }

    @Test
    fun composeRestart() = withApp { state ->
        val tab = state.freshDocker()
        withTimeout(30_000) { tab.composeAvailable.first { it } }
        tab.select(DockerSection.Compose)
        val project = withTimeout(30_000) { tab.composeProjects.filterNotNull().first { it.isNotEmpty() } }.single()
        assertEquals("shop", project.name)
        state.sessions.dockerCompose(tab, project, ComposeAction.Restart)
        withTimeout(30_000) { tab.composeProjects.first { it?.singleOrNull()?.status == "running(3)" } }

        // Going down asks first.
        state.sessions.dockerCompose(tab, project, ComposeAction.Down)
        assertNotNull(tab.confirmation.value)
        state.sessions.dockerConfirm(tab)
        withTimeout(30_000) { tab.composeProjects.first { it?.singleOrNull()?.status == "exited(2)" } }
        state.sessions.dockerCompose(tab, project, ComposeAction.Up)
        assertEquals(null, tab.confirmation.value)
        withTimeout(30_000) { tab.composeProjects.first { it?.singleOrNull()?.status == "running(2)" } }
        assertEquals(null, tab.actionError.value)
    }

    @Test
    fun noComposeInstalledHidesSection() = withApp { state ->
        val host = state.actions.testHost("basic", TestEnv.BASIC_PORT, state.actions.keyIdentity())
        state.sessions.connections.run(host, "rm -rf /tmp/fake-docker; docker ps > /dev/null; touch /tmp/fake-docker/no-compose")
        val tab = state.sessions.openDocker(host)
        tab.awaitContainer("web") { true }
        assertEquals(false, tab.composeAvailable.value)
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
