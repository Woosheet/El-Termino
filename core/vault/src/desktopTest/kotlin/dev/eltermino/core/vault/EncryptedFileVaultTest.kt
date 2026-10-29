package dev.eltermino.core.vault

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EncryptedFileVaultTest {
    private val dir = Files.createTempDirectory("vault").toFile().apply { deleteOnExit() }
    private val file = dir.resolve("vault.bin")

    /** Stands in for the system keychain. */
    private class MemoryKey(private var key: ByteArray? = null) : MasterKeyProvider {
        var createdKeys = 0
        override fun load(createIfMissing: Boolean): ByteArray {
            key?.let { return it.copyOf() }
            if (!createIfMissing) throw VaultException("missing")
            createdKeys++
            return Random.nextBytes(32).also { key = it }.copyOf()
        }
    }

    @Test
    fun roundTripAndPersistence() = runTest {
        val key = MemoryKey()
        val vault = EncryptedFileVault(file, key)
        val id = vault.add("devpass".encodeToByteArray())
        assertContentEquals("devpass".encodeToByteArray(), vault.get(id))

        val reopened = EncryptedFileVault(file, key)
        assertContentEquals("devpass".encodeToByteArray(), reopened.get(id))
        assertEquals(1, key.createdKeys)
    }

    @Test
    fun secretsNeverAppearInTheFile() = runTest {
        EncryptedFileVault(file, MemoryKey()).add("super-secret-password".encodeToByteArray())
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("super-secret"))
    }

    @Test
    fun removeDeletesTheEntry() = runTest {
        val key = MemoryKey()
        val vault = EncryptedFileVault(file, key)
        val id = vault.add(byteArrayOf(1, 2, 3))
        vault.remove(id)
        assertNull(EncryptedFileVault(file, key).get(id))
    }

    @Test
    fun returnedCopiesCanBeWiped() = runTest {
        val vault = EncryptedFileVault(file, MemoryKey())
        val id = vault.add(byteArrayOf(9, 9))
        vault.get(id)!!.fill(0)
        assertContentEquals(byteArrayOf(9, 9), vault.get(id))
    }

    @Test
    fun tamperedFileIsRejected() = runTest {
        val key = MemoryKey()
        EncryptedFileVault(file, key).add(byteArrayOf(1))
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<VaultException> { EncryptedFileVault(file, key).get("x") }
    }

    @Test
    fun wrongKeyIsRejected() = runTest {
        EncryptedFileVault(file, MemoryKey()).add(byteArrayOf(1))
        assertFailsWith<VaultException> { EncryptedFileVault(file, MemoryKey(Random.nextBytes(32))).get("x") }
    }

    @Test
    fun existingVaultNeverGetsANewKey() = runTest {
        EncryptedFileVault(file, MemoryKey()).add(byteArrayOf(1))
        val lostKey = MemoryKey()
        assertFailsWith<VaultException> { EncryptedFileVault(file, lostKey).get("x") }
        assertEquals(0, lostKey.createdKeys)
    }
}
