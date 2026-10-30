package dev.eltermino.app

import dev.eltermino.core.data.FileWorkspaceStore
import dev.eltermino.core.ssh.mina.MinaConfig
import dev.eltermino.core.ssh.mina.MinaKeyTool
import dev.eltermino.core.ssh.mina.MinaSshConnector
import dev.eltermino.core.vault.EncryptedFileVault
import dev.eltermino.core.vault.MasterKeyProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlinx.coroutines.flow.first

/** Servers and throwaway credentials from testenv/README.md. */
object TestEnv {
    val keys = File(System.getProperty("testenv.dir") ?: error("testenv.dir not set"), "keys")
    const val HOST = "127.0.0.1"
    const val BASIC_PORT = 2201
    const val OTP_PORT = 2202
    const val BASTION_PORT = 2203
    const val USER = "dev"
    const val PASSWORD = "devpass"
    const val KEY_PASSPHRASE = "testpass"

    fun key(name: String): File = File(keys, name).also { check(it.exists()) { "Missing $it, run testenv/gen-keys.sh" } }

    fun totp(): String {
        val secret = base32("JBSWY3DPEHPK3PXP")
        val counter = System.currentTimeMillis() / 1000 / 30
        val hash = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
            .doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        return ((ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff) % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        for (c in text) {
            buffer = (buffer shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += (buffer shr bits).toByte()
            }
        }
        return out.toByteArray()
    }
}

/** Reads absolute paths only, like a desktop with no ~/.ssh of interest. Records notifications. */
class TestPlatform : Platform {
    override val isDesktop = true

    @Volatile
    var foreground = true
    val notifications: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())

    override val isInForeground: Boolean get() = foreground

    override fun notify(title: String, message: String) {
        notifications += title to message
    }
    override suspend fun pickFile(title: String): PickedFile? = null
    override fun readSshConfig(): String? = null
    override fun resolveSshInclude(pattern: String): List<Pair<String, String>> = emptyList()
    override fun readLocalFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()
}

/** Pass the same [dir] and [key] again to simulate a restart of the app. */
fun testServices(
    dir: File = Files.createTempDirectory("eltermino").toFile().apply { deleteOnExit() },
    key: ByteArray = Random.nextBytes(32),
    platform: TestPlatform = TestPlatform(),
): AppServices {
    val local = File(dir, "local").apply { mkdirs() }
    return AppServices(
        workspace = FileWorkspaceStore(File(dir, "workspace.json")),
        vault = EncryptedFileVault(File(dir, "vault.bin"), MasterKeyProvider { key.copyOf() }),
        connector = MinaSshConnector(MinaConfig()),
        keyTool = MinaKeyTool(),
        platform = platform,
        localFiles = LocalFileSystem(local),
        recordings = FileRecordingStore(File(dir, "recordings")),
    )
}

/** Polls [condition] until it holds, failing with [message] after [timeoutMs]. */
fun eventually(message: () -> String = { "condition not met" }, timeoutMs: Long = 20_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError(message())
        Thread.sleep(50)
    }
}

/**
 * A TCP relay to a testenv server that a test can break, like a network that drops:
 * [cut] kills the open connections, and while [up] is false new ones are refused.
 */
class TestProxy(private val targetPort: Int) : AutoCloseable {
    private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    val port: Int = server.localPort

    @Volatile
    var up = true

    private val sockets = java.util.Collections.synchronizedSet(mutableSetOf<java.net.Socket>())

    init {
        kotlin.concurrent.thread(isDaemon = true, name = "proxy-$port") {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: java.io.IOException) { break }
                if (!up) {
                    client.close()
                    continue
                }
                val upstream = try { java.net.Socket(TestEnv.HOST, targetPort) } catch (_: java.io.IOException) {
                    client.close()
                    continue
                }
                sockets += client
                sockets += upstream
                pipe(client, upstream)
                pipe(upstream, client)
            }
        }
    }

    private fun pipe(from: java.net.Socket, to: java.net.Socket) = kotlin.concurrent.thread(isDaemon = true) {
        try {
            from.getInputStream().copyTo(to.getOutputStream())
        } catch (_: java.io.IOException) {
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
            sockets -= from
            sockets -= to
        }
    }

    fun cut() {
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
    }

    override fun close() {
        server.close()
        cut()
    }
}

suspend fun WorkspaceActions.keyIdentity(): dev.eltermino.core.data.Identity {
    val key = importKey("test-ed25519", TestEnv.key("id_ed25519").readBytes())
    return saveIdentity(dev.eltermino.core.data.Identity(dev.eltermino.core.data.newId(), "dev", TestEnv.USER, keyId = key.id))
}

suspend fun WorkspaceActions.testHost(
    label: String,
    port: Int,
    identity: dev.eltermino.core.data.Identity,
    address: String = TestEnv.HOST,
    jumpHostId: String? = null,
) = dev.eltermino.core.data.Host(dev.eltermino.core.data.newId(), label, address, port, identityId = identity.id, jumpHostId = jumpHostId)
    .also { saveHost(it) }

/** Runs [command] on [host] over its shared link and returns stdout, trimmed. */
suspend fun Connections.run(host: dev.eltermino.core.data.Host, command: String): String {
    val lease = acquire(host)
    try {
        val up = kotlinx.coroutines.withTimeout(30_000) { lease.link.state.first { it is LinkState.Up || it is LinkState.Down } }
        check(up is LinkState.Up) { (up as LinkState.Down).message }
        val result = up.connection.exec(command)
        check(result.exitCode == 0) { "$command: ${result.stderr.decodeToString()}" }
        return result.stdout.decodeToString().trim()
    } finally {
        lease.release()
    }
}

fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
