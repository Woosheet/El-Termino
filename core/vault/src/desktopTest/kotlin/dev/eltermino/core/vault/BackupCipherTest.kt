package dev.eltermino.core.vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BackupCipherTest {
    private val plain = "host e segreti".encodeToByteArray()
    private val password = "correct horse".toCharArray()

    @Test
    fun roundTrip() {
        assertContentEquals(plain, BackupCipher.open(BackupCipher.seal(plain, password), password))
    }

    @Test
    fun wrongPasswordThrows() {
        val sealed = BackupCipher.seal(plain, password)
        assertFailsWith<VaultException> { BackupCipher.open(sealed, "other".toCharArray()) }
    }

    @Test
    fun everyFlippedByteThrows() {
        val sealed = BackupCipher.seal(plain, password)
        // Skips the cost fields, tested on their own: a larger one only makes opening slow.
        val positions = listOf(0, 3, 16, 20, 32, 40, sealed.size - 1)
        for (position in positions) {
            val damaged = sealed.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertFailsWith<VaultException>("byte $position") { BackupCipher.open(damaged, password) }
        }
    }

    @Test
    fun unknownFormatVersionThrows() {
        val sealed = BackupCipher.seal(plain, password).also { it[3] = 99 }
        assertFailsWith<VaultException> { BackupCipher.open(sealed, password) }
    }

    @Test
    fun absurdCostInHeaderIsRefusedBeforeDerivingTheKey() {
        // A planted file must not be able to make the app allocate gigabytes.
        val sealed = BackupCipher.seal(plain, password).also { it[4] = 0x7f }
        assertFailsWith<VaultException> { BackupCipher.open(sealed, password) }
    }

    @Test
    fun truncatedFileThrows() {
        assertFailsWith<VaultException> { BackupCipher.open(ByteArray(10), password) }
    }

    @Test
    fun sealTwiceDiffers() {
        assertFalse(BackupCipher.seal(plain, password).contentEquals(BackupCipher.seal(plain, password)))
    }

    @Test
    fun aFileAskingForTooMuchMemoryIsRefused() {
        val file = BackupCipher.seal(ByteArray(4), "password".toCharArray())
        java.nio.ByteBuffer.wrap(file).putInt(4, 300_000)
        val error = kotlin.test.assertFailsWith<VaultException> { BackupCipher.open(file, "password".toCharArray()) }
        kotlin.test.assertTrue("unreasonable" in error.message.orEmpty(), error.message)
    }

    @Test
    fun aNewerFormatSaysSo() {
        val file = BackupCipher.seal(ByteArray(4), "password".toCharArray())
        file[3] = 2
        kotlin.test.assertFailsWith<BackupTooNewException> { BackupCipher.open(file, "password".toCharArray()) }
    }
}
