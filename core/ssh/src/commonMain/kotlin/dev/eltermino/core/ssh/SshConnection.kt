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
