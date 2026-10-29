package dev.eltermino.app

import dev.eltermino.core.data.FileWorkspaceStore
import dev.eltermino.core.ssh.mina.MinaConfig
import dev.eltermino.core.ssh.mina.MinaKeyTool
import dev.eltermino.core.ssh.mina.MinaSshConnector
import dev.eltermino.core.vault.EncryptedFileVault
import dev.eltermino.core.vault.MasterKeyProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Servers and throwaway credentials from testenv/README.md. */
object TestEnv {
    val keys = File(System.getProperty("testenv.dir") ?: error("testenv.dir not set"), "keys")
    const val HOST = "127.0.0.1"
    const val BASIC_PORT = 2201
    const val OTP_PORT = 2202
    const val BASTION_PORT = 2203
    const val USER = "dev"
    const val PASSWORD = "devpass"
    const val KEY_PASSPHRASE = "testpass"

    fun key(name: String): File = File(keys, name).also { check(it.exists()) { "Missing $it, run testenv/gen-keys.sh" } }

    fun totp(): String {
        val secret = base32("JBSWY3DPEHPK3PXP")
        val counter = System.currentTimeMillis() / 1000 / 30
        val hash = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
            .doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        return ((ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff) % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        for (c in text) {
            buffer = (buffer shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += (buffer shr bits).toByte()
            }
        }
        return out.toByteArray()
    }
}

/** Reads absolute paths only, like a desktop with no ~/.ssh of interest. */
class TestPlatform : Platform {
    override val isDesktop = true
    override suspend fun pickFile(title: String): PickedFile? = null
    override fun readSshConfig(): String? = null
    override fun resolveSshInclude(pattern: String): List<Pair<String, String>> = emptyList()
    override fun readLocalFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()
}

fun testServices(dir: File = Files.createTempDirectory("eltermino").toFile().apply { deleteOnExit() }): AppServices {
    val key = Random.nextBytes(32)
    return AppServices(
        workspace = FileWorkspaceStore(File(dir, "workspace.json")),
        vault = EncryptedFileVault(File(dir, "vault.bin"), MasterKeyProvider { key.copyOf() }),
        connector = MinaSshConnector(MinaConfig()),
        keyTool = MinaKeyTool(),
        platform = TestPlatform(),
    )
}
