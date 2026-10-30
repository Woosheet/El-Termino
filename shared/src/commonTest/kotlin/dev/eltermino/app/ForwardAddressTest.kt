package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.PortForwardRule
import dev.eltermino.core.data.Workspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForwardAddressTest {
    @Test
    fun defaultStaysLoopback() {
        assertEquals("127.0.0.1", PortForwardRule("r", bindPort = 1).bindAddress)
    }

    @Test
    fun oldRulesLoadAsLoopback() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertEquals("127.0.0.1", json.decodeFromString(PortForwardRule.serializer(), """{"id":"r","bindPort":1}""").bindAddress)
    }

    @Test
    fun loopbackNeedsNoWarning() {
        listOf("127.0.0.1", "localhost", "::1", "127.0.0.5").forEach { assertTrue(isLoopbackAddress(it), it) }
        listOf("0.0.0.0", "::", "192.168.1.10", "*", "mio-mac.local").forEach { assertFalse(isLoopbackAddress(it), it) }
    }

    @Test
    fun invalidAddressRefused() {
        listOf("", " ", "1.2.3.4 ", "a b", "http://x", "1.2.3.4:80").forEach { assertFalse(isValidBindAddress(it), "'$it'") }
        listOf("127.0.0.1", "0.0.0.0", "::", "::1", "fe80::1", "mio-mac.local", "*").forEach { assertTrue(isValidBindAddress(it), it) }
    }

    @Test
    fun activeForwardsListsAllHosts() {
        val a = PortForwardRule("a", bindPort = 1)
        val b = PortForwardRule("b", bindPort = 2)
        val off = PortForwardRule("c", bindPort = 3)
        val workspace = Workspace(hosts = listOf(Host("1", "uno", "u", forwards = listOf(a, off)), Host("2", "due", "d", forwards = listOf(b))))
        val states = mapOf("a" to ForwardState.Active(1), "b" to ForwardState.Starting)
        assertEquals(listOf("uno" to "a", "due" to "b"), runningForwards(workspace, states).map { it.host.label to it.rule.id })
    }

    @Test
    fun failedRuleStaysListedSoItCanBeStopped() {
        val a = PortForwardRule("a", bindPort = 1)
        val workspace = Workspace(hosts = listOf(Host("1", "uno", "u", forwards = listOf(a))))
        assertEquals(1, runningForwards(workspace, mapOf("a" to ForwardState.Failed("no"))).size)
    }
}
