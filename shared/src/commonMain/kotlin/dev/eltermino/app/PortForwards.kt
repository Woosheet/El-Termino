package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.PortForwardRule
import dev.eltermino.core.ssh.PortForward
import dev.eltermino.core.ssh.SshConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a saved forward is doing; a rule that isn't running has no state. */
sealed interface ForwardState {
    /** Waiting for the host's connection, at first or after it dropped. */
    data object Starting : ForwardState

    /** Listening on [port]: on this device, or on the server for a remote forward. */
    data class Active(val port: Int) : ForwardState

    data class Failed(val message: String) : ForwardState
}

/**
 * Runs the forwards saved on hosts. Each one holds a share of its host's link, so the
 * connection stays up while it runs, and opens again on the new connection after a reconnect.
 */
class PortForwards(private val services: AppServices, private val connections: Connections, private val scope: CoroutineScope) {
    private val mutableStates = MutableStateFlow<Map<String, ForwardState>>(emptyMap())

    /** Rule id to state, for the rules started since the app opened. */
    val states: StateFlow<Map<String, ForwardState>> = mutableStates.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    init {
        // Rules marked to start with the connection; the ones already running stay as they are.
        scope.launch {
            connections.logins.collect { hostId ->
                val host = services.workspace.workspace.value.host(hostId) ?: return@collect
                host.forwards.filter { it.autoStart }.forEach { if (!isRunning(it.id)) start(host, it) }
            }
        }
    }

    fun isRunning(ruleId: String) = synchronized(jobs) { jobs[ruleId]?.isActive == true }

    /** Starts [rule] on [host], or starts it again with its current settings if it was running. */
    fun start(host: Host, rule: PortForwardRule) {
        stop(rule.id)
        set(rule.id, ForwardState.Starting)
        val job = scope.launch {
            val self = currentCoroutineContext().job
            val lease = connections.acquire(host)
            try {
                // Stops once the link is down for good; until then each new connection gets the forward again.
                lease.link.state.transformWhile { emit(it); it !is LinkState.Down }.collectLatest { link ->
                    when (link) {
                        is LinkState.Up -> {
                            val forward = try {
                                open(link.connection, rule)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                set(rule.id, ForwardState.Failed(message(host, rule, e)))
                                self.cancel()
                                return@collectLatest
                            }
                            try {
                                set(rule.id, ForwardState.Active(forward.port))
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) { forward.close() }
                            }
                        }
                        is LinkState.Down -> set(rule.id, ForwardState.Failed(link.message))
                        else -> set(rule.id, ForwardState.Starting)
                    }
                }
            } finally {
                withContext(NonCancellable) { lease.release() }
            }
        }
        synchronized(jobs) { jobs[rule.id] = job }
    }

    fun stop(ruleId: String) {
        synchronized(jobs) { jobs.remove(ruleId) }?.cancel()
        mutableStates.update { it - ruleId }
    }

    private fun set(ruleId: String, state: ForwardState) = mutableStates.update { it + (ruleId to state) }

    private suspend fun open(connection: SshConnection, rule: PortForwardRule): PortForward = when (rule.kind) {
        "remote" -> connection.forwardRemote(rule.bindPort, rule.targetHost, rule.targetPort)
        "dynamic" -> connection.forwardDynamic(rule.bindPort)
        else -> connection.forwardLocal(rule.targetHost, rule.targetPort, rule.bindPort)
    }

    private fun message(host: Host, rule: PortForwardRule, e: Exception): String {
        // The innermost cause says why: usually a port already in use, or one the server won't open.
        val why = generateSequence<Throwable>(e) { it.cause }.last().message ?: "errore sconosciuto"
        return if (rule.kind == "remote") "${host.label} non apre la porta ${rule.bindPort}: $why"
        else "La porta ${rule.bindPort} di questo dispositivo non si apre: $why"
    }
}
