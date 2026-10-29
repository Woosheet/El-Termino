package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.RemoteFile
import dev.eltermino.core.ssh.SftpClient
import dev.eltermino.core.ssh.SshException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient.Attributes
import java.io.IOException
import org.apache.sshd.sftp.client.SftpClient as MinaSftp

/**
 * [stat] and [list] describe the path itself (lstat): a symlink is reported as
 * [RemoteFile.Type.Symlink] with its [RemoteFile.linkTarget].
 */
internal class MinaSftpClient(private val sftp: MinaSftp) : SftpClient {
    override suspend fun list(path: String): List<RemoteFile> = io("list $path") {
        sftp.readDir(path)
            .filter { it.filename != "." && it.filename != ".." }
            .map { entry -> entry.attributes.toRemoteFile(childPath(path, entry.filename), entry.filename) }
            .sortedBy { it.name }
    }

    override suspend fun stat(path: String): RemoteFile = io("stat $path") {
        sftp.lstat(path).toRemoteFile(path, path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" })
    }

    override suspend fun rename(from: String, to: String) = io("rename $from") { sftp.rename(from, to) }

    override suspend fun delete(path: String, recursive: Boolean) = io("delete $path") { deleteBlocking(path, recursive) }

    override suspend fun mkdir(path: String) = io("mkdir $path") { sftp.mkdir(path) }

    override suspend fun chmod(path: String, mode: Int) = io("chmod $path") {
        sftp.setStat(path, Attributes().perms(mode and 0x0FFF))
    }

    override suspend fun close() = withContext(Dispatchers.IO) { sftp.close() }

    private fun deleteBlocking(path: String, recursive: Boolean) {
        val attributes = sftp.lstat(path)
        if (!attributes.isDirectory) {
            sftp.remove(path)
            return
        }
        if (recursive) {
            // Symlinks are removed, never followed.
            sftp.readDir(path)
                .filter { it.filename != "." && it.filename != ".." }
                .forEach { deleteBlocking(childPath(path, it.filename), recursive = true) }
        }
        sftp.rmdir(path)
    }

    private fun Attributes.toRemoteFile(path: String, name: String): RemoteFile {
        val type = when {
            isSymbolicLink -> RemoteFile.Type.Symlink
            isDirectory -> RemoteFile.Type.Directory
            isRegularFile -> RemoteFile.Type.File
            else -> RemoteFile.Type.Other
        }
        return RemoteFile(
            path = path,
            name = name,
            type = type,
            size = size,
            permissions = permissions and 0x0FFF,
            modifiedEpochSeconds = modifyTime?.toMillis()?.div(1000) ?: 0,
            linkTarget = if (type == RemoteFile.Type.Symlink) runCatching { sftp.readLink(path) }.getOrNull() else null,
        )
    }

    private suspend fun <T> io(what: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            throw SshException.ChannelFailed("SFTP $what failed: ${e.message}", e)
        }
    }
}

private fun childPath(dir: String, name: String) = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
