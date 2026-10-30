package dev.eltermino.core.ssh.agent

import org.apache.sshd.agent.common.AbstractAgentProxy
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import java.io.Closeable
import java.io.IOException

/** One conversation with an SSH agent. */
interface AgentTransport : Closeable {
    /** Sends a framed request and returns the reply without its length field. */
    fun exchange(request: ByteArray): ByteArray
}

/** MINA's agent protocol over a [AgentTransport], with a new connection for each request. */
class SocketAgent(private val open: () -> AgentTransport?) : AbstractAgentProxy(null) {
    override fun isOpen() = true

    override fun request(buffer: Buffer): Buffer {
        val transport = open() ?: throw IOException("SSH agent not available")
        return transport.use { ByteArrayBuffer(it.exchange(buffer.compactData)) }
    }
}
