package dev.eltermino.app

import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpCertificateNameTest {
    private val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIBZTCCAQygAwIBAgIUUbkrNmiYwF/BTcZis70F7mZyI+wwCgYIKoZIzj0EAwIw
        GDEWMBQGA1UEAwwNYWx0cm8uZXhhbXBsZTAgFw0yNjEwMzAxNjM3MDRaGA8yMTI2
        MTAwNjE2MzcwNFowGDEWMBQGA1UEAwwNYWx0cm8uZXhhbXBsZTBZMBMGByqGSM49
        AgEGCCqGSM49AwEHA0IABIlbMb/hiwcS2QveWCWFO3eeziqQCzrygY09teGGOXY1
        U/YFvahlxHFW/aid2mqzk8dPWuaL4dD+59IOYILcOLWjMjAwMB0GA1UdDgQWBBTY
        7xzcSJ+EdlUGzME7/F7agCGs3zAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMC
        A0cAMEQCIAIhYaRVApuvBarIT3fgYeCL9IfL33QSdR7lSaitFYswAiB58cnajiQv
        aCJd8opM8jRh9PDM1oSaEwrQOOdFkflSIQ==
        -----END CERTIFICATE-----
        """.trimIndent().byteInputStream(),
    ) as X509Certificate

    @Test
    fun aCertificateForAnotherNameCanBeAcceptedByHand() {
        val refused = assertFailsWith<IOException> { checkAcceptedName(certificate, emptySet()) }
        val offered = assertIs<UntrustedCertificate>(refused.cause)
        assertEquals("il certificato è per un altro nome", offered.reason)
        assertEquals(sha256Hex(certificate.encoded), offered.sha256)

        assertTrue(checkAcceptedName(certificate, setOf(offered.sha256)))
    }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
