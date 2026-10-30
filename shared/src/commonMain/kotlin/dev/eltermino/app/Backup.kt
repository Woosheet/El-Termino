package dev.eltermino.app

import dev.eltermino.core.data.BackupContent
import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.data.WorkspaceStore
import dev.eltermino.core.data.forBackup
import dev.eltermino.core.data.knownHostConflicts
import dev.eltermino.core.data.mergedWith
import dev.eltermino.core.data.replacedBy
import dev.eltermino.core.vault.Vault
import dev.eltermino.core.vault.VaultException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

internal expect fun sealBackup(plain: ByteArray, password: CharArray): ByteArray

internal expect fun openBackup(file: ByteArray, password: CharArray): ByteArray

class BackupPreview(
    val hosts: Int,
    val identities: Int,
    val keys: Int,
    val snippets: Int,
    val collections: Int,
    val layouts: Int,
    val knownHostConflicts: List<KnownHost>,
)

/** Everything in one password-encrypted file, to move between devices. */
class BackupService(
    private val store: WorkspaceStore,
    private val vault: Vault,
    private val appVersion: String? = null,
    /** Keeps the encrypted copy of what a restore is about to change. */
    private val saveSafetyCopy: suspend (ByteArray) -> Unit,
) {
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
            throw VaultException("Backup written by a newer version of the app")
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
        )
    }

    /**
     * Secrets go in first, on top of the old ones, and the old ones leave only once the
     * workspace is saved: a crash at any point leaves no workspace pointing at a missing secret.
     *
     * @param password protects the safety copy, so the backup's own password opens it too.
     */
    suspend fun restore(content: BackupContent, replaceAll: Boolean, password: CharArray) {
        saveSafetyCopy(export(password))
        val before = vault.snapshot()
        val secrets = content.secrets.mapValues { Base64.decode(it.value) }
        try {
            vault.restore(secrets, replace = false)
            try {
                store.update { if (replaceAll) it.replacedBy(content.workspace) else it.mergedWith(content.workspace) }
            } catch (e: Exception) {
                vault.restore(before, replace = true)
                throw e
            }
            if (replaceAll) vault.restore(secrets, replace = true)
        } finally {
            before.values.forEach { it.fill(0) }
            secrets.values.forEach { it.fill(0) }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
