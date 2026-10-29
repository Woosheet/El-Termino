package dev.eltermino.core.importer.termius

import java.io.File
import java.io.IOException

/**
 * Chromium's IndexedDB on top of LevelDB: object stores by name, each with its records
 * decoded from V8's serialization into plain maps, lists, strings and numbers.
 */
internal object IndexedDb {
    /** Records of every object store, by store name. Several databases are merged. */
    fun read(dir: File): Map<String, List<Map<String, Any?>>> {
        val entries = LevelDb.read(dir)
        val names = HashMap<Pair<Long, Long>, String>()
        val values = mutableListOf<Triple<Long, Long, ByteArray>>()
        for (entry in entries) {
            val key = Bytes(entry.key)
            val prefix = runCatching { prefix(key) }.getOrNull() ?: continue
            val (database, store, index) = prefix
            when {
                database > 0 && store == 0L && index == 0L && key.peek() == OBJECT_STORE_METADATA -> {
                    key.byte()
                    val storeId = runCatching { key.varint() }.getOrNull() ?: continue
                    if (key.remaining > 0 && key.byte() == 0) names[database to storeId] = utf16be(entry.value)
                }
                database > 0 && store > 0 && index == OBJECT_STORE_DATA -> values += Triple(database, store, entry.value)
            }
        }
        val stores = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for ((database, store, raw) in values) {
            val name = names[database to store] ?: "store-$store"
            @Suppress("UNCHECKED_CAST")
            val record = runCatching { decodeValue(raw) }.getOrNull() as? Map<String, Any?> ?: continue
            stores.getOrPut(name) { mutableListOf() } += record
        }
        return stores
    }

    private const val OBJECT_STORE_METADATA = 50
    private const val OBJECT_STORE_DATA = 1L

    /** (database id, object store id, index id), packed with their byte widths in the first byte. */
    private fun prefix(key: Bytes): Triple<Long, Long, Long> {
        val first = key.byte()
        val databaseBytes = ((first ushr 5) and 7) + 1
        val storeBytes = ((first ushr 2) and 7) + 1
        val indexBytes = (first and 3) + 1
        return Triple(key.int(databaseBytes), key.int(storeBytes), key.int(indexBytes))
    }

    private fun utf16be(bytes: ByteArray): String = String(bytes, Charsets.UTF_16BE)

    /** A stored value: a version varint, Blink's envelope, then V8's serialization. */
    fun decodeValue(raw: ByteArray): Any? {
        val input = Bytes(raw)
        input.varint()
        // Blink and V8 each start with 0xFF and a version; newer Blink adds a trailer offset.
        while (input.peek() == 0xFF || input.peek() == 0x00 || input.peek() == 0xFE) {
            when (input.byte()) {
                0xFF -> input.varint()
                0xFE -> input.take(12)
            }
        }
        return V8Reader(input).value()
    }
}

/**
 * The subset of V8's ValueSerializer that apps store in IndexedDB: objects, arrays,
 * maps, sets, strings, numbers, booleans, dates and references back to earlier objects.
 */
internal class V8Reader(private val input: Bytes) {
    private val objects = mutableListOf<Any?>()

    fun value(): Any? {
        val tag = input.byte()
        return when (tag.toChar()) {
            '\u0000' -> value()
            '_', '0', '-' -> null
            'T' -> true
            'F' -> false
            'I' -> input.varint().let { (it ushr 1) xor -(it and 1) }
            'U' -> input.varint()
            'N' -> java.lang.Double.longBitsToDouble(input.fixed64())
            'Z' -> {
                val bitfield = input.varint()
                input.take((bitfield ushr 1).toInt())
                null
            }
            '"' -> String(input.take(input.varint().toInt()), Charsets.ISO_8859_1)
            'c' -> String(input.take(input.varint().toInt()), Charsets.UTF_16LE)
            'S' -> String(input.take(input.varint().toInt()), Charsets.UTF_8)
            'D' -> remember(java.lang.Double.longBitsToDouble(input.fixed64()))
            'y' -> remember(true)
            'x' -> remember(false)
            'n' -> remember(java.lang.Double.longBitsToDouble(input.fixed64()))
            's' -> remember(value())
            '^' -> objects.getOrNull(input.varint().toInt())
            'o' -> {
                val map = LinkedHashMap<String, Any?>()
                remember(map)
                properties(map, end = '{')
                input.varint()
                map
            }
            'A' -> {
                val length = input.varint().toInt()
                val list = ArrayList<Any?>(minOf(length, 4096))
                remember(list)
                repeat(length) { list += value() }
                properties(LinkedHashMap(), end = '$')
                input.varint()
                input.varint()
                list
            }
            'a' -> {
                input.varint()
                val sparse = LinkedHashMap<String, Any?>()
                remember(sparse)
                properties(sparse, end = '@')
                input.varint()
                input.varint()
                sparse.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.map { it.value }
            }
            ';' -> {
                val map = LinkedHashMap<String, Any?>()
                remember(map)
                while (input.peek() != ':'.code) map[value().toString()] = value()
                input.byte()
                input.varint()
                map
            }
            '\'' -> {
                val set = ArrayList<Any?>()
                remember(set)
                while (input.peek() != ','.code) set += value()
                input.byte()
                input.varint()
                set
            }
            'R' -> {
                val pattern = value()
                input.varint()
                remember(pattern)
            }
            'B' -> remember(input.take(input.varint().toInt()))
            else -> throw IOException("unsupported V8 tag 0x${tag.toString(16)}")
        }
    }

    private fun properties(into: MutableMap<String, Any?>, end: Char) {
        while (input.peek() != end.code) {
            if (input.peek() == 0) {
                input.byte()
                continue
            }
            val key = value()
            into[(if (key is Double && key % 1.0 == 0.0) key.toLong() else key).toString()] = value()
        }
        input.byte()
    }

    private fun <T> remember(value: T): T {
        objects += value
        return value
    }
}
