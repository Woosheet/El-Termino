package dev.eltermino.core.terminal

import androidx.compose.ui.input.key.Key
import dev.eltermino.core.ssh.PtyConfig
import dev.eltermino.core.ssh.ShellChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.io.OutputStream
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue

/** A [ShellChannel] over a MINA shell, standing in for core/ssh's real one in these tests. */
private class MinaTestShell(session: ClientSession, pty: PtyConfig) : ShellChannel {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val channel: ChannelShell = session.createShellChannel().apply {
        ptyType = pty.term
        ptyColumns = pty.cols
        ptyLines = pty.rows
        setRedirectErrorStream(true)
        out = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                incoming.trySend(b.copyOfRange(off, off + len))
            }
        }
        addCloseFutureListener { incoming.close() }
        open().verify(Duration.ofSeconds(10))
    }

    override val output: Flow<ByteArray> = incoming.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        channel.invertedIn.write(bytes)
        channel.invertedIn.flush()
    }

    override suspend fun resize(cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        channel.sendWindowChange(cols, rows)
    }

    override suspend fun close() {
        channel.close(false)
    }
}

/** Drives htop and vim on the testenv "basic" server through [TerminalView]. Skipped without testenv. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveTerminalViewTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: SshClient? = null
    private var ssh: ClientSession? = null

    @BeforeAll
    fun connect() {
        val key = File(System.getProperty("testenv.dir").orEmpty(), "keys/id_ed25519")
        assumeTrue(key.exists(), "testenv keys not generated")
        val c = SshClient.setUpDefaultClient().apply {
            hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
            keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            start()
        }
        client = c
        val session = runCatching { c.connect("dev", "127.0.0.1", 2201).verify(Duration.ofSeconds(5)).session }.getOrNull()
        assumeTrue(session != null, "testenv server not reachable")
        session!!.addPublicKeyIdentity(FileKeyPairProvider(key.toPath()).loadKeys(null).first())
        session.auth().verify(Duration.ofSeconds(10))
        ssh = session
    }

    @AfterAll
    fun disconnect() {
        scope.cancel()
        ssh?.close(false)
        client?.stop()
    }

    private fun quiet(session: TerminalSession, quietMs: Long = 500, maxMs: Long = 8000) {
        val deadline = System.currentTimeMillis() + maxMs
        var last = session.screenState.value
        var since = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            val now = session.screenState.value
            if (now !== last) {
                last = now
                since = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - since >= quietMs) {
                return
            }
        }
    }

    @Test
    fun htopAndVimThroughTheView() {
        val config = TerminalSessionConfig()
        val session = TerminalSession(MinaTestShell(ssh!!, config.ptyConfig()), scope, config)
        HeadlessView(session, 1800, 1000).use { view ->
            view.frames(5)
            eventually({ "size ${session.size.value}" }) { session.size.value != TerminalSize(80, 24) }
            val size = session.size.value
            view.frames(3)
            session.type("export LANG=en_US.UTF-8 PS1='\\$ '; clear; stty size\n")
            eventually({ session.screenState.value.dump() }) { "${size.rows} ${size.cols}" in session.screenState.value.dump() }

            session.type("htop -d 10\n")
            Thread.sleep(2500)
            view.frames(3)
            view.savePng(File(snapshotDir, "live-htop.png"))
            assertTrue("F10" in session.screenState.value.dump(), session.screenState.value.dump())
            // F10 through the view's key handler quits htop.
            view.press(Key.F10)
            eventually({ session.screenState.value.dump() }) { "F10Quit" !in session.screenState.value.dump().replace(" ", "") }
            quiet(session)

            session.type("vim -c 'syntax on' -c 'set number' /etc/ssh/sshd_config\n")
            quiet(session, quietMs = 800)
            view.savePng(File(snapshotDir, "live-vim.png"))
            assertTrue("sshd_config" in session.screenState.value.dump(), session.screenState.value.dump())
            view.press(Key.Escape, 27)
            session.type(":q!\n")
            quiet(session)

            // Ctrl+C from the view interrupts a running command.
            session.type("sleep 30; echo slept\n")
            Thread.sleep(500)
            view.press(Key.C, 3, ctrl = true)
            session.type("echo interrupted\n")
            eventually({ session.screenState.value.dump() }) { session.screenState.value.dump().lines().any { it.trim() == "interrupted" } }
            assertTrue(session.screenState.value.dump().lines().none { it.trim() == "slept" })
        }
    }
}
