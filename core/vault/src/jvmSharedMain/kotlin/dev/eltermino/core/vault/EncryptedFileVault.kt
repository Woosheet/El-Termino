package dev.eltermino.core.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * All secrets in one file, encrypted as a whole with AES-256-GCM:
 * `magic (4) | nonce (12) | ciphertext + tag`. The magic is authenticated too, so any
 * change to the file makes it unreadable instead of silently wrong.
 */
class EncryptedFileVault(
    private val file: File,
    private val masterKey: MasterKeyProvider,
) : Vault {
    private val mutex = Mutex()
    private var cache: MutableMap<String, ByteArray>? = null
    private val random = SecureRandom()

    override suspend fun add(value: ByteArray): String = withEntries { entries ->
        val id = UUID.randomUUID().toString()
        entries[id] = value.copyOf()
        write(entries)
        id
    }

    override suspend fun get(id: String): ByteArray? = withEntries { it[id]?.copyOf() }

    override suspend fun remove(id: String) = withEntries { entries ->
        val removed = entries.remove(id) ?: return@withEntries
        removed.fill(0)
        write(entries)
    }

    private suspend fun <T> withEntries(block: (MutableMap<String, ByteArray>) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val entries = cache ?: read().also { cache = it }
            block(entries)
        }
    }

    private fun key(): SecretKeySpec {
        val bytes = masterKey.load(createIfMissing = !file.exists())
        if (bytes.size != KEY_BYTES) throw VaultException("Master key must be $KEY_BYTES bytes, got ${bytes.size}")
        return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
    }

    private fun read(): MutableMap<String, ByteArray> {
        if (!file.exists()) return mutableMapOf()
        val data = file.readBytes()
        if (data.size < MAGIC.size + NONCE_BYTES || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw VaultException("${file.name} isn't a vault file")
        }
        val plain = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, MAGIC.size, NONCE_BYTES))
                updateAAD(MAGIC)
                doFinal(data, MAGIC.size + NONCE_BYTES, data.size - MAGIC.size - NONCE_BYTES)
            }
        } catch (e: GeneralSecurityException) {
            throw VaultException("Can't decrypt ${file.name}: wrong key or damaged file", e)
        }
        return try {
            decode(plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun write(entries: Map<String, ByteArray>) {
        val plain = encode(entries)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val sealed = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(MAGIC)
                doFinal(plain)
            }
        } finally {
            plain.fill(0)
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(MAGIC + nonce + sealed)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun encode(entries: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(entries.size)
            for ((id, value) in entries) {
                out.writeUTF(id)
                out.writeInt(value.size)
                out.write(value)
            }
        }
        return buffer.toByteArray()
    }

    private fun decode(plain: ByteArray): MutableMap<String, ByteArray> =
        DataInputStream(ByteArrayInputStream(plain)).use { input ->
            val count = input.readInt()
            buildMap(count) {
                repeat(count) {
                    val id = input.readUTF()
                    val value = ByteArray(input.readInt())
                    input.readFully(value)
                    put(id, value)
                }
            }.toMutableMap()
        }

    private companion object {
        val MAGIC = byteArrayOf('E'.code.toByte(), 'T'.code.toByte(), 'V'.code.toByte(), 1)
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BYTES = 32
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
