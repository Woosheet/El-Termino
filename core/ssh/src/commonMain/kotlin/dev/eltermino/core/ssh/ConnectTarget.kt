package dev.eltermino.core.ssh

data class ConnectTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: List<AuthMethod>,
    /** Hosts to hop through, outermost first, as with OpenSSH `ProxyJump`. */
    val jumpHosts: List<ConnectTarget> = emptyList(),
    /** Lets this host use the local SSH agent. Never applies to the jump hosts. */
    val agentForwarding: Boolean = false,
)

/**
 * The methods are tried in order until the server accepts one, so a host can list,
 * say, its key first and a password as fallback.
 */
sealed interface AuthMethod {
    data class Password(val password: SecretRef) : AuthMethod

    data class PublicKey(
        val privateKey: SecretRef,
        val passphrase: SecretRef? = null,
        /** OpenSSH `-cert.pub` contents, when the key is signed by a CA. */
        val certificate: String? = null,
    ) : AuthMethod

    data class KeyboardInteractive(val responder: KeyboardInteractiveResponder) : AuthMethod

    data object Agent : AuthMethod
}

fun interface KeyboardInteractiveResponder {
    suspend fun respond(name: String, instruction: String, prompts: List<Prompt>): List<String>

    data class Prompt(val text: String, val echo: Boolean)
}

/** Pointer into the vault; the secret itself never lives in models or the database. */
data class SecretRef(val id: String)

data class HostKeyFingerprint(
    val keyType: String,
    /** `SHA256:` followed by unpadded base64, as `ssh-keygen -l` prints it. */
    val sha256: String,
    /** The key in `authorized_keys` form, so a known-hosts store can keep it. */
    val publicKey: String,
    /** Set when the server presented a certificate. Nothing about it has been judged yet. */
    val certificate: HostCertificateInfo? = null,
)

fun interface HostKeyVerifier {
    suspend fun verify(host: String, port: Int, fingerprint: HostKeyFingerprint): Boolean
}
