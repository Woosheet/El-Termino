package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.AuthMethod
import dev.eltermino.core.ssh.ConnectTarget
import dev.eltermino.core.ssh.HostKeyFingerprint
import dev.eltermino.core.ssh.HostKeyVerifier
import dev.eltermino.core.ssh.SecretRef
import dev.eltermino.core.ssh.SecretResolver
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The servers in testenv/ and their throwaway credentials (see testenv/README.md). */
object TestServers {
    private val keys = File(System.getProperty("testenv.dir") ?: error("testenv.dir not set"), "keys")

    const val HOST = "127.0.0.1"
    const val BASIC = 2201
    const val OTP = 2202
    const val BASTION = 2203
    const val INTERNAL_HOST = "internal"
    const val USER = "dev"
    const val PASSWORD = "devpass"
    const val KEY_PASSPHRASE = "testpass"
    private const val TOTP_SECRET = "JBSWY3DPEHPK3PXP"

    fun keyFile(name: String): File = File(keys, name).also { check(it.exists()) { "Missing $it, run testenv/gen-keys.sh" } }

    fun keyRef(name: String) = SecretRef("file:$name")

    fun basic(vararg auth: AuthMethod) = ConnectTarget(HOST, BASIC, USER, auth.toList())

    fun passwordAuth() = AuthMethod.Password(SecretRef("password"))

    fun keyAuth(name: String, passphrase: Boolean = false, certificate: String? = null) =
        AuthMethod.PublicKey(keyRef(name), if (passphrase) SecretRef("passphrase") else null, certificate)

    fun totp(epochMillis: Long = System.currentTimeMillis()): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(base32(TOTP_SECRET), "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(epochMillis / 1000 / 30).array())
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

/** Serves the test password, passphrase and key files, and counts how often it's asked. */
class TestSecrets(private val extra: Map<String, ByteArray> = emptyMap()) : SecretResolver {
    val calls = AtomicInteger()

    override suspend fun resolve(ref: SecretRef): ByteArray {
        calls.incrementAndGet()
        extra[ref.id]?.let { return it.copyOf() }
        return when {
            ref.id == "password" -> TestServers.PASSWORD.toByteArray()
            ref.id == "passphrase" -> TestServers.KEY_PASSPHRASE.toByteArray()
            ref.id.startsWith("file:") -> TestServers.keyFile(ref.id.removePrefix("file:")).readBytes()
            else -> error("Unknown secret ${ref.id}")
        }
    }
}

class RecordingVerifier(private val accept: Boolean = true) : HostKeyVerifier {
    val seen = CopyOnWriteArrayList<Triple<String, Int, HostKeyFingerprint>>()

    override suspend fun verify(host: String, port: Int, fingerprint: HostKeyFingerprint): Boolean {
        seen += Triple(host, port, fingerprint)
        return accept
    }
}
