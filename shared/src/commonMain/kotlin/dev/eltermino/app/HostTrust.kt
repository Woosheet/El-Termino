package dev.eltermino.app

import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.data.KnownHostStatus
import dev.eltermino.core.data.TrustedCa
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.data.knownHostStatus
import dev.eltermino.core.ssh.HostCertificateInfo
import dev.eltermino.core.ssh.HostKeyFingerprint

/** What to do with the key a server just presented. */
internal sealed interface HostTrust {
    data object Accepted : HostTrust

    /** A trusted CA vouches for a key other than the one we had: the old one gets replaced. */
    data class AcceptedRenewed(val previous: KnownHost) : HostTrust

    /** No way past this one: a CA covers the host and the host doesn't pass. */
    data class Refused(val reason: String) : HostTrust

    data object AskFirstUse : HostTrust

    data class AskChanged(val previous: KnownHost) : HostTrust
}

internal fun Workspace.hostTrust(host: String, port: Int, fingerprint: HostKeyFingerprint, nowEpoch: Long): HostTrust {
    val status = knownHostStatus(host, port, fingerprint.keyType, fingerprint.sha256)
    val covering = trustedCas.filter { it.covers(host, port) }
    if (covering.isEmpty()) {
        return when (status) {
            KnownHostStatus.Trusted -> HostTrust.Accepted
            KnownHostStatus.Unknown -> HostTrust.AskFirstUse
            is KnownHostStatus.Changed -> HostTrust.AskChanged(status.previous)
        }
    }
    // A plain key must not be a way around the CA, but one already trusted by hand stays trusted.
    val certificate = fingerprint.certificate
        ?: return if (status == KnownHostStatus.Trusted) HostTrust.Accepted else HostTrust.Refused("il server non presenta un certificato")
    if (covering.none { it.fingerprintSha256 == certificate.caFingerprint }) {
        return HostTrust.Refused("il certificato non è firmato da una CA fidata per questo host")
    }
    certificateProblem(certificate, host, nowEpoch)?.let { return HostTrust.Refused(it) }
    return if (status is KnownHostStatus.Changed) HostTrust.AcceptedRenewed(status.previous) else HostTrust.Accepted
}

private fun certificateProblem(certificate: HostCertificateInfo, host: String, nowEpoch: Long): String? = when {
    !certificate.signatureValid -> "il certificato non è valido"
    nowEpoch < certificate.validAfterEpoch -> "il certificato non è ancora valido"
    nowEpoch >= certificate.validBeforeEpoch -> "il certificato è scaduto"
    certificate.principals.none { it.equals(host, ignoreCase = true) } -> "il certificato è per un altro nome"
    else -> null
}

/** Negated patterns win, as in `known_hosts`. */
fun TrustedCa.covers(host: String, port: Int): Boolean {
    val (negated, plain) = patterns.partition { it.startsWith("!") }
    return plain.any { hostPatternMatches(it, host, port) } && negated.none { hostPatternMatches(it.drop(1), host, port) }
}

/** `*` and `?` as in `known_hosts`, where a host on another port than 22 reads `[host]:port`. */
fun hostPatternMatches(pattern: String, host: String, port: Int): Boolean {
    val name = (if (port == 22) host else "[$host]:$port").lowercase()
    val regex = pattern.lowercase().split('*').joinToString(".*") { part ->
        part.split('?').joinToString(".") { Regex.escape(it) }
    }
    return Regex(regex).matches(name)
}
