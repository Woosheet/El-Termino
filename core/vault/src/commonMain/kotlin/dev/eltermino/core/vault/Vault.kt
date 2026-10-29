package dev.eltermino.core.vault

/** Encrypted storage for passwords, private keys, passphrases and other secrets. */
interface Vault {
    /** Stores a copy of [value] under a new id and returns the id. */
    suspend fun add(value: ByteArray): String

    /** Returns a copy the caller may wipe, or null when [id] is unknown. */
    suspend fun get(id: String): ByteArray?

    suspend fun remove(id: String)
}

/**
 * Supplies the 256-bit key that encrypts the vault. It must come from somewhere the
 * operating system protects (Keychain, Credential Manager, Secret Service, Android
 * Keystore), never from a plain file.
 */
fun interface MasterKeyProvider {
    /**
     * @param createIfMissing false once a vault exists: a missing key then means the
     *   keychain is unavailable or was reset, and a new key would lock the old vault forever.
     */
    fun load(createIfMissing: Boolean): ByteArray
}

class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
