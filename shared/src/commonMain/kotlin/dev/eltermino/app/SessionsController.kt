package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.newId
import dev.eltermino.core.ssh.AgentUnavailableException
import dev.eltermino.core.ssh.ConnectionState
import dev.eltermino.core.ssh.PtyConfig
import dev.eltermino.core.ssh.SshConnection
import dev.eltermino.core.ssh.SshException
import dev.eltermino.core.terminal.TerminalInput
import dev.eltermino.core.terminal.TerminalKey
import dev.eltermino.core.terminal.TerminalSession
import dev.eltermino.core.terminal.TerminalSessionConfig
import dev.eltermino.core.vault.VaultException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import dev.eltermino.core.data.SavedGroup
import dev.eltermino.core.data.SavedLayout
import dev.eltermino.core.data.SavedPane
import dev.eltermino.core.data.SavedTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark

/** What a tab shows: a terminal, a file panel, an editor or HTTP requests. Any of them fits in a pane. */
sealed class Tab(val id: String, val label: String) {
    /** Null for this device's files. */
    abstract val hostId: String?
}

class SessionTab internal constructor(id: String, override val hostId: String, label: String) : Tab(id, label) {
    internal val mutableStatus = MutableStateFlow<TabStatus>(TabStatus.Connecting)
    val status: StateFlow<TabStatus> = mutableStatus.asStateFlow()

    internal val mutableTerminal = MutableStateFlow<TerminalSession?>(null)

    /** Made with the first shell and kept across reconnects, so the scrollback stays. */
    val terminal: StateFlow<TerminalSession?> = mutableTerminal.asStateFlow()

    internal var job: Job? = null
    internal var link: HostLink? = null

    internal var recorder: AsciicastRecorder? = null
    internal var recordJob: Job? = null
    internal val mutableRecording = MutableStateFlow(false)

    /** True while what the terminal shows goes to a recording. */
    val recording: StateFlow<Boolean> = mutableRecording.asStateFlow()
}

/** A file panel on a host, or on this device when [hostId] is null. */
class FilesTab internal constructor(id: String, override val hostId: String?, label: String, val browser: FileBrowser) : Tab(id, label)

/** A text file opened from a file panel, with its own share of the host's link. */
class EditorTab internal constructor(
    id: String,
    override val hostId: String?,
    label: String,
    val path: String,
    /** The text lives in an app of the system; this tab only uploads its saves. */
    val external: Boolean = false,
) : Tab(id, label) {
    internal val mutableEditor = MutableStateFlow<RemoteEditor?>(null)
    internal var release: (suspend () -> Unit)? = null
    internal var job: Job? = null

    /** Stops following the external file, after uploading a save not seen yet. */
    internal var finishExternal: (() -> Unit)? = null

    /** Closed with an external file still followed: nobody is left to answer. */
    @Volatile
    internal var closing = false

    /** External saves uploaded so far. */
    val uploads = MutableStateFlow(0)

    /** Waiting for an answer: the file changed on the server, replace it anyway? */
    val conflict = MutableStateFlow<CompletableDeferred<Boolean>?>(null)

    /** Why the last external save didn't reach the server; the next one tries again. */
    val uploadError = MutableStateFlow<String?>(null)

    /** The text as loaded; the editor keeps its own copy while you type. */
    val loaded = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val dirty = MutableStateFlow(false)
    val editor: StateFlow<RemoteEditor?> = mutableEditor.asStateFlow()
}

sealed interface TabStatus {
    data object Connecting : TabStatus
    class Open(val connection: SshConnection, val terminal: TerminalSession) : TabStatus

    /**
     * The connection dropped and is being retried; [terminal] keeps the last screen.
     * [attempt] is 0 for a reconnect the user asked for.
     */
    class Reconnecting(
        val terminal: TerminalSession,
        val attempt: Int,
        val attempts: Int,
        val retryAt: TimeMark?,
        val message: String?,
    ) : TabStatus

    class Failed(val message: String) : TabStatus

    /** The session ended; [terminal] keeps the last screen visible. */
    class Closed(val terminal: TerminalSession?, val message: String) : TabStatus
}

/**
 * Owns the session tabs, the split view and broadcast input. Tabs on the same host share
 * one [HostLink]: a second tab opens instantly, without logging in again, and a dropped
 * connection is retried for all of them at once.
 */
class SessionsController(
    private val services: AppServices,
    private val actions: WorkspaceActions,
    private val scope: CoroutineScope,
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val terminalConfig: TerminalSessionConfig = TerminalSessionConfig(),
    val connections: Connections = Connections(services, actions, scope, reconnectPolicy),
) {
    val prompter: Prompter get() = connections.prompter

    private val mutableTabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = mutableTabs.asStateFlow()

    private val terminals get() = tabs.value.filterIsInstance<SessionTab>()

    private val mutableBar = MutableStateFlow(TabBar())

    /** The tabs in the bar, each with its own split, their groups and the selected one. */
    val bar: StateFlow<TabBar> = mutableBar.asStateFlow()

    private val mutablePanes = MutableStateFlow(Panes())

    /** The selected tab's panes. */
    val panes: StateFlow<Panes> = mutablePanes.asStateFlow()

    private val mutableSelectedId = MutableStateFlow<String?>(null)

    /** The session with the keyboard: the focused pane of the selected tab. */
    val selectedId: StateFlow<String?> = mutableSelectedId.asStateFlow()

    private val mutableBroadcast = MutableStateFlow<Set<String>>(emptySet())

    /** Tabs that receive each other's typing; empty when broadcast is off. */
    val broadcast: StateFlow<Set<String>> = mutableBroadcast.asStateFlow()

    /** Hosts with at least one open session, for the green dots in the host list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectedHostIds: StateFlow<Set<String>> = tabs
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptySet())
            else combine(list.map { tab -> tab.connectedHost() }) { hosts -> hosts.filterNotNull().toSet() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        scope.launch { mirrorBroadcastInput() }
        scope.launch { rememberLastSession() }
    }

    /** Applies [transform] to the bar; null means "not possible", and returns false. */
    private fun updateBar(transform: (TabBar) -> TabBar?): Boolean {
        var changed = false
        mutableBar.update { current -> transform(current)?.also { changed = true } ?: current }
        val selected = mutableBar.value.selectedFrame
        mutablePanes.value = selected?.panes ?: Panes()
        mutableSelectedId.value = selected?.panes?.focusedTabId
        return changed
    }

    /** Selects the tab showing [tabId] and gives its pane the keyboard. */
    fun select(tabId: String) = updateBar { it.focusSession(tabId) }

    fun selectFrame(frameId: String) = updateBar { it.select(frameId) }

    /** The session with the keyboard, if any. */
    fun focusedTab(): Tab? = selectedId.value?.let { id -> tabs.value.firstOrNull { it.id == id } }

    /** The tabs in the bar, left to right, leaving out those folded into a collapsed group. */
    private fun visibleFrames(): List<Frame> {
        val bar = bar.value
        return bar.frames.filter { frame -> bar.groups.none { it.id == frame.groupId && it.collapsed } }
    }

    /** The next tab ([step] 1) or the previous one (-1), going round at the ends. */
    fun selectNeighbour(step: Int) {
        val frames = visibleFrames().ifEmpty { return }
        val current = frames.indexOfFirst { it.id == bar.value.selected }
        val next = if (current < 0) 0 else ((current + step) % frames.size + frames.size) % frames.size
        selectFrame(frames[next].id)
    }

    /** The tab at [index] from the left; -1 is the last one. */
    fun selectFrameAt(index: Int) {
        val frames = visibleFrames()
        (if (index < 0) frames.lastOrNull() else frames.getOrNull(index))?.let { selectFrame(it.id) }
    }

    fun focusPane(index: Int) = updateBar { it.focusPane(index) }

    /** Shows [tabId] in pane [index] of the selected tab; whatever was there takes its place. */
    fun showInPane(index: Int, tabId: String) = updateBar { it.showIn(index, tabId) }

    /** The selected tab's split. Sessions that don't fit any more get tabs of their own. */
    fun setLayout(layout: SplitLayout) = updateBar { bar -> bar.selected?.let { bar.setLayout(it, layout, ::newId) } }

    /** Drops tab [source] next to [target]'s panes; false when they'd need more than four. */
    fun merge(source: String, target: String, side: Side): Boolean = updateBar { it.merge(source, target, side) }

    fun separate(frameId: String) = updateBar { it.separate(frameId, ::newId) }

    fun detach(tabId: String) = updateBar { it.detach(tabId, newId()) }

    fun moveFrame(frameId: String, index: Int) = updateBar { it.move(frameId, index) }

    fun closeFrame(frameId: String) {
        val ids = bar.value.frame(frameId)?.sessionIds.orEmpty()
        tabs.value.filter { it.id in ids }.forEach(::close)
    }

    fun createGroup(frameIds: List<String>, name: String) = updateBar { it.createGroup(frameIds, newId(), name) }

    fun addToGroup(frameId: String, groupId: String) = updateBar { it.addToGroup(frameId, groupId) }

    fun removeFromGroup(frameId: String) = updateBar { it.removeFromGroup(frameId) }

    fun renameGroup(groupId: String, name: String) = updateBar { it.renameGroup(groupId, name) }

    fun recolorGroup(groupId: String, color: GroupColor) = updateBar { it.recolorGroup(groupId, color) }

    fun toggleGroup(groupId: String) = updateBar { it.toggleCollapsed(groupId) }

    fun ungroup(groupId: String) = updateBar { it.ungroup(groupId) }

    fun gridFromGroup(groupId: String) = updateBar { it.gridFromGroup(groupId) }

    fun closeGroup(groupId: String) = bar.value.framesIn(groupId).forEach { closeFrame(it.id) }

    /** One group per host with two or more tabs, named after the host. */
    fun groupByHost() {
        val byId = tabs.value.associateBy { it.id }
        val workspace = services.workspace.workspace.value
        updateBar { bar ->
            bar.groupByHost(
                hostOf = { id -> byId[id]?.hostId ?: "local" },
                labelOf = { host -> workspace.host(host)?.label ?: "Questo computer" },
                newGroupId = ::newId,
            )
        }
    }

    /** The terminals of a group, for broadcast and snippets. */
    fun terminalsIn(groupId: String): Set<String> {
        val ids = bar.value.framesIn(groupId).flatMap { it.sessionIds }.toSet()
        return terminals.filter { it.id in ids }.map { it.id }.toSet()
    }

    /** Opens a new tab on [host], in an empty pane of the selected tab if it has one. */
    fun connect(host: Host): SessionTab = add(createTerminal(host))

    /** A file panel on [host], or on this device when null. */
    fun openFiles(host: Host?, path: String? = null): FilesTab = add(createFiles(host, path))

    /** [path] as text in a new tab; it gets its own share of the link, so closing the panel doesn't stop it. */
    fun openEditor(endpoint: Endpoint, path: String): EditorTab = add(createEditor(endpoint, path))

    /** [path] in the system's app for it; the tab stays open to upload every save. */
    fun openExternalEditor(endpoint: Endpoint, path: String): EditorTab = add(createEditor(endpoint, path, external = true))

    /** HTTP requests sent from [host], through its shared link. */
    fun openRequests(host: Host): RequestsTab = add(createRequests(host))

    private fun createRequests(host: Host): RequestsTab {
        val tab = register(RequestsTab(newId(), host.id, host.label))
        tab.job = scope.launch {
            val lease = connections.acquire(host)
            tab.mutableLease.value = lease
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { lease.release() }
            }
        }
        return tab
    }

    /** What [host] is using: CPU, memory, disks, network. */
    fun openMonitor(host: Host): MonitorTab = add(createMonitor(host))

    private fun createMonitor(host: Host): MonitorTab {
        val tab = register(MonitorTab(newId(), host.id, host.label))
        tab.job = scope.launch {
            val lease = connections.acquire(host)
            tab.mutableLease.value = lease
            try {
                monitor(tab, lease)
            } finally {
                withContext(NonCancellable) { lease.release() }
            }
        }
        return tab
    }

    /** Starts recording what [tab]'s terminal shows, from now on. */
    fun startRecording(tab: SessionTab) {
        val store = services.recordings ?: return
        val terminal = tab.terminal.value ?: return
        if (tab.recorder != null) return
        val size = terminal.size.value
        val recorder = AsciicastRecorder(store.create(tab.label), tab.label, size.cols, size.rows, nowEpochSeconds())
        tab.recorder = recorder
        terminal.onOutput = recorder::output
        tab.recordJob = scope.launch { terminal.size.drop(1).collect { recorder.resized(it.cols, it.rows) } }
        tab.mutableRecording.value = true
    }

    fun stopRecording(tab: SessionTab) {
        val recorder = tab.recorder ?: return
        tab.terminal.value?.onOutput = null
        tab.recordJob?.cancel()
        recorder.close()
        tab.recorder = null
        tab.mutableRecording.value = false
    }

    /** Plays a recording in a new tab. */
    fun openReplay(summary: RecordingSummary): ReplayTab? {
        val bytes = services.recordings?.read(summary.id) ?: return null
        val cast = runCatching { parseAsciicast(bytes.decodeToString()) }.getOrNull() ?: return null
        return add(register(ReplayTab(newId(), summary.title.ifBlank { "Registrazione" }, cast, scope)))
    }

    /** The containers on [host], with their state and usage. */
    fun openDocker(host: Host): DockerTab = add(createDocker(host))

    /** Asks before removing; [dockerConfirm] goes ahead. */
    fun dockerRemove(tab: DockerTab, kind: DockerKind, id: String, name: String) {
        tab.mutableConfirmation.value = DockerConfirmation(
            title = "Rimuovere $name?",
            message = "Da ${tab.label}. Non si può annullare.",
            action = "Rimuovi",
            busyId = id,
            label = name,
            command = dockerRemoveCommand(kind, id),
        )
    }

    /** Taking a project down asks first, as it removes its containers. */
    fun dockerCompose(tab: DockerTab, project: ComposeProject, action: ComposeAction) {
        val command = dockerComposeCommand(project, action)
        if (action == ComposeAction.Down) {
            tab.mutableConfirmation.value = DockerConfirmation(
                title = "Fermare e rimuovere ${project.name}?",
                message = "docker compose down toglie i container e le reti del progetto da ${tab.label}.",
                action = "Esegui down",
                busyId = project.name,
                label = project.name,
                command = command,
            )
        } else {
            val lease = dockerLease(tab) ?: return
            scope.launch { runDockerCommand(tab, lease, project.name, project.name, command) }
        }
    }

    fun dockerConfirm(tab: DockerTab) {
        val confirmation = tab.confirmation.value ?: return
        tab.mutableConfirmation.value = null
        val lease = dockerLease(tab) ?: return
        scope.launch { runDockerCommand(tab, lease, confirmation.busyId, confirmation.label, confirmation.command) }
    }

    private fun dockerLease(tab: DockerTab): LinkLease? =
        tab.lease.value ?: null.also { tab.mutableActionError.value = "${tab.label} non è collegato" }

    fun dockerCancel(tab: DockerTab) {
        tab.mutableConfirmation.value = null
    }

    private fun createDocker(host: Host): DockerTab {
        val tab = register(DockerTab(newId(), host.id, host.label))
        tab.job = scope.launch {
            val lease = connections.acquire(host)
            tab.mutableLease.value = lease
            try {
                watchDocker(tab, lease)
            } finally {
                withContext(NonCancellable) { lease.release() }
            }
        }
        return tab
    }

    fun dockerAction(tab: DockerTab, container: DockerContainer, action: DockerAction) {
        val lease = tab.lease.value ?: return
        scope.launch { runDockerAction(tab, lease, container, action) }
    }

    /** Opens a terminal on the tab's host following [container]'s log. */
    fun dockerLogs(tab: DockerTab, container: DockerContainer) = runSnippet(dockerLogsCommand(container), true, SnippetTarget.Hosts(listOf(tab.hostId)))

    /** Opens a terminal on the tab's host with a shell inside [container]. */
    fun dockerShell(tab: DockerTab, container: DockerContainer) = runSnippet(dockerShellCommand(container), true, SnippetTarget.Hosts(listOf(tab.hostId)))

    private fun createTerminal(host: Host): SessionTab {
        val tab = register(SessionTab(newId(), host.id, host.label))
        start(tab)
        return tab
    }

    private fun createFiles(host: Host?, path: String?): FilesTab {
        val browser = if (host == null) {
            val local = services.localFiles ?: throw IllegalStateException("Su questo dispositivo non ci sono file locali")
            FileBrowser(Endpoint.Local, scope, open = { local }, startPath = path)
        } else {
            FileBrowser(Endpoint.Remote(host.id), scope, open = { connections.files(host) }, onClose = { (it as? RemoteFileSystem)?.release() }, startPath = path)
        }
        return register(FilesTab(newId(), host?.id, host?.label ?: "Questo computer", browser))
    }

    private fun createEditor(endpoint: Endpoint, path: String, external: Boolean = false): EditorTab {
        val host = (endpoint as? Endpoint.Remote)?.let { services.workspace.workspace.value.host(it.hostId) }
        val tab = register(EditorTab(newId(), host?.id, fileName(path), path, external))
        tab.job = scope.launch {
            try {
                val fs: FileSystem = if (host != null) {
                    connections.files(host).also { remote -> tab.release = { remote.release() } }
                } else {
                    services.localFiles ?: throw IllegalStateException("Su questo dispositivo non ci sono file locali")
                }
                if (external) {
                    followExternally(tab, RemoteEditor(endpoint, path, fs, EXTERNAL_EDIT_LIMIT_BYTES))
                    return@launch
                }
                val editor = RemoteEditor(endpoint, path, fs)
                tab.loaded.value = editor.load()
                tab.mutableEditor.value = editor
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                tab.error.value = failureMessage(e)
            }
        }
        return tab
    }

    private suspend fun followExternally(tab: EditorTab, editor: RemoteEditor) {
        val file = services.platform.openExternally(tab.label, editor.loadBytes())
            ?: throw IllegalStateException("Su questo dispositivo non si può aprire un editor esterno")
        tab.finishExternal = file::finish
        tab.mutableEditor.value = editor
        tab.loaded.value = ""
        fun leftBehind(why: String) =
            services.platform.notify("${tab.label} non caricato", "$why La copia resta in ${file.path} fino alla chiusura dell'app.")
        try {
            followExternalFile(
                editor,
                file,
                conflict = { _ ->
                    val replace = if (tab.closing) {
                        false
                    } else {
                        val answer = CompletableDeferred<Boolean>()
                        tab.conflict.value = answer
                        answer.await().also { tab.conflict.value = null }
                    }
                    if (!replace && tab.closing) leftBehind("È cambiato sul server.")
                    replace
                },
                saved = {
                    tab.uploads.update { it + 1 }
                    tab.uploadError.value = null
                },
                failed = {
                    if (tab.closing) leftBehind(failureMessage(it))
                    tab.uploadError.value = failureMessage(it)
                },
            )
        } finally {
            tab.finishExternal = null
            tab.release?.invoke()
            tab.release = null
        }
    }

    private fun <T : Tab> register(tab: T): T {
        mutableTabs.update { it + tab }
        return tab
    }

    private fun <T : Tab> add(tab: T): T {
        updateBar { it.open(tab.id, newId()) }
        return tab
    }

    /** The bar as it is now, or one group of it, to save and reopen later; null when there's nothing. */
    fun snapshot(name: String, groupId: String? = null): SavedLayout? {
        val whole = bar.value
        val current = if (groupId == null) whole else whole.copy(frames = whole.framesIn(groupId), groups = listOfNotNull(whole.group(groupId)))
        if (current.frames.isEmpty()) return null
        val byId = tabs.value.associateBy { it.id }
        fun pane(id: String?): SavedPane? = when (val tab = id?.let(byId::get)) {
            null -> null
            is SessionTab -> SavedPane("terminal", tab.hostId)
            is FilesTab -> SavedPane("files", tab.hostId, tab.browser.path.value)
            is EditorTab -> SavedPane("editor", tab.hostId, tab.path)
            is RequestsTab -> SavedPane("requests", tab.hostId)
            is MonitorTab -> SavedPane("monitor", tab.hostId)
            is DockerTab -> SavedPane("docker", tab.hostId)
            // A replay is for now; it isn't reopened with a layout.
            is ReplayTab -> null
        }
        return SavedLayout(
            id = newId(),
            name = name,
            tabs = current.frames.map { SavedTab(it.panes.layout.name, it.panes.tabIds.map(::pane), it.panes.focused, it.groupId) },
            groups = current.groups.map { SavedGroup(it.id, it.name, it.color.name, it.collapsed) },
        )
    }

    /** Opens [layout]'s tabs after the ones already open, logging in to each host as it goes. */
    fun restore(layout: SavedLayout) {
        val workspace = services.workspace.workspace.value
        val groupIds = layout.groups.associate { it.id to newId() }
        val frames = layout.tabs.mapNotNull { saved ->
            val split = SplitLayout.entries.firstOrNull { it.name == saved.layout } ?: SplitLayout.Single
            val ids = saved.panes.take(split.panes).map { pane -> pane?.let { open(it, workspace) }?.id }
            if (ids.all { it == null }) return@mapNotNull null
            val panes = Panes(split, ids + List(split.panes - ids.size) { null }, saved.focused.coerceIn(0, split.panes - 1))
            // A pane whose host is gone leaves a smaller split, not a hole.
            val lost = ids.count { it == null } > saved.panes.take(split.panes).count { it == null }
            Frame(newId(), if (lost) panes.compacted() else panes, saved.groupId?.let(groupIds::get))
        }
        if (frames.isEmpty()) return
        val groups = layout.groups.map { saved ->
            TabGroup(groupIds.getValue(saved.id), saved.name, GroupColor.entries.firstOrNull { it.name == saved.color } ?: GroupColor.Blu, saved.collapsed)
        }
        updateBar { bar -> TabBar(bar.frames + frames, bar.groups + groups, frames.first().id).normalized() }
    }

    private fun open(pane: SavedPane, workspace: dev.eltermino.core.data.Workspace): Tab? {
        val host = pane.hostId?.let { workspace.host(it) ?: return null }
        return when (pane.kind) {
            "terminal" -> host?.let(::createTerminal)
            "files" -> if (host == null && services.localFiles == null) null else createFiles(host, pane.path)
            "editor" -> pane.path?.let { createEditor(host?.let { Endpoint.Remote(it.id) } ?: Endpoint.Local, it) }
            "requests" -> host?.let(::createRequests)
            "monitor" -> host?.let(::createMonitor)
            "docker" -> host?.let(::createDocker)
            else -> null
        }
    }

    /** Keeps what's open in the workspace, so the next start can offer it again. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private suspend fun rememberLastSession() {
        val folders = tabs.flatMapLatest { list ->
            val panels = list.filterIsInstance<FilesTab>()
            if (panels.isEmpty()) flowOf(emptyList()) else combine(panels.map { it.browser.path }) { it.toList() }
        }
        combine(bar, folders) { current, _ -> current }
            .debounce(1_000)
            .collect { current ->
                if (current.frames.isEmpty()) return@collect
                val saved = snapshot("Ultima sessione") ?: return@collect
                runCatching { services.workspace.update { it.copy(lastSession = saved.copy(id = LAST_SESSION)) } }
            }
    }

    /** Another shell on the same host; it shares the connection, so it opens without a login. */
    fun duplicate(tab: SessionTab): SessionTab? = services.workspace.workspace.value.host(tab.hostId)?.let(::connect)

    fun reconnect(tab: SessionTab) = start(tab)

    /** Skips the wait before the next attempt of a dropped connection. */
    fun retryNow(tab: SessionTab) {
        tab.link?.retryNow()
    }

    /** Stops retrying: the tab keeps its last screen and a button to reconnect. */
    fun stopReconnecting(tab: SessionTab) {
        val job = tab.job ?: return
        job.cancel()
        // Once the job is really over, so a status it was still setting can't win.
        job.invokeOnCompletion { tab.mutableStatus.value = TabStatus.Closed(tab.terminal.value, "Riconnessione annullata") }
    }

    fun close(tab: Tab) {
        when (tab) {
            is SessionTab -> {
                stopRecording(tab)
                tab.job?.cancel()
                scope.launch { tab.terminal.value?.let { withContext(NonCancellable) { runCatching { it.close() } } } }
            }
            is FilesTab -> scope.launch { tab.browser.close() }
            is EditorTab -> tab.finishExternal?.let { finish ->
                tab.closing = true
                tab.conflict.value?.complete(false)
                finish()
            } ?: scope.launch {
                tab.job?.cancel()
                tab.release?.invoke()
            }
            is RequestsTab -> tab.job?.cancel()
            is MonitorTab -> tab.job?.cancel()
            is DockerTab -> tab.job?.cancel()
            is ReplayTab -> scope.launch { tab.terminal.close() }
        }
        mutableTabs.update { it - tab }
        updateBar { it.remove(tab.id) }
        mutableBroadcast.update { (it - tab.id).takeIf { ids -> ids.size >= 2 } ?: emptySet() }
    }

    fun closeAll() = tabs.value.forEach(::close)

    /** Starts broadcasting between [tabIds]; fewer than two turns it off. */
    fun setBroadcast(tabIds: Set<String>) {
        val open = terminals.map(SessionTab::id).toSet()
        mutableBroadcast.value = (tabIds intersect open).takeIf { it.size >= 2 } ?: emptySet()
    }

    /**
     * Types [command] into [target], then Enter if [run]. Text goes in as a paste, so a
     * multi-line command can't run line by line where the shell supports bracketed paste.
     */
    fun runSnippet(command: String, run: Boolean, target: SnippetTarget) {
        when (target) {
            // Typed like the user would, so broadcast mirrors it.
            SnippetTarget.Focused -> terminals.firstOrNull { it.id == panes.value.focusedTabId }?.terminal?.value?.let { terminal ->
                terminal.paste(command)
                if (run) terminal.sendKey(TerminalKey.Enter)
            }
            is SnippetTarget.Tabs -> terminals.filter { it.id in target.tabIds }.forEach { tab ->
                tab.terminal.value?.let { send(it, command, run) }
            }
            is SnippetTarget.Hosts -> {
                val workspace = services.workspace.workspace.value
                target.hostIds.mapNotNull(workspace::host).forEach { host ->
                    val tab = connect(host)
                    scope.launch {
                        val open = withTimeoutOrNull(5.minutes) { tab.status.first { it !is TabStatus.Connecting } } as? TabStatus.Open
                        open?.let { send(it.terminal, command, run) }
                    }
                }
            }
        }
    }

    private fun send(terminal: TerminalSession, command: String, run: Boolean) {
        terminal.receive(TerminalInput.Paste(command))
        if (run) terminal.receive(TerminalInput.Key(TerminalKey.Enter))
    }

    private suspend fun mirrorBroadcastInput() {
        combine(broadcast, tabs) { ids, list -> list.filterIsInstance<SessionTab>().filter { it.id in ids } }
            .distinctUntilChanged()
            .collectLatest { members ->
                if (members.size < 2) return@collectLatest
                coroutineScope {
                    for (source in members) {
                        launch {
                            source.terminal.filterNotNull().collectLatest { terminal ->
                                terminal.input.collect { input ->
                                    for (target in members) if (target !== source) target.terminal.value?.receive(input)
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun start(tab: SessionTab) {
        tab.job?.cancel()
        tab.mutableStatus.value = tab.terminal.value?.let { TabStatus.Reconnecting(it, 0, 0, null, null) } ?: TabStatus.Connecting
        tab.job = scope.launch {
            val host = services.workspace.workspace.value.host(tab.hostId)
            if (host == null) {
                tab.mutableStatus.value = TabStatus.Failed("L'host è stato eliminato")
                return@launch
            }
            val lease = connections.acquire(host)
            tab.link = lease.link
            try {
                follow(tab, lease.link)
            } finally {
                tab.link = null
                withContext(NonCancellable) { lease.release() }
            }
        }
    }

    /** Mirrors [link] into the tab until the tab's session ends or the link gives up. */
    private suspend fun follow(tab: SessionTab, link: HostLink) = coroutineScope {
        val done = CompletableDeferred<Unit>()
        var generation = 0
        val watcher = launch {
            link.state.collectLatest { state ->
                val terminal = tab.terminal.value
                when (state) {
                    LinkState.Connecting -> tab.mutableStatus.value =
                        terminal?.let { TabStatus.Reconnecting(it, 0, 0, null, null) } ?: TabStatus.Connecting
                    is LinkState.Retrying -> tab.mutableStatus.value =
                        terminal?.let { TabStatus.Reconnecting(it, state.attempt, state.attempts, state.retryAt, state.message) }
                            ?: TabStatus.Connecting
                    is LinkState.Down -> {
                        tab.mutableStatus.value = terminal?.let { TabStatus.Closed(it, state.message) } ?: TabStatus.Failed(state.message)
                        done.complete(Unit)
                    }
                    is LinkState.Up -> if (state.generation != generation) {
                        generation = state.generation
                        val ended = runShell(tab, state.connection)
                        val last = tab.terminal.value
                        tab.mutableStatus.value = last?.let { TabStatus.Closed(it, ended) } ?: TabStatus.Failed(ended)
                        done.complete(Unit)
                    }
                }
            }
        }
        done.await()
        watcher.cancel()
    }

    /**
     * Runs a shell on [connection] in the tab's terminal and returns why it ended. When the
     * whole connection drops instead, it waits to be cancelled by the link's next state.
     */
    private suspend fun runShell(tab: SessionTab, connection: SshConnection): String {
        val existing = tab.terminal.value
        val size = existing?.size?.value
        val shell = try {
            connection.openShell(PtyConfig(cols = size?.cols ?: terminalConfig.cols, rows = size?.rows ?: terminalConfig.rows))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (connection.state.value is ConnectionState.Closed) awaitCancellation()
            return describe(e)
        }
        val terminal = if (existing == null) {
            TerminalSession(shell, scope, terminalConfig).also {
                tab.mutableTerminal.value = it
                if (services.workspace.workspace.value.ui.recordSessions) startRecording(tab)
            }
        } else {
            existing.attach(shell, notice = "── Riconnesso alle ${clockTime()} ──")
            existing
        }
        tab.mutableStatus.value = TabStatus.Open(connection, terminal)
        terminal.closed.first { it }
        // The output also ends when the whole connection drops, a moment before it reports Closed.
        if (withTimeoutOrNull(2.seconds) { connection.state.first { it is ConnectionState.Closed } } != null) awaitCancellation()
        return "La sessione è terminata"
    }
}

internal const val LAST_SESSION = "last"

/** Tab titles, numbered when a host has more than one of a kind: "web-01", "web-01 · 2". */
fun List<Tab>.titles(): Map<String, String> {
    val seen = mutableMapOf<Pair<String?, String>, Int>()
    val counts = groupingBy { it.kindKey() }.eachCount()
    return associate { tab ->
        val key = tab.kindKey()
        val n = (seen[key] ?: 0) + 1
        seen[key] = n
        tab.id to if (counts.getValue(key) > 1 && tab !is EditorTab) "${tab.label} · $n" else tab.label
    }
}

private fun Tab.kindKey(): Pair<String?, String> = hostId to (this::class.simpleName ?: "")

/** The host this tab has a working connection to, if any. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun Tab.connectedHost(): kotlinx.coroutines.flow.Flow<String?> = when (this) {
    is SessionTab -> status.map { if (it is TabStatus.Open) hostId else null }
    is FilesTab -> combine(browser.path, browser.error) { path, error -> if (hostId != null && path != null && error == null) hostId else null }
    is EditorTab -> flowOf(null)
    is RequestsTab -> lease.flatMapLatest { it?.link?.state ?: flowOf(null) }.map { if (it is LinkState.Up) hostId else null }
    is MonitorTab -> lease.flatMapLatest { it?.link?.state ?: flowOf(null) }.map { if (it is LinkState.Up) hostId else null }
    is DockerTab -> lease.flatMapLatest { it?.link?.state ?: flowOf(null) }.map { if (it is LinkState.Up) hostId else null }
    is ReplayTab -> flowOf(null)
}

@OptIn(ExperimentalTime::class)
internal fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

/** The local time as "14:32". */
internal expect fun clockTime(): String

/** A file's date as the panel shows it: "14:32" today, "29 ott 14:32" this year, "29 ott 2024" before. */
expect fun formatFileTime(epochSeconds: Long): String

internal fun describe(e: Throwable): String = when (e) {
    is SshException.ConnectFailed -> "Impossibile raggiungere ${e.host}:${e.port}" + (e.cause?.message?.let { ": $it" } ?: "")
    is SshException.HostKeyRejected -> "Chiave dell'host ${e.host} rifiutata, connessione interrotta"
    is SshException.HostTrustRefused -> "${e.host}: ${e.reason}. Se è corretto, cambia l'elenco delle CA fidate nelle Impostazioni."
    is SshException.AuthFailed -> "Autenticazione non riuscita come ${e.username} su ${e.host}" + when ((e.cause as? AgentUnavailableException)?.running) {
        true -> ": l'SSH agent non ha chiavi"
        false -> ": nessun SSH agent attivo"
        null -> ""
    }
    is SshException.ChannelFailed -> e.message ?: "Errore del canale SSH"
    is VaultException -> "Vault: ${e.message}"
    else -> e.message ?: e::class.simpleName ?: "Errore sconosciuto"
}
