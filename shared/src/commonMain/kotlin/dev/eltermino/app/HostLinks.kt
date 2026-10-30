package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.ssh.ConnectionState
import dev.eltermino.core.ssh.SshConnection
import dev.eltermino.core.ssh.SshException
import dev.eltermino.core.vault.VaultException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How a dropped connection is retried: one wait per attempt, then it gives up. */
data class ReconnectPolicy(
    val delays: List<Duration> = listOf(1, 2, 4, 8, 15, 30, 30, 30, 30, 30).map { it.seconds },
)

/** What a [HostLink] is doing; the tabs on it follow along. */
sealed interface LinkState {
    data object Connecting : LinkState

    /** [generation] grows with every reconnect, so a user can tell a new connection from the one it had. */
    class Up(val connection: SshConnection, val generation: Int) : LinkState

    /** The connection dropped. [retryAt] is when attempt number [attempt] starts; null while it runs. */
    class Retrying(val attempt: Int, val attempts: Int, val retryAt: TimeMark?, val message: String) : LinkState

    /** The first login failed, a reconnect hit something retrying can't fix, or the attempts ran out. */
    class Down(val message: String) : LinkState
}

/**
 * One SSH connection to a host, shared by every tab open on it, that reconnects by itself
 * when the network drops it. It closes when its last user lets go.
 */
class HostLink internal constructor(val hostId: String, internal val key: LinkKey) {
    internal val mutableState = MutableStateFlow<LinkState>(LinkState.Connecting)
    val state: StateFlow<LinkState> = mutableState.asStateFlow()
    internal var users = 0
    internal var job: Job? = null
    internal val wake = Channel<Unit>(Channel.CONFLATED)

    /** Skips the wait before the next reconnect attempt. */
    fun retryNow() {
        wake.trySend(Unit)
    }
}

/** Hosts edited while connected get a new link; tabs already open keep the old one. */
internal data class LinkKey(
    val hostId: String,
    val address: String,
    val port: Int,
    val username: String?,
    val identityId: String?,
    val jumpHostId: String?,
)

internal fun Host.linkKey() = LinkKey(id, address, port, username, identityId, jumpHostId)

class LinkLease internal constructor(val link: HostLink, private val onRelease: suspend () -> Unit) {
    private var released = false

    suspend fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

/**
 * Hands out [HostLink]s. OpenSSH allows 10 sessions per connection by default
 * (`MaxSessions`), so past [maxUsersPerLink] users a host gets a second connection.
 */
internal class HostLinks(
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy,
    /** Plans, authenticates and connects; may ask the user. */
    private val open: suspend (hostId: String) -> SshConnection,
    private val maxUsersPerLink: Int = 8,
) {
    private val mutex = Mutex()
    private val links = mutableListOf<HostLink>()

    suspend fun acquire(host: Host): LinkLease = mutex.withLock {
        val key = host.linkKey()
        val link = links.firstOrNull { it.key == key && it.users < maxUsersPerLink && it.state.value !is LinkState.Down }
            ?: HostLink(host.id, key).also { link ->
                links += link
                link.job = scope.launch { run(link) }
            }
        link.users++
        LinkLease(link) { release(link) }
    }

    private suspend fun release(link: HostLink) {
        val last = mutex.withLock {
            link.users--
            (link.users == 0).also { if (it) links -= link }
        }
        // Cancelling closes the connection, see run().
        if (last) link.job?.cancel()
    }

    private suspend fun forget(link: HostLink) = mutex.withLock { links -= link }

    private suspend fun run(link: HostLink) {
        var current: SshConnection? = null
        try {
            current = try {
                open(link.hostId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                link.mutableState.value = LinkState.Down(failureMessage(e))
                forget(link)
                return
            }
            var generation = 1
            link.mutableState.value = LinkState.Up(current, generation)
            while (true) {
                val closed = current!!.state.first { it is ConnectionState.Closed } as ConnectionState.Closed
                current = null
                var message = closed.cause?.let(::describe) ?: "Connessione interrotta"
                var reconnected: SshConnection? = null
                for ((index, wait) in policy.delays.withIndex()) {
                    val attempt = index + 1
                    while (link.wake.tryReceive().isSuccess) Unit
                    link.mutableState.value = LinkState.Retrying(attempt, policy.delays.size, TimeSource.Monotonic.markNow() + wait, message)
                    withTimeoutOrNull(wait) { link.wake.receive() }
                    link.mutableState.value = LinkState.Retrying(attempt, policy.delays.size, null, message)
                    try {
                        reconnected = open(link.hostId)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (!e.isWorthRetrying()) {
                            link.mutableState.value = LinkState.Down(failureMessage(e))
                            forget(link)
                            return
                        }
                        message = describe(e)
                    }
                }
                if (reconnected == null) {
                    link.mutableState.value = LinkState.Down("Connessione persa: $message")
                    forget(link)
                    return
                }
                current = reconnected
                generation++
                link.mutableState.value = LinkState.Up(reconnected, generation)
            }
        } finally {
            current?.let { withContext(NonCancellable) { runCatching { it.close() } } }
        }
    }
}

/** A network that's down comes back; a refused key or a cancelled dialog doesn't. */
private fun Throwable.isWorthRetrying(): Boolean {
    val chain = generateSequence(this) { it.cause }
    if (chain.any { it is PromptCancelledException }) return false
    return when (this) {
        is SshException.HostKeyRejected, is SshException.HostTrustRefused, is SshException.AuthFailed, is VaultException, is HostMissingException -> false
        else -> true
    }
}

internal fun failureMessage(e: Throwable): String =
    if (generateSequence(e) { it.cause }.any { it is PromptCancelledException }) "Connessione annullata" else describe(e)

internal class HostMissingException : Exception("L'host è stato eliminato")
