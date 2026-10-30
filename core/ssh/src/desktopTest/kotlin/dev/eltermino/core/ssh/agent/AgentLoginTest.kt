package dev.eltermino.core.ssh.agent

import dev.eltermino.core.ssh.AgentUnavailableException
import dev.eltermino.core.ssh.AuthMethod
import dev.eltermino.core.ssh.ConnectTarget
import dev.eltermino.core.ssh.PtyConfig
import dev.eltermino.core.ssh.SshException
import dev.eltermino.core.ssh.mina.FORWARD_AGENT
import dev.eltermino.core.ssh.mina.MinaSshConnector
import dev.eltermino.core.ssh.mina.RecordingVerifier
import dev.eltermino.core.ssh.mina.TestSecrets
import dev.eltermino.core.ssh.mina.TestServers
import dev.eltermino.core.ssh.mina.agentForwardingChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.session.Session
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentLoginTest {
    private val socket = File(Files.createTempDirectory("agent").toFile().apply { deleteOnExit() }, "s")
    private var process: Process? = null
    private val connector = MinaSshConnector(agent = { openUnixAgent(socket.toPath()) })

    @AfterTest
    fun stop() {
        connector.shutdown()
        process?.destroyForcibly()
    }

    private fun bastion(vararg auth: AuthMethod) = ConnectTarget(TestServers.HOST, TestServers.BASTION, TestServers.USER, auth.toList())

    private fun login(target: ConnectTarget, connector: MinaSshConnector = this.connector) = runBlocking {
        val connection = connector.connect(target, RecordingVerifier(), TestSecrets())
        try {
            assertEquals("ok", connection.exec("echo ok").stdout.decodeToString().trim())
        } finally {
            connection.close()
        }
    }

    @Test
    fun agentLogin() {
        process = startTestAgent(socket, "id_ed25519")
        login(bastion(AuthMethod.Agent))
    }

    @Test
    fun agentWorksOnAJumpHop() {
        process = startTestAgent(socket, "id_ed25519")
        val target = ConnectTarget(TestServers.INTERNAL_HOST, 22, TestServers.USER, listOf(AuthMethod.Agent), jumpHosts = listOf(bastion(AuthMethod.Agent)))
        login(target)
    }

    @Test
    fun agentOnlyOnHopsThatAskForIt() {
        process = startTestAgent(socket, "id_ed25519")
        // The agent's key would open the bastion, but this login never asked for the agent.
        assertFailsWith<SshException.AuthFailed> { login(bastion(TestServers.keyAuth("id_cert"))) }
    }

    @Test
    fun aWorkingAgentNeverAsksForTheRest() = runBlocking {
        process = startTestAgent(socket, "id_ed25519")
        val secrets = TestSecrets()
        val connection = connector.connect(bastion(AuthMethod.Agent, TestServers.passwordAuth()), RecordingVerifier(), secrets)
        connection.close()
        assertEquals(0, secrets.calls.get())
    }

    @Test
    fun emptyAgentFallsThroughToPassword() {
        process = startTestAgent(socket)
        login(TestServers.basic(AuthMethod.Agent, TestServers.passwordAuth()))
    }

    @Test
    fun missingAgentFallsThroughToPassword() {
        login(TestServers.basic(AuthMethod.Agent, TestServers.passwordAuth()))
    }

    @Test
    fun missingAgentFailureSaysSo() {
        val error = assertFailsWith<SshException.AuthFailed> { login(bastion(AuthMethod.Agent)) }
        assertFalse(assertIs<AgentUnavailableException>(error.cause).running)
    }

    @Test
    fun emptyAgentFailureSaysSo() {
        process = startTestAgent(socket)
        val error = assertFailsWith<SshException.AuthFailed> { login(bastion(AuthMethod.Agent)) }
        assertTrue(assertIs<AgentUnavailableException>(error.cause).running)
    }

    @Test
    fun connectorWithoutAgentRefusesIt() {
        val plain = MinaSshConnector()
        assertFalse(plain.supportsAgent)
        assertTrue(connector.supportsAgent)
        assertFailsWith<SshException.AuthFailed> { login(bastion(AuthMethod.Agent), plain) }
        plain.shutdown()
    }

    private fun shellOutput(target: ConnectTarget, command: String): String = runBlocking {
        val connection = connector.connect(target, RecordingVerifier(), TestSecrets())
        try {
            val shell = connection.openShell(PtyConfig())
            val text = StringBuffer()
            val job = launch(Dispatchers.IO) { shell.output.collect { text.append(it.decodeToString()) } }
            shell.write("$command; echo FINE\n".encodeToByteArray())
            withTimeout(10.seconds) { while ("\nFINE" !in text) delay(20) }
            job.cancel()
            text.toString()
        } finally {
            connection.close()
        }
    }

    @Test
    fun forwardedAgentVisibleOnServer() {
        process = startTestAgent(socket, "id_ed25519")
        val output = shellOutput(TestServers.basic(TestServers.passwordAuth()).copy(agentForwarding = true), "ssh-add -l")
        assertTrue("test-ed25519" in output || "ED25519" in output, output)
    }

    @Test
    fun forwardingOffByDefault() {
        process = startTestAgent(socket, "id_ed25519")
        val output = shellOutput(TestServers.basic(TestServers.passwordAuth()), "echo sock=[\$SSH_AUTH_SOCK]")
        assertTrue("sock=[]" in output, output)
    }

    @Test
    fun forwardingReachesOnlyTheDestination() {
        process = startTestAgent(socket, "id_ed25519")
        val hop = bastion(TestServers.keyAuth("id_ed25519"))
        val target = ConnectTarget(TestServers.INTERNAL_HOST, 22, TestServers.USER, listOf(TestServers.keyAuth("id_ed25519")), jumpHosts = listOf(hop), agentForwarding = true)
        assertTrue("ED25519" in shellOutput(target, "ssh-add -l"))
    }

    @Test
    fun serverCantOpenAgentChannelWhenNotAsked() {
        val session = sessionWithAttributes()
        assertNull(agentForwardingChannels.createChannel(session))
    }

    @Test
    fun serverCanOpenAgentChannelWhenAsked() {
        val session = sessionWithAttributes(FORWARD_AGENT to true)
        assertNotNull(agentForwardingChannels.createChannel(session))
    }

    private fun sessionWithAttributes(vararg attributes: Pair<AttributeRepository.AttributeKey<Boolean>, Boolean>): Session =
        Proxy.newProxyInstance(Session::class.java.classLoader, arrayOf(Session::class.java)) { _, method, args ->
            if (method.name == "getAttribute") attributes.toMap()[args[0]] else null
        } as Session
}
