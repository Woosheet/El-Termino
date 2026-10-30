package dev.eltermino.app

import dev.eltermino.core.vault.BackupCipher

internal actual fun sealBackup(plain: ByteArray, password: CharArray): ByteArray = BackupCipher.seal(plain, password)

internal actual fun openBackup(file: ByteArray, password: CharArray): ByteArray = BackupCipher.open(file, password)
