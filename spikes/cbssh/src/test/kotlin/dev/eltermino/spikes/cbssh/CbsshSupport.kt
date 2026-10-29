package dev.eltermino.spikes.cbssh

import dev.eltermino.spikes.TestEnv
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthHandler
import org.connectbot.sshlib.AuthPublicKey
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.KeyFingerprint
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.SshSession
import org.connectbot.sshlib.transport.TransportFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.Base64

/** Records every host key it is shown and accepts it, like a first-time TOFU prompt answered "yes". */
class RecordingVerifier(private val accept: Boolean = true) : HostKeyVerifier {
    val seen = mutableListOf<Pair<String, String>>()

    override suspend fun verify(key: PublicKey): Boolean {
        seen += key.type to KeyFingerprint.sha256(key.encoded)
        return accept
    }
}

fun config(
    host: String = TestEnv.HOST,
    port: Int = TestEnv.BASIC_PORT,
    verifier: HostKeyVerifier = RecordingVerifier(),
    transport: TransportFactory? = null,
    extra: SshClientConfig.Builder.() -> Unit = {},
): SshClientConfig = SshClientConfig {
    if (transport != null) {
        transportFactory = transport
    } else {
        this.host = host
        this.port = port
    }
    hostKeyVerifier = verifier
    // Default is true: the transport drops as soon as the last channel closes, which is
    // wrong for a connection pool that opens shells/SFTP on demand.
    autoDisconnectOnLastChannelClose = false
    extra()
}

suspend fun connected(cfg: SshClientConfig = config()): SshClient {
    val client = SshClient(cfg)
    val result = withTimeout(15_000) { client.connect() }
    check(result is ConnectResult.Success) { "connect failed: $result" }
    return client
}

suspend fun SshClient.keyAuth(keyName: String = "id_ed25519", passphrase: String? = null) {
    val result = authenticatePublicKey(TestEnv.USER, TestEnv.key(keyName).readText(), passphrase)
    check(result is AuthResult.Success) { "auth with $keyName failed: $result" }
}

/** Runs [command] on its own exec channel and returns stdout and the exit code. */
suspend fun SshClient.run(command: String): Pair<String, Long?> {
    val session = checkNotNull(openSession()) { "openSession returned null" }
    check(session.requestExec(command)) { "exec rejected" }
    val out = ByteArrayOutputStream()
    while (true) {
        val chunk = session.read() ?: break
        out.write(chunk)
    }
    val exit = withTimeout(10_000) { session.exitInfo.await() }
    session.close()
    val code = (exit as? org.connectbot.sshlib.SessionExit.Status)?.code
    return out.toString(Charsets.UTF_8) to code
}

/** Reads shell output until [marker] shows up, returning everything read so far. */
suspend fun SshSession.readUntil(marker: String, timeoutMs: Long = 10_000): String {
    val out = StringBuilder()
    val done = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        while (marker !in out) {
            val chunk = stdout.receiveCatching().getOrNull()
                ?: error("stdout closed (exit=${exitInfo.takeIf { it.isCompleted }?.getCompleted()}) after: ${out.toString().replace("\r", "\\r").replace("\n", "\\n")}")
            out.append(String(chunk, Charsets.UTF_8))
        }
    }
    checkNotNull(done) { "timed out waiting for ${marker.trim()}, got: ${out.toString().replace("\r", "\\r").replace("\n", "\\n")}" }
    return out.toString()
}

/** SHA256 fingerprint of an OpenSSH `.pub` file, computed the same way ssh-keygen -l does. */
fun fingerprintOfPubFile(file: File): String {
    val blob = Base64.getDecoder().decode(file.readText().trim().split(" ")[1])
    return KeyFingerprint.sha256(blob)
}

/**
 * Minimal ssh-agent client (identities + sign). cbssh only ships an agent *provider* for
 * forwarding, so authenticating through the local agent needs this on top.
 */
class AgentAuthHandler(private val socket: File) : AuthHandler {
    private fun exchange(request: ByteArray): ByteBuffer {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(UnixDomainSocketAddress.of(socket.toPath()))
            val out = DataOutputStream(Channels.newOutputStream(ch))
            out.writeInt(request.size)
            out.write(request)
            out.flush()
            val input = DataInputStream(Channels.newInputStream(ch))
            val len = input.readInt()
            val body = ByteArray(len).also { input.readFully(it) }
            return ByteBuffer.wrap(body)
        }
    }

    private fun ByteBuffer.string(): ByteArray = ByteArray(int).also { get(it) }

    override suspend fun onPublicKeysNeeded(): List<AuthPublicKey> {
        val reply = exchange(byteArrayOf(11))
        check(reply.get().toInt() == 12) { "unexpected agent reply" }
        return List(reply.int) {
            val blob = reply.string()
            reply.string()
            val type = String(ByteBuffer.wrap(blob).string(), Charsets.US_ASCII)
            AuthPublicKey(type, blob)
        }
    }

    override suspend fun onSignatureRequest(key: AuthPublicKey, dataToSign: ByteArray): ByteArray? {
        val req = ByteArrayOutputStream()
        DataOutputStream(req).apply {
            writeByte(13)
            writeInt(key.publicKeyBlob.size); write(key.publicKeyBlob)
            writeInt(dataToSign.size); write(dataToSign)
            writeInt(0)
        }
        val reply = exchange(req.toByteArray())
        if (reply.get().toInt() != 14) return null
        return reply.string()
    }

    override suspend fun onKeyboardInteractivePrompt(
        name: String,
        instruction: String,
        prompts: List<KeyboardInteractiveCallback.Prompt>,
    ): List<String>? = null

    override suspend fun onPasswordNeeded(): String? = null
}
