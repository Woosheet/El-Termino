package dev.eltermino.core.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore keys can't be exported, so the vault's master key is wrapped with a
 * Keystore AES-GCM key and stored in [wrappedKeyFile] as `iv (12) | ciphertext + tag`.
 */
class AndroidKeystoreMasterKeyProvider(
    private val wrappedKeyFile: File,
    private val alias: String = "eltermino-vault",
) : MasterKeyProvider {
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun load(createIfMissing: Boolean): ByteArray {
        if (wrappedKeyFile.exists()) return unwrap(wrappedKeyFile.readBytes())
        if (!createIfMissing) throw VaultException("The vault exists but its wrapped key is missing")

        val master = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val wrapped = cipher.iv + cipher.doFinal(master)
        wrappedKeyFile.parentFile?.mkdirs()
        val temp = File(wrappedKeyFile.parentFile, "${wrappedKeyFile.name}.tmp")
        temp.writeBytes(wrapped)
        Files.move(temp.toPath(), wrappedKeyFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return master
    }

    private fun unwrap(data: ByteArray): ByteArray {
        val key = keyStore.getKey(alias, null) as? SecretKey
            ?: throw VaultException("Keystore key $alias is gone, the vault can't be opened")
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, IV_BYTES))
                doFinal(data, IV_BYTES, data.size - IV_BYTES)
            }
        } catch (e: Exception) {
            throw VaultException("Can't unwrap the vault key", e)
        }
    }

    private fun wrappingKey(): SecretKey =
        keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
