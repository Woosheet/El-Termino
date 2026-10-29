package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.InvalidKeyException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinaKeyToolTest {
    private val tool = MinaKeyTool()

    @ParameterizedTest
    @CsvSource(
        "id_ed25519, ssh-ed25519, false",
        "id_rsa, ssh-rsa, false",
        "id_ecdsa, ecdsa-sha2-nistp256, false",
        "id_cert, ssh-ed25519, false",
        "id_ed25519_enc, ssh-ed25519, true",
    )
    fun inspectsTestenvKeys(name: String, type: String, encrypted: Boolean) {
        val bytes = TestServers.keyFile(name).readBytes()
        assertEquals(encrypted, tool.isEncrypted(bytes))
        val info = tool.inspect(bytes, if (encrypted) TestServers.KEY_PASSPHRASE.toByteArray() else null)
        assertEquals(type, info.type)
        assertEquals(sshKeygenFingerprint(TestServers.keyFile("$name.pub")), info.fingerprintSha256)
        assertTrue(TestServers.keyFile("$name.pub").readText().startsWith(info.publicKey))
    }

    @Test
    fun encryptedKeyNeedsTheRightPassphrase() {
        val bytes = TestServers.keyFile("id_ed25519_enc").readBytes()
        assertFailsWith<InvalidKeyException> { tool.inspect(bytes) }
        assertFailsWith<InvalidKeyException> { tool.inspect(bytes, "wrong".toByteArray()) }
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<InvalidKeyException> { tool.inspect("not a key".toByteArray()) }
        assertFalse(tool.isEncrypted("not a key".toByteArray()))
    }

    @Test
    fun generatedKeyRoundTrips() {
        val generated = tool.generateEd25519("me@laptop")
        assertEquals("ssh-ed25519", generated.info.type)
        assertTrue(generated.info.publicKey.endsWith(" me@laptop"))
        assertFalse(tool.isEncrypted(generated.privateKey))
        val reread = tool.inspect(generated.privateKey)
        assertEquals(generated.info.fingerprintSha256, reread.fingerprintSha256)

        val pub = Files.createTempFile("gen", ".pub").toFile().apply { writeText(generated.info.publicKey + "\n"); deleteOnExit() }
        assertEquals(sshKeygenFingerprint(pub), generated.info.fingerprintSha256)
    }

    @Test
    fun puttyKey() {
        val puttygen = listOf("/opt/homebrew/bin/puttygen", "/usr/local/bin/puttygen", "/usr/bin/puttygen").firstOrNull { java.io.File(it).canExecute() }
        assumeTrue(puttygen != null, "puttygen is not installed, .ppk parsing not exercised")
        val ppk = Files.createTempFile("key", ".ppk").toFile().apply { deleteOnExit() }
        val process = ProcessBuilder(puttygen, TestServers.keyFile("id_ed25519").path, "-O", "private", "-o", ppk.path).redirectErrorStream(true).start()
        assertEquals(0, process.waitFor())
        val info = tool.inspect(ppk.readBytes())
        assertEquals(sshKeygenFingerprint(TestServers.keyFile("id_ed25519.pub")), info.fingerprintSha256)
    }
}
