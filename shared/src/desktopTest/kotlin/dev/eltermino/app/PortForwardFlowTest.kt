package dev.eltermino.app

import dev.eltermino.core.data.PortForwardRule
import dev.eltermino.core.data.newId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Saved forwards on a testenv host: started by hand, started with the connection, failing, stopped. */
class PortForwardFlowTest {
    private fun freePort() = java.net.ServerSocket(0).use { it.localPort }

    private fun get(port: Int, proxy: java.net.Proxy = java.net.Proxy.NO_PROXY): String = java.net.Socket(proxy).use { socket ->
        val target = if (proxy == java.net.Proxy.NO_PROXY) java.net.InetSocketAddress("127.0.0.1", port) else java.net.InetSocketAddress.createUnresolved("localhost", 8080)
        socket.connect(target, 10_000)
        socket.getOutputStream().write("GET /health.json HTTP/1.0\r\n\r\n".toByteArray())
        socket.getInputStream().readBytes().decodeToString()
    }

    @Test
    fun savedForwardsRunOnTheHostsConnection() {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        val busy = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        try {
            runBlocking {
                val identity = state.actions.keyIdentity()
                val web = PortForwardRule(newId(), "Web", "local", freePort(), "localhost", 8080, autoStart = true)
                val socks = PortForwardRule(newId(), "Proxy", "dynamic", freePort())
                val taken = PortForwardRule(newId(), "Occupata", "local", busy.localPort, "localhost", 8080)
                val host = state.actions.testHost("basic", TestEnv.BASIC_PORT, identity).copy(forwards = listOf(web, socks, taken))
                state.actions.saveHost(host)
                val forwards = state.forwards

                // Starting the proxy logs in, and the login starts the rule marked to start with it.
                forwards.start(host, socks)
                val running = withTimeout(60_000) {
                    forwards.states.first { s -> listOf(web, socks).all { s[it.id] is ForwardState.Active } }
                }
                assertEquals(ForwardState.Active(web.bindPort), running[web.id])
                assertTrue(get(web.bindPort).endsWith("""{"status":"ok","host":"basic"}"""))
                val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socks.bindPort))
                assertTrue(get(0, proxy).endsWith("""{"status":"ok","host":"basic"}"""))

                forwards.start(host, taken)
                val failed = withTimeout(30_000) { forwards.states.first { it[taken.id] !is ForwardState.Starting }[taken.id] }
                assertTrue("porta ${busy.localPort}" in assertIs<ForwardState.Failed>(failed).message, failed.toString())
                assertTrue(!forwards.isRunning(taken.id))

                forwards.stop(web.id)
                assertEquals(null, forwards.states.value[web.id])
                withTimeout(10_000) {
                    while (runCatching { java.net.Socket("127.0.0.1", web.bindPort).close() }.isSuccess) kotlinx.coroutines.delay(100)
                }
                assertFailsWith<java.io.IOException> { java.net.Socket("127.0.0.1", web.bindPort).close() }
                forwards.stop(socks.id)
            }
        } finally {
            busy.close()
            state.scope.cancel()
        }
    }

    @Test
    fun forwardsListenOnTheChosenAddress() {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        runBlocking {
            val identity = state.actions.keyIdentity()
            val local = PortForwardRule(newId(), "Web", "local", freePort(), "localhost", 8080, bindAddress = "0.0.0.0")
            val remote = PortForwardRule(newId(), "Ritorno", "remote", freePort(), "localhost", 22, bindAddress = "0.0.0.0")
            val host = state.actions.testHost("basic", TestEnv.BASIC_PORT, identity).copy(forwards = listOf(local, remote))
            state.actions.saveHost(host)
            host.forwards.forEach { state.forwards.start(host, it) }
            withTimeout(20_000) {
                state.forwards.states.first { states -> host.forwards.all { states[it.id] is ForwardState.Active } }
            }
            assertTrue("status" in get(local.bindPort))
            host.forwards.forEach { state.forwards.stop(it.id) }
        }
        state.scope.cancel()
    }
}
