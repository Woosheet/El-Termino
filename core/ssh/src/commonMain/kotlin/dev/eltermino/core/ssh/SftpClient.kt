package dev.eltermino.core.ssh

/** One SFTP session. Several can be open on the same [SshConnection] at once. */
interface SftpClient {
    suspend fun list(path: String): List<RemoteFile>

    /** Describes [path] itself: a symlink is reported as a symlink, not as its target. */
    suspend fun stat(path: String): RemoteFile

    /** Like [stat], but null when nothing is at [path]. */
    suspend fun find(path: String): RemoteFile?

    /** The absolute form of [path]; "." is the login directory. */
    suspend fun canonicalPath(path: String): String

    /** Reads from [offset] on; reads are pipelined, so latency doesn't cap the speed. */
    suspend fun openRead(path: String, offset: Long = 0): ByteSource

    /**
     * Writes from [offset] on. Offset 0 starts the file over; a larger one resumes an
     * interrupted upload and keeps what's already there.
     */
    suspend fun openWrite(path: String, offset: Long = 0): ByteSink

    /** With [overwrite], replaces [to]: in one atomic step where the server has `posix-rename@openssh.com`. */
    suspend fun rename(from: String, to: String, overwrite: Boolean = false)
    suspend fun delete(path: String, recursive: Boolean = false)
    suspend fun mkdir(path: String)
    suspend fun chmod(path: String, mode: Int)
    suspend fun setModified(path: String, epochSeconds: Long)
    suspend fun close()
}

data class RemoteFile(
    val path: String,
    val name: String,
    val type: Type,
    val size: Long,
    val permissions: Int,
    val modifiedEpochSeconds: Long,
    val linkTarget: String? = null,
) {
    enum class Type { File, Directory, Symlink, Other }

    val isDirectory: Boolean get() = type == Type.Directory
}

/** A stream of bytes being read; [read] returns -1 at the end. */
interface ByteSource {
    suspend fun read(buffer: ByteArray): Int
    suspend fun close()
}

/** A stream of bytes being written; [close] flushes and waits for the last write. */
interface ByteSink {
    suspend fun write(buffer: ByteArray, length: Int = buffer.size)
    suspend fun close()
}
