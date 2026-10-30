package dev.eltermino.core.ssh.agent

import dev.eltermino.core.ssh.AgentUnavailableException
import dev.eltermino.core.ssh.AuthMethod
import dev.eltermino.core.ssh.ConnectTarget
import dev.eltermino.core.ssh.SshException
import dev.eltermino.core.ssh.mina.MinaSshConnector
import dev.eltermino.core.ssh.mina.RecordingVerifier
import dev.eltermino.core.ssh.mina.TestSecrets
import dev.eltermino.core.ssh.mina.TestServers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
}
