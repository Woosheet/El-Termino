package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.HostCertificateInfo
import dev.eltermino.core.ssh.HostKeyFingerprint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.kex.DHGClient
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.OpenSshCertificate
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.future.Cancellable
import org.apache.sshd.common.future.SshFuture
import org.apache.sshd.common.kex.DHFactory
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.signature.BuiltinSignatures
import java.security.PublicKey
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Suspends until the future completes, without parking a thread. Cancelling cancels the future. */
internal suspend fun <F : SshFuture<F>> F.awaitDone(timeout: Duration): F = withTimeout(timeout) {
    suspendCancellableCoroutine { continuation ->
        addListener { continuation.resume(it) }
        continuation.invokeOnCancellation { (this@awaitDone as? Cancellable)?.cancel() }
    }
}

/**
 * Closes gracefully and returns once MINA reports the close as complete. A peer that
 * doesn't answer within [graceTimeout] gets an immediate close instead.
 */
internal suspend fun org.apache.sshd.common.Closeable.closeAndAwait(graceTimeout: Duration = 5.seconds) {
    if (isClosed) return
    val done = withTimeoutOrNull(graceTimeout) {
        suspendCancellableCoroutine { continuation ->
            close(false).addListener { continuation.resume(Unit) }
        }
    }
    if (done == null) close(true)
}

/**
 * A certificate is reduced to the key it certifies, so that a renewed certificate doesn't look
 * like a changed host, and its details travel beside it for the host key check to judge.
 */
internal fun PublicKey.toHostKeyFingerprint(): HostKeyFingerprint {
    val certificate = this as? OpenSshCertificate
    val key = certificate?.certPubKey ?: this
    return HostKeyFingerprint(
        keyType = KeyUtils.getKeyType(key),
        sha256 = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key),
        publicKey = PublicKeyEntry.toString(key),
        certificate = certificate?.toInfo(),
    )
}

private fun OpenSshCertificate.toInfo() = HostCertificateInfo(
    caPublicKey = PublicKeyEntry.toString(caPubKey),
    caFingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, caPubKey),
    principals = principals.toList(),
    validAfterEpoch = validAfter.unsignedEpoch(),
    validBeforeEpoch = validBefore.unsignedEpoch(),
    signatureValid = isWellFormedHostCertificate(),
)

/** The format's times are unsigned: "forever" reads as a negative long. */
private fun Long.unsignedEpoch() = if (this < 0) Long.MAX_VALUE else this

/** SHA-1 RSA, which OpenSSH stopped accepting by default. */
internal fun caSignatureAllowed(algorithm: String): Boolean = algorithm != "ssh-rsa"

private fun OpenSshCertificate.isWellFormedHostCertificate(): Boolean {
    if (type != OpenSshCertificate.Type.HOST || criticalOptions.isNotEmpty() || !caSignatureAllowed(signatureAlgorithm)) return false
    val factory = BuiltinSignatures.fromFactoryName(signatureAlgorithm)?.takeIf { it.isSupported } ?: return false
    return runCatching {
        factory.create().run {
            initVerifier(null, caPubKey)
            update(null, message)
            verify(null, rawSignature)
        }
    }.getOrDefault(false)
}

/**
 * Keeps every host certificate for [toHostKeyFingerprint]. MINA's own check would drop a bad one
 * unseen, and it compares principals with the address it dialled, which behind a jump host is
 * the local end of a tunnel. The host key check does it all, knowing the real name.
 */
internal class CertificateKeepingClient(factory: DHFactory, session: Session) : DHGClient(factory, session) {
    override fun verifyCertificate(session: Session, openSshKey: OpenSshCertificate) = Unit
}

internal val keepCertificates = java.util.function.Function<DHFactory, KeyExchangeFactory> { factory ->
    if (factory.isGroupExchange) return@Function ClientBuilder.DH2KEX.apply(factory)
    object : KeyExchangeFactory {
        override fun getName(): String = factory.name
        override fun createKeyExchange(session: Session) = CertificateKeepingClient(factory, session)
    }
}
