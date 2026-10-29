package dev.eltermino.core.data

import kotlinx.serialization.Serializable

/**
 * Everything the user configures, saved as one JSON document. Secrets never appear
 * here: fields ending in `SecretId` point into the vault.
 */
@Serializable
data class Workspace(
    val version: Int = CURRENT_VERSION,
    val hosts: List<Host> = emptyList(),
    val groups: List<HostGroup> = emptyList(),
    val identities: List<Identity> = emptyList(),
    val keys: List<SshKeyEntry> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
) {
    fun host(id: String?): Host? = id?.let { hosts.firstOrNull { h -> h.id == it } }
    fun group(id: String?): HostGroup? = id?.let { groups.firstOrNull { g -> g.id == it } }
    fun identity(id: String?): Identity? = id?.let { identities.firstOrNull { i -> i.id == it } }
    fun key(id: String?): SshKeyEntry? = id?.let { keys.firstOrNull { k -> k.id == it } }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class Host(
    val id: String,
    val label: String,
    val address: String,
    val port: Int = 22,
    val groupId: String? = null,
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    /** Overrides the identity's username when set. */
    val username: String? = null,
    val identityId: String? = null,
    /** The host to go through first; that host may have its own jump host. */
    val jumpHostId: String? = null,
    val notes: String = "",
    val lastConnectedEpochSeconds: Long? = null,
)

@Serializable
data class HostGroup(
    val id: String,
    val name: String,
    val parentId: String? = null,
)

/** Credentials that several hosts can share, as Termius identities do. */
@Serializable
data class Identity(
    val id: String,
    val label: String,
    val username: String,
    val passwordSecretId: String? = null,
    val keyId: String? = null,
)

@Serializable
data class SshKeyEntry(
    val id: String,
    val label: String,
    val type: String,
    /** `authorized_keys` line. */
    val publicKey: String,
    val fingerprintSha256: String,
    val privateKeySecretId: String,
    val encrypted: Boolean,
    /** Saved only when the user chose to remember it. */
    val passphraseSecretId: String? = null,
    /** OpenSSH `-cert.pub` contents. */
    val certificate: String? = null,
)

@Serializable
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprintSha256: String,
    val publicKey: String,
    val addedAtEpochSeconds: Long,
)
