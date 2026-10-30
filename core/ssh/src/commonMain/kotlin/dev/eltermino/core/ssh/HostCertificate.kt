package dev.eltermino.core.ssh

data class HostCertificateInfo(
    /** The CA's key in `authorized_keys` form. */
    val caPublicKey: String,
    val caFingerprint: String,
    val principals: List<String>,
    val validAfterEpoch: Long,
    /** [Long.MAX_VALUE] when it never expires. */
    val validBeforeEpoch: Long,
    /** A host certificate, with no critical options, that its CA's key really signed. */
    val signatureValid: Boolean,
)
