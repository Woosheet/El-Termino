package dev.eltermino.spikes

import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Addresses and throwaway credentials of the servers in testenv/ (see testenv/README.md). */
object TestEnv {
    val dir = File(System.getProperty("testenv.dir") ?: error("testenv.dir system property not set"))
    val keys = File(dir, "keys")

    const val HOST = "127.0.0.1"
    const val BASIC_PORT = 2201
    const val OTP_PORT = 2202
    const val BASTION_PORT = 2203
    const val INTERNAL_HOST = "internal"
    const val INTERNAL_PORT = 22

    const val USER = "dev"
    const val PASSWORD = "devpass"
    const val KEY_PASSPHRASE = "testpass"
    const val TOTP_SECRET = "JBSWY3DPEHPK3PXP"

    fun key(name: String): File = File(keys, name).also { check(it.exists()) { "Missing $it, run testenv/gen-keys.sh" } }

    /** RFC 6238 code for [TOTP_SECRET], as the PAM module on the "otp" server expects it. */
    fun totp(epochMillis: Long = System.currentTimeMillis()): String {
        val counter = epochMillis / 1000 / 30
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(base32(TOTP_SECRET), "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        for (c in text.uppercase()) {
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

/** Prints one line of the comparison table; collect with `grep '\[SPIKE\]'` on the test output. */
fun spikeResult(library: String, check: String, outcome: String, details: String = "") {
    println("[SPIKE] $library | $check | $outcome | $details")
}
