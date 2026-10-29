package dev.eltermino.core.importer.termius

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Reads a LevelDB directory without opening it as a database: every table (`.ldb`,
 * `.sst`) and write-ahead log (`.log`) is parsed directly, and for each key the newest
 * write wins. Nothing is written, so the owning app can keep running.
 */
internal object LevelDb {
    class Entry(val key: ByteArray, val value: ByteArray)

    fun read(dir: File): List<Entry> {
        val latest = HashMap<KeyBytes, Pair<Long, ByteArray?>>()
        fun record(key: ByteArray, sequence: Long, value: ByteArray?) {
            val k = KeyBytes(key)
            val current = latest[k]
            if (current == null || sequence >= current.first) latest[k] = sequence to value
        }
        val files = dir.listFiles().orEmpty()
        files.filter { it.name.endsWith(".ldb") || it.name.endsWith(".sst") }.forEach { table ->
            runCatching { readTable(table, ::record) }
        }
        files.filter { it.name.endsWith(".log") }.forEach { log ->
            runCatching { readLog(log.readBytes(), ::record) }
        }
        return latest.mapNotNull { (key, entry) -> entry.second?.let { Entry(key.bytes, it) } }
    }

    private class KeyBytes(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is KeyBytes && bytes.contentEquals(other.bytes)
    }

    // Tables: footer → index block → data blocks; keys are internal keys (user key + 8-byte tag).

    private const val MAGIC = -0x24b88adb747f04a9L // 0xdb4775248b80fb57

    private fun readTable(file: File, record: (ByteArray, Long, ByteArray?) -> Unit) {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 48) return
            val footer = ByteArray(48).also { raf.seek(length - 48); raf.readFully(it) }
            if (Bytes(footer).apply { position = 40 }.fixed64() != MAGIC) throw IOException("not a LevelDB table")
            val handles = Bytes(footer)
            handles.varint() // metaindex offset
            handles.varint() // metaindex size
            val indexOffset = handles.varint()
            val indexSize = handles.varint()
            val index = block(raf, indexOffset, indexSize)
            entries(index) { _, value ->
                val handle = Bytes(value)
                val data = block(raf, handle.varint(), handle.varint())
                entries(data) { internalKey, v ->
                    if (internalKey.size < 8) return@entries
                    val tag = Bytes(internalKey).apply { position = internalKey.size - 8 }.fixed64()
                    val userKey = internalKey.copyOf(internalKey.size - 8)
                    val deleted = (tag and 0xFF) == 0L
                    record(userKey, tag ushr 8, if (deleted) null else v)
                }
            }
        }
    }

    private fun block(raf: RandomAccessFile, offset: Long, size: Long): ByteArray {
        val raw = ByteArray(size.toInt() + 5)
        raf.seek(offset)
        raf.readFully(raw)
        val contents = raw.copyOf(size.toInt())
        return when (raw[size.toInt()].toInt()) {
            0 -> contents
            1 -> Snappy.decompress(contents)
            else -> throw IOException("unknown block compression ${raw[size.toInt()]}")
        }
    }

    /** Walks a block's prefix-compressed entries. */
    private fun entries(block: ByteArray, visit: (ByteArray, ByteArray) -> Unit) {
        if (block.size < 4) return
        val restarts = Bytes(block).apply { position = block.size - 4 }.fixed32()
        val end = block.size - 4 * (restarts + 1)
        val input = Bytes(block)
        var key = ByteArray(0)
        while (input.position < end) {
            val shared = input.varint().toInt()
            val nonShared = input.varint().toInt()
            val valueLength = input.varint().toInt()
            key = key.copyOf(shared) + input.take(nonShared)
            visit(key, input.take(valueLength))
        }
    }

    // Logs: 32 KiB blocks of fragments that add up to write batches.

    private fun readLog(data: ByteArray, record: (ByteArray, Long, ByteArray?) -> Unit) {
        val block = 32 * 1024
        var position = 0
        var pending = java.io.ByteArrayOutputStream()
        while (position + 7 <= data.size) {
            val left = block - position % block
            if (left < 7) {
                position += left
                continue
            }
            val length = (data[position + 4].toInt() and 0xFF) or ((data[position + 5].toInt() and 0xFF) shl 8)
            val type = data[position + 6].toInt()
            val start = position + 7
            if (type == 0 || start + length > data.size) {
                position += left
                continue
            }
            val fragment = data.copyOfRange(start, start + length)
            position = start + length
            when (type) {
                1 -> batch(fragment, record)
                2 -> pending = java.io.ByteArrayOutputStream().apply { write(fragment) }
                3 -> pending.write(fragment)
                4 -> {
                    pending.write(fragment)
                    batch(pending.toByteArray(), record)
                    pending = java.io.ByteArrayOutputStream()
                }
            }
        }
    }

    private fun batch(data: ByteArray, record: (ByteArray, Long, ByteArray?) -> Unit) {
        if (data.size < 12) return
        val input = Bytes(data)
        var sequence = input.fixed64()
        val count = input.fixed32()
        repeat(count) {
            if (input.remaining <= 0) return
            when (input.byte()) {
                1 -> {
                    val key = input.take(input.varint().toInt())
                    val value = input.take(input.varint().toInt())
                    record(key, sequence, value)
                }
                0 -> record(input.take(input.varint().toInt()), sequence, null)
                else -> return
            }
            sequence++
        }
    }
}

/** A cursor over little-endian LevelDB and Chromium encodings. */
internal class Bytes(private val data: ByteArray, start: Int = 0) {
    var position = start
    val remaining: Int get() = data.size - position

    fun byte(): Int {
        if (position >= data.size) throw IOException("unexpected end of data")
        return data[position++].toInt() and 0xFF
    }

    fun peek(): Int = if (position < data.size) data[position].toInt() and 0xFF else -1

    fun take(n: Int): ByteArray {
        if (n < 0 || position + n > data.size) throw IOException("unexpected end of data")
        return data.copyOfRange(position, position + n).also { position += n }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = byte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IOException("varint too long")
    }

    fun fixed32(): Int = (0 until 4).fold(0) { acc, i -> acc or (byte() shl (8 * i)) }

    fun fixed64(): Long = (0 until 8).fold(0L) { acc, i -> acc or (byte().toLong() shl (8 * i)) }

    /** Little-endian integer of [size] bytes, as Chromium's key prefixes use. */
    fun int(size: Int): Long = (0 until size).fold(0L) { acc, i -> acc or (byte().toLong() shl (8 * i)) }
}

/** Snappy block decompression, enough for LevelDB tables. */
internal object Snappy {
    fun decompress(input: ByteArray): ByteArray {
        val source = Bytes(input)
        val out = ByteArray(source.varint().toInt())
        var written = 0
        while (source.remaining > 0) {
            val tag = source.byte()
            when (tag and 3) {
                0 -> {
                    var length = tag ushr 2
                    if (length >= 60) {
                        val bytes = length - 59
                        length = source.int(bytes).toInt()
                    }
                    length += 1
                    source.take(length).copyInto(out, written)
                    written += length
                }
                else -> {
                    val (length, offset) = when (tag and 3) {
                        1 -> (((tag ushr 2) and 7) + 4) to (((tag ushr 5) shl 8) or source.byte())
                        2 -> ((tag ushr 2) + 1) to source.int(2).toInt()
                        else -> ((tag ushr 2) + 1) to source.int(4).toInt()
                    }
                    if (offset <= 0 || offset > written) throw IOException("bad snappy offset")
                    // Copies may overlap themselves, so byte by byte.
                    repeat(length) { out[written] = out[written - offset]; written++ }
                }
            }
        }
        return out
    }
}
