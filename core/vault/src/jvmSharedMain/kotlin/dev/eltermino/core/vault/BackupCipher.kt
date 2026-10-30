package dev.eltermino.core.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backups, readable on any platform:
 * `"ETB" | version (1) | memory KiB (4) | iterations (4) | parallelism (4) | salt (16) |
 * nonce (12) | ciphertext + tag`. The whole header is authenticated.
 */
object BackupCipher {
    private val MAGIC = "ETB".encodeToByteArray()
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_BYTES = 4 + 12 + SALT_BYTES + NONCE_BYTES
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun seal(plain: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(VERSION)
            .putInt(ARGON2_MEMORY_KIB).putInt(ARGON2_ITERATIONS).putInt(ARGON2_PARALLELISM)
            .put(salt).put(nonce)
            .array()
        val key = deriveKey(password, salt, ARGON2_MEMORY_KIB, ARGON2_ITERATIONS, ARGON2_PARALLELISM)
        try {
            val sealed = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(header)
                doFinal(plain)
            }
            return header + sealed
        } finally {
            key.fill(0)
        }
    }

    /** @throws VaultException for a wrong password or a damaged or foreign file, which GCM can't tell apart. */
    fun open(file: ByteArray, password: CharArray): ByteArray {
        if (file.size < HEADER_BYTES || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw VaultException("Not an El Termino backup")
        }
        if (file[MAGIC.size] != VERSION) throw BackupTooNewException("Backup format ${file[MAGIC.size]} isn't supported")
        val header = ByteBuffer.wrap(file, 4, HEADER_BYTES - 4)
        val memory = header.getInt()
        val iterations = header.getInt()
        val parallelism = header.getInt()
        // The header comes from a file we don't trust: it must not set the cost freely.
        if (memory !in 8..MAX_MEMORY_KIB || iterations !in 1..MAX_ITERATIONS || parallelism !in 1..MAX_PARALLELISM) {
            throw VaultException("Backup asks for unreasonable key derivation settings")
        }
        val salt = ByteArray(SALT_BYTES).also { header.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { header.get(it) }
        val key = try {
            deriveKey(password, salt, memory, iterations, parallelism)
        } catch (e: OutOfMemoryError) {
            throw VaultException("Not enough memory to open this backup", e)
        }
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(file, 0, HEADER_BYTES)
                doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
            }
        } catch (e: GeneralSecurityException) {
            throw VaultException("Wrong password or damaged backup", e)
        } finally {
            key.fill(0)
        }
    }

    private const val MAX_MEMORY_KIB = 262_144
    private const val MAX_ITERATIONS = 16
    private const val MAX_PARALLELISM = 8
}

internal const val ARGON2_MEMORY_KIB = 65_536
internal const val ARGON2_ITERATIONS = 3
internal const val ARGON2_PARALLELISM = 1

/** Argon2id to a 256-bit key. */
internal fun deriveKey(password: CharArray, salt: ByteArray, memoryKib: Int, iterations: Int, parallelism: Int): ByteArray {
    val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(password))
    val passwordBytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
    encoded.array().fill(0)
    try {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build(),
        )
        return ByteArray(32).also { generator.generateBytes(passwordBytes, it) }
    } finally {
        passwordBytes.fill(0)
    }
}
