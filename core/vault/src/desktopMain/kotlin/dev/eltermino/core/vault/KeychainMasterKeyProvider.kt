package dev.eltermino.core.vault

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import java.security.SecureRandom
import java.util.Base64

/**
 * Keeps the master key in the system keychain: Keychain on macOS, Credential Manager
 * on Windows, Secret Service (GNOME Keyring, KWallet) on Linux.
 */
class KeychainMasterKeyProvider(
    private val service: String = "El Termino",
    private val account: String = "vault-master-key",
) : MasterKeyProvider {
    override fun load(createIfMissing: Boolean): ByteArray = try {
        Keyring.create().use { keyring ->
            val stored = try {
                keyring.getPassword(service, account)
            } catch (e: PasswordAccessException) {
                null
            }
            when {
                stored != null -> Base64.getDecoder().decode(stored)
                createIfMissing -> ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                    keyring.setPassword(service, account, Base64.getEncoder().encodeToString(it))
                }
                else -> throw VaultException(
                    "The vault exists but its key isn't in the system keychain ($service / $account)",
                )
            }
        }
    } catch (e: VaultException) {
        throw e
    } catch (e: Exception) {
        throw VaultException("System keychain unavailable: ${e.message}", e)
    }
}
