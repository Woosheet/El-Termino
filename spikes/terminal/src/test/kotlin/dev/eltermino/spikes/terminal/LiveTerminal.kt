package dev.eltermino.spikes.terminal

import com.johnan.terminal.core.TerminalEmulator
import com.johnan.terminal.core.TerminalScreenState
import com.johnan.terminal.core.terminalConfig
import dev.eltermino.spikes.TestEnv
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executors

/** One SSH session to the "basic" test server, shared by all scenarios. */
class SpikeSsh : AutoCloseable {
    private val client = SshClient.setUpDefaultClient().apply {
        serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
        start()
    }

    val session: ClientSession = client.connect(TestEnv.USER, TestEnv.HOST, TestEnv.BASIC_PORT)
        .verify(Duration.ofSeconds(10)).session
        .apply {
            FileKeyPairProvider(TestEnv.key("id_ed25519").toPath()).loadKeys(this).forEach { addPublicKeyIdentity(it) }
            auth().verify(Duration.ofSeconds(10))
        }

    override fun close() {
        session.close(false).await(Duration.ofSeconds(5))
        client.stop()
    }
}

/**
 * A PTY shell whose output is fed into a live [TerminalEmulator] as it arrives, with the
 * emulator's responses (DA, CPR, ...) written back to the channel, like the real app will.
 */
class LiveTerminal(session: ClientSession, val cols: Int = 100, val rows: Int = 30) : AutoCloseable {
    private val lock = Any()
    private val raw = ByteArrayOutputStream()
    private val writer = Executors.newSingleThreadExecutor()
    val responses: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val emulator = TerminalEmulator(
        config = terminalConfig {
            initialRows = rows
            initialCols = cols
            maxScrollback = 2000
        },
        onTerminalResponse = { response ->
            responses += response
            writer.execute { send(response) }
        },
    )

    private val channel = session.createShellChannel().apply {
        setPtyType("xterm-256color")
        setPtyColumns(cols)
        setPtyLines(rows)
        setRedirectErrorStream(true)
        out = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                val chunk = b.copyOfRange(off, off + len)
                synchronized(lock) {
                    raw.write(chunk)
                    emulator.processOutputSync(chunk)
                }
            }
        }
    }

    init {
        channel.open().verify(Duration.ofSeconds(10))
    }

    fun send(text: String) {
        channel.invertedIn.write(text.toByteArray())
        channel.invertedIn.flush()
    }

    /** Sends [text] and waits until output has been quiet for [quietMs], at most [maxMs]. */
    fun sendAndSettle(text: String, quietMs: Long = 400, maxMs: Long = 8000) {
        send(text)
        settle(quietMs, maxMs)
    }

    fun settle(quietMs: Long = 400, maxMs: Long = 8000) {
        val deadline = System.currentTimeMillis() + maxMs
        var lastSize = -1
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            val size = synchronized(lock) { raw.size() }
            if (size != lastSize) {
                lastSize = size
                quietSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietSince >= quietMs) {
                return
            }
        }
    }

    fun snapshot(): Pair<TerminalScreenState, ByteArray> =
        synchronized(lock) { emulator.snapshotScreenState() to raw.toByteArray() }

    override fun close() {
        channel.close(false).await(Duration.ofSeconds(5))
        writer.shutdownNow()
    }
}

/** Plain-text dump of the visible screen, wide-char spacers dropped. */
fun TerminalScreenState.dump(): String = rows.joinToString("\n") { row ->
    row.joinToString("") { it.glyphString() }.trimEnd()
}
