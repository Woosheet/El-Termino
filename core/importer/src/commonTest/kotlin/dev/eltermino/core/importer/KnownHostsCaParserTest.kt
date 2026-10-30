package dev.eltermino.core.importer

import kotlin.test.Test
import kotlin.test.assertEquals

class KnownHostsCaParserTest {
    @Test
    fun readsCertAuthorityLines() {
        val parsed = parseCertAuthorities("@cert-authority *.example.com,[10.0.0.1]:2201 ssh-ed25519 AAAAC3Nza azienda ca\n")
        assertEquals(listOf(ParsedCa(listOf("*.example.com", "[10.0.0.1]:2201"), "ssh-ed25519 AAAAC3Nza", "azienda ca")), parsed)
    }

    @Test
    fun commentIsOptional() {
        assertEquals("", parseCertAuthorities("@cert-authority * ssh-rsa AAAA").single().comment)
    }

    @Test
    fun ignoresPlainAndCommentLines() {
        val text = """
            # @cert-authority * ssh-ed25519 AAAA
            example.com ssh-ed25519 AAAA
            @revoked * ssh-ed25519 AAAA

        """.trimIndent()
        assertEquals(emptyList(), parseCertAuthorities(text))
    }

    @Test
    fun ignoresMalformedKey() {
        assertEquals(emptyList(), parseCertAuthorities("@cert-authority *\n@cert-authority * ssh-ed25519"))
    }
}
