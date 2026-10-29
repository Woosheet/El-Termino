package dev.eltermino.core.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshConfigParserTest {
    private fun parse(text: String, include: (String) -> List<IncludedFile> = { emptyList() }) =
        SshConfigParser.parse(text.trimIndent(), include)

    private fun ImportResult.host(label: String) = hosts.single { it.label == label }

    @Test
    fun basicHost() {
        val result = parse(
            """
            Host web-01
                HostName 10.0.0.11
                Port 2222
                User deploy
                IdentityFile ~/.ssh/id_ed25519
            """,
        )
        assertEquals(
            listOf(ImportedHost("web-01", "10.0.0.11", 2222, "deploy", identityFiles = listOf("~/.ssh/id_ed25519"))),
            result.hosts,
        )
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun hostWithoutHostNameUsesAlias() {
        val host = parse("Host nas.local").host("nas.local")
        assertEquals("nas.local", host.address)
        assertNull(host.port)
        assertNull(host.username)
    }

    @Test
    fun wildcardDefaultsOnlyFillGaps() {
        val result = parse(
            """
            Host web
                User deploy

            Host db
                HostName 10.0.0.21

            Host *
                User root
                Port 2200
                IdentityFile ~/.ssh/id_default
            """,
        )
        assertEquals(listOf("web", "db"), result.hosts.map { it.label })
        assertEquals("deploy", result.host("web").username)
        assertEquals("root", result.host("db").username)
        assertEquals(2200, result.host("web").port)
        assertEquals(listOf("~/.ssh/id_default"), result.host("db").identityFiles)
    }

    @Test
    fun globalOptionsBeforeFirstHostWin() {
        val result = parse(
            """
            User admin

            Host web
                User deploy
            """,
        )
        assertEquals("admin", result.host("web").username)
    }

    @Test
    fun severalAliasesOnOneLine() {
        val result = parse(
            """
            Host web-01 web-02 *.example.com
                User deploy
            """,
        )
        assertEquals(listOf("web-01", "web-02"), result.hosts.map { it.label })
        assertTrue(result.hosts.all { it.username == "deploy" })
    }

    @Test
    fun patternBlocksApplyToMatchingAliases() {
        val result = parse(
            """
            Host db.example.com app.example.org
                Port 2201

            Host *.example.com
                User dba

            Host ap?.example.org
                User app
            """,
        )
        assertEquals("dba", result.host("db.example.com").username)
        assertEquals("app", result.host("app.example.org").username)
    }

    @Test
    fun negatedPatternsExcludeHosts() {
        val result = parse(
            """
            Host bastion web
                Port 22

            Host * !bastion
                ProxyJump bastion
            """,
        )
        assertEquals(emptyList(), result.host("bastion").proxyJump)
        assertEquals(listOf(JumpTarget(null, "bastion", null)), result.host("web").proxyJump)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val result = parse(
            """
            Host Web
                HostName 10.0.0.1

            Host web
                User deploy

            HOST *.LAN
                user lan-user

            Host printer.lan
            """,
        )
        assertEquals(listOf("Web", "printer.lan"), result.hosts.map { it.label })
        assertEquals("deploy", result.host("Web").username)
        assertEquals("lan-user", result.host("printer.lan").username)
    }

    @Test
    fun keywordSyntaxVariants() {
        val result = parse(
            "Host a\n\tHOSTNAME=10.0.0.1\n\tport = 2022\n\tUser\tdev\n\tidentityfile=\"~/My Keys/id key\"\n",
        )
        val host = result.host("a")
        assertEquals("10.0.0.1", host.address)
        assertEquals(2022, host.port)
        assertEquals("dev", host.username)
        assertEquals(listOf("~/My Keys/id key"), host.identityFiles)
    }

    @Test
    fun commentsBlankLinesAndCrlf() {
        val text = "# my servers\r\n\r\nHost a\r\n  # inline comment line\r\n  HostName 10.0.0.1 # trailing comment\r\n  Port 22\r\n"
        val result = SshConfigParser.parse(text)
        assertEquals(listOf(ImportedHost("a", "10.0.0.1", 22)), result.hosts)
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun hostNameTokens() {
        val result = parse(
            """
            Host app db
                HostName %h.internal.example.com

            Host percent
                HostName 100%%.example.com
            """,
        )
        assertEquals("app.internal.example.com", result.host("app").address)
        assertEquals("db.internal.example.com", result.host("db").address)
        assertEquals("100%.example.com", result.host("percent").address)
    }

    @Test
    fun identityFileTokensAndAccumulation() {
        val result = parse(
            """
            Host web
                HostName 10.0.0.5
                User deploy
                IdentityFile %d/.ssh/%r@%h
                IdentityFile ~/.ssh/id_%n

            Host *
                IdentityFile ~/.ssh/id_ed25519
                IdentityFile ~/.ssh/id_%l
                CertificateFile ~/.ssh/id_ed25519-cert.pub
            """,
        )
        val host = result.host("web")
        assertEquals(
            listOf("~/.ssh/deploy@10.0.0.5", "~/.ssh/id_web", "~/.ssh/id_ed25519", "~/.ssh/id_%l"),
            host.identityFiles,
        )
        assertEquals(listOf("~/.ssh/id_ed25519-cert.pub"), host.certificateFiles)
    }

    @Test
    fun proxyJumpForms() {
        val result = parse(
            """
            Host a
                ProxyJump bastion
            Host b
                ProxyJump ops@bastion.example.com:2222,inner
            Host c
                ProxyJump [2001:db8::1]:2200
            Host d
                ProxyJump admin@[fe80::1%en0]
            Host e
                ProxyJump ssh://jump@gw.example.com:22/
            Host f
                ProxyJump none
                ProxyJump ignored
            Host g
                ProxyJump 2001:db8::7
            """,
        )
        assertEquals(listOf(JumpTarget(null, "bastion", null)), result.host("a").proxyJump)
        assertEquals(
            listOf(JumpTarget("ops", "bastion.example.com", 2222), JumpTarget(null, "inner", null)),
            result.host("b").proxyJump,
        )
        assertEquals(listOf(JumpTarget(null, "2001:db8::1", 2200)), result.host("c").proxyJump)
        assertEquals(listOf(JumpTarget("admin", "fe80::1%en0", null)), result.host("d").proxyJump)
        assertEquals(listOf(JumpTarget("jump", "gw.example.com", 22)), result.host("e").proxyJump)
        assertEquals(emptyList(), result.host("f").proxyJump)
        assertEquals(listOf(JumpTarget(null, "2001:db8::7", null)), result.host("g").proxyJump)
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun invalidProxyJumpIsWarned() {
        val result = parse(
            """
            Host a
                ProxyJump good,bad:99999
            """,
        )
        assertEquals(listOf(JumpTarget(null, "good", null)), result.host("a").proxyJump)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().message.startsWith("ProxyJump non valido"))
    }

    @Test
    fun proxyCommandIsWarnedButNoneIsNot() {
        val result = parse(
            """
            Host a
                ProxyCommand ssh -W %h:%p bastion
            Host b
                ProxyCommand none
            Host c
                ProxyJump bastion
                ProxyCommand nc %h %p
            """,
        )
        assertEquals(1, result.warnings.size)
        assertEquals(2, result.warnings.single().line)
        assertEquals(emptyList(), result.host("a").proxyJump)
        assertEquals(listOf(JumpTarget(null, "bastion", null)), result.host("c").proxyJump)
    }

    @Test
    fun matchBlocksAreSkipped() {
        val result = parse(
            """
            Host a
                User first

            Match host a exec "true"
                User from-match
                Port 9999

            Host b
                User second
            """,
        )
        assertEquals("first", result.host("a").username)
        assertNull(result.host("a").port)
        assertEquals("second", result.host("b").username)
        assertEquals(listOf(4), result.warnings.map { it.line })
    }

    @Test
    fun forwardsAreWarnedOncePerHost() {
        val result = parse(
            """
            Host a
                LocalForward 8080 localhost:80
                LocalForward 8443 localhost:443
                DynamicForward 1080
            Host b
                RemoteForward 9000 localhost:9000
            """,
        )
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.all { "port forwarding" in it.message })
    }

    @Test
    fun invalidPortIsWarnedAndIgnored() {
        val result = parse(
            """
            Host a
                Port 70000
                Port 2222
            Host b
                Port abc
            """,
        )
        assertEquals(2222, result.host("a").port)
        assertNull(result.host("b").port)
        assertEquals(listOf(2, 5), result.warnings.map { it.line })
    }

    @Test
    fun duplicateAliasesMergeFirstWins() {
        val result = parse(
            """
            Host web
                HostName 10.0.0.1
            Host web
                HostName 10.0.0.2
                User deploy
            """,
        )
        assertEquals(listOf(ImportedHost("web", "10.0.0.1", username = "deploy")), result.hosts)
    }

    @Test
    fun includeIsReadInPlace() {
        val requested = mutableListOf<String>()
        val result = parse(
            """
            Include config.d/*

            Host main
                User root
            """,
        ) { pattern ->
            requested += pattern
            listOf(
                IncludedFile("~/.ssh/config.d/work", "Host work-01\n  HostName 10.1.0.1\n  User worker\n"),
                IncludedFile("~/.ssh/config.d/defaults", "Host *\n  Port 2200\n"),
            )
        }
        assertEquals(listOf("config.d/*"), requested)
        assertEquals(listOf("work-01", "main"), result.hosts.map { it.label })
        assertEquals("worker", result.host("work-01").username)
        assertEquals(2200, result.host("main").port)
    }

    @Test
    fun includeInsideHostBlockKeepsItsContext() {
        val result = parse(
            """
            Host web
                Include web-extra
                User deploy

            Host db
                HostName 10.0.0.2
            """,
        ) { listOf(IncludedFile("web-extra", "HostName 10.0.0.1\nPort 2201\n")) }
        assertEquals("10.0.0.1", result.host("web").address)
        assertEquals(2201, result.host("web").port)
        assertEquals("deploy", result.host("web").username)
        assertNull(result.host("db").port)
    }

    @Test
    fun includeWarningsCarryTheFile() {
        val result = parse("Include missing\nInclude bad") { pattern ->
            if (pattern == "bad") listOf(IncludedFile("~/.ssh/bad", "Host x\n  Port nope\n")) else emptyList()
        }
        assertEquals(
            listOf(
                ImportWarning(1, "Include non risolto: missing"),
                ImportWarning(2, "Porta non valida: nope", "~/.ssh/bad"),
            ),
            result.warnings,
        )
    }

    @Test
    fun recursiveIncludeStopsAtTheDepthLimit() {
        val result = parse("Host loop\nInclude self") { listOf(IncludedFile("self", "Include self")) }
        assertEquals(listOf("loop"), result.hosts.map { it.label })
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().message.startsWith("Troppi Include"))
    }

    @Test
    fun emptyAndGarbageInput() {
        assertEquals(ImportResult(emptyList(), emptyList()), SshConfigParser.parse(""))
        assertEquals(ImportResult(emptyList(), emptyList()), SshConfigParser.parse("\n\n   \n# only comments\n"))

        val result = parse(
            """
            this is not a config
            ServerAliveInterval 30
            Host
            Host good
                HostName "unterminated
                User ok
                WeirdKeyword whatever
            """,
        )
        assertEquals(listOf(ImportedHost("good", "good", username = "ok")), result.hosts)
        assertEquals(listOf(3, 5), result.warnings.map { it.line })
    }

    @Test
    fun globMatching() {
        assertTrue(SshConfigParser.glob("web-01.example.com", "*.example.com"))
        assertTrue(SshConfigParser.glob("web1", "web?"))
        assertTrue(SshConfigParser.glob("anything", "*"))
        assertTrue(SshConfigParser.glob("ABC", "a*c"))
        assertTrue(!SshConfigParser.glob("web10", "web?"))
        assertTrue(!SshConfigParser.glob("example.com", "*.example.com"))
    }
}
