package dev.eltermino.core.ssh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One authenticated SSH transport to a host. Shells, SFTP sessions, exec commands and
 * port forwards are all channels multiplexed over it, so opening another one never
 * authenticates again.
 */
interface SshConnection {
    val target: ConnectTarget
    val state: StateFlow<ConnectionState>

    suspend fun openShell(pty: PtyConfig = PtyConfig()): ShellChannel
    suspend fun openSftp(): SftpClient
    suspend fun exec(command: String): ExecResult

    /**
     * Listens on this device at [bindAddress], on [bindPort] or a free port when 0, and carries each
     * connection to [host]:[port] as the server sees it, so `localhost` there is the server itself.
     */
    suspend fun forwardLocal(host: String, port: Int, bindPort: Int = 0, bindAddress: String = LOOPBACK): PortForward

    /**
     * Asks the server to listen at [bindAddress], which its `GatewayPorts` may overrule, on [bindPort] or a free port when 0, and carries
     * each connection back to [host]:[port] as this device sees it.
     */
    suspend fun forwardRemote(bindPort: Int, host: String, port: Int, bindAddress: String = LOOPBACK): PortForward

    /** A SOCKS proxy on this device at [bindAddress], on [bindPort]: each connection goes out from the server. */
    suspend fun forwardDynamic(bindPort: Int, bindAddress: String = LOOPBACK): PortForward
    suspend fun close()
}

/** A running forward, listening on [port]: on this device, or on the server for a remote one. */
interface PortForward {
    val port: Int
    suspend fun close()
}

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class VerifyingHostKey(val fingerprint: HostKeyFingerprint) : ConnectionState
    data object Authenticating : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
    data class Closed(val cause: Throwable? = null) : ConnectionState
}

data class PtyConfig(
    val term: String = "xterm-256color",
    val cols: Int = 80,
    val rows: Int = 24,
)

const val LOOPBACK = "127.0.0.1"

interface ShellChannel {
    val output: Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
    suspend fun resize(cols: Int, rows: Int)
    suspend fun close()
}

data class ExecResult(
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: ByteArray,
)
