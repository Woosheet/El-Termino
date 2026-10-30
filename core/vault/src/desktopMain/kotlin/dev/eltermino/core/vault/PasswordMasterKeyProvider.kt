package dev.eltermino.core.vault

import java.io.File
import java.security.SecureRandom

enum class MasterKeySource { Keychain, Password }

/**
 * Where the master key comes from. A vault keeps its source for life: one made with the keychain
 * must fail with its error when the keychain is gone, because a new key couldn't open it.
 */
fun chooseMasterKeySource(keychainWorks: Boolean, vaultExists: Boolean, saltExists: Boolean): MasterKeySource = when {
    saltExists -> MasterKeySource.Password
    keychainWorks || vaultExists -> MasterKeySource.Keychain
    else -> MasterKeySource.Password
}

/**
 * For systems with no keychain: the key comes from a password the user types at startup, with
 * Argon2id. [saltFile] isn't secret. The key stays in memory, like the open vault it unlocks.
 *
 * @param ask gets true when the password is being chosen; null means the user cancelled.
 */
class PasswordMasterKeyProvider(
    private val saltFile: File,
    private val ask: (firstTime: Boolean) -> CharArray?,
) : MasterKeyProvider {
    private var key: ByteArray? = null

    @Synchronized
    override fun load(createIfMissing: Boolean): ByteArray {
        key?.let { return it.copyOf() }
        val firstTime = !saltFile.exists()
        if (firstTime && !createIfMissing) throw VaultException("The vault exists but ${saltFile.name} is missing")
        val password = ask(firstTime) ?: throw VaultException("Master password not given")
        try {
            val salt = if (firstTime) ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes) else saltFile.readBytes()
            val derived = deriveKey(password, salt, ARGON2_MEMORY_KIB, ARGON2_ITERATIONS, ARGON2_PARALLELISM)
            if (firstTime) {
                saltFile.parentFile?.mkdirs()
                saltFile.writeBytes(salt)
            }
            key = derived
            return derived.copyOf()
        } finally {
            password.fill('\u0000')
        }
    }

    private companion object {
        const val SALT_BYTES = 16
    }
}
