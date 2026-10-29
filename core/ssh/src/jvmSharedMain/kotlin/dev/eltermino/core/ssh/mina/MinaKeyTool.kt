package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.GeneratedKey
import dev.eltermino.core.ssh.InvalidKeyException
import dev.eltermino.core.ssh.KeyInfo
import dev.eltermino.core.ssh.SshKeyTool
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.putty.PuttyKeyUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair

class MinaKeyTool : SshKeyTool {
    override fun inspect(privateKey: ByteArray, passphrase: ByteArray?): KeyInfo {
        val keyPair = loadKeyPair(privateKey, passphrase)
        return keyPair.toKeyInfo(comment = null)
    }

    override fun isEncrypted(privateKey: ByteArray): Boolean = try {
        loadKeyPair(privateKey, passwordProvider = { _, _, _ -> throw PassphraseRequested() })
        false
    } catch (e: InvalidKeyException) {
        e.cause.causes().any { it is PassphraseRequested }
    }

    override fun generateEd25519(comment: String): GeneratedKey {
        val keyPair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256)
        val privateKey = ByteArrayOutputStream().use { out ->
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, comment, null, out)
            out.toByteArray()
        }
        return GeneratedKey(privateKey, keyPair.toKeyInfo(comment))
    }

    private class PassphraseRequested : RuntimeException()
}

/**
 * Parses an OpenSSH, PEM or PuTTY private key. The passphrase, if any, is only turned
 * into a String because MINA's API wants one.
 */
internal fun loadKeyPair(privateKey: ByteArray, passphrase: ByteArray?): KeyPair {
    val provider = if (passphrase == null) FilePasswordProvider.EMPTY
    else FilePasswordProvider { _, _, _ -> String(passphrase, Charsets.UTF_8) }
    return loadKeyPair(privateKey, provider)
}

private fun loadKeyPair(privateKey: ByteArray, passwordProvider: FilePasswordProvider): KeyPair {
    val resource = NamedResource.ofName("private key")
    val keys = try {
        val parser = if (String(privateKey, 0, minOf(privateKey.size, 64), Charsets.UTF_8).startsWith("PuTTY-User-Key-File-")) {
            PuttyKeyUtils.DEFAULT_INSTANCE
        } else {
            SecurityUtils.getKeyPairResourceParser()
        }
        parser.loadKeyPairs(null, resource, passwordProvider, ByteArrayInputStream(privateKey))
    } catch (e: Exception) {
        throw InvalidKeyException(e.message ?: "Unreadable private key", e)
    }
    return keys?.firstOrNull() ?: throw InvalidKeyException("No private key found, or the passphrase is missing")
}

internal fun KeyPair.toKeyInfo(comment: String?): KeyInfo {
    val line = PublicKeyEntry.toString(public)
    return KeyInfo(
        type = KeyUtils.getKeyType(public),
        publicKey = if (comment.isNullOrBlank()) line else "$line $comment",
        fingerprintSha256 = KeyUtils.getFingerPrint(BuiltinDigests.sha256, public),
    )
}

private fun Throwable?.causes(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }
