package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.data.newId
import dev.eltermino.core.importer.ImportResult
import dev.eltermino.core.importer.ImportedHost
import dev.eltermino.core.importer.ImportedKey
import dev.eltermino.core.importer.IncludedFile
import dev.eltermino.core.importer.JumpTarget
import dev.eltermino.core.importer.SshConfigParser
import dev.eltermino.core.importer.TermiusCsvParser
import dev.eltermino.core.ssh.InvalidKeyException

class ImportSummary(val hostsAdded: Int, val duplicatesSkipped: Int, val keysImported: Int, val notes: List<String>)

/** Reads foreign host lists and merges them into the workspace. */
class ImportService(private val services: AppServices, private val actions: WorkspaceActions) {
    fun parseSshConfig(text: String): ImportResult = SshConfigParser.parse(text) { pattern ->
        services.platform.resolveSshInclude(pattern).map { (name, contents) -> IncludedFile(name, contents) }
    }

    fun parseTermiusCsv(text: String): ImportResult = TermiusCsvParser.parse(text)

    /** True when an equivalent host (same address, port and user) is already saved. */
    fun isDuplicate(workspace: Workspace, imported: ImportedHost): Boolean = workspace.hosts.any { existing ->
        existing.address.equals(imported.address, ignoreCase = true) &&
            existing.port == (imported.port ?: 22) &&
            (imported.username == null || workspace.usernameFor(existing) == imported.username)
    }

    suspend fun apply(selected: List<ImportedHost>): ImportSummary {
        val notes = mutableListOf<String>()
        val keysBefore = services.workspace.workspace.value.keys.size
        var duplicates = 0
        val created = mutableMapOf<String, Host>()
        // Hosts sharing user and password in the source share one identity here.
        val passwordIdentities = mutableMapOf<Pair<String, String>, Identity>()
        val keyIdentities = mutableMapOf<Pair<String, String>, Identity>()

        for (imported in selected) {
            if (isDuplicate(services.workspace.workspace.value, imported)) {
                duplicates++
                continue
            }
            val groupId = actions.ensureGroupPath(imported.groupPath)
            var identity: Identity? = null
            val username = imported.username

            val keyId = imported.key?.let { importCarriedKey(it, notes) }
                ?: imported.identityFiles.firstNotNullOfOrNull { path -> importKeyFile(path, notes) }
            val password = imported.password
            if (username != null && keyId != null) {
                identity = keyIdentities.getOrPut(username to keyId) {
                    val workspace = services.workspace.workspace.value
                    workspace.identities.firstOrNull { it.username == username && it.keyId == keyId && it.passwordSecretId == null }
                        ?: actions.saveIdentity(Identity(newId(), "$username · ${workspace.key(keyId)?.label}", username, keyId = keyId))
                }
            } else if (username != null && password != null) {
                identity = passwordIdentities.getOrPut(username to password) {
                    actions.saveIdentity(Identity(newId(), "$username@${imported.label}", username), password.encodeToByteArray())
                }
            }

            val host = Host(
                id = newId(),
                label = imported.label,
                address = imported.address,
                port = imported.port ?: 22,
                groupId = groupId,
                tags = imported.tags,
                username = if (identity == null) username else null,
                identityId = identity?.id,
                notes = imported.notes.orEmpty(),
            )
            actions.saveHost(host)
            created[imported.label.lowercase()] = host
        }

        for (imported in selected) {
            val host = created[imported.label.lowercase()] ?: continue
            if (imported.proxyJump.isNotEmpty()) linkJumpChain(host, imported.proxyJump, created)
        }
        val keysAdded = services.workspace.workspace.value.keys.size - keysBefore
        return ImportSummary(created.size, duplicates, keysAdded, notes.distinct())
    }

    /**
     * ProxyJump `a,b` means a first, then b, then the host: so host → b → a, each hop
     * pointing to the previous one. Hops not found among the hosts become new hosts.
     */
    private suspend fun linkJumpChain(host: Host, chain: List<JumpTarget>, created: MutableMap<String, Host>) {
        var previous: Host? = null
        for (hop in chain) {
            val hopHost = findOrCreateHop(hop, created)
            if (previous != null && hopHost.jumpHostId == null && hopHost.id != previous.id) {
                actions.saveHost(hopHost.copy(jumpHostId = previous.id))
            }
            previous = services.workspace.workspace.value.host(hopHost.id)
        }
        val current = services.workspace.workspace.value.host(host.id) ?: return
        if (previous != null && previous.id != current.id) actions.saveHost(current.copy(jumpHostId = previous.id))
    }

    private suspend fun findOrCreateHop(hop: JumpTarget, created: MutableMap<String, Host>): Host {
        created[hop.host.lowercase()]?.let { return services.workspace.workspace.value.host(it.id) ?: it }
        val workspace = services.workspace.workspace.value
        workspace.hosts.firstOrNull { it.label.equals(hop.host, ignoreCase = true) }?.let { return it }
        workspace.hosts.firstOrNull {
            it.address.equals(hop.host, ignoreCase = true) && it.port == (hop.port ?: 22) &&
                (hop.username == null || workspace.usernameFor(it) == hop.username)
        }?.let { return it }
        val host = Host(newId(), hop.host, hop.host, hop.port ?: 22, username = hop.username)
        actions.saveHost(host)
        created[hop.host.lowercase()] = host
        return host
    }

    private suspend fun importCarriedKey(key: ImportedKey, notes: MutableList<String>): String? {
        val bytes = key.privateKey.encodeToByteArray()
        val passphrase = key.passphrase?.encodeToByteArray()
        return try {
            actions.importKey(key.label, bytes, passphrase, savePassphrase = passphrase != null).id
        } catch (e: InvalidKeyException) {
            notes += "Chiave ${key.label} non importata: ${e.message}"
            null
        } finally {
            bytes.fill(0)
            passphrase?.fill(0)
        }
    }

    private suspend fun importKeyFile(path: String, notes: MutableList<String>): String? {
        val bytes = services.platform.readLocalFile(path) ?: run {
            notes += "Chiave $path non leggibile: importala da Chiavi"
            return null
        }
        return try {
            if (services.keyTool.isEncrypted(bytes)) {
                notes += "Chiave $path protetta da passphrase: importala da Chiavi"
                null
            } else {
                actions.importKey(path.substringAfterLast('/'), bytes).id
            }
        } catch (e: InvalidKeyException) {
            notes += "Chiave $path non valida: ${e.message}"
            null
        } finally {
            bytes.fill(0)
        }
    }
}
