package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.ssh.ByteSink
import dev.eltermino.core.ssh.ByteSource
import dev.eltermino.core.ssh.RemoteFile
import dev.eltermino.core.ssh.SftpClient
import dev.eltermino.core.ssh.SshException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A place files live: a host over SFTP, or this device. Paths are absolute and use '/',
 * also on Windows ("C:/Users/…"). Entries are [RemoteFile]s on both sides.
 */
interface FileSystem {
    /** Where browsing starts: the login directory, or the user's home here. */
    suspend fun home(): String
    suspend fun list(path: String): List<RemoteFile>

    /** Null when nothing is at [path]. A symlink is described as itself. */
    suspend fun stat(path: String): RemoteFile?
    suspend fun openRead(path: String, offset: Long = 0): ByteSource

    /** Offset 0 starts the file over; a larger one resumes and keeps what's there. */
    suspend fun openWrite(path: String, offset: Long = 0): ByteSink
    suspend fun mkdir(path: String)
    suspend fun delete(path: String, recursive: Boolean)
    suspend fun rename(from: String, to: String, overwrite: Boolean = false)
    suspend fun chmod(path: String, mode: Int)
    suspend fun setModified(path: String, epochSeconds: Long)
}

/** Which [FileSystem] a path belongs to. */
sealed interface Endpoint {
    data object Local : Endpoint
    data class Remote(val hostId: String) : Endpoint
}

data class Location(val endpoint: Endpoint, val path: String)

fun childPath(dir: String, name: String): String = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

/** "/a/b" → "/a", "/a" → "/", "C:/a" → "C:/"; a root is its own parent. */
fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    return when {
        trimmed.isEmpty() -> "/"
        cut < 0 -> path
        cut == 0 -> "/"
        trimmed.substring(0, cut).endsWith(":") -> trimmed.substring(0, cut + 1)
        else -> trimmed.substring(0, cut)
    }
}

fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/**
 * SFTP on a host's shared link. The SFTP session is opened on first use and again after
 * a reconnect; calls made while the link is reconnecting wait for it. [release] lets go.
 */
class RemoteFileSystem internal constructor(val host: Host, private val lease: LinkLease) : FileSystem {
    private val mutex = Mutex()
    private var client: SftpClient? = null
    private var generation = -1

    val link: HostLink get() = lease.link

    private suspend fun sftp(): SftpClient {
        val state = lease.link.state.first { it is LinkState.Up || it is LinkState.Down }
        if (state is LinkState.Down) throw SshException.ChannelFailed(state.message)
        state as LinkState.Up
        return mutex.withLock {
            client?.takeIf { generation == state.generation } ?: run {
                client?.let { runCatching { it.close() } }
                state.connection.openSftp().also {
                    client = it
                    generation = state.generation
                }
            }
        }
    }

    override suspend fun home() = sftp().canonicalPath(".")
    override suspend fun list(path: String) = sftp().list(path)
    override suspend fun stat(path: String) = sftp().find(path)
    override suspend fun openRead(path: String, offset: Long) = sftp().openRead(path, offset)
    override suspend fun openWrite(path: String, offset: Long) = sftp().openWrite(path, offset)
    override suspend fun mkdir(path: String) = sftp().mkdir(path)
    override suspend fun delete(path: String, recursive: Boolean) = sftp().delete(path, recursive)
    override suspend fun rename(from: String, to: String, overwrite: Boolean) = sftp().rename(from, to, overwrite)
    override suspend fun chmod(path: String, mode: Int) = sftp().chmod(path, mode)
    override suspend fun setModified(path: String, epochSeconds: Long) = sftp().setModified(path, epochSeconds)

    suspend fun release() = withContext(NonCancellable) {
        mutex.withLock { client?.let { runCatching { it.close() } }; client = null }
        lease.release()
    }
}

/** Opens SFTP on [host] over its shared link; the login happens on first use if needed. */
suspend fun Connections.files(host: Host): RemoteFileSystem = RemoteFileSystem(host, acquire(host))

/** "940 B", "4,2 KB", "120 MB", in the Italian style the rest of the app uses. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = if (value >= 100) value.toLong().toString() else (kotlin.math.round(value * 10) / 10).toString().removeSuffix(".0")
    return "${rounded.replace('.', ',')} ${units[unit]}"
}

/** "rwxr-xr-x" from 0755. */
fun formatPermissions(mode: Int): String = buildString {
    for (shift in listOf(6, 3, 0)) {
        val bits = (mode shr shift) and 7
        append(if (bits and 4 != 0) 'r' else '-')
        append(if (bits and 2 != 0) 'w' else '-')
        append(if (bits and 1 != 0) 'x' else '-')
    }
}
