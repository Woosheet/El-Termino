package dev.eltermino.core.vault

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PasswordMasterKeyProviderTest {
    private val dir = Files.createTempDirectory("vault-pw").toFile().apply { deleteOnExit() }
    private val salt = dir.resolve("vault.salt")
    private val vaultFile = dir.resolve("vault.bin")

    private fun provider(password: String?) = PasswordMasterKeyProvider(salt) { password?.toCharArray() }

    @Test
    fun sameSaltAndPasswordGiveSameKey() {
        val first = provider("una password").load(createIfMissing = true)
        assertContentEquals(first, provider("una password").load(createIfMissing = false))
        assertEquals(32, first.size)
    }

    @Test
    fun missingSaltWithoutCreateThrows() {
        assertFailsWith<VaultException> { provider("x").load(createIfMissing = false) }
        assertFalse(salt.exists())
    }

    @Test
    fun cancelThrowsVaultException() {
        assertFailsWith<VaultException> { provider(null).load(createIfMissing = true) }
        assertFalse(salt.exists(), "a cancelled first run must not leave a salt behind")
    }

    @Test
    fun wrongPasswordOpensNothing() = runTest {
        val vault = EncryptedFileVault(vaultFile, provider("giusta"))
        vault.add(byteArrayOf(1, 2, 3))
        val before = vaultFile.readBytes()
        val reopened = EncryptedFileVault(vaultFile, provider("sbagliata"))
        assertFailsWith<VaultException> { reopened.get("qualunque") }
        assertContentEquals(before, vaultFile.readBytes())
    }

    @Test
    fun saltFileHoldsNoKeyMaterial() {
        val key = provider("una password").load(createIfMissing = true)
        assertEquals(16, salt.length().toInt())
        assertFalse(salt.readBytes().contentEquals(key.copyOf(16)))
    }

    @Test
    fun firstTimeIsToldApart() {
        val seen = mutableListOf<Boolean>()
        fun open() = PasswordMasterKeyProvider(salt) { first -> seen += first; "pw".toCharArray() }
        open().load(createIfMissing = true)
        open().load(createIfMissing = false)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun asksOnlyOnce() = runTest {
        // The vault reloads its key on every write: each one must not mean a dialog and Argon2.
        var asked = 0
        val vault = EncryptedFileVault(vaultFile, PasswordMasterKeyProvider(salt) { asked++; "pw".toCharArray() })
        vault.add(byteArrayOf(1))
        vault.add(byteArrayOf(2))
        assertEquals(1, asked)
    }

    @Test
    fun chooseFallsBackOnlyWhenSafe() {
        assertEquals(MasterKeySource.Password, chooseMasterKeySource(keychainWorks = false, vaultExists = false, saltExists = false))
        assertEquals(MasterKeySource.Password, chooseMasterKeySource(keychainWorks = false, vaultExists = true, saltExists = true))
        assertEquals(MasterKeySource.Keychain, chooseMasterKeySource(keychainWorks = true, vaultExists = true, saltExists = false))
        // A vault made with the keychain must stay locked rather than get a new key.
        assertEquals(MasterKeySource.Keychain, chooseMasterKeySource(keychainWorks = false, vaultExists = true, saltExists = false))
        // A password vault stays one even once a keychain shows up.
        assertEquals(MasterKeySource.Password, chooseMasterKeySource(keychainWorks = true, vaultExists = true, saltExists = true))
    }
}
