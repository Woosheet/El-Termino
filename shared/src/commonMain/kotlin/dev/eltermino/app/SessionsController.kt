package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.data.KnownHostStatus
import dev.eltermino.core.data.knownHostStatus
import dev.eltermino.core.data.newId
import dev.eltermino.core.data.trust
import dev.eltermino.core.ssh.ConnectionState
import dev.eltermino.core.ssh.HostKeyVerifier
import dev.eltermino.core.ssh.PtyConfig
import dev.eltermino.core.ssh.SecretResolver
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

class SessionTab internal constructor(val id: String, val hostId: String, val label: String) {
    internal val mutableStatus = MutableStateFlow<TabStatus>(TabStatus.Connecting)
    val status: StateFlow<TabStatus> = mutableStatus.asStateFlow()

    internal val mutableTerminal = MutableStateFlow<TerminalSession?>(null)

    /** Made with the first shell and kept across reconnects, so the scrollback stays. */
    val terminal: StateFlow<TerminalSession?> = mutableTerminal.asStateFlow()

    internal var job: Job? = null
    internal var link: HostLink? = null
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
) {
    val prompter = Prompter()

    private val links = HostLinks(scope, reconnectPolicy, ::openConnection)

    private val mutableTabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = mutableTabs.asStateFlow()

    private val mutablePanes = MutableStateFlow(Panes())
    val panes: StateFlow<Panes> = mutablePanes.asStateFlow()

    /** The tab with the keyboard: the one in the focused pane. */
    val selectedId: StateFlow<String?> = panes.map { it.focusedTabId }.stateIn(scope, SharingStarted.Eagerly, null)

    private val mutableBroadcast = MutableStateFlow<Set<String>>(emptySet())

    /** Tabs that receive each other's typing; empty when broadcast is off. */
    val broadcast: StateFlow<Set<String>> = mutableBroadcast.asStateFlow()

    /** Hosts with at least one open session, for the green dots in the host list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectedHostIds: StateFlow<Set<String>> = tabs
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptySet())
            else combine(list.map { tab -> tab.status.map { tab.hostId to (it is TabStatus.Open) } }) { pairs ->
                pairs.filter { it.second }.map { it.first }.toSet()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        scope.launch { mirrorBroadcastInput() }
    }

    fun select(tabId: String) = mutablePanes.update { it.show(tabId) }

    fun focusPane(index: Int) = mutablePanes.update { it.focus(index) }

    fun showInPane(index: Int, tabId: String) = mutablePanes.update { it.showIn(index, tabId) }

    fun setLayout(layout: SplitLayout) = mutablePanes.update { it.withLayout(layout, tabs.value.map(SessionTab::id)) }

    /** Opens a new tab on [host] in the focused pane. */
    fun connect(host: Host): SessionTab {
        val tab = SessionTab(newId(), host.id, host.label)
        mutableTabs.update { it + tab }
        mutablePanes.update { it.show(tab.id) }
        start(tab)
        return tab
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

    fun close(tab: SessionTab) {
        tab.job?.cancel()
        scope.launch { tab.terminal.value?.let { withContext(NonCancellable) { runCatching { it.close() } } } }
        val remaining = mutableTabs.value - tab
        mutableTabs.value = remaining
        mutablePanes.update { it.without(tab.id, remaining.map(SessionTab::id)) }
        mutableBroadcast.update { (it - tab.id).takeIf { ids -> ids.size >= 2 } ?: emptySet() }
    }

    fun closeAll() = tabs.value.forEach(::close)

    /** Starts broadcasting between [tabIds]; fewer than two turns it off. */
    fun setBroadcast(tabIds: Set<String>) {
        val open = tabs.value.map(SessionTab::id).toSet()
        mutableBroadcast.value = (tabIds intersect open).takeIf { it.size >= 2 } ?: emptySet()
    }

    /**
     * Types [command] into [target], then Enter if [run]. Text goes in as a paste, so a
     * multi-line command can't run line by line where the shell supports bracketed paste.
     */
    fun runSnippet(command: String, run: Boolean, target: SnippetTarget) {
        when (target) {
            // Typed like the user would, so broadcast mirrors it.
            SnippetTarget.Focused -> tabs.value.firstOrNull { it.id == panes.value.focusedTabId }?.terminal?.value?.let { terminal ->
                terminal.paste(command)
                if (run) terminal.sendKey(TerminalKey.Enter)
            }
            is SnippetTarget.Tabs -> tabs.value.filter { it.id in target.tabIds }.forEach { tab ->
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
        combine(broadcast, tabs) { ids, list -> list.filter { it.id in ids } }
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
            val lease = links.acquire(host)
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
            TerminalSession(shell, scope, terminalConfig).also { tab.mutableTerminal.value = it }
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

    /** Logs in to [hostId]; any answer the user asked to keep is saved once the login worked. */
    private suspend fun openConnection(hostId: String): SshConnection {
        val lazySecrets = LazySecrets()
        val afterSuccess = mutableListOf<suspend () -> Unit>()
        try {
            val workspace = services.workspace.workspace.value
            val host = workspace.host(hostId) ?: throw HostMissingException()
            val target = ConnectionPlanner(workspace, prompter, actions, lazySecrets, afterSuccess).plan(host)
            val connection = services.connector.connect(target, hostKeyVerifier(), secretResolver(lazySecrets))
            afterSuccess.forEach { save -> runCatching { save() } }
            markConnected(hostId)
            return connection
        } finally {
            lazySecrets.clear()
        }
    }

    private fun hostKeyVerifier() = HostKeyVerifier { host, port, fingerprint ->
        val workspace = services.workspace.workspace.value
        when (val status = workspace.knownHostStatus(host, port, fingerprint.keyType, fingerprint.sha256)) {
            KnownHostStatus.Trusted -> true
            else -> {
                val previous = (status as? KnownHostStatus.Changed)?.previous
                val accepted = prompter.ask(PendingPrompt.HostKey(host, port, fingerprint, previous)) == true
                if (accepted) {
                    services.workspace.update {
                        it.trust(KnownHost(host, port, fingerprint.keyType, fingerprint.sha256, fingerprint.publicKey, nowEpochSeconds()))
                    }
                }
                accepted
            }
        }
    }

    private fun secretResolver(lazySecrets: LazySecrets) = SecretResolver { ref ->
        lazySecrets.resolve(ref.id)
            ?: services.vault.get(ref.id)
            ?: throw VaultException("Un segreto di questo host non è più nel vault")
    }

    private suspend fun markConnected(hostId: String) = services.workspace.update { ws ->
        ws.copy(hosts = ws.hosts.map { if (it.id == hostId) it.copy(lastConnectedEpochSeconds = nowEpochSeconds()) else it })
    }
}

/** Tab titles, numbered when a host has more than one: "web-01", "web-01 · 2". */
fun List<SessionTab>.titles(): Map<String, String> {
    val seen = mutableMapOf<String, Int>()
    val counts = groupingBy { it.hostId }.eachCount()
    return associate { tab ->
        val n = (seen[tab.hostId] ?: 0) + 1
        seen[tab.hostId] = n
        tab.id to if (counts.getValue(tab.hostId) > 1) "${tab.label} · $n" else tab.label
    }
}

@OptIn(ExperimentalTime::class)
internal fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

/** The local time as "14:32". */
internal expect fun clockTime(): String

internal fun describe(e: Throwable): String = when (e) {
    is SshException.ConnectFailed -> "Impossibile raggiungere ${e.host}:${e.port}" + (e.cause?.message?.let { ": $it" } ?: "")
    is SshException.HostKeyRejected -> "Chiave dell'host ${e.host} rifiutata, connessione interrotta"
    is SshException.AuthFailed -> "Autenticazione non riuscita come ${e.username} su ${e.host}"
    is SshException.ChannelFailed -> e.message ?: "Errore del canale SSH"
    is VaultException -> "Vault: ${e.message}"
    else -> e.message ?: e::class.simpleName ?: "Errore sconosciuto"
}
