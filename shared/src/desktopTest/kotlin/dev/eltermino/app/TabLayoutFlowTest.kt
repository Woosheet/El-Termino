package dev.eltermino.app

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Phase 4's goal: a layout with two groups and a split tab, saved, comes back the same
 * after the app restarts, with its sessions connected again.
 */
class TabLayoutFlowTest {
    private val dir = Files.createTempDirectory("eltermino-layout").toFile().apply { deleteOnExit() }
    private val key = Random.nextBytes(32)

    private fun start(): AppState = AppState(testServices(dir, key)).also { state ->
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
    }

    /** Layout and groups by kind, host label and folder, independent of ids. */
    private fun AppState.shape(): List<String> {
        val bar = sessions.bar.value
        val byId = sessions.tabs.value.associateBy { it.id }
        val hosts = services.workspace.workspace.value
        return bar.frames.map { frame ->
            val group = bar.group(frame.groupId)?.let { "${it.name}(${it.color})" } ?: "-"
            val panes = frame.panes.tabIds.joinToString(",") { id ->
                when (val tab = id?.let(byId::get)) {
                    null -> "·"
                    is SessionTab -> "term:${hosts.host(tab.hostId)?.label}"
                    is FilesTab -> "files:${tab.hostId?.let { hosts.host(it)?.label } ?: "local"}"
                    is EditorTab -> "edit:${tab.path}"
                    is RequestsTab -> "http:${hosts.host(tab.hostId)?.label}"
                }
            }
            "$group ${frame.panes.layout}[$panes]"
        }
    }

    @Test
    fun aSavedLayoutComesBackTheSameAfterARestart() {
        val first = start()
        val (basic, bastion) = runBlocking {
            val identity = first.actions.keyIdentity()
            first.actions.testHost("basic", TestEnv.BASIC_PORT, identity) to first.actions.testHost("bastion", TestEnv.BASTION_PORT, identity)
        }
        val sessions = first.sessions
        val web = sessions.connect(basic)
        val files = sessions.openFiles(basic, "/tmp")
        val webAgain = sessions.connect(basic)
        val jump = sessions.connect(bastion)
        sessions.openFiles(null)
        sessions.openRequests(basic)
        // web | files of basic, side by side in one tab
        assertEquals(true, sessions.merge(sessions.bar.value.frameOf(files.id)!!.id, sessions.bar.value.frameOf(web.id)!!.id, Side.Right))
        sessions.createGroup(listOf(sessions.bar.value.frameOf(web.id)!!.id, sessions.bar.value.frameOf(webAgain.id)!!.id), "Web")
        sessions.createGroup(listOf(sessions.bar.value.frameOf(jump.id)!!.id), "Accesso")
        sessions.recolorGroup(sessions.bar.value.groups.first { it.name == "Accesso" }.id, GroupColor.Corallo)
        val expected = first.shape()
        assertEquals(
            listOf(
                "Web(Blu) Columns[term:basic,files:basic]",
                "Web(Blu) Single[term:basic]",
                "Accesso(Corallo) Single[term:bastion]",
                "- Single[files:local]",
                "- Single[http:basic]",
            ),
            expected,
        )
        runBlocking {
            withTimeout(30_000) { files.browser.path.first { it == "/tmp" } }
            first.actions.saveLayout(assertNotNull(sessions.snapshot("Deploy")))
            // The last session is kept by itself, a moment after the last change.
            withTimeout(10_000) { first.services.workspace.workspace.first { it.lastSession != null } }
        }
        sessions.closeAll()
        Thread.sleep(300)
        first.scope.cancel()

        val second = start()
        val workspace = second.services.workspace.workspace.value
        assertEquals(listOf("Deploy"), workspace.savedLayouts.map { it.name })
        assertNotNull(workspace.lastSession, "offered again at the next start")
        second.sessions.restore(workspace.savedLayouts.single())
        assertEquals(expected, second.shape())

        val terminals = second.sessions.tabs.value.filterIsInstance<SessionTab>()
        runBlocking {
            terminals.forEach { tab ->
                val status = withTimeout(30_000) { tab.status.first { it !is TabStatus.Connecting } }
                assertIs<TabStatus.Open>(status, (status as? TabStatus.Failed)?.message)
            }
            val panel = second.sessions.tabs.value.filterIsInstance<FilesTab>().first { it.hostId != null }
            withTimeout(30_000) { panel.browser.path.first { it == "/tmp" } }
        }
        second.sessions.closeAll()
        Thread.sleep(300)
        second.scope.cancel()
    }
}
