package dev.eltermino.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
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
    /** Snapshots compare against each other, so they don't follow this Mac's light or dark mode. */
    private fun lightState() = AppState(testServices()).also { state ->
        runBlocking { state.services.workspace.update { it.copy(ui = it.ui.copy(theme = "carta", welcomeDone = true)) } }
    }

    private fun snapshot(state: AppState, name: String, width: Int = 2400, height: Int = 1520, titleBar: Dp = 0.dp, settle: () -> Unit = {}) {
        ImageComposeScene(width, height, Density(2f)) { App(state, titleBarHeight = titleBar) }.use { scene ->
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
        val state = lightState()
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
    fun requestsTab(): Unit = runBlocking {
        val state = lightState()
        val web = seed(state)
        val answering = CoroutineScope(Dispatchers.Default)
        answering.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { prompt ->
                if (prompt is PendingPrompt.HostKey) prompt.answer(true) else prompt.cancel()
            }
        }
        val health = dev.eltermino.core.data.HttpRequest(
            newId(), "Salute", url = "http://localhost:{{porta}}/health.json",
            headers = listOf(dev.eltermino.core.data.HttpHeader("Accept", "application/json")),
            checks = listOf(dev.eltermino.core.data.HttpCheck("status", expected = "200"), dev.eltermino.core.data.HttpCheck("json", "host", "web-01")),
        )
        val collection = dev.eltermino.core.data.HttpCollection(
            newId(), "API interne",
            listOf(health, dev.eltermino.core.data.HttpRequest(newId(), "Crea ordine", "POST", "http://localhost:{{porta}}/cgi-bin/echo", bodyType = "json", body = "{\"id\": 1}")),
            mapOf("porta" to "8080"),
        )
        state.actions.saveCollection(collection)
        state.actions.saveCollection(dev.eltermino.core.data.HttpCollection(newId(), "Elasticsearch", listOf(dev.eltermino.core.data.HttpRequest(newId(), "Salute del cluster", url = "http://10.0.0.5:9200/_cluster/health"))))
        val tab = state.sessions.openRequests(web)
        tab.selected.value = health.id
        state.requests.send(tab, collection, health)
        withTimeout(30_000) { tab.history.first { it[health.id] != null } }
        snapshot(state, "app-requests")
        ImageComposeScene(780, 1600, Density(2f)) {
            dev.eltermino.ui.theme.CartaTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(dev.eltermino.ui.theme.Carta.colors.background)) {
                    dev.eltermino.ui.RequestsPane(state, tab, focused = true)
                }
            }
        }.use { scene ->
            repeat(3) { scene.render(System.nanoTime()); Thread.sleep(80) }
            File("build/snapshots/app-phone-requests.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        answering.cancel()
        state.sessions.closeAll()
    }

    @Test
    fun desktopHostKeyPrompt(): Unit = runBlocking {
        val state = lightState()
        val web = seed(state)
        state.sessions.connect(web)
        withTimeout(30_000) { state.sessions.prompter.prompt.filterNotNull().first() }
        snapshot(state, "app-desktop-hostkey")
        state.sessions.prompter.prompt.value?.cancel()
    }

    @Test
    fun phoneHostList(): Unit = runBlocking {
        val state = lightState()
        seed(state)
        snapshot(state, "app-phone-hosts", width = 780, height = 1600)
    }

    @Test
    fun hostEditorWithErrors(): Unit = runBlocking {
        val state = lightState()
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
        val state = lightState()
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
            val state = lightState()
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
        val state = lightState()
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
    fun filePanelsSideBySideWithATransfer(): Unit = runBlocking {
        val state = lightState()
        val services = state.services
        val web = seed(state)
        val answering = CoroutineScope(Dispatchers.Default)
        answering.answerHostKeys(state)
        val dir = "/tmp/snap-${newId().take(6)}"
        state.sessions.connections.run(web, "mkdir -p $dir/config $dir/logs && head -c 90000000 /dev/urandom > $dir/backup.tar.gz && printf 'server:\\n  porta: 8080\\n' > $dir/app.yaml && touch $dir/.env $dir/deploy.sh && chmod 755 $dir/deploy.sh")
        val localRoot = services.localFiles!!.home()
        java.io.File(localRoot, "Documenti").mkdirs()
        java.io.File(localRoot, "note.txt").writeText("ciao")
        java.io.File(localRoot, "foto.jpg").writeBytes(ByteArray(2_400_000))

        val local = state.sessions.openFiles(null)
        state.sessions.setLayout(SplitLayout.Columns)
        val remote = state.sessions.openFiles(web)
        withTimeout(30_000) { remote.browser.path.first { it != null } }
        remote.browser.open(dir)
        withTimeout(10_000) { remote.browser.path.first { it == dir } }
        remote.browser.selection.value = setOf("$dir/app.yaml", "$dir/deploy.sh")
        val backup = withTimeout(10_000) { remote.browser.entries.first { list -> list.any { it.name == "backup.tar.gz" } } }.first { it.name == "backup.tar.gz" }
        state.transfers.maxParallel.value = 1
        state.transfers.enqueue(Endpoint.Remote(web.id), listOf(backup), Location(Endpoint.Local, localRoot), ConflictPolicy.Overwrite)
        state.transfers.enqueue(Endpoint.Local, listOf(services.localFiles!!.stat("$localRoot/foto.jpg")!!), Location(Endpoint.Remote(web.id), dir), ConflictPolicy.Overwrite)
        withTimeout(30_000) { state.transfers.transfers.value.first().progress.first { it.bytesDone > 0 } }
        state.transfers.pause(state.transfers.transfers.value.first())
        snapshot(state, "app-desktop-files", width = 2800, height = 1700)
        state.transfers.transfers.value.forEach { state.transfers.cancel(it) }
        state.sessions.connections.run(web, "rm -rf $dir")
        answering.cancel()
        state.sessions.closeAll()
        local.browser.close()
    }

    @Test
    fun remoteEditorWithYaml(): Unit = runBlocking {
        val state = lightState()
        val web = seed(state)
        val answering = CoroutineScope(Dispatchers.Default)
        answering.answerHostKeys(state)
        val path = "/tmp/snap-${newId().take(6)}.yaml"
        state.sessions.connections.run(
            web,
            "printf '# Configurazione del servizio\\nserver:\\n  host: \"0.0.0.0\"\\n  porta: 8080\\n  tls: true\\nlog:\\n  livello: info   # debug in staging\\n  file: /var/log/app.log\\nworker:\\n  - nome: email\\n    repliche: 3\\n' > $path",
        )
        val tab = state.sessions.openEditor(Endpoint.Remote(web.id), path)
        withTimeout(30_000) { tab.editor.first { it != null } }
        snapshot(state, "app-desktop-editor")
        state.sessions.connections.run(web, "rm -f $path")
        answering.cancel()
        state.sessions.closeAll()
    }

    @Test
    fun tabGroupsAndASplitTab(): Unit = runBlocking {
        val state = lightState()
        val web = seed(state)
        val workspace = state.services.workspace.workspace.value
        val bastion = workspace.hosts.first { it.label == "bastion" }
        val db = workspace.hosts.first { it.label == "db-01" }
        val answering = CoroutineScope(Dispatchers.Default)
        answering.answerHostKeys(state)
        val sessions = state.sessions
        val shell = sessions.connect(web)
        val files = sessions.openFiles(web, "/etc")
        val second = sessions.connect(web)
        val jump = sessions.connect(bastion)
        val database = sessions.connect(db)
        listOf(shell, second, jump, database).forEach { it.awaitOpen() }
        fun frameOf(tab: Tab) = sessions.bar.value.frameOf(tab.id)!!.id
        sessions.merge(frameOf(files), frameOf(shell), Side.Right)
        sessions.createGroup(listOf(frameOf(shell), frameOf(second)), "Web")
        sessions.createGroup(listOf(frameOf(jump)), "Accesso")
        val access = sessions.bar.value.groups.first { it.name == "Accesso" }.id
        sessions.recolorGroup(access, GroupColor.Corallo)
        sessions.toggleGroup(access)
        sessions.selectFrame(frameOf(shell))
        shell.awaitOpen().terminal.type("tail -n 5 /etc/services\n")
        withTimeout(30_000) { files.browser.path.first { it == "/etc" } }
        snapshot(state, "app-desktop-tab-groups", width = 2800, height = 1500)
        answering.cancel()
        sessions.closeAll()
    }

    @Test
    fun workspacesDialogAndRestoreOffer(): Unit = runBlocking {
        val state = lightState()
        val web = seed(state)
        val bastion = state.services.workspace.workspace.value.hosts.first { it.label == "bastion" }
        fun pane(kind: String, host: Host?, path: String? = null) = dev.eltermino.core.data.SavedPane(kind, host?.id, path)
        val deploy = dev.eltermino.core.data.SavedLayout(
            newId(), "Deploy di venerdì",
            tabs = listOf(
                dev.eltermino.core.data.SavedTab("Columns", listOf(pane("terminal", web), pane("files", web, "/var/www")), groupId = "g1"),
                dev.eltermino.core.data.SavedTab("Single", listOf(pane("terminal", bastion)), groupId = "g2"),
                dev.eltermino.core.data.SavedTab("Single", listOf(pane("files", null))),
            ),
            groups = listOf(dev.eltermino.core.data.SavedGroup("g1", "Web", "Blu"), dev.eltermino.core.data.SavedGroup("g2", "Accesso", "Corallo")),
        )
        state.actions.saveLayout(deploy)
        state.actions.saveLayout(deploy.copy(id = newId(), name = "Log di produzione", groups = emptyList(), tabs = deploy.tabs.take(1).map { it.copy(groupId = null) }))
        state.services.workspace.update { it.copy(lastSession = deploy.copy(id = "last", name = "Ultima sessione")) }
        snapshot(state, "app-desktop-restore-offer")
        ImageComposeScene(1400, 1300, Density(2f)) {
            dev.eltermino.ui.theme.CartaTheme { dev.eltermino.ui.WorkspacesDialog(state) {} }
        }.use { scene ->
            repeat(3) { scene.render(System.nanoTime()); Thread.sleep(80) }
            val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/snapshots/workspaces.png").apply { parentFile.mkdirs(); writeBytes(png) }
        }
    }

    private suspend fun themedSession(themeId: String, name: String) {
        val state = lightState()
        val web = seed(state)
        state.services.workspace.update { it.copy(ui = it.ui.copy(theme = themeId)) }
        val answering = CoroutineScope(Dispatchers.Default)
        answering.answerHostKeys(state)
        val tab = state.sessions.connect(web)
        val files = state.sessions.openFiles(web, "/etc")
        state.sessions.merge(state.sessions.bar.value.frameOf(files.id)!!.id, state.sessions.bar.value.frameOf(tab.id)!!.id, Side.Right)
        state.sessions.select(tab.id)
        tab.awaitOpen().terminal.sendText("ls --color=always -la /etc | head -8; printf '\\e[31mrosso \\e[32mverde \\e[33mgiallo \\e[34mblu \\e[35mmagenta \\e[36mciano\\e[0m\\n'\r")
        withTimeout(30_000) { files.browser.path.first { it == "/etc" } }
        snapshot(state, name, width = 2800, height = 1500)
        answering.cancel()
        state.sessions.closeAll()
    }

    @Test
    fun nightTheme(): Unit = runBlocking { themedSession("notte", "theme-notte") }

    @Test
    fun otherThemes(): Unit = runBlocking {
        themedSession("tokyo-night", "theme-tokyo-night")
        themedSession("solarized-chiaro", "theme-solarized")
    }

    @Test
    fun settingsScreen(): Unit = runBlocking {
        val state = lightState()
        state.services.workspace.update { it.copy(ui = it.ui.copy(theme = "notte")) }
        ImageComposeScene(2000, 1500, Density(2f)) {
            dev.eltermino.ui.theme.CartaTheme(dev.eltermino.ui.theme.Themes.Notte) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(dev.eltermino.ui.theme.Carta.colors.background)) {
                    dev.eltermino.ui.SettingsScreen(state, androidx.compose.ui.Modifier.fillMaxSize())
                }
            }
        }.use { scene ->
            repeat(3) { scene.render(System.nanoTime()); Thread.sleep(80) }
            val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/snapshots/settings.png").apply { parentFile.mkdirs(); writeBytes(png) }
        }
    }

    @Test
    fun welcomeAndTour() {
        val state = lightState()
        listOf(false to "welcome", true to "tour").forEach { (tour, name) ->
            ImageComposeScene(1500, 1300, Density(2f)) {
                dev.eltermino.ui.theme.CartaTheme { dev.eltermino.ui.WelcomeFlow(state, startAtTour = tour, onOpenImport = {}) {} }
            }.use { scene ->
                repeat(3) { scene.render(System.nanoTime()); Thread.sleep(80) }
                val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File("build/snapshots/$name.png").apply { parentFile.mkdirs(); writeBytes(png) }
            }
        }
    }

    @Test
    fun emptyFirstRun() {
        snapshot(lightState(), "app-desktop-empty")
    }

    /** macOS draws its title bar over the app; the bars at the top reach under it. */
    @Test
    fun underTheMacTitleBar(): Unit = runBlocking {
        val state = lightState()
        seed(state)
        state.services.workspace.update { it.copy(ui = it.ui.copy(theme = "notte")) }
        snapshot(state, "app-desktop-title-bar", titleBar = 28.dp)
    }
}
