package dev.eltermino.core.ssh.agent

import dev.eltermino.core.ssh.mina.TestServers
import org.apache.sshd.common.SshException
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        val agent = runCatching { ProcessBuilder("ssh-agent", "-D", "-a", socket.path).redirectErrorStream(true).start() }.getOrNull()
        assumeTrue(agent != null, "ssh-agent isn't installed")
        process = agent
        repeat(100) { if (!socket.exists()) Thread.sleep(20) }
        keys.forEach { key ->
            val add = ProcessBuilder("ssh-add", TestServers.keyFile(key).path).redirectErrorStream(true)
            add.environment()["SSH_AUTH_SOCK"] = socket.path
            assertEquals(0, add.start().waitFor())
        }
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
