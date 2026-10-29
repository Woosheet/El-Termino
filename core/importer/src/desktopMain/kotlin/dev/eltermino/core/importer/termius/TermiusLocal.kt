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

    /**
     * Store names, record counts, and each field's name with the kinds of value it holds
     * (testo, cifrato, numero, oggetto{chiavi}…): what's needed to fix the mapping, never a value.
     */
    fun describe(stores: Map<String, List<Map<String, Any?>>>): String = stores.entries.joinToString("\n") { (name, records) ->
        val fields = records.flatMap { it.entries }.groupBy({ it.key }, { kindOf(it.value) })
            .toSortedMap()
            .map { (field, kinds) -> "$field: ${kinds.distinct().sorted().joinToString("|")}" }
        "$name: ${records.size} record\n  " + fields.joinToString("\n  ")
    }

    /** The structure of Termius's data in [dataDir], without the key: names and kinds only. */
    fun structure(dataDir: File): String {
        val databases = File(dataDir, "IndexedDB").listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(".leveldb") }
        val stores = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (database in databases) {
            IndexedDb.read(database).forEach { (name, records) -> stores.getOrPut(name) { mutableListOf() } += records }
        }
        return "${databases.joinToString { it.name }}\n" + describe(stores)
    }

    private fun kindOf(value: Any?): String = when (value) {
        null -> "vuoto"
        is Boolean -> "sì/no"
        is Number -> "numero"
        is String -> when {
            SecretBox.isSealed(value) -> "cifrato"
            value.isEmpty() -> "testo vuoto"
            else -> "testo"
        }
        is Map<*, *> -> "oggetto{${value.keys.joinToString(",")}}"
        is List<*> -> "lista(${value.size})"
        is ByteArray -> "byte"
        else -> value::class.simpleName ?: "?"
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
 * hosts point to a group and an ssh config; the config's identity is either in the config
 * or, in recent versions, in the `ssh_config_identities` link table; the identity points to
 * a key. Groups nest and carry defaults. References are `{local_id, id}` objects.
 */
internal class TermiusMapper(stores: Map<String, List<Map<String, Any?>>>, private val key: ByteArray) {
    private fun normalized(name: String) = name.lowercase().filter { it.isLetter() }

    private fun store(vararg hints: String, without: List<String> = emptyList()): List<Map<String, Any?>> =
        stores.entries.filter { (name, _) ->
            val n = normalized(name)
            hints.any { it == n || it in n } && without.none { it in n }
        }.flatMap { it.value }

    private val stores = stores
    // Store names as Termius uses them (hosts, ssh_configs, ssh_identities, ssh_config_identities,
    // keys, groups); the exclusions keep known_hosts and the link tables out of the wrong list.
    private val hosts = latest(store("host", without = listOf("known", "tag", "history")))
    private val groups = table(store("group"))
    private val configs = table(store("sshconfig", without = listOf("identit")))
    private val identities = table(store("identit", "credential", without = listOf("config")))
    /** In recent Termius, a config's identity is linked here rather than in the config itself. */
    private val configIdentityLinks = store("configidentit")
    private val keys = table(storeNamed("keys") + store("sshkey", "keycrypt", "privatekey"))
    private val tags = table(store("tag", without = listOf("host")))
    private val tagLinks = store("taghost")

    private fun storeNamed(name: String) = stores.entries.filter { normalized(it.key) == name }.flatMap { it.value }
    private val warnings = mutableListOf<ImportWarning>()

    fun map(): ImportResult {
        val imported = hosts.mapNotNull { host -> runCatching { host(host) }.getOrElse { null } }
        if (hosts.isNotEmpty() && imported.isEmpty()) warnings += ImportWarning(null, "Ho trovato ${hosts.size} host ma nessuno con un indirizzo leggibile")
        return ImportResult(imported, warnings)
    }

    private fun host(record: Map<String, Any?>): ImportedHost? {
        if (isDeleted(record)) return null
        val address = address(record["address"]) ?: return null
        val group = ref(field(record, GROUP), groups)
        val chain = groupChain(group)
        fun configOf(owner: Map<String, Any?>) = ref(field(owner, CONFIG), configs)
        val config = configOf(record) ?: chain.firstNotNullOfOrNull { configOf(it) }
        fun identityOf(owner: Map<String, Any?>?) = owner?.let { o ->
            ref(field(o, IDENTITY), identities)
                ?: configIdentityLinks.firstOrNull { refersTo(field(it, CONFIG), o) }?.let { ref(field(it, IDENTITY), identities) }
        }
        // The identity may hang off the host's config, the host itself, or a group's config.
        val identity = identityOf(config) ?: identityOf(record) ?: chain.firstNotNullOfOrNull { identityOf(configOf(it)) ?: identityOf(it) }
        val port = number(field(config, PORT)) ?: number(field(record, PORT)) ?: chain.firstNotNullOfOrNull { number(field(configOf(it), PORT)) }
        val sshKey = ref(field(identity, KEY), keys) ?: ref(field(config, KEY), keys)
        val label = text(record["label"])?.takeIf { it.isNotBlank() } ?: address
        val hostTags = tagLinks.filter { refersTo(it["host"], record) }.mapNotNull { text(ref(it["tag"], tags)?.get("label")) }
        return ImportedHost(
            label = label,
            address = address,
            port = port?.toInt()?.takeIf { it in 1..65535 },
            username = (text(field(identity, USERNAME)) ?: text(field(config, USERNAME)) ?: text(field(record, USERNAME)))?.takeIf { it.isNotBlank() },
            password = (text(field(identity, PASSWORD)) ?: text(field(record, PASSWORD)))?.takeIf { it.isNotEmpty() },
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
            current = ref(field(current, PARENT), groups)
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

    /** The first of [names] that [record] has, so a renamed field still maps. */
    private fun field(record: Map<String, Any?>?, names: List<String>): Any? =
        record?.let { r -> names.firstNotNullOfOrNull { r[it] } }

    private fun normalizeId(value: Any?): Any? = when (value) {
        is Number -> value.toLong()
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    }

    /**
     * Records by their local id and, separately, by their sync id: the two number spaces
     * overlap, so a reference `{local_id, id}` is looked up in each with its own number.
     */
    private class Table(val byLocal: Map<Any, Map<String, Any?>>, val byId: Map<Any, Map<String, Any?>>)

    private fun table(records: List<Map<String, Any?>>): Table {
        val current = latest(records)
        return Table(
            byLocal = current.mapNotNull { r -> LOCAL_IDS.firstNotNullOfOrNull { normalizeId(r[it]) }?.let { it to r } }.toMap(),
            byId = current.mapNotNull { r -> REMOTE_IDS.firstNotNullOfOrNull { normalizeId(r[it]) }?.let { it to r } }.toMap(),
        )
    }

    private fun ref(value: Any?, table: Table): Map<String, Any?>? = when (value) {
        is Map<*, *> -> {
            val local = LOCAL_IDS.firstNotNullOfOrNull { normalizeId(value[it]) }?.let(table.byLocal::get)
            val remote = REMOTE_IDS.firstNotNullOfOrNull { normalizeId(value[it]) }?.let(table.byId::get)
            @Suppress("UNCHECKED_CAST")
            local ?: remote ?: (value as Map<String, Any?>).takeIf { m -> m.keys.any { it !in LOCAL_IDS && it !in REMOTE_IDS } }
        }
        // A bare number is a sync id in the API's model; fall back to a local id.
        else -> normalizeId(value)?.let { table.byId[it] ?: table.byLocal[it] }
    }

    /** Whether reference [value] points at [record]. */
    private fun refersTo(value: Any?, record: Map<String, Any?>): Boolean {
        val local = LOCAL_IDS.firstNotNullOfOrNull { normalizeId(record[it]) }
        val remote = REMOTE_IDS.firstNotNullOfOrNull { normalizeId(record[it]) }
        return when (value) {
            is Map<*, *> -> {
                val refLocal = LOCAL_IDS.firstNotNullOfOrNull { normalizeId(value[it]) }
                val refRemote = REMOTE_IDS.firstNotNullOfOrNull { normalizeId(value[it]) }
                (refLocal != null && refLocal == local) || (refLocal == null && refRemote != null && refRemote == remote)
            }
            else -> normalizeId(value)?.let { it == remote || it == local } ?: false
        }
    }

    /** Several versions of a record can survive; the most recently updated wins. */
    private fun latest(records: List<Map<String, Any?>>): List<Map<String, Any?>> =
        records.groupBy { r -> LOCAL_IDS.firstNotNullOfOrNull { normalizeId(r[it]) }?.let { "l$it" } ?: REMOTE_IDS.firstNotNullOfOrNull { normalizeId(r[it]) }?.let { "r$it" } ?: r }
            .values.map { versions -> versions.maxBy { updated(it) } }

    private fun isDeleted(record: Map<String, Any?>) =
        record["is_deleted"] == true || record["deleted"] == true || (record["status"] as? String)?.equals("deleted", ignoreCase = true) == true

    private companion object {
        val LOCAL_IDS = listOf("local_id", "localId")
        val REMOTE_IDS = listOf("id", "remote_id", "remoteId", "uuid")
        val GROUP = listOf("group", "group_id", "groupId")
        val PARENT = listOf("parent_group", "parent_group_id", "parentGroup", "parent")
        val CONFIG = listOf("ssh_config", "ssh_config_id", "sshConfig", "config")
        val IDENTITY = listOf("identity", "identity_id", "ssh_identity", "sshIdentity")
        val KEY = listOf("ssh_key", "ssh_key_id", "sshKey", "key")
        val USERNAME = listOf("username", "user", "login")
        val PASSWORD = listOf("password", "pass")
        val PORT = listOf("port")
    }

    private fun updated(record: Map<String, Any?>): Double = when (val value = record["updated_at"]) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: runCatching { java.time.Instant.parse(value).toEpochMilli().toDouble() }.getOrDefault(0.0)
        else -> 0.0
    }
}
