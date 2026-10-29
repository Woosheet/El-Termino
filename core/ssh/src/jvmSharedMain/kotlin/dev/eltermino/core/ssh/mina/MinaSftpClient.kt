package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.ByteSink
import dev.eltermino.core.ssh.ByteSource
import dev.eltermino.core.ssh.RemoteFile
import dev.eltermino.core.ssh.SftpClient
import dev.eltermino.core.ssh.SshException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient.Attributes
import org.apache.sshd.sftp.client.SftpClient.OpenMode
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension as PosixRenameExtension
import org.apache.sshd.sftp.client.impl.SftpOutputStreamAsync
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
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

    override suspend fun find(path: String): RemoteFile? = io("stat $path") {
        // Decided here: once the error crosses withContext, coroutines may wrap it again.
        val attributes = try {
            sftp.lstat(path)
        } catch (e: SftpException) {
            if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE) null else throw e
        }
        attributes?.toRemoteFile(path, path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" })
    }

    override suspend fun canonicalPath(path: String): String = io("realpath $path") { sftp.canonicalPath(path) }

    override suspend fun openRead(path: String, offset: Long): ByteSource = io("read $path") {
        val input = sftp.read(path, BUFFER_SIZE)
        // Without reads in flight, skip only moves the offset: nothing is downloaded.
        if (offset > 0) input.skip(offset)
        StreamSource(input, path)
    }

    override suspend fun openWrite(path: String, offset: Long): ByteSink = io("write $path") {
        val modes = if (offset > 0) listOf(OpenMode.Write, OpenMode.Create) else listOf(OpenMode.Write, OpenMode.Create, OpenMode.Truncate)
        val output = sftp.write(path, BUFFER_SIZE, modes)
        if (offset > 0) {
            (output as? SftpOutputStreamAsync)?.setOffset(offset) ?: run {
                output.close()
                throw IOException("resuming needs an offset-capable stream")
            }
        }
        StreamSink(output, path)
    }

    override suspend fun rename(from: String, to: String, overwrite: Boolean) = io("rename $from") {
        // SFTP v3, which OpenSSH speaks, refuses to rename over a file; its extension doesn't.
        val posix = sftp.getExtension(PosixRenameExtension::class.java)
        when {
            !overwrite -> sftp.rename(from, to)
            posix.isSupported -> posix.posixRename(from, to)
            else -> {
                runCatching { sftp.remove(to) }
                sftp.rename(from, to)
            }
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = io("delete $path") { deleteBlocking(path, recursive) }

    override suspend fun mkdir(path: String) = io("mkdir $path") { sftp.mkdir(path) }

    override suspend fun chmod(path: String, mode: Int) = io("chmod $path") {
        sftp.setStat(path, Attributes().perms(mode and 0x0FFF))
    }

    override suspend fun setModified(path: String, epochSeconds: Long) = io("touch $path") {
        val time = FileTime.from(epochSeconds, TimeUnit.SECONDS)
        sftp.setStat(path, Attributes().accessTime(time).modifyTime(time))
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

    private class StreamSource(private val input: InputStream, private val path: String) : ByteSource {
        override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
            try {
                input.read(buffer)
            } catch (e: IOException) {
                throw SshException.ChannelFailed("SFTP read $path failed: ${e.message}", e)
            }
        }

        override suspend fun close() = withContext(Dispatchers.IO) { runCatching { input.close() }.let { } }
    }

    private class StreamSink(private val output: OutputStream, private val path: String) : ByteSink {
        override suspend fun write(buffer: ByteArray, length: Int) = withContext(Dispatchers.IO) {
            try {
                output.write(buffer, 0, length)
            } catch (e: IOException) {
                throw SshException.ChannelFailed("SFTP write $path failed: ${e.message}", e)
            }
        }

        override suspend fun close() = withContext(Dispatchers.IO) {
            try {
                output.close()
            } catch (e: IOException) {
                throw SshException.ChannelFailed("SFTP write $path failed: ${e.message}", e)
            }
        }
    }

    private companion object {
        /** Per request; MINA keeps several in flight. */
        const val BUFFER_SIZE = 64 * 1024
    }
}

private fun childPath(dir: String, name: String) = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
