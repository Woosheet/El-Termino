package dev.eltermino.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Identity
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
    fun emptyFirstRun() {
        snapshot(AppState(testServices()), "app-desktop-empty")
    }
}
