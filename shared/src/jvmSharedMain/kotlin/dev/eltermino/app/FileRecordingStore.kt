package dev.eltermino.app

import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Recordings as `.cast` files in [dir], named after the host and the moment they started. */
class FileRecordingStore(private val dir: File) : RecordingStore {
    override fun create(name: String): RecordingWriter {
        dir.mkdirs()
        val safe = name.replace(Regex("""[^\p{L}\p{N}._-]+"""), "-").trim('-').ifEmpty { "sessione" }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))
        var file = File(dir, "$safe $stamp.cast")
        var n = 2
        while (file.exists()) file = File(dir, "$safe $stamp ${n++}.cast")
        return FileWriter(file)
    }

    override fun list(): List<RecordingFile> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".cast") }.orEmpty().mapNotNull { file ->
            runCatching {
                val first = file.bufferedReader().use { it.readLine() }.orEmpty()
                RecordingFile(file.name, file.length(), file.lastModified() / 1000, first, lastLine(file))
            }.getOrNull()
        }.sortedByDescending { it.modifiedEpochSeconds }

    override fun read(id: String): ByteArray? = file(id)?.takeIf { it.isFile }?.readBytes()

    override fun delete(id: String) {
        file(id)?.delete()
    }

    /** Only names inside [dir]: an id never reaches another folder. */
    private fun file(id: String): File? = File(dir, id).takeIf { it.parentFile == dir && !id.contains('/') && !id.contains('\\') }

    /** The last line, read from the end so a long recording isn't read whole. */
    private fun lastLine(file: File): String = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val size = minOf(length, 64 * 1024L).toInt()
        raf.seek(length - size)
        val tail = ByteArray(size).also { raf.readFully(it) }.decodeToString().trimEnd()
        tail.substringAfterLast('\n')
    }

    private class FileWriter(private val file: File) : RecordingWriter {
        private val out = file.bufferedWriter()
        private var flushedAt = System.currentTimeMillis()
        override val id: String = file.name

        override fun append(line: String) {
            out.write(line)
            out.write("\n")
            // Kept on disk at least every second, so a crash loses little.
            if (System.currentTimeMillis() - flushedAt > 1000) {
                out.flush()
                flushedAt = System.currentTimeMillis()
            }
        }

        override fun close() = out.close()
    }
}
