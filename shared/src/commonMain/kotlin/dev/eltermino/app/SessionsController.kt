package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.data.KnownHostStatus
import dev.eltermino.core.data.knownHostStatus
import dev.eltermino.core.data.newId
import dev.eltermino.core.data.trust
import dev.eltermino.core.ssh.ConnectionState
import dev.eltermino.core.ssh.HostKeyVerifier
import dev.eltermino.core.ssh.SecretResolver
import dev.eltermino.core.ssh.SshConnection
import dev.eltermino.core.ssh.SshException
import dev.eltermino.core.terminal.TerminalSession
import dev.eltermino.core.terminal.TerminalSessionConfig
import dev.eltermino.core.vault.VaultException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class SessionTab internal constructor(val id: String, val hostId: String, val label: String) {
    internal val mutableStatus = MutableStateFlow<TabStatus>(TabStatus.Connecting)
    val status: StateFlow<TabStatus> = mutableStatus.asStateFlow()
    internal var job: Job? = null
}

sealed interface TabStatus {
    data object Connecting : TabStatus
    class Open(val connection: SshConnection, val terminal: TerminalSession) : TabStatus
    class Failed(val message: String) : TabStatus
    /** The connection ended; [terminal] keeps the last screen visible. */
    class Closed(val terminal: TerminalSession?, val message: String) : TabStatus
}

/** Opens connections, owns the session tabs and asks the user what the login needs. */
class SessionsController(
    private val services: AppServices,
    private val actions: WorkspaceActions,
    private val scope: CoroutineScope,
) {
    val prompter = Prompter()

    private val mutableTabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = mutableTabs.asStateFlow()

    private val mutableSelected = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = mutableSelected.asStateFlow()

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

    fun select(tabId: String) {
        mutableSelected.value = tabId
    }

    fun connect(host: Host): SessionTab {
        val tab = SessionTab(newId(), host.id, host.label)
        mutableTabs.update { it + tab }
        mutableSelected.value = tab.id
        start(tab)
        return tab
    }

    fun reconnect(tab: SessionTab) = start(tab)

    fun close(tab: SessionTab) {
        tab.job?.cancel()
        scope.launch { (tab.status.value as? TabStatus.Open)?.let { shutdown(it) } }
        val remaining = mutableTabs.value - tab
        mutableTabs.value = remaining
        if (mutableSelected.value == tab.id) mutableSelected.value = remaining.lastOrNull()?.id
    }

    private fun start(tab: SessionTab) {
        tab.job?.cancel()
        tab.mutableStatus.value = TabStatus.Connecting
        tab.job = scope.launch {
            val lazySecrets = LazySecrets()
            val afterSuccess = mutableListOf<suspend () -> Unit>()
            try {
                val workspace = services.workspace.workspace.value
                val host = workspace.host(tab.hostId) ?: throw IllegalStateException("L'host è stato eliminato")
                val target = ConnectionPlanner(workspace, prompter, actions, lazySecrets, afterSuccess).plan(host)
                val connection = services.connector.connect(target, hostKeyVerifier(), secretResolver(lazySecrets))
                val terminal = try {
                    val config = TerminalSessionConfig()
                    TerminalSession(connection.openShell(config.ptyConfig()), scope, config)
                } catch (e: Exception) {
                    connection.close()
                    throw e
                }
                // Save before showing the session, so a quick reconnect already finds the password.
                afterSuccess.forEach { save -> runCatching { save() } }
                markConnected(host.id)
                tab.mutableStatus.value = TabStatus.Open(connection, terminal)

                val closed = connection.state.first { it is ConnectionState.Closed } as ConnectionState.Closed
                tab.mutableStatus.value = TabStatus.Closed(terminal, closed.cause?.let(::describe) ?: "Connessione chiusa")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A cancelled dialog can surface wrapped, as the cause of an auth failure.
                val cancelled = generateSequence<Throwable>(e) { it.cause }.any { it is PromptCancelledException }
                tab.mutableStatus.value = TabStatus.Failed(if (cancelled) "Connessione annullata" else describe(e))
            } finally {
                lazySecrets.clear()
            }
        }
    }

    private suspend fun shutdown(open: TabStatus.Open) = withContext(NonCancellable) {
        runCatching { open.terminal.close() }
        runCatching { open.connection.close() }
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

@OptIn(ExperimentalTime::class)
internal fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

internal fun describe(e: Throwable): String = when (e) {
    is SshException.ConnectFailed -> "Impossibile raggiungere ${e.host}:${e.port}" + (e.cause?.message?.let { ": $it" } ?: "")
    is SshException.HostKeyRejected -> "Chiave dell'host ${e.host} rifiutata, connessione interrotta"
    is SshException.AuthFailed -> "Autenticazione non riuscita come ${e.username} su ${e.host}"
    is SshException.ChannelFailed -> e.message ?: "Errore del canale SSH"
    is VaultException -> "Vault: ${e.message}"
    else -> e.message ?: e::class.simpleName ?: "Errore sconosciuto"
}
