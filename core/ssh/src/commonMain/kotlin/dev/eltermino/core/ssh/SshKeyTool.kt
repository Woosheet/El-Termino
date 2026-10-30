package dev.eltermino.core.ssh

/** Reads and creates private keys without the rest of the app knowing key formats. */
interface SshKeyTool {
    /**
     * Parses an OpenSSH, PEM or PuTTY `.ppk` private key.
     *
     * @throws InvalidKeyException when the text isn't a supported key, or the
     *   passphrase is missing or wrong.
     */
    fun inspect(privateKey: ByteArray, passphrase: ByteArray? = null): KeyInfo

    /** True when [privateKey] can't be read without a passphrase. */
    fun isEncrypted(privateKey: ByteArray): Boolean

    fun generateEd25519(comment: String): GeneratedKey

    /**
     * The `SHA256:` fingerprint of a public key in `authorized_keys` form.
     *
     * @throws InvalidKeyException when the text isn't a public key.
     */
    fun publicKeyFingerprint(publicKey: String): String
}

data class KeyInfo(
    /** For example `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`. */
    val type: String,
    /** One `authorized_keys` line. */
    val publicKey: String,
    val fingerprintSha256: String,
)

class GeneratedKey(
    /** OpenSSH private key format, unencrypted. */
    val privateKey: ByteArray,
    val info: KeyInfo,
)

class InvalidKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
