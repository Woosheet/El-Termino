package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.HostGroup
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.SshKeyEntry
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.data.newId
import dev.eltermino.core.ssh.InvalidKeyException

/** Changes to the workspace that also touch the vault, kept consistent in one place. */
class WorkspaceActions(private val services: AppServices) {
    private val store get() = services.workspace
    private val vault get() = services.vault

    suspend fun saveHost(host: Host) = store.update { ws ->
        if (ws.hosts.any { it.id == host.id }) ws.copy(hosts = ws.hosts.map { if (it.id == host.id) host else it })
        else ws.copy(hosts = ws.hosts + host)
    }

    suspend fun deleteHost(id: String) = store.update { ws ->
        ws.copy(hosts = ws.hosts.filterNot { it.id == id }.map { if (it.jumpHostId == id) it.copy(jumpHostId = null) else it })
    }

    /** Returns the id of the group at [path], creating the missing levels. */
    suspend fun ensureGroupPath(path: List<String>): String? {
        if (path.isEmpty()) return null
        var parentId: String? = null
        store.update { ws ->
            var groups = ws.groups
            for (name in path.map { it.trim() }.filter { it.isNotEmpty() }) {
                val existing = groups.firstOrNull { it.parentId == parentId && it.name.equals(name, ignoreCase = true) }
                val group = existing ?: HostGroup(newId(), name, parentId).also { groups = groups + it }
                parentId = group.id
            }
            ws.copy(groups = groups)
        }
        return parentId
    }

    suspend fun renameGroup(id: String, name: String) = store.update { ws ->
        ws.copy(groups = ws.groups.map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Hosts and subgroups move up to the parent of the deleted group. */
    suspend fun deleteGroup(id: String) = store.update { ws ->
        val parent = ws.group(id)?.parentId
        ws.copy(
            groups = ws.groups.filterNot { it.id == id }.map { if (it.parentId == id) it.copy(parentId = parent) else it },
            hosts = ws.hosts.map { if (it.groupId == id) it.copy(groupId = parent) else it },
        )
    }

    /**
     * Saves [identity]. [newPassword] replaces the stored password; an empty array
     * removes it; null leaves it as it is.
     */
    suspend fun saveIdentity(identity: Identity, newPassword: ByteArray? = null): Identity {
        var saved = identity
        if (newPassword != null) {
            identity.passwordSecretId?.let { vault.remove(it) }
            saved = saved.copy(passwordSecretId = if (newPassword.isEmpty()) null else vault.add(newPassword))
        }
        store.update { ws ->
            if (ws.identities.any { it.id == saved.id }) ws.copy(identities = ws.identities.map { if (it.id == saved.id) saved else it })
            else ws.copy(identities = ws.identities + saved)
        }
        return saved
    }

    suspend fun deleteIdentity(id: String) {
        val identity = store.workspace.value.identity(id) ?: return
        identity.passwordSecretId?.let { vault.remove(it) }
        store.update { ws ->
            ws.copy(
                identities = ws.identities.filterNot { it.id == id },
                hosts = ws.hosts.map {
                    // Keep the username on the host so it still knows who to log in as.
                    if (it.identityId == id) it.copy(identityId = null, username = it.username ?: identity.username) else it
                },
            )
        }
    }

    /** Called after a login with a password the user asked to keep. */
    internal suspend fun savePassword(hostId: String, identity: Identity?, username: String, password: ByteArray) {
        if (identity != null && identity.passwordSecretId == null) {
            saveIdentity(identity, password)
            return
        }
        val host = store.workspace.value.host(hostId) ?: return
        val created = saveIdentity(Identity(newId(), "$username@${host.label}", username), password)
        store.update { ws -> ws.copy(hosts = ws.hosts.map { if (it.id == hostId) it.copy(identityId = created.id) else it }) }
    }

    internal suspend fun savePassphrase(keyId: String, passphrase: ByteArray) {
        val id = vault.add(passphrase)
        store.update { ws -> ws.copy(keys = ws.keys.map { if (it.id == keyId) it.copy(passphraseSecretId = id) else it }) }
    }

    /**
     * Stores a private key. An encrypted key needs its [passphrase] to be read; it's
     * kept only if [savePassphrase]. Importing the same key twice returns the first entry.
     *
     * @throws InvalidKeyException
     */
    suspend fun importKey(
        label: String,
        privateKey: ByteArray,
        passphrase: ByteArray? = null,
        savePassphrase: Boolean = false,
        certificate: String? = null,
    ): SshKeyEntry {
        val tool = services.keyTool
        val encrypted = tool.isEncrypted(privateKey)
        if (encrypted && passphrase == null) throw InvalidKeyException("La chiave è protetta da passphrase")
        val info = tool.inspect(privateKey, passphrase)
        store.workspace.value.keys.firstOrNull { it.fingerprintSha256 == info.fingerprintSha256 }?.let { return it }

        val entry = SshKeyEntry(
            id = newId(),
            label = label,
            type = info.type,
            publicKey = info.publicKey,
            fingerprintSha256 = info.fingerprintSha256,
            privateKeySecretId = vault.add(privateKey),
            encrypted = encrypted,
            passphraseSecretId = if (encrypted && savePassphrase && passphrase != null) vault.add(passphrase) else null,
            certificate = certificate?.trim()?.takeIf { it.isNotEmpty() },
        )
        store.update { it.copy(keys = it.keys + entry) }
        return entry
    }

    suspend fun generateKey(label: String): SshKeyEntry {
        val generated = services.keyTool.generateEd25519(label)
        return try {
            importKey(label, generated.privateKey)
        } finally {
            generated.privateKey.fill(0)
        }
    }

    suspend fun updateKey(key: SshKeyEntry) = store.update { ws ->
        ws.copy(keys = ws.keys.map { if (it.id == key.id) key else it })
    }

    suspend fun deleteKey(id: String) {
        val key = store.workspace.value.key(id) ?: return
        vault.remove(key.privateKeySecretId)
        key.passphraseSecretId?.let { vault.remove(it) }
        store.update { ws ->
            ws.copy(
                keys = ws.keys.filterNot { it.id == id },
                identities = ws.identities.map { if (it.keyId == id) it.copy(keyId = null) else it },
            )
        }
    }
}

internal fun Workspace.hostsUsing(identityId: String) = hosts.count { it.identityId == identityId }
internal fun Workspace.identitiesUsing(keyId: String) = identities.count { it.keyId == keyId }
