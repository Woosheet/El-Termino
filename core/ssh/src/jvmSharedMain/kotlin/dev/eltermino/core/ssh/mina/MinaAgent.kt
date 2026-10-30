package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.agent.AgentTransport
import dev.eltermino.core.ssh.agent.SocketAgent
import org.apache.sshd.agent.SshAgent
import org.apache.sshd.agent.SshAgentFactory
import org.apache.sshd.agent.SshAgentKeyConstraint
import org.apache.sshd.agent.SshAgentServer
import org.apache.sshd.agent.local.ChannelAgentForwardingFactory
import org.apache.sshd.common.AttributeRepository.AttributeKey
import org.apache.sshd.common.FactoryManager
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelFactory
import org.apache.sshd.common.session.ConnectionService
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionContext
import java.security.KeyPair
import java.security.PublicKey

/** Set on a session while logging in with the agent. */
internal val USE_AGENT = AttributeKey<Boolean>()

/** Set once logged in on a session whose user asked for agent forwarding. */
internal val FORWARD_AGENT = AttributeKey<Boolean>()

/** A server can try to open this channel unasked, and must not get to the agent that way. */
internal val agentForwardingChannels = object : ChannelFactory {
    override fun getName(): String = ChannelAgentForwardingFactory.OPENSSH.name

    override fun createChannel(session: Session): Channel? =
        if (session.getAttribute(FORWARD_AGENT) == true) ChannelAgentForwardingFactory.OPENSSH.createChannel(session) else null
}

/** The client is shared, so each session says for itself whether it may see the agent's keys. */
internal class MinaAgentFactory(private val open: () -> AgentTransport?) : SshAgentFactory {
    override fun getChannelForwardingFactories(manager: FactoryManager): List<ChannelFactory> = emptyList()

    override fun createClient(session: Session, manager: FactoryManager): SshAgent {
        val allowed = session.getAttribute(USE_AGENT) == true || session.getAttribute(FORWARD_AGENT) == true
        // Unreachable counts as empty, so that the login moves on to the next method.
        return if (allowed && open()?.also { it.close() } != null) SocketAgent(open) else NoAgent
    }

    override fun createServer(service: ConnectionService): SshAgentServer? = null
}

private object NoAgent : SshAgent {
    override fun getIdentities(): Iterable<Map.Entry<PublicKey, String>> = emptyList()
    override fun sign(session: SessionContext?, key: PublicKey, algorithm: String, data: ByteArray) = throw UnsupportedOperationException()
    override fun addIdentity(key: KeyPair, comment: String, vararg constraints: SshAgentKeyConstraint) = throw UnsupportedOperationException()
    override fun removeIdentity(key: PublicKey) = throw UnsupportedOperationException()
    override fun removeAllIdentities() = throw UnsupportedOperationException()
    override fun isOpen() = true
    override fun close() = Unit
}
