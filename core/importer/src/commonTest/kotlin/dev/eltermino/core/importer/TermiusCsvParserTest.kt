package dev.eltermino.core.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermiusCsvParserTest {
    private val header = "Groups,Label,Tags,Hostname/IP,Protocol,Port,Username,Password"

    @Test
    fun templateRowsWithBomAndCrlf() {
        val csv = "﻿$header\r\n" +
            "Produzione/Web,web-01,\"prod,nginx\",10.0.0.11,ssh,22,deploy,\r\n" +
            "Produzione,db-01,,10.0.0.21,SSH,2222,admin,s3cret\r\n"
        val result = TermiusCsvParser.parse(csv)
        assertEquals(
            listOf(
                ImportedHost("web-01", "10.0.0.11", 22, "deploy", groupPath = listOf("Produzione", "Web"), tags = listOf("prod", "nginx")),
                ImportedHost("db-01", "10.0.0.21", 2222, "admin", password = "s3cret", groupPath = listOf("Produzione")),
            ),
            result.hosts,
        )
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun quotedFieldsWithDelimitersQuotesAndNewlines() {
        val csv = "$header\n" +
            "\"Team \"\"Ops\"\"\",\"web, primary\",,web.example.com,ssh,,,\"pa,ss\n\"\"word\"\"\"\n"
        val host = TermiusCsvParser.parse(csv).hosts.single()
        assertEquals(listOf("Team \"Ops\""), host.groupPath)
        assertEquals("web, primary", host.label)
        assertEquals("pa,ss\n\"word\"", host.password)
    }

    @Test
    fun headersAreMatchedByNameInAnyOrder() {
        val csv = """
            username;HOST;label;tag;group;subgroup;ssh_key
            dev;nas.local;NAS;home;Casa;Rete;~/.ssh/id_nas
        """.trimIndent()
        val result = TermiusCsvParser.parse(csv)
        assertEquals(
            listOf(
                ImportedHost(
                    "NAS", "nas.local", 22, "dev",
                    groupPath = listOf("Casa", "Rete"),
                    tags = listOf("home"),
                    identityFiles = listOf("~/.ssh/id_nas"),
                ),
            ),
            result.hosts,
        )
    }

    @Test
    fun onlyTheAddressIsRequired() {
        val result = TermiusCsvParser.parse("Hostname\n10.0.0.5\n\n")
        assertEquals(listOf(ImportedHost("10.0.0.5", "10.0.0.5", 22)), result.hosts)
    }

    @Test
    fun protocols() {
        val csv = "$header\n" +
            ",a,,10.0.0.1,,,,\n" +
            ",b,,10.0.0.2,mosh,,,\n" +
            ",c,,10.0.0.3,telnet,23,,\n"
        val result = TermiusCsvParser.parse(csv)
        assertEquals(listOf("a", "b"), result.hosts.map { it.label })
        assertEquals(listOf(3, 4), result.warnings.map { it.line })
        assertTrue("Mosh" in result.warnings[0].message)
        assertTrue("telnet" in result.warnings[1].message)
    }

    @Test
    fun invalidRowsAreSkippedWithWarnings() {
        val csv = "$header\n" +
            ",no-address,,,ssh,22,,\n" +
            ",bad-port,,10.0.0.1,ssh,99999,,\n" +
            ",bad-port-2,,10.0.0.1,ssh,abc,,\n" +
            ",bad-host,,not a host,ssh,22,,\n" +
            ",ok,,10.0.0.9,ssh,22,,\n"
        val result = TermiusCsvParser.parse(csv)
        assertEquals(listOf("ok"), result.hosts.map { it.label })
        assertEquals(listOf(2, 3, 4, 5), result.warnings.map { it.line })
    }

    @Test
    fun addressColumnMayCarryUserAndPort() {
        val csv = "Label,Hostname/IP,Port,Username\n" +
            "a,deploy@10.0.0.1:2201,,\n" +
            "b,[2001:db8::1]:2222,,root\n" +
            "c,2001:db8::2,,\n" +
            "d,other@10.0.0.4,,kept\n"
        val hosts = TermiusCsvParser.parse(csv).hosts
        assertEquals(ImportedHost("a", "10.0.0.1", 2201, "deploy"), hosts[0])
        assertEquals(ImportedHost("b", "2001:db8::1", 2222, "root"), hosts[1])
        assertEquals(ImportedHost("c", "2001:db8::2", 22), hosts[2])
        // An explicit username column wins, so the address is left alone and rejected.
        assertEquals(3, hosts.size)
    }

    @Test
    fun duplicatesAreSkipped() {
        val csv = "$header\n" +
            "G,web,,10.0.0.1,ssh,22,deploy,\n" +
            "G,web,x,10.0.0.1,ssh,22,deploy,other\n" +
            "G,web,,10.0.0.1,ssh,22,root,\n"
        val result = TermiusCsvParser.parse(csv)
        assertEquals(listOf("deploy", "root"), result.hosts.map { it.username })
        assertEquals(listOf(3), result.warnings.map { it.line })
    }

    @Test
    fun extraFieldsAreWarned() {
        // Tags written without quotes spill into the following columns.
        val csv = "$header\nG,web,prod,nginx,10.0.0.1,ssh,22,deploy,\n"
        val result = TermiusCsvParser.parse(csv)
        assertTrue(result.warnings.any { it.line == 2 && "più colonne" in it.message })
    }

    @Test
    fun keyPassphrasesAndProxiesAreNotImported() {
        val csv = "Label,Hostname,KeyLabel,KeyPassphrase,ProxyType,ProxyHost,ProxyPort\n" +
            "a,10.0.0.1,main,topsecret,,,\n" +
            "b,10.0.0.2,,,socks5,proxy.local,1080\n"
        val result = TermiusCsvParser.parse(csv)
        assertEquals(2, result.hosts.size)
        assertEquals(listOf(3, null), result.warnings.map { it.line })
        assertFalse(result.hosts.toString().contains("topsecret"))
    }

    @Test
    fun passwordsNeverShowUpInToString() {
        val host = ImportedHost("a", "10.0.0.1", password = "hunter2")
        assertFalse("hunter2" in host.toString())
        assertNull(ImportedHost("b", "10.0.0.2").password)
    }

    @Test
    fun emptyOrForeignFiles() {
        assertEquals(ImportResult(emptyList(), emptyList()), TermiusCsvParser.parse(""))
        assertEquals(ImportResult(emptyList(), emptyList()), TermiusCsvParser.parse(header))
        val foreign = TermiusCsvParser.parse("name,email\nMario,mario@example.com\n")
        assertEquals(emptyList(), foreign.hosts)
        assertEquals(1, foreign.warnings.size)
    }

    @Test
    fun csvReaderEdgeCases() {
        val records = Csv.read("a,\"b\"\"c\",d\"e\n\n\"multi\nline\",x\r\nlast")
        assertEquals(listOf("a", "b\"c", "d\"e"), records[0].fields)
        assertEquals(1, records[0].line)
        assertEquals(listOf(""), records[1].fields)
        assertEquals(listOf("multi\nline", "x"), records[2].fields)
        assertEquals(3, records[2].line)
        assertEquals(listOf("last"), records[3].fields)
        assertEquals(5, records[3].line)
        assertEquals(';', Csv.guessDelimiter("a;b;c\n1;2;3"))
        assertEquals(',', Csv.guessDelimiter("a,b;c\n"))
    }
}
