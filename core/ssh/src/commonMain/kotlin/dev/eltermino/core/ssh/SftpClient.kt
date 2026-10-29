package dev.eltermino.core.ssh

/** One SFTP session. Several can be open on the same [SshConnection] at once. */
interface SftpClient {
    suspend fun list(path: String): List<RemoteFile>
    suspend fun stat(path: String): RemoteFile
    suspend fun rename(from: String, to: String)
    suspend fun delete(path: String, recursive: Boolean = false)
    suspend fun mkdir(path: String)
    suspend fun chmod(path: String, mode: Int)
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
}
