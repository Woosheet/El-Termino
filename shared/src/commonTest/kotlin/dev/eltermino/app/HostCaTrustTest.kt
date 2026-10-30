package dev.eltermino.app

import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.data.TrustedCa
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.ssh.HostCertificateInfo
import dev.eltermino.core.ssh.HostKeyFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HostCaTrustTest {
    private val now = 1_000_000L
    private val ca = TrustedCa(id = "c", label = "Azienda", publicKey = "ssh-ed25519 AAAA", fingerprintSha256 = "SHA256:ca", patterns = listOf("*.example.com"))
    private val host = "a.example.com"

    private fun certificate(
        ca: String = "SHA256:ca",
        principals: List<String> = listOf(host),
        after: Long = now - 10,
        before: Long = now + 10,
        signature: Boolean = true,
    ) = HostCertificateInfo("ssh-ed25519 AAAA", ca, principals, after, before, signature)

    private fun key(sha: String = "SHA256:key", certificate: HostCertificateInfo? = null) =
        HostKeyFingerprint("ssh-ed25519", sha, "ssh-ed25519 KEY", certificate)

    private fun pinned(sha: String = "SHA256:key") = KnownHost(host, 22, "ssh-ed25519", sha, "ssh-ed25519 KEY", 0)

    private fun covered(vararg pins: KnownHost) = Workspace(trustedCas = listOf(ca), knownHosts = pins.toList())

    @Test
    fun noCaBehavesAsToday() {
        assertIs<HostTrust.AskFirstUse>(Workspace().hostTrust(host, 22, key(), now))
        assertIs<HostTrust.Accepted>(Workspace(knownHosts = listOf(pinned())).hostTrust(host, 22, key(), now))
        assertIs<HostTrust.AskChanged>(Workspace(knownHosts = listOf(pinned("SHA256:old"))).hostTrust(host, 22, key(), now))
    }

    @Test
    fun certificateWithoutTrustedCaIsJustItsKey() {
        // Nobody asked to trust this CA: an expired certificate changes nothing.
        assertIs<HostTrust.AskFirstUse>(Workspace().hostTrust(host, 22, key(certificate = certificate(before = now - 1)), now))
    }

    @Test
    fun validCertificateAccepted() {
        assertIs<HostTrust.Accepted>(covered().hostTrust(host, 22, key(certificate = certificate()), now))
    }

    @Test
    fun validCertificateDifferentPinIsRenewed() {
        val trust = covered(pinned("SHA256:old")).hostTrust(host, 22, key(certificate = certificate()), now)
        assertEquals("SHA256:old", assertIs<HostTrust.AcceptedRenewed>(trust).previous.fingerprintSha256)
    }

    @Test
    fun expiredRefused() {
        assertIs<HostTrust.Refused>(covered().hostTrust(host, 22, key(certificate = certificate(before = now - 1)), now))
    }

    @Test
    fun expiredRefusedEvenWhenKeyIsPinned() {
        assertIs<HostTrust.Refused>(covered(pinned()).hostTrust(host, 22, key(certificate = certificate(before = now - 1)), now))
    }

    @Test
    fun notYetValidRefused() {
        assertIs<HostTrust.Refused>(covered().hostTrust(host, 22, key(certificate = certificate(after = now + 1)), now))
    }

    @Test
    fun badSignatureRefused() {
        assertIs<HostTrust.Refused>(covered().hostTrust(host, 22, key(certificate = certificate(signature = false)), now))
    }

    @Test
    fun wrongPrincipalRefused() {
        assertIs<HostTrust.Refused>(covered().hostTrust(host, 22, key(certificate = certificate(principals = listOf("b.example.com"))), now))
    }

    @Test
    fun noPrincipalsRefused() {
        assertIs<HostTrust.Refused>(covered().hostTrust(host, 22, key(certificate = certificate(principals = emptyList())), now))
    }

    @Test
    fun unknownCaOnCoveredHostRefused() {
        assertIs<HostTrust.Refused>(covered().hostTrust(host, 22, key(certificate = certificate(ca = "SHA256:other")), now))
    }

    @Test
    fun plainKeyOnCoveredHostRefusedUnlessPinned() {
        assertIs<HostTrust.Refused>(covered().hostTrust(host, 22, key(), now))
        assertIs<HostTrust.Accepted>(covered(pinned()).hostTrust(host, 22, key(), now))
        assertIs<HostTrust.Refused>(covered(pinned("SHA256:old")).hostTrust(host, 22, key(), now))
    }

    @Test
    fun removingCaFallsBackToFirstUse() {
        val workspace = covered()
        assertIs<HostTrust.Accepted>(workspace.hostTrust(host, 22, key(certificate = certificate()), now))
        assertIs<HostTrust.AskFirstUse>(workspace.copy(trustedCas = emptyList()).hostTrust(host, 22, key(certificate = certificate()), now))
    }

    @Test
    fun patternsMatchLikeOpenSsh() {
        assertTrue(hostPatternMatches("*.example.com", "a.example.com", 22))
        assertFalse(hostPatternMatches("*.example.com", "example.com", 22))
        assertTrue(hostPatternMatches("A.Example.com", "a.example.COM", 22))
        assertTrue(hostPatternMatches("[10.0.0.1]:2201", "10.0.0.1", 2201))
        assertFalse(hostPatternMatches("[10.0.0.1]:2201", "10.0.0.1", 22))
        // As in known_hosts, a bare name means port 22.
        assertFalse(hostPatternMatches("10.0.0.1", "10.0.0.1", 2201))
        assertTrue(hostPatternMatches("10.0.0.?", "10.0.0.1", 22))
        assertFalse(hostPatternMatches("a.b", "aXb", 22))
    }

    @Test
    fun negatedPatternExcludesHost() {
        val mixed = ca.copy(patterns = listOf("*.example.com", "!secret.example.com"))
        assertTrue(mixed.covers("a.example.com", 22))
        assertFalse(mixed.covers("secret.example.com", 22))
    }
}
