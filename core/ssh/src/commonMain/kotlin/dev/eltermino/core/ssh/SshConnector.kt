package dev.eltermino.core.ssh

interface SshConnector {
    /**
     * Opens and authenticates a connection, hopping through [ConnectTarget.jumpHosts]
     * first. [hostKeyVerifier] is asked about every hop, outermost first.
     *
     * @throws SshException
     */
    suspend fun connect(
        target: ConnectTarget,
        hostKeyVerifier: HostKeyVerifier,
        secrets: SecretResolver,
    ): SshConnection
}

/** Turns a [SecretRef] into its bytes, normally by reading the vault. */
fun interface SecretResolver {
    suspend fun resolve(ref: SecretRef): ByteArray
}

sealed class SshException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectFailed(val host: String, val port: Int, cause: Throwable?) :
        SshException("Can't reach $host:$port", cause)

    class HostKeyRejected(val host: String, val port: Int, val fingerprint: HostKeyFingerprint) :
        SshException("Host key of $host:$port was rejected (${fingerprint.sha256})")

    /** The host doesn't meet a trusted CA's rules. [reason] is for the user. */
    class HostTrustRefused(val host: String, val port: Int, val reason: String) :
        SshException("Host $host:$port refused: $reason")

    class AuthFailed(val host: String, val username: String, cause: Throwable? = null) :
        SshException("Authentication as $username on $host failed", cause)

    class ChannelFailed(message: String, cause: Throwable? = null) : SshException(message, cause)
}
