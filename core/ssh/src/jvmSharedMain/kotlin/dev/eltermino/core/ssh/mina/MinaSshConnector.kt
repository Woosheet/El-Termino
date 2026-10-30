package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.AuthMethod
import dev.eltermino.core.ssh.ConnectTarget
import dev.eltermino.core.ssh.HostKeyVerifier
import dev.eltermino.core.ssh.KeyboardInteractiveResponder
import dev.eltermino.core.ssh.SecretResolver
import dev.eltermino.core.ssh.SshConnection
import dev.eltermino.core.ssh.SshConnector
import dev.eltermino.core.ssh.SshException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent
import org.apache.sshd.common.config.keys.OpenSshCertificate
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.core.CoreModuleProperties
import java.security.KeyPair
import java.util.EnumSet
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class MinaSshConnector(private val config: MinaConfig = MinaConfig()) : SshConnector {
    private val client: SshClient by lazy {
        SshClient.setUpDefaultClient().apply {
            // Nothing from ~/.ssh: every host, key and setting comes from the app.
            hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
            keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            // MINA only records the key here; connect() asks our HostKeyVerifier
            // before authenticating, so no MINA thread ever waits on the UI.
            serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            // The default rejects what a remote forward sends back. The server can only use the
            // ports we asked it to listen on, each tied to the address we gave.
            forwardingFilter = org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE
            // A certificate whose principals don't match the name we dialled is treated
            // like a plain key, as OpenSSH does without a trusted CA.
            CoreModuleProperties.ABORT_ON_INVALID_CERTIFICATE.set(this, false)
            CoreModuleProperties.IDLE_TIMEOUT.set(this, java.time.Duration.ZERO)
            CoreModuleProperties.NIO2_READ_TIMEOUT.set(this, java.time.Duration.ZERO)
            if (config.keepAliveInterval.isPositive()) {
                CoreModuleProperties.HEARTBEAT_INTERVAL.set(this, config.keepAliveInterval.toJavaDuration())
                CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(this, config.keepAliveMaxMissed)
            }
            start()
        }
    }

    override suspend fun connect(
        target: ConnectTarget,
        hostKeyVerifier: HostKeyVerifier,
        secrets: SecretResolver,
    ): SshConnection {
        // target.jumpHosts is the whole chain; a hop's own jumpHosts are not followed.
        val hops = target.jumpHosts.map { it.copy(jumpHosts = emptyList()) } + target
        hops.forEach { hop ->
            if (hop.auth.any { it is AuthMethod.Agent }) {
                throw SshException.AuthFailed(
                    hop.host, hop.username,
                    UnsupportedOperationException("SSH agent authentication isn't supported yet"),
                )
            }
        }

        val sessions = mutableListOf<ClientSession>()
        try {
            for (hop in hops) {
                val (host, port) = sessions.lastOrNull()?.let { previous ->
                    val bound = withContext(Dispatchers.IO) {
                        previous.startLocalPortForwarding(
                            SshdSocketAddress(SshdSocketAddress.LOCALHOST_IPV4, 0),
                            SshdSocketAddress(hop.host, hop.port),
                        )
                    }
                    bound.hostName to bound.port
                } ?: (hop.host to hop.port)
                sessions += openSession(hop, host, port, hostKeyVerifier, secrets)
            }
        } catch (e: Throwable) {
            sessions.asReversed().forEach { it.close(true) }
            throw e
        }
        return MinaSshConnection(target, sessions.last(), hops = sessions.dropLast(1), config)
    }

    /** Stops the shared client and every connection it opened. */
    fun shutdown() {
        client.stop()
    }

    private suspend fun openSession(
        hop: ConnectTarget,
        host: String,
        port: Int,
        hostKeyVerifier: HostKeyVerifier,
        secrets: SecretResolver,
    ): ClientSession {
        val connected = try {
            client.connect(hop.username, host, port).awaitDone(config.connectTimeout)
        } catch (e: TimeoutCancellationException) {
            throw SshException.ConnectFailed(hop.host, hop.port, e)
        }
        if (!connected.isConnected) throw SshException.ConnectFailed(hop.host, hop.port, connected.exception)
        val session = connected.session
        try {
            val events = withContext(Dispatchers.IO) {
                session.waitFor(EnumSet.of(ClientSessionEvent.WAIT_AUTH, ClientSessionEvent.CLOSED), config.connectTimeout.toJavaDuration())
            }
            val serverKey = session.serverKey
            if (ClientSessionEvent.WAIT_AUTH !in events || serverKey == null) {
                throw SshException.ConnectFailed(hop.host, hop.port, null)
            }
            val fingerprint = serverKey.toHostKeyFingerprint()
            if (!hostKeyVerifier.verify(hop.host, hop.port, fingerprint)) {
                throw SshException.HostKeyRejected(hop.host, hop.port, fingerprint)
            }
            authenticate(session, hop, secrets)
            return session
        } catch (e: Throwable) {
            session.close(true)
            throw e
        }
    }

    /** Secrets are only read once the host key has been accepted. */
    private suspend fun authenticate(session: ClientSession, hop: ConnectTarget, secrets: SecretResolver) {
        val methods = mutableListOf<String>()
        var responder: KeyboardInteractiveResponder? = null
        for (method in hop.auth) {
            when (method) {
                is AuthMethod.Password -> {
                    val bytes = secrets.resolve(method.password)
                    session.addPasswordIdentity(String(bytes, Charsets.UTF_8))
                    bytes.fill(0)
                    methods += "password"
                }
                is AuthMethod.PublicKey -> {
                    session.addPublicKeyIdentity(resolveKeyPair(method, secrets, hop))
                    methods += "publickey"
                }
                is AuthMethod.KeyboardInteractive -> {
                    responder = method.responder
                    methods += "keyboard-interactive"
                }
                AuthMethod.Agent -> Unit
            }
        }
        // A stored password can also answer a keyboard-interactive password prompt.
        if ("password" in methods) methods += "keyboard-interactive"
        val interactive = responder != null
        val timeout = if (interactive) config.interactiveAuthTimeout else config.authTimeout
        CoreModuleProperties.PREFERRED_AUTHS.set(session, methods.distinct().joinToString(","))
        CoreModuleProperties.AUTH_TIMEOUT.set(session, (timeout * 2).toJavaDuration())
        responder?.let { session.userInteraction = BlockingInteraction(it, timeout) }

        val result = try {
            session.auth().awaitDone(timeout)
        } catch (e: TimeoutCancellationException) {
            throw SshException.AuthFailed(hop.host, hop.username, e)
        }
        if (!result.isSuccess) throw SshException.AuthFailed(hop.host, hop.username, result.exception)
    }

    private suspend fun resolveKeyPair(method: AuthMethod.PublicKey, secrets: SecretResolver, hop: ConnectTarget): KeyPair {
        val keyBytes = secrets.resolve(method.privateKey)
        val passphrase = method.passphrase?.let { secrets.resolve(it) }
        try {
            val keyPair = loadKeyPair(keyBytes, passphrase)
            val certificate = method.certificate?.let {
                PublicKeyEntry.parsePublicKeyEntry(it.trim()).resolvePublicKey(null, emptyMap(), null) as? OpenSshCertificate
                    ?: throw IllegalArgumentException("Not an OpenSSH certificate")
            }
            return if (certificate == null) keyPair else KeyPair(certificate, keyPair.private)
        } catch (e: Exception) {
            throw SshException.AuthFailed(hop.host, hop.username, e)
        } finally {
            keyBytes.fill(0)
            passphrase?.fill(0)
        }
    }
}

/**
 * MINA asks keyboard-interactive questions synchronously on one of its I/O threads, so
 * that thread waits for the user's answer. The wait is bounded by [timeout].
 */
private class BlockingInteraction(
    private val responder: KeyboardInteractiveResponder,
    private val timeout: Duration,
) : UserInteraction {
    override fun interactive(
        session: ClientSession,
        name: String?,
        instruction: String?,
        lang: String?,
        prompt: Array<String>,
        echo: BooleanArray,
    ): Array<String>? {
        val prompts = prompt.mapIndexed { i, text -> KeyboardInteractiveResponder.Prompt(text, echo.getOrElse(i) { false }) }
        return try {
            runBlocking { withTimeout(timeout) { responder.respond(name.orEmpty(), instruction.orEmpty(), prompts) } }
                .toTypedArray()
        } catch (e: Exception) {
            null
        }
    }

    override fun getUpdatedPassword(session: ClientSession, prompt: String?, lang: String?): String? = null
}
