package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.HostKeyFingerprint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.OpenSshCertificate
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.future.Cancellable
import org.apache.sshd.common.future.SshFuture
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
 * Host certificates are reduced to the key they certify: Phase 1 trusts keys on first
 * use, and a renewed certificate must not look like a changed host.
 */
internal fun PublicKey.toHostKeyFingerprint(): HostKeyFingerprint {
    val key = (this as? OpenSshCertificate)?.certPubKey ?: this
    return HostKeyFingerprint(
        keyType = KeyUtils.getKeyType(key),
        sha256 = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key),
        publicKey = PublicKeyEntry.toString(key),
    )
}
