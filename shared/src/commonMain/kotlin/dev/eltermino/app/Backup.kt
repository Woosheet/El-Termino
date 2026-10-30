package dev.eltermino.app

import dev.eltermino.core.data.BackupContent
import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.data.WorkspaceStore
import dev.eltermino.core.data.forBackup
import dev.eltermino.core.data.knownHostConflicts
import dev.eltermino.core.data.mergedWith
import dev.eltermino.core.data.newId
import dev.eltermino.core.data.replacedBy
import dev.eltermino.core.vault.BackupTooNewException
import dev.eltermino.core.vault.Vault
import dev.eltermino.core.vault.VaultException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal expect fun sealBackup(plain: ByteArray, password: CharArray): ByteArray

internal expect fun openBackup(file: ByteArray, password: CharArray): ByteArray

private const val MIN_PASSWORD_LENGTH = 8

/** What's wrong with a new backup password, in the user's words; null when it's fine. */
internal fun backupPasswordProblem(password: String, repeated: String): String? = when {
    password.length < MIN_PASSWORD_LENGTH -> "Almeno $MIN_PASSWORD_LENGTH caratteri"
    password != repeated -> "Le due password non coincidono"
    else -> null
}

@OptIn(ExperimentalTime::class)
internal fun backupFileName(): String = "el-termino-${Clock.System.now().toString().take(10)}.etbackup"

class BackupPreview(
    val hosts: Int,
    val identities: Int,
    val keys: Int,
    val snippets: Int,
    val collections: Int,
    val layouts: Int,
    val knownHostConflicts: List<KnownHost>,
    /** What the backup would have this device trust. */
    val cas: Int = 0,
    val httpCerts: Int = 0,
)

/** Everything in one password-encrypted file, to move between devices. */
class BackupService(
    private val store: WorkspaceStore,
    private val vault: Vault,
    private val appVersion: String? = null,
    /** The encrypted copy the last restore left, if any. */
    private val readSafetyCopy: suspend () -> ByteArray? = { null },
    /** Keeps the encrypted copy of what a restore is about to change. */
    private val saveSafetyCopy: suspend (ByteArray) -> Unit,
) {
    suspend fun lastSafetyCopy(): ByteArray? = readSafetyCopy()

    suspend fun export(password: CharArray): ByteArray {
        val secrets = vault.snapshot()
        val content = BackupContent(
            formatVersion = BackupContent.CURRENT_FORMAT,
            appVersion = appVersion,
            workspace = store.workspace.value.forBackup(),
            secrets = secrets.mapValues { Base64.encode(it.value) },
        )
        secrets.values.forEach { it.fill(0) }
        val plain = json.encodeToString(BackupContent.serializer(), content).encodeToByteArray()
        return try {
            sealBackup(plain, password)
        } finally {
            plain.fill(0)
        }
    }

    /** @throws VaultException for a wrong password, a damaged file or a newer format. */
    fun read(file: ByteArray, password: CharArray): BackupContent {
        val plain = openBackup(file, password)
        val content = try {
            json.decodeFromString(BackupContent.serializer(), plain.decodeToString())
        } catch (e: SerializationException) {
            throw VaultException("Not an El Termino backup", e)
        } finally {
            plain.fill(0)
        }
        if (content.formatVersion > BackupContent.CURRENT_FORMAT || content.workspace.version > Workspace.CURRENT_VERSION) {
            throw BackupTooNewException("Backup written by a newer version of the app")
        }
        return content
    }

    fun preview(content: BackupContent): BackupPreview = with(content.workspace) {
        BackupPreview(
            hosts = hosts.size,
            identities = identities.size,
            keys = keys.size,
            snippets = snippets.size,
            collections = httpCollections.size,
            layouts = savedLayouts.size,
            knownHostConflicts = store.workspace.value.knownHostConflicts(this),
            cas = trustedCas.size,
            httpCerts = trustedHttpCerts.size,
        )
    }

    /**
     * Saving the workspace is the one moment the restore happens. Until then the vault only
     * gains: a secret whose id is taken by a different value comes in under a new id, so
     * a crash or a full disk at any point leaves the old workspace with its own secrets.
     *
     * @param password protects the safety copy, so the backup's own password opens it too.
     */
    suspend fun restore(content: BackupContent, replaceAll: Boolean, password: CharArray): Unit = withContext(NonCancellable) {
        saveSafetyCopy(export(password))
        val before = vault.snapshot()
        val incoming = content.secrets.mapValues { Base64.decode(it.value) }
        val renamed = incoming.filter { (id, value) -> before[id]?.contentEquals(value) == false }.keys.associateWith { newId() }
        val secrets = incoming.mapKeys { (id, _) -> renamed[id] ?: id }
        // Ids are random, so as text they can only be the references to them.
        val workspace = renamed.entries.fold(json.encodeToString(Workspace.serializer(), content.workspace)) { text, (old, new) ->
            text.replace("\"$old\"", "\"$new\"")
        }.let { json.decodeFromString(Workspace.serializer(), it) }
        try {
            vault.restore(secrets, replace = false)
            try {
                store.update { if (replaceAll) it.replacedBy(workspace) else it.mergedWith(workspace) }
            } catch (e: Exception) {
                runCatching { secrets.keys.filter { it !in before }.forEach { vault.remove(it) } }
                throw e
            }
            // What's left is tidying up: the restore is done, even if this doesn't get through.
            runCatching {
                if (replaceAll) {
                    vault.restore(secrets, replace = true)
                } else {
                    val text = json.encodeToString(Workspace.serializer(), store.workspace.value)
                    renamed.keys.filter { "\"$it\"" !in text }.forEach { vault.remove(it) }
                }
            }
        } finally {
            before.values.forEach { it.fill(0) }
            incoming.values.forEach { it.fill(0) }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
