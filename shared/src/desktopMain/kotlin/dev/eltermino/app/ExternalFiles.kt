package dev.eltermino.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/** Names come from the server: one must never point outside the folder it lands in. */
private fun requireLocalName(name: String) =
    require(name !in setOf("", ".", "..") && '/' !in name && '\\' !in name) { "Nome di file non valido: $name" }

/**
 * A copy of a remote file in a folder only this user can read.
 *
 * Saves are found by polling size and date: editors that save by renaming a new file over
 * the old one would lose a watch on the file itself.
 */
class DesktopExternalFile(name: String, bytes: ByteArray, private val pollMillis: Long = 1_000) : ExternalFile {
    private val folder: File
    private val file: File

    init {
        requireLocalName(name)
        folder = Files.createTempDirectory("eltermino-edit").toFile().apply { deleteOnExit() }
        file = File(folder, name).apply { deleteOnExit() }
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        file.writeBytes(bytes)
    }

    override val path: String get() = file.path

    /** The file key changes when a save renames a new file over this one, even within the same clock tick. */
    private fun stamp() = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).let {
        Triple(it.lastModifiedTime(), it.size(), it.fileKey())
    }

    private var uploaded = stamp()

    @Volatile
    private var finishing = false

    override fun finish() {
        finishing = true
    }

    override suspend fun awaitChange(): ByteArray? = withContext(Dispatchers.IO) { nextSave() }

    private suspend fun nextSave(): ByteArray? {
        var last = uploaded
        var missing = 0
        while (true) {
            val lastLook = finishing
            // Null too when the file goes between the check and the read.
            val now = if (file.exists()) runCatching { stamp() }.getOrNull() else null
            if (now != null) {
                missing = 0
                // Unchanged since the previous look: the editor is done writing.
                if (now != uploaded && (now == last || lastLook)) {
                    uploaded = now
                    return file.readBytes()
                }
                last = now
            } else if (++missing >= MISSING_POLLS) {
                return null
            }
            if (lastLook) return null
            delay(pollMillis)
        }
    }

    override fun close() {
        file.delete()
        folder.delete()
    }

    private companion object {
        /** Some editors delete the file before writing the new one, so one absence means nothing. */
        const val MISSING_POLLS = 5
    }
}

/** Copies [paths] into [folder] under their own names. */
internal suspend fun downloadForDrag(fs: FileSystem, paths: List<String>, folder: File): List<File> = withContext(Dispatchers.IO) {
    paths.map { path ->
        val name = fileName(path)
        requireLocalName(name)
        val target = File(folder, name)
        val source = fs.openRead(path)
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
            }
        } finally {
            source.close()
        }
        target
    }
}

/** What a drag out of the app carries: the download happens when the system asks for the files, on drop. */
class FilesForDrag(
    private val fs: suspend () -> FileSystem,
    private val paths: List<String>,
    private val timeoutMillis: Long = 5 * 60_000,
    private val onFailure: (String) -> Unit = {},
) : Transferable {
    private val files: List<File> by lazy {
        // shortcut: blocks the window until the download ends, since AWT wants the files back from this call; promised files would need native code on each system.
        runBlocking { withTimeout(timeoutMillis) { downloadForDrag(fs(), paths, dragFolder()) } }
    }

    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return try {
            files
        } catch (e: TimeoutCancellationException) {
            onFailure("Il trascinamento ha impiegato troppo: usa \"Copia in…\"")
            throw IOException("Drag download timed out", e)
        } catch (e: Exception) {
            onFailure("Trascinamento non riuscito: ${e.message}")
            throw IOException("Drag download failed", e)
        }
    }

    private companion object {
        private var folder: File? = null

        /** The system may still be reading a finished drag's files, so they go at the next drag or on exit. */
        @Synchronized
        fun dragFolder(): File {
            if (folder == null) Runtime.getRuntime().addShutdownHook(Thread { folder?.deleteRecursively() })
            folder?.deleteRecursively()
            return Files.createTempDirectory("eltermino-drag").toFile().also { folder = it }
        }
    }
}
