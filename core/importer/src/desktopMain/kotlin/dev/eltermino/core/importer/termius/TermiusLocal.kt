package dev.eltermino.core.importer.termius

import dev.eltermino.core.importer.ImportResult
import dev.eltermino.core.importer.ImportWarning
import dev.eltermino.core.importer.ImportedHost
import dev.eltermino.core.importer.ImportedKey
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** What reading Termius's own data on this computer found. */
sealed interface TermiusLocal {
    data object NotFound : TermiusLocal

    /** The data is there but can't be read: [reason] says why, in Italian. */
    data class Unreadable(val reason: String, val details: String) : TermiusLocal

    data class Found(val result: ImportResult, val details: String) : TermiusLocal
}

/**
 * Reads the hosts, groups, identities and keys that Termius keeps on this computer, in its
 * Electron IndexedDB. Secret fields are sealed with NaCl secretbox (XSalsa20-Poly1305)
 * under a "local key" kept in the system keychain, which the caller supplies.
 */
object TermiusLocalReader {
    /** Where Termius keeps its data, newest-looking first, for the current OS. */
    fun dataDirs(home: File = File(System.getProperty("user.home")), os: String = System.getProperty("os.name"), env: Map<String, String> = System.getenv()): List<File> {
        val name = os.lowercase()
        val candidates = when {
            "mac" in name -> listOf(File(home, "Library/Application Support/Termius")) +
                File(home, "Library/Containers").listFiles().orEmpty()
                    .filter { it.name.contains("termius", ignoreCase = true) }
                    .map { File(it, "Data/Library/Application Support/Termius") }
            "win" in name -> listOfNotNull(env["APPDATA"]?.let { File(it, "Termius") }, File(home, "AppData/Roaming/Termius"))
            else -> listOf(
                File(home, ".config/Termius"),
                File(home, "snap/termius-app/current/.config/Termius"),
                File(home, ".var/app/com.termius.Termius/config/Termius"),
            )
        }
        return candidates.filter { File(it, "IndexedDB").isDirectory }.distinct()
    }

    /**
     * Reads [dataDir] with the first of [keys] that opens its secrets. Values are never
     * put in [TermiusLocal.Unreadable.details]: only store names, counts and field names.
     */
    fun read(dataDir: File, keys: List<ByteArray>): TermiusLocal {
        val indexedDb = File(dataDir, "IndexedDB")
        val databases = indexedDb.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(".leveldb") }
        if (databases.isEmpty()) {
            val sqlite = indexedDb.walkTopDown().any { it.isFile && it.name.endsWith(".sqlite") }
            return TermiusLocal.Unreadable(
                if (sqlite) "Questa versione di Termius salva i dati in un formato (SQLite) che El Termino non sa ancora leggere" else "Nella cartella di Termius non c'è il database atteso",
                details = indexedDb.listFiles().orEmpty().joinToString { it.name },
            )
        }
        val stores = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (database in databases) {
            IndexedDb.read(database).forEach { (name, records) -> stores.getOrPut(name) { mutableListOf() } += records }
        }
        val details = describe(stores)
        if (stores.isEmpty()) return TermiusLocal.Unreadable("Il database di Termius è vuoto o illeggibile", details)

        val key = keys.firstOrNull { candidate -> opens(stores, candidate) }
            ?: return TermiusLocal.Unreadable(
                if (keys.isEmpty()) "Non trovo la chiave di Termius nel portachiavi" else "La chiave di Termius nel portachiavi non apre questi dati",
                details,
            )
        return TermiusLocal.Found(TermiusMapper(stores, key).map(), details)
    }

    /** Store names, record counts and field names: what's needed to fix the mapping, never values. */
    fun describe(stores: Map<String, List<Map<String, Any?>>>): String = stores.entries.joinToString("\n") { (name, records) ->
        val fields = records.flatMap { it.keys }.distinct().sorted()
        "$name: ${records.size} record · campi ${fields.joinToString(", ")}"
    }

    private fun opens(stores: Map<String, List<Map<String, Any?>>>, key: ByteArray): Boolean {
        val sealed = stores.values.asSequence().flatten().flatMap { it.values.asSequence() }.filterIsInstance<String>().firstOrNull { SecretBox.isSealed(it) }
        return sealed == null || SecretBox.open(sealed, key) != null
    }
}

/** NaCl secretbox as Termius stores it: base64 of 0x04 0x01, a 24-byte nonce, the tag and the ciphertext. */
internal object SecretBox {
    fun isSealed(value: String): Boolean {
        if (value.length < 56) return false
        val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return false
        return bytes.size >= 42 && bytes[0] == 4.toByte() && bytes[1] == 1.toByte()
    }

    fun open(value: String, key: ByteArray): String? {
        val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
        if (bytes.size < 42 || bytes[0] != 4.toByte() || bytes[1] != 1.toByte() || key.size != 32) return null
        val nonce = bytes.copyOfRange(2, 26)
        val tag = bytes.copyOfRange(26, 42)
        val cipherText = bytes.copyOfRange(42, bytes.size)
        val stream = XSalsa20Engine().apply { init(false, ParametersWithIV(KeyParameter(key), nonce)) }
        val polyKey = ByteArray(32).also { stream.processBytes(ByteArray(32), 0, 32, it, 0) }
        val mac = ByteArray(16)
        Poly1305().apply {
            init(KeyParameter(polyKey))
            update(cipherText, 0, cipherText.size)
            doFinal(mac, 0)
        }
        if (!MessageDigest.isEqual(mac, tag)) return null
        val plain = ByteArray(cipherText.size).also { stream.processBytes(cipherText, 0, cipherText.size, it, 0) }
        return plain.decodeToString()
    }

    /** The other direction, for tests that build Termius-like data. */
    internal fun seal(text: String, key: ByteArray, nonce: ByteArray): String {
        val plain = text.encodeToByteArray()
        val stream = XSalsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }
        val polyKey = ByteArray(32).also { stream.processBytes(ByteArray(32), 0, 32, it, 0) }
        val cipherText = ByteArray(plain.size).also { stream.processBytes(plain, 0, plain.size, it, 0) }
        val mac = ByteArray(16)
        Poly1305().apply {
            init(KeyParameter(polyKey))
            update(cipherText, 0, cipherText.size)
            doFinal(mac, 0)
        }
        return Base64.getEncoder().encodeToString(byteArrayOf(4, 1) + nonce + mac + cipherText)
    }
}

/**
 * From Termius's records to [ImportedHost]s. The model is the one of Termius's sync API:
 * hosts point to a group and an ssh config, which points to an identity, which points to
 * a key; groups nest and carry defaults. References are local ids, or the object itself.
 */
internal class TermiusMapper(stores: Map<String, List<Map<String, Any?>>>, private val key: ByteArray) {
    private fun normalized(name: String) = name.lowercase().filter { it.isLetter() }

    private fun store(vararg hints: String, without: String? = null): List<Map<String, Any?>> =
        stores.entries.filter { (name, _) ->
            val n = normalized(name)
            hints.any { it in n } && (without == null || without !in n)
        }.flatMap { it.value }

    private val stores = stores
    private val hosts = latest(store("host", without = "tag"))
    private val groups = byId(store("group"))
    private val configs = byId(store("sshconfig"))
    private val identities = byId(store("identit"))
    private val keys = byId(store("sshkey", "keycrypt"))
    private val tags = byId(store("tag", without = "host"))
    private val tagLinks = store("taghost")
    private val warnings = mutableListOf<ImportWarning>()

    fun map(): ImportResult {
        val imported = hosts.mapNotNull { host -> runCatching { host(host) }.getOrElse { null } }
        if (hosts.isNotEmpty() && imported.isEmpty()) warnings += ImportWarning(null, "Ho trovato ${hosts.size} host ma nessuno con un indirizzo leggibile")
        return ImportResult(imported, warnings)
    }

    private fun host(record: Map<String, Any?>): ImportedHost? {
        if (isDeleted(record)) return null
        val address = address(record["address"]) ?: return null
        val group = ref(record["group"], groups)
        val config = ref(record["ssh_config"], configs) ?: groupChain(group).firstNotNullOfOrNull { ref(it["ssh_config"], configs) }
        val identity = ref(config?.get("identity"), identities)
            ?: groupChain(group).firstNotNullOfOrNull { ref(ref(it["ssh_config"], configs)?.get("identity"), identities) }
        val port = number(config?.get("port")) ?: groupChain(group).firstNotNullOfOrNull { number(ref(it["ssh_config"], configs)?.get("port")) }
        val sshKey = ref(identity?.get("ssh_key"), keys)
        val label = text(record["label"])?.takeIf { it.isNotBlank() } ?: address
        val hostTags = tagLinks.filter { idOf(it["host"]) == idOf(record) }.mapNotNull { text(ref(it["tag"], tags)?.get("label")) }
        return ImportedHost(
            label = label,
            address = address,
            port = port?.toInt()?.takeIf { it in 1..65535 },
            username = text(identity?.get("username"))?.takeIf { it.isNotBlank() },
            password = text(identity?.get("password"))?.takeIf { it.isNotEmpty() },
            groupPath = groupChain(group).reversed().mapNotNull { text(it["label"]) },
            tags = hostTags,
            key = sshKey?.let { keyOf(it) },
        )
    }

    private fun keyOf(record: Map<String, Any?>): ImportedKey? {
        val private = text(record["private_key"])?.takeIf { it.isNotBlank() } ?: return null
        return ImportedKey(
            label = text(record["label"])?.takeIf { it.isNotBlank() } ?: "Chiave da Termius",
            privateKey = private,
            passphrase = text(record["passphrase"])?.takeIf { it.isNotEmpty() },
        )
    }

    /** The group and its parents, nearest first; stops at cycles. */
    private fun groupChain(group: Map<String, Any?>?): List<Map<String, Any?>> {
        val chain = mutableListOf<Map<String, Any?>>()
        var current = group
        while (current != null && chain.none { it === current } && chain.size < 32) {
            chain += current
            current = ref(current["parent_group"], groups)
        }
        return chain
    }

    /** A plain or sealed string; sealed ones are opened with the local key. */
    private fun text(value: Any?): String? = when (value) {
        is String -> if (SecretBox.isSealed(value)) SecretBox.open(value, key) else value
        is Number -> value.toString()
        else -> null
    }

    private fun number(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> text(value)?.trim()?.toLongOrNull()
        else -> null
    }

    /** Addresses can be strings, sealed strings, or an IPv4 address packed into a number. */
    private fun address(value: Any?): String? = when (value) {
        is Number -> value.toLong().let { n -> (3 downTo 0).joinToString(".") { ((n ushr (8 * it)) and 0xFF).toString() } }
        else -> text(value)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun idOf(value: Any?): Any? = when (value) {
        is Map<*, *> -> value["id"] ?: value["local_id"]
        is Number -> value.toLong()
        is String -> value
        else -> null
    }.let { if (it is Number) it.toLong() else it }

    private fun ref(value: Any?, table: Map<Any, Map<String, Any?>>): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        if (value is Map<*, *> && value.size > 2) return value as Map<String, Any?>
        return idOf(value)?.let { table[it] }
    }

    private fun isDeleted(record: Map<String, Any?>) =
        record["is_deleted"] == true || record["deleted"] == true || (record["status"] as? String)?.equals("deleted", ignoreCase = true) == true

    private fun byId(records: List<Map<String, Any?>>): Map<Any, Map<String, Any?>> =
        latest(records).mapNotNull { record -> idOf(record)?.let { it to record } }.toMap()

    /** Several versions of a record can survive; the most recently updated wins. */
    private fun latest(records: List<Map<String, Any?>>): List<Map<String, Any?>> =
        records.groupBy { idOf(it) ?: it }.values.map { versions -> versions.maxBy { updated(it) } }

    private fun updated(record: Map<String, Any?>): Double = when (val value = record["updated_at"]) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: runCatching { java.time.Instant.parse(value).toEpochMilli().toDouble() }.getOrDefault(0.0)
        else -> 0.0
    }
}
