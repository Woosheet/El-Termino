package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.ConnectTarget
import dev.eltermino.core.ssh.ConnectionState
import dev.eltermino.core.ssh.ExecResult
import dev.eltermino.core.ssh.PortForward
import dev.eltermino.core.ssh.PtyConfig
import dev.eltermino.core.ssh.ShellChannel
import dev.eltermino.core.ssh.SftpClient
import dev.eltermino.core.ssh.SshConnection
import dev.eltermino.core.ssh.SshException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

internal class MinaSshConnection(
    override val target: ConnectTarget,
    private val session: ClientSession,
    /** Jump host sessions, outermost first; they live exactly as long as [session]. */
    private val hops: List<ClientSession>,
    private val config: MinaConfig,
) : SshConnection {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Owns the shell readers; cancelled when the session goes away. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastError: Throwable? = null

    init {
        session.addSessionListener(object : SessionListener {
            override fun sessionException(session: Session, t: Throwable) {
                lastError = t
            }

            override fun sessionClosed(session: Session) = onClosed()
        })
        // A hop dropping takes the tunnel with it, so it counts as a close too.
        hops.forEach { hop ->
            hop.addSessionListener(object : SessionListener {
                override fun sessionClosed(session: Session) {
                    this@MinaSshConnection.session.close(true)
                }
            })
        }
        if (!session.isOpen) onClosed()
    }

    private fun onClosed() {
        if (_state.value is ConnectionState.Closed) return
        _state.value = ConnectionState.Closed(lastError)
        hops.asReversed().forEach { it.close(true) }
        scope.cancel()
    }

    private fun checkOpen() {
        if (_state.value is ConnectionState.Closed) throw SshException.ChannelFailed("Connection to ${target.host} is closed")
    }

    override suspend fun openShell(pty: PtyConfig): ShellChannel {
        checkOpen()
        val channel = withContext(Dispatchers.IO) {
            session.createShellChannel().apply {
                ptyType = pty.term
                ptyColumns = pty.cols
                ptyLines = pty.rows
                setRedirectErrorStream(true)
                isAgentForwarding = target.agentForwarding
            }
        }
        val opened = try {
            channel.open().awaitDone(config.channelOpenTimeout)
        } catch (e: TimeoutCancellationException) {
            channel.close(true)
            throw SshException.ChannelFailed("Opening a shell on ${target.host} timed out", e)
        }
        if (!opened.isOpened) {
            channel.close(true)
            throw SshException.ChannelFailed("The server refused a shell on ${target.host}", opened.exception)
        }
        return MinaShellChannel(channel, scope)
    }

    override suspend fun openSftp(): SftpClient {
        checkOpen()
        return try {
            withContext(Dispatchers.IO) { MinaSftpClient(SftpClientFactory.instance().createSftpClient(session)) }
        } catch (e: Exception) {
            throw SshException.ChannelFailed("Couldn't start SFTP on ${target.host}", e)
        }
    }

    override suspend fun exec(command: String): ExecResult {
        checkOpen()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val channel = withContext(Dispatchers.IO) {
            session.createExecChannel(command).apply {
                out = stdout
                err = stderr
            }
        }
        try {
            val opened = channel.open().awaitDone(config.channelOpenTimeout)
            if (!opened.isOpened) throw SshException.ChannelFailed("The server refused to run a command", opened.exception)
            channel.awaitClosed()
            return ExecResult(channel.exitStatus, stdout.toByteArray(), stderr.toByteArray())
        } catch (e: TimeoutCancellationException) {
            throw SshException.ChannelFailed("Running a command on ${target.host} timed out", e)
        } finally {
            channel.close(true)
        }
    }

    override suspend fun forwardLocal(host: String, port: Int, bindPort: Int): PortForward =
        forward("Couldn't open a tunnel to $host:$port on ${target.host}") {
            val bound = session.startLocalPortForwarding(SshdSocketAddress("127.0.0.1", bindPort), SshdSocketAddress(host, port))
            bound.port to { session.stopLocalPortForwarding(bound) }
        }

    override suspend fun forwardRemote(bindPort: Int, host: String, port: Int): PortForward =
        forward("${target.host} refused to listen on port $bindPort") {
            val bound = session.startRemotePortForwarding(SshdSocketAddress("127.0.0.1", bindPort), SshdSocketAddress(host, port))
            bound.port to { session.stopRemotePortForwarding(bound) }
        }

    override suspend fun forwardDynamic(bindPort: Int): PortForward =
        forward("Couldn't start a SOCKS proxy on port $bindPort") {
            val bound = session.startDynamicPortForwarding(SshdSocketAddress("127.0.0.1", bindPort))
            bound.port to { session.stopDynamicPortForwarding(bound) }
        }

    /** Starts a forward with [start], which returns the port it listens on and how to stop it. */
    private suspend fun forward(failure: String, start: () -> Pair<Int, () -> Unit>): PortForward {
        checkOpen()
        val (bound, stop) = try {
            withContext(Dispatchers.IO) { start() }
        } catch (e: Exception) {
            throw SshException.ChannelFailed(failure, e)
        }
        return object : PortForward {
            override val port = bound

            override suspend fun close() {
                withContext(Dispatchers.IO) { runCatching { stop() } }
            }
        }
    }

    override suspend fun close() {
        session.closeAndAwait()
        onClosed()
    }
}

/** Waits for the remote side to close the channel; cancelling closes it from our side. */
private suspend fun ClientChannel.awaitClosed() = suspendCancellableCoroutine { continuation ->
    addCloseFutureListener { continuation.resume(Unit) }
    continuation.invokeOnCancellation { close(true) }
}
