package dev.eltermino.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.Snippet
import dev.eltermino.core.data.newId
import dev.eltermino.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Renders the real UI headless to build/snapshots/, with a live session to the testenv
 * server, to check layout and theme by eye.
 */
class AppSnapshotTest {
    private fun snapshot(state: AppState, name: String, width: Int = 2400, height: Int = 1520, settle: () -> Unit = {}) {
        ImageComposeScene(width, height, Density(2f)) { App(state) }.use { scene ->
            // Let flows, effects and terminal output reach the screen.
            repeat(6) {
                scene.render(System.nanoTime())
                settle()
                Thread.sleep(150)
            }
            val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/snapshots/$name.png").apply { parentFile.mkdirs(); writeBytes(png) }
        }
    }

    private suspend fun seed(state: AppState): Host {
        val actions = state.actions
        val production = actions.ensureGroupPath(listOf("Produzione"))
        val staging = actions.ensureGroupPath(listOf("Staging"))
        val key = actions.importKey("laptop", TestEnv.key("id_ed25519").readBytes())
        val identity = actions.saveIdentity(Identity(newId(), "dev", TestEnv.USER, keyId = key.id))
        val web = Host(newId(), "web-01", TestEnv.HOST, TestEnv.BASIC_PORT, groupId = production, identityId = identity.id, favorite = true)
        val bastion = Host(newId(), "bastion", TestEnv.HOST, TestEnv.BASTION_PORT, groupId = production, identityId = identity.id)
        listOf(
            web,
            Host(newId(), "web-02", "10.0.0.12", groupId = production, username = "root"),
            bastion,
            Host(newId(), "db-01", "internal", groupId = production, identityId = identity.id, jumpHostId = bastion.id),
            Host(newId(), "stage-01", "10.1.0.5", groupId = staging, username = "deploy", tags = listOf("docker")),
            Host(newId(), "nas-casa", "nas.local", username = "francesco"),
        ).forEach { actions.saveHost(it) }
        return web
    }

    @Test
    fun desktopWithLiveSession(): Unit = runBlocking {
        val state = AppState(testServices())
        val web = seed(state)
        val answering = CoroutineScope(Dispatchers.Default)
        // Trusts host keys so the session opens without a person at the keyboard.
        answering.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { prompt ->
                if (prompt is PendingPrompt.HostKey) prompt.answer(true) else prompt.cancel()
            }
        }
        val tab = state.sessions.connect(web)
        val open = withTimeout(30_000) { tab.status.first { it !is TabStatus.Connecting } }
        assertIs<TabStatus.Open>(open, (open as? TabStatus.Failed)?.message)
        open.terminal.sendText("ls --color=always -la /etc | head -12; echo; printf '\\e[32mok\\e[0m ✓ Perché 中文\\n'\r")
        snapshot(state, "app-desktop-session")
        answering.cancel()
        state.sessions.close(tab)
    }

    @Test
    fun desktopHostKeyPrompt(): Unit = runBlocking {
        val state = AppState(testServices())
        val web = seed(state)
        state.sessions.connect(web)
        withTimeout(30_000) { state.sessions.prompter.prompt.filterNotNull().first() }
        snapshot(state, "app-desktop-hostkey")
        state.sessions.prompter.prompt.value?.cancel()
    }

    @Test
    fun phoneHostList(): Unit = runBlocking {
        val state = AppState(testServices())
        seed(state)
        snapshot(state, "app-phone-hosts", width = 780, height = 1600)
    }

    @Test
    fun hostEditorWithErrors(): Unit = runBlocking {
        val state = AppState(testServices())
        seed(state)
        val db = state.services.workspace.workspace.value.hosts.first { it.label == "db-01" }
        ImageComposeScene(1400, 1700, Density(2f)) {
            dev.eltermino.ui.theme.CartaTheme { dev.eltermino.ui.HostEditorDialog(state, db) {} }
        }.use { scene ->
            repeat(4) { scene.render(System.nanoTime()); Thread.sleep(100) }
            val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/snapshots/host-editor.png").apply { parentFile.mkdirs(); writeBytes(png) }
        }
    }

    @Test
    fun hostMenuStaysNarrow() {
        ImageComposeScene(1400, 700, Density(2f)) {
            dev.eltermino.ui.theme.CartaTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(start = 300.dp, top = 40.dp)) {
                        dev.eltermino.ui.components.Text("⋮")
                        dev.eltermino.ui.components.Menu(expanded = true, onDismiss = {}, alignEnd = true) {
                            listOf("Connetti", "Modifica", "Duplica", "Aggiungi ai preferiti").forEach {
                                dev.eltermino.ui.components.MenuItem(it, onClick = {})
                            }
                            dev.eltermino.ui.components.MenuItem("Elimina", onClick = {}, danger = true)
                        }
                    }
                }
            }
        }.use { scene ->
            repeat(3) { scene.render(System.nanoTime()); Thread.sleep(50) }
            val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/snapshots/host-menu.png").apply { parentFile.mkdirs(); writeBytes(png) }
        }
    }

    /** Trusts host keys so sessions open without a person at the keyboard. */
    private fun CoroutineScope.answerHostKeys(state: AppState) = launch {
        state.sessions.prompter.prompt.filterNotNull().collect { prompt ->
            if (prompt is PendingPrompt.HostKey) prompt.answer(true) else prompt.cancel()
        }
    }

    private suspend fun SessionTab.awaitOpen(): TabStatus.Open {
        val open = withTimeout(30_000) { status.first { it !is TabStatus.Connecting } }
        return assertIs<TabStatus.Open>(open, (open as? TabStatus.Failed)?.message)
    }

    @Test
    fun splitViewWithBroadcast(): Unit = runBlocking {
        val state = AppState(testServices())
        val web = seed(state)
        val workspace = state.services.workspace.workspace.value
        val bastion = workspace.hosts.first { it.label == "bastion" }
        val db = workspace.hosts.first { it.label == "db-01" }
        val answering = CoroutineScope(Dispatchers.Default)
        answering.answerHostKeys(state)
        val tabs = listOf(state.sessions.connect(web), state.sessions.connect(bastion), state.sessions.connect(db), state.sessions.connect(web))
        val open = tabs.map { it.awaitOpen() }
        state.sessions.setLayout(SplitLayout.Grid)
        state.sessions.setBroadcast(setOf(tabs[0].id, tabs[1].id, tabs[2].id))
        // Typing in a broadcast member shows the band above it.
        state.sessions.select(tabs[0].id)
        Thread.sleep(300)
        open[0].terminal.type("uptime\n")
        open[3].terminal.type("printf '\\e[33mfuori dal broadcast\\e[0m\\n'\n")
        snapshot(state, "app-desktop-split-broadcast", width = 2800, height = 1700)
        answering.cancel()
        state.sessions.closeAll()
    }

    @Test
    fun reconnectingBanner(): Unit = TestProxy(TestEnv.BASIC_PORT).use { proxy ->
        runBlocking {
            val state = AppState(testServices())
            seed(state)
            val identity = state.services.workspace.workspace.value.identities.first()
            val host = Host(newId(), "web-proxy", TestEnv.HOST, proxy.port, identityId = identity.id)
            state.actions.saveHost(host)
            val answering = CoroutineScope(Dispatchers.Default)
            answering.answerHostKeys(state)
            val tab = state.sessions.connect(host)
            tab.awaitOpen().terminal.type("echo prima della caduta\n")
            Thread.sleep(500)
            proxy.up = false
            proxy.cut()
            withTimeout(15_000) { tab.status.first { it is TabStatus.Reconnecting && it.retryAt != null } }
            snapshot(state, "app-desktop-reconnecting")
            answering.cancel()
            state.sessions.closeAll()
        }
    }

    @Test
    fun snippetsScreenAndRunDialog(): Unit = runBlocking {
        val state = AppState(testServices())
        seed(state)
        val restart = Snippet(newId(), "Riavvia un servizio", "sudo systemctl restart {{servizio}}")
        listOf(
            restart,
            Snippet(newId(), "Spazio su disco", "df -h /"),
            Snippet(newId(), "Log di un container", "docker logs --tail {{righe}} -f {{container}}", run = false),
        ).forEach { state.actions.saveSnippet(it) }
        ImageComposeScene(2400, 1520, Density(2f)) {
            dev.eltermino.ui.theme.CartaTheme { dev.eltermino.ui.SnippetsScreen(state, androidx.compose.ui.Modifier.fillMaxSize()) }
        }.use { scene ->
            repeat(3) { scene.render(System.nanoTime()); Thread.sleep(80) }
            val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/snapshots/snippets.png").apply { parentFile.mkdirs(); writeBytes(png) }
        }
        ImageComposeScene(1400, 1600, Density(2f)) {
            dev.eltermino.ui.theme.CartaTheme { dev.eltermino.ui.SnippetRunDialog(state, restart) {} }
        }.use { scene ->
            repeat(3) { scene.render(System.nanoTime()); Thread.sleep(80) }
            val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/snapshots/snippet-run.png").apply { parentFile.mkdirs(); writeBytes(png) }
        }
    }

    @Test
    fun emptyFirstRun() {
        snapshot(AppState(testServices()), "app-desktop-empty")
    }
}
