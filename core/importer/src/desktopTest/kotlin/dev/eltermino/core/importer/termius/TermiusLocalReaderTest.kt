package dev.eltermino.core.importer.termius

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Builds a Termius-like data folder by hand (LevelDB log and table, IndexedDB keys, V8
 * values, sealed fields) and reads it back. Real Termius data isn't in the repository.
 */
class TermiusLocalReaderTest {
    private val key = Random.nextBytes(32)
    private fun seal(text: String) = SecretBox.seal(text, key, Random.nextBytes(24))

    // V8 serialization of plain objects, as Electron stores them.
    private class V8 {
        val out = ByteArrayOutputStream()
        fun varint(n: Long) {
            var v = n
            while (true) {
                if (v and 0x7F.inv().toLong() == 0L) { out.write(v.toInt()); return }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
        fun value(v: Any?) {
            when (v) {
                null -> out.write('0'.code)
                is Boolean -> out.write(if (v) 'T'.code else 'F'.code)
                is Int -> { out.write('I'.code); varint(((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL) }
                is Long -> { out.write('N'.code); val bits = java.lang.Double.doubleToLongBits(v.toDouble()); repeat(8) { out.write(((bits ushr (8 * it)) and 0xFF).toInt()) } }
                is String -> { val bytes = v.toByteArray(Charsets.ISO_8859_1); out.write('"'.code); varint(bytes.size.toLong()); out.write(bytes) }
                is Map<*, *> -> {
                    out.write('o'.code)
                    v.forEach { (k, value) -> value(k as String); value(value) }
                    out.write('{'.code); varint(v.size.toLong())
                }
                else -> error("unsupported $v")
            }
        }
    }

    private fun record(map: Map<String, Any?>): ByteArray {
        val v8 = V8()
        v8.out.write(byteArrayOf(0x01, 0xFF.toByte(), 0x11, 0xFF.toByte(), 0x0F))
        v8.value(map)
        return v8.out.toByteArray()
    }

    private fun dataKey(store: Int, id: Int) = byteArrayOf(0, 1, store.toByte(), 1, 3) + id.toByte()
    private fun storeName(store: Int, name: String) = byteArrayOf(0, 1, 0, 0, 50, store.toByte(), 0) to name.toByteArray(Charsets.UTF_16BE)

    /** One full-record write batch per call, in a LevelDB log. */
    private class Log {
        val out = ByteArrayOutputStream()
        var sequence = 1L
        fun batch(entries: List<Pair<ByteArray, ByteArray>>) {
            val body = ByteArrayOutputStream()
            fun fixed(n: Long, size: Int) = repeat(size) { body.write(((n ushr (8 * it)) and 0xFF).toInt()) }
            fun varint(n: Int) { var v = n; while (v >= 0x80) { body.write((v and 0x7F) or 0x80); v = v ushr 7 }; body.write(v) }
            fixed(sequence, 8)
            fixed(entries.size.toLong(), 4)
            entries.forEach { (k, v) -> body.write(1); varint(k.size); body.write(k); varint(v.size); body.write(v) }
            sequence += entries.size
            val payload = body.toByteArray()
            out.write(byteArrayOf(0, 0, 0, 0, (payload.size and 0xFF).toByte(), (payload.size ushr 8).toByte(), 1))
            out.write(payload)
        }
    }

    private fun termiusFolder(): File {
        val home = Files.createTempDirectory("termius").toFile().apply { deleteOnExit() }
        val dir = File(home, "Library/Application Support/Termius/IndexedDB/file__0.indexeddb.leveldb").apply { mkdirs() }
        val log = Log()
        log.batch(listOf(storeName(1, "hosts"), storeName(2, "groups"), storeName(3, "ssh_configs"), storeName(4, "identities"), storeName(5, "ssh_key_crypts")))
        log.batch(listOf(dataKey(1, 1) to record(mapOf("id" to 1, "label" to seal("vecchio nome"), "address" to "10.0.0.5", "updated_at" to 50L))))
        log.batch(
            listOf(
                dataKey(1, 1) to record(mapOf("id" to 1, "label" to seal("web-01"), "address" to "10.0.0.5", "group" to 2, "ssh_config" to 3, "updated_at" to 100L)),
                dataKey(1, 2) to record(mapOf("id" to 2, "label" to seal(""), "address" to 167772161L, "group" to 2)),
                dataKey(2, 2) to record(mapOf("id" to 2, "label" to "Produzione", "parent_group" to null, "ssh_config" to 6)),
                dataKey(3, 3) to record(mapOf("id" to 3, "port" to 2222, "identity" to 4)),
                dataKey(3, 6) to record(mapOf("id" to 6, "port" to 22, "identity" to 4)),
                dataKey(4, 4) to record(mapOf("id" to 4, "label" to "deploy", "username" to seal("deploy"), "password" to seal("segreta"), "ssh_key" to 5)),
                dataKey(5, 5) to record(mapOf("id" to 5, "label" to seal("laptop"), "private_key" to seal("-----BEGIN OPENSSH PRIVATE KEY-----"), "passphrase" to seal("pp"))),
            ),
        )
        File(dir, "000003.log").writeBytes(log.out.toByteArray())
        return home
    }

    @Test
    fun readsHostsGroupsIdentitiesAndKeys() {
        val home = termiusFolder()
        val data = TermiusLocalReader.dataDirs(home, os = "Mac OS X").single()
        val found = assertIs<TermiusLocal.Found>(TermiusLocalReader.read(data, listOf(Random.nextBytes(32), key)))
        val hosts = found.result.hosts.associateBy { it.address }
        val web = hosts.getValue("10.0.0.5")
        assertEquals("web-01", web.label, "the newest version of the record wins")
        assertEquals(2222, web.port)
        assertEquals("deploy", web.username)
        assertEquals("segreta", web.password)
        assertEquals(listOf("Produzione"), web.groupPath)
        assertEquals("laptop", web.key?.label)
        assertEquals("pp", web.key?.passphrase)
        val packed = hosts.getValue("10.0.0.1")
        assertEquals("10.0.0.1", packed.label, "an empty label falls back to the address")
        assertEquals(22, packed.port, "the group's ssh config fills in")
        assertEquals("deploy", packed.username)
        assertTrue("hosts: 2 record" in found.details)
        assertTrue("segreta" !in found.details, "details never carry values")
    }

    /** As a current Termius stores it: references are {local_id, id}, identities sit in a link table. */
    @Test
    fun readsTheCurrentTermiusLayout() {
        val home = Files.createTempDirectory("termius").toFile().apply { deleteOnExit() }
        val dir = File(home, "Library/Application Support/Termius/IndexedDB/file__0.indexeddb.leveldb").apply { mkdirs() }
        fun ref(local: Int, id: Int) = mapOf("local_id" to local, "id" to id)
        val log = Log()
        log.batch(listOf(storeName(1, "hosts"), storeName(2, "ssh_configs"), storeName(3, "ssh_config_identities"), storeName(4, "ssh_identities"), storeName(5, "keys"), storeName(6, "known_hosts")))
        log.batch(
            listOf(
                // Local and sync ids overlap on purpose: config local 7 is not identity id 7.
                dataKey(1, 1) to record(mapOf("local_id" to 1, "id" to 501, "label" to seal("web-01"), "address" to seal("10.0.0.5"), "group" to null, "ssh_config" to ref(7, 901), "status" to "created", "updated_at" to "2026-10-01T10:00:00Z")),
                dataKey(1, 2) to record(mapOf("local_id" to 2, "id" to 502, "label" to seal("db-01"), "address" to seal("10.0.0.6"), "ssh_config" to ref(8, 902), "status" to "created")),
                dataKey(1, 3) to record(mapOf("local_id" to 3, "id" to 503, "label" to seal("vecchio"), "address" to seal("10.0.0.9"), "ssh_config" to ref(8, 902), "status" to "deleted")),
                dataKey(2, 7) to record(mapOf("local_id" to 7, "id" to 901, "port" to 2222, "identity" to null)),
                dataKey(2, 8) to record(mapOf("local_id" to 8, "id" to 902, "port" to null, "identity" to null)),
                dataKey(3, 1) to record(mapOf("local_id" to 1, "id" to 7, "ssh_config" to ref(7, 901), "identity" to ref(4, 801))),
                dataKey(3, 2) to record(mapOf("local_id" to 2, "id" to 8, "ssh_config" to ref(8, 902), "identity" to ref(5, 802))),
                dataKey(4, 4) to record(mapOf("local_id" to 4, "id" to 801, "label" to seal("deploy"), "username" to seal("deploy"), "password" to seal("segreta"), "ssh_key" to null)),
                dataKey(4, 5) to record(mapOf("local_id" to 5, "id" to 802, "label" to seal("postgres"), "username" to seal("postgres"), "password" to seal(""), "ssh_key" to ref(1, 701))),
                dataKey(5, 1) to record(mapOf("local_id" to 1, "label" to seal("laptop"), "private_key" to seal("-----BEGIN OPENSSH PRIVATE KEY-----"), "passphrase" to seal(""), "public_key" to seal("ssh-ed25519 AAAA"))),
                dataKey(6, 1) to record(mapOf("local_id" to 1, "id" to 1, "hostnames" to seal("10.0.0.5"), "key" to seal("ssh-ed25519 AAAA"))),
            ),
        )
        File(dir, "000003.log").writeBytes(log.out.toByteArray())

        val found = assertIs<TermiusLocal.Found>(TermiusLocalReader.read(TermiusLocalReader.dataDirs(home, os = "Mac OS X").single(), listOf(key)))
        val hosts = found.result.hosts.associateBy { it.label }
        assertEquals(setOf("web-01", "db-01"), hosts.keys, "deleted hosts and known_hosts stay out")
        val web = hosts.getValue("web-01")
        assertEquals("10.0.0.5", web.address)
        assertEquals(2222, web.port)
        assertEquals("deploy", web.username)
        assertEquals("segreta", web.password)
        assertEquals(null, web.key)
        val db = hosts.getValue("db-01")
        assertEquals("postgres", db.username)
        assertEquals(null, db.password, "an empty password is no password")
        assertEquals("laptop", db.key?.label)
        assertEquals(null, db.key?.passphrase)
    }

    @Test
    fun aWrongKeyIsReportedNotGuessed() {
        val data = TermiusLocalReader.dataDirs(termiusFolder(), os = "Mac OS X").single()
        val unreadable = assertIs<TermiusLocal.Unreadable>(TermiusLocalReader.read(data, listOf(Random.nextBytes(32))))
        assertTrue("non apre" in unreadable.reason, unreadable.reason)
    }

    @Test
    fun secretBoxRoundTrip() {
        val sealed = seal("ciao")
        assertTrue(SecretBox.isSealed(sealed))
        assertEquals("ciao", SecretBox.open(sealed, key))
        assertEquals(null, SecretBox.open(sealed, Random.nextBytes(32)))
    }

    @Test
    fun snappyLiteralsAndCopies() {
        // "abcabcabcX": literal "abc", copy of 6 bytes from 3 back, literal "X".
        val compressed = byteArrayOf(10, (2 shl 2).toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), (((6 - 4) shl 2) or 1).toByte(), 3, 0, 'X'.code.toByte())
        assertEquals("abcabcabcX", Snappy.decompress(compressed).decodeToString())
    }

    @Test
    fun readsTablesToo() {
        val dir = Files.createTempDirectory("leveldb").toFile().apply { deleteOnExit() }
        fun internal(key: String, sequence: Long, type: Int) = key.toByteArray() + ByteArray(8) { ((((sequence shl 8) or type.toLong()) ushr (8 * it)) and 0xFF).toByte() }
        val block = ByteArrayOutputStream()
        fun entry(key: ByteArray, value: ByteArray) { block.write(0); block.write(key.size); block.write(value.size); block.write(key); block.write(value) }
        entry(internal("a", 5, 1), "uno".toByteArray())
        entry(internal("b", 6, 0), ByteArray(0))
        block.write(byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0))
        val data = block.toByteArray()
        val index = ByteArrayOutputStream()
        val handle = byteArrayOf(0, data.size.toByte())
        index.write(0); index.write(1); index.write(handle.size); index.write('z'.code); index.write(handle)
        index.write(byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0))
        val indexBytes = index.toByteArray()
        val file = ByteArrayOutputStream()
        file.write(data); file.write(ByteArray(5))
        val indexOffset = file.size()
        file.write(indexBytes); file.write(ByteArray(5))
        val footer = ByteArray(48)
        byteArrayOf(0, 0, indexOffset.toByte(), indexBytes.size.toByte()).copyInto(footer)
        val magic = -0x24b88adb747f04a9L
        repeat(8) { footer[40 + it] = ((magic ushr (8 * it)) and 0xFF).toByte() }
        file.write(footer)
        File(dir, "000005.ldb").writeBytes(file.toByteArray())
        // A newer write in the log replaces the table's value; a deletion hides "b".
        val log = Log().apply { sequence = 9 }
        log.batch(listOf("a".toByteArray() to "due".toByteArray()))
        File(dir, "000007.log").writeBytes(log.out.toByteArray())
        val entries = LevelDb.read(dir).associate { it.key.decodeToString() to it.value.decodeToString() }
        assertEquals(mapOf("a" to "due"), entries)
    }
}
