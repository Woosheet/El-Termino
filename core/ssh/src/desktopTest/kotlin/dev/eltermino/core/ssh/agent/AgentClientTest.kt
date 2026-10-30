package dev.eltermino.core.ssh.agent

import dev.eltermino.core.ssh.mina.TestServers
import org.apache.sshd.common.SshException
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentClientTest {
    private val dir = Files.createTempDirectory("agent").toFile().apply { deleteOnExit() }
    private val socket = File(dir, "s")
    private var process: Process? = null

    @AfterTest
    fun stop() {
        process?.destroyForcibly()
    }

    private fun startRealAgent(vararg keys: String) {
        process = startTestAgent(socket, *keys)
    }

    /** Answers every request with [reply], sent as it is. */
    private fun fakeAgent(reply: ByteArray) {
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket.toPath()))
        thread(isDaemon = true) {
            server.accept().use { client ->
                client.read(ByteBuffer.allocate(1024))
                client.write(ByteBuffer.wrap(reply))
            }
        }
    }

    private fun agent() = SocketAgent { openUnixAgent(socket.toPath()) }

    @Test
    fun listsIdentities() {
        startRealAgent("id_ed25519")
        assertEquals(1, agent().identities.count())
    }

    @Test
    fun emptyAgentHasNoIdentities() {
        startRealAgent()
        assertEquals(0, agent().identities.count())
    }

    @Test
    fun signsData() {
        startRealAgent("id_ed25519")
        val agent = agent()
        val key = agent.identities.first().key
        val (algorithm, signature) = agent.sign(null, key, "ssh-ed25519", "dati".encodeToByteArray())
        assertEquals("ssh-ed25519", algorithm)
        assertTrue(signature.isNotEmpty())
    }

    @Test
    fun missingSocketIsNull() {
        assertNull(openUnixAgent(socket.toPath()))
    }

    @Test
    fun failureReplyThrows() {
        fakeAgent(byteArrayOf(0, 0, 0, 1, 5))
        assertFailsWith<SshException> { assertNotNull(agent()).identities.count() }
    }

    @Test
    fun truncatedReplyThrows() {
        fakeAgent(byteArrayOf(0, 0, 0, 9, 12))
        assertFailsWith<IOException> { agent().identities.count() }
    }

    @Test
    fun oversizedLengthThrows() {
        // More than any agent reply, and it must not turn into an allocation.
        fakeAgent(byteArrayOf(0x7f, 0, 0, 0))
        assertFailsWith<IOException> { agent().identities.count() }
    }
}

/** A real `ssh-agent` on [socket] holding [keys]. Skips the test where it isn't installed. */
fun startTestAgent(socket: File, vararg keys: String): Process {
    val agent = runCatching { ProcessBuilder("ssh-agent", "-D", "-a", socket.path).redirectErrorStream(true).start() }.getOrNull()
    org.junit.jupiter.api.Assumptions.assumeTrue(agent != null, "ssh-agent isn't installed")
    repeat(100) { if (!socket.exists()) Thread.sleep(20) }
    keys.forEach { key ->
        val add = ProcessBuilder("ssh-add", TestServers.keyFile(key).path).redirectErrorStream(true)
        add.environment()["SSH_AUTH_SOCK"] = socket.path
        check(add.start().waitFor() == 0) { "ssh-add failed" }
    }
    return agent!!

    @Test
    fun pageantsPipeHasAnUnpredictableEnd() {
        val pipes = listOf("openssh-ssh-agent", "pageant.anna.0a1b2c", "pageant.bruno.ffff", "altro")
        assertEquals(listOf("openssh-ssh-agent", "pageant.anna.0a1b2c"), agentPipes(pipes, "anna").map { it.substringAfterLast('\\') })
    }
}
