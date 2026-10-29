package dev.eltermino.app

import dev.eltermino.core.ssh.ByteSink
import dev.eltermino.core.ssh.ByteSource
import dev.eltermino.core.ssh.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/** This device's files under [root], with the same paths and entries as a remote host. */
class LocalFileSystem(private val root: File) : FileSystem {
    override suspend fun home(): String = root.absoluteFile.path.slashes()

    override suspend fun list(path: String): List<RemoteFile> = io("list") {
        val dir = File(path)
        val children = dir.listFiles() ?: throw IOException("Impossibile leggere la cartella $path")
        children.mapNotNull { entry(it.toPath()) }.sortedBy { it.name }
    }

    override suspend fun stat(path: String): RemoteFile? = io("stat") { entry(File(path).toPath()) }

    override suspend fun openRead(path: String, offset: Long): ByteSource = io("read") {
        val file = RandomAccessFile(File(path), "r").apply { seek(offset) }
        object : ByteSource {
            override suspend fun read(buffer: ByteArray): Int = io("read") { file.read(buffer) }
            override suspend fun close() = io("read") { file.close() }
        }
    }

    override suspend fun openWrite(path: String, offset: Long): ByteSink = io("write") {
        val file = RandomAccessFile(File(path), "rw").apply {
            // Anything past the resume point is from an older attempt.
            setLength(offset)
            seek(offset)
        }
        object : ByteSink {
            override suspend fun write(buffer: ByteArray, length: Int) = io("write") { file.write(buffer, 0, length) }
            override suspend fun close() = io("write") { file.close() }
        }
    }

    override suspend fun mkdir(path: String) = io("mkdir") {
        Files.createDirectory(File(path).toPath())
        Unit
    }

    override suspend fun delete(path: String, recursive: Boolean) = io("delete") {
        val target = File(path).toPath()
        if (!recursive || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target)
            return@io
        }
        // walkFileTree doesn't follow symlinks unless asked, so a link is removed, not emptied.
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    override suspend fun rename(from: String, to: String, overwrite: Boolean) = io("rename") {
        val options = if (overwrite) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
        Files.move(File(from).toPath(), File(to).toPath(), *options)
        Unit
    }

    override suspend fun chmod(path: String, mode: Int) = io("chmod") {
        // Windows has no POSIX permissions; there's nothing to set.
        val view = Files.getFileAttributeView(File(path).toPath(), PosixFileAttributeView::class.java) ?: return@io
        view.setPermissions(PERMISSION_BITS.filter { (bit, _) -> mode and bit != 0 }.map { it.second }.toSet())
    }

    override suspend fun setModified(path: String, epochSeconds: Long) = io("touch") {
        File(path).setLastModified(epochSeconds * 1000)
        Unit
    }

    private fun entry(path: Path): RemoteFile? {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return null
        }
        val type = when {
            attributes.isSymbolicLink -> RemoteFile.Type.Symlink
            attributes.isDirectory -> RemoteFile.Type.Directory
            attributes.isRegularFile -> RemoteFile.Type.File
            else -> RemoteFile.Type.Other
        }
        val permissions = runCatching {
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).sumOf { permission -> PERMISSION_BITS.first { it.second == permission }.first }
        }.getOrDefault(0)
        return RemoteFile(
            path = path.toAbsolutePath().toString().slashes(),
            name = path.fileName?.toString() ?: path.toString(),
            type = type,
            size = attributes.size(),
            permissions = permissions,
            modifiedEpochSeconds = attributes.lastModifiedTime().toMillis() / 1000,
            linkTarget = if (type == RemoteFile.Type.Symlink) runCatching { Files.readSymbolicLink(path).toString() }.getOrNull() else null,
        )
    }

    private suspend fun <T> io(what: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            throw IOException("Operazione locale non riuscita ($what): ${e.message}", e)
        }
    }

    private companion object {
        val PERMISSION_BITS = listOf(
            0b100_000_000 to PosixFilePermission.OWNER_READ,
            0b010_000_000 to PosixFilePermission.OWNER_WRITE,
            0b001_000_000 to PosixFilePermission.OWNER_EXECUTE,
            0b000_100_000 to PosixFilePermission.GROUP_READ,
            0b000_010_000 to PosixFilePermission.GROUP_WRITE,
            0b000_001_000 to PosixFilePermission.GROUP_EXECUTE,
            0b000_000_100 to PosixFilePermission.OTHERS_READ,
            0b000_000_010 to PosixFilePermission.OTHERS_WRITE,
            0b000_000_001 to PosixFilePermission.OTHERS_EXECUTE,
        )

        fun String.slashes() = replace('\\', '/')
    }
}
