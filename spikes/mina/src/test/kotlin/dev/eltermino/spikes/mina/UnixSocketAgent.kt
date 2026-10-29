package dev.eltermino.spikes.mina

import org.apache.sshd.agent.SshAgent
import org.apache.sshd.agent.SshAgentFactory
import org.apache.sshd.agent.SshAgentServer
import org.apache.sshd.agent.common.AbstractAgentProxy
import org.apache.sshd.common.FactoryManager
import org.apache.sshd.common.channel.ChannelFactory
import org.apache.sshd.common.session.ConnectionService
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * MINA's own Unix agent client needs Tomcat's APR native library, so this talks to
 * `SSH_AUTH_SOCK` over the JDK's Unix domain sockets instead. AbstractAgentProxy
 * already implements the agent protocol; we only move the bytes.
 */
class UnixSocketAgent(socket: Path) : AbstractAgentProxy(null) {
    private val channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))

    override fun request(buffer: Buffer): Buffer {
        channel.write(ByteBuffer.wrap(buffer.array(), buffer.rpos(), buffer.available()))
        val length = readFully(4).int
        return ByteArrayBuffer(readFully(length).array())
    }

    private fun readFully(size: Int): ByteBuffer {
        val target = ByteBuffer.allocate(size)
        while (target.hasRemaining()) {
            check(channel.read(target) >= 0) { "Agent closed the socket" }
        }
        return target.flip()
    }

    override fun isOpen() = channel.isOpen

    override fun close() {
        super.close()
        channel.close()
    }
}

class UnixSocketAgentFactory(private val socket: Path) : SshAgentFactory {
    override fun getChannelForwardingFactories(manager: FactoryManager): List<ChannelFactory> = emptyList()
    override fun createClient(session: Session, manager: FactoryManager): SshAgent = UnixSocketAgent(socket)
    override fun createServer(service: ConnectionService): SshAgentServer? = null
}
