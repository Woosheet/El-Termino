package dev.eltermino.spikes.mina

import dev.eltermino.spikes.TestEnv
import dev.eltermino.spikes.spikeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.config.hosts.HostConfigEntry
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.OpenSshCertificate
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.time.Duration
import java.util.EnumSet
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LIB = "mina"
private val TIMEOUT: Duration = Duration.ofSeconds(15)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinaSpikeTest {
    private lateinit var client: SshClient

    @BeforeAll
    fun start() {
        client = newClient()
    }

    @AfterAll
    fun stop() {
        client.stop()
    }

    /** No ~/.ssh/config, no default identities: only what each test supplies. */
    private fun newClient(verifier: ServerKeyVerifier = ServerKeyVerifier { _, _, _ -> true }): SshClient =
        SshClient.setUpDefaultClient().apply {
            hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
            keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            serverKeyVerifier = verifier
            start()
        }

    private fun connect(port: Int = TestEnv.BASIC_PORT, c: SshClient = client): ClientSession =
        c.connect(TestEnv.USER, TestEnv.HOST, port).verify(TIMEOUT).session

    private fun loadKey(name: String, passphrase: String? = null): KeyPair =
        FileKeyPairProvider(TestEnv.key(name).toPath())
            .apply { if (passphrase != null) passwordFinder = FilePasswordProvider.of(passphrase) }
            .loadKeys(null)
            .first()

    private fun ClientSession.echo(): String = executeRemoteCommand("echo ok").trim()

    private fun keyLogin(check: String, keyName: String, passphrase: String? = null) {
        connect().use { session ->
            val key = loadKey(keyName, passphrase)
            session.addPublicKeyIdentity(key)
            session.auth().verify(TIMEOUT)
            assertEquals("ok", session.echo())
            spikeResult(LIB, check, "ok", KeyUtils.getKeyType(key))
        }
    }

    @Test
    fun password() {
        connect().use { session ->
            session.addPasswordIdentity(TestEnv.PASSWORD)
            session.auth().verify(TIMEOUT)
            assertEquals("ok", session.echo())
            spikeResult(LIB, "password", "ok")
        }
    }

    @Test fun keyEd25519() = keyLogin("key-ed25519", "id_ed25519")

    // OpenSSH 10 refuses SHA-1 "ssh-rsa" signatures, so a successful login means rsa-sha2-*.
    @Test fun keyRsa() = keyLogin("key-rsa", "id_rsa")

    @Test fun keyEcdsa() = keyLogin("key-ecdsa", "id_ecdsa")

    @Test fun keyEd25519Passphrase() = keyLogin("key-ed25519-passphrase", "id_ed25519_enc", TestEnv.KEY_PASSPHRASE)

    @Test
    fun keyboardInteractiveOtp() {
        val prompts = mutableListOf<String>()
        connect(TestEnv.OTP_PORT).use { session ->
            session.userInteraction = object : UserInteraction {
                override fun interactive(
                    session: ClientSession, name: String?, instruction: String?, lang: String?,
                    prompt: Array<String>, echo: BooleanArray,
                ): Array<String> {
                    prompts += prompt
                    return prompt.map { if (it.contains("password", ignoreCase = true)) TestEnv.PASSWORD else TestEnv.totp() }
                        .toTypedArray()
                }

                override fun getUpdatedPassword(session: ClientSession, prompt: String?, lang: String?): String? = null
            }
            session.auth().verify(TIMEOUT)
            assertEquals("ok", session.echo())
            spikeResult(LIB, "keyboard-interactive-otp", "ok", "prompts=" + prompts.joinToString { it.trim() })
        }
    }

    @Test
    fun jumpHost() {
        val key = loadKey("id_ed25519")
        val jumping = newClient().apply { keyIdentityProvider = KeyIdentityProvider { listOf(key) } }
        try {
            val entry = HostConfigEntry(
                TestEnv.INTERNAL_HOST, TestEnv.INTERNAL_HOST, TestEnv.INTERNAL_PORT, TestEnv.USER,
                "${TestEnv.USER}@${TestEnv.HOST}:${TestEnv.BASTION_PORT}",
            )
            jumping.connect(entry).verify(TIMEOUT).session.use { session ->
                session.auth().verify(TIMEOUT)
                val hostname = session.executeRemoteCommand("hostname").trim()
                assertTrue(hostname.isNotEmpty())
                spikeResult(LIB, "jump-host", "ok", "HostConfigEntry.proxyJump, reached $hostname")
            }
        } finally {
            jumping.stop()
        }
    }

    @Test
    fun userCertificate() {
        val key = loadKey("id_cert")
        val certLine = TestEnv.key("id_cert-cert.pub").readText().trim()
        val cert = PublicKeyEntry.parsePublicKeyEntry(certLine).resolvePublicKey(null, emptyMap(), null)
        assertTrue(cert is OpenSshCertificate)
        connect().use { session ->
            session.addPublicKeyIdentity(KeyPair(cert, key.private))
            session.auth().verify(TIMEOUT)
            assertEquals("ok", session.echo())
            spikeResult(LIB, "user-certificate", "ok", "KeyPair(OpenSshCertificate, private)")
        }
    }

    @Test
    fun hostCertificate() {
        fun tryWith(knownHosts: File): Pair<Boolean, PublicKey?> {
            var seen: PublicKey? = null
            val verifier = KnownHostsServerKeyVerifier({ _, _, _ -> false }, knownHosts.toPath())
            val c = newClient { session, address, key -> seen = key; verifier.verifyServerKey(session, address, key) }
            return try {
                connect(c = c).use { session ->
                    session.addPasswordIdentity(TestEnv.PASSWORD)
                    session.auth().verify(TIMEOUT)
                }
                true to seen
            } catch (e: Exception) {
                false to seen
            } finally {
                c.stop()
            }
        }

        val (accepted, key) = tryWith(TestEnv.key("known_hosts_ca"))
        val wrongCa = Files.createTempFile("known_hosts_wrong", "").toFile().apply {
            writeText("@cert-authority [127.0.0.1]:2201 ${TestEnv.key("user_ca.pub").readText().trim()}\n")
        }
        val (acceptedWrong, _) = tryWith(wrongCa)
        val type = key?.let { KeyUtils.getKeyType(it) }
        if (key !is OpenSshCertificate) {
            spikeResult(LIB, "host-certificate", "partial", "negotiated $type, not a certificate")
            abort<Unit>("server certificate not negotiated")
        }
        assertTrue(accepted, "known_hosts @cert-authority should accept")
        assertFalse(acceptedWrong, "a different CA must be rejected")
        spikeResult(LIB, "host-certificate", "ok", "$type, KnownHostsServerKeyVerifier honours @cert-authority")
    }

    @Test
    fun agent() {
        val dir = Files.createTempDirectory("agt")
        val socket = dir.resolve("s")
        val agent = ProcessBuilder("ssh-agent", "-D", "-a", socket.toString()).redirectErrorStream(true).start()
        try {
            repeat(50) { if (!Files.exists(socket)) Thread.sleep(100) }
            val add = ProcessBuilder("ssh-add", TestEnv.key("id_ed25519").path)
                .apply { environment()["SSH_AUTH_SOCK"] = socket.toString() }
                .redirectErrorStream(true).start()
            assertEquals(0, add.waitFor())

            val c = newClient().apply { agentFactory = UnixSocketAgentFactory(socket) }
            try {
                connect(c = c).use { session ->
                    session.auth().verify(TIMEOUT)
                    assertEquals("ok", session.echo())
                    spikeResult(LIB, "agent", "ok", "own SshAgent over JDK UnixDomainSocket (~50 lines)")
                }
            } finally {
                c.stop()
            }
        } finally {
            agent.destroy()
            dir.toFile().deleteRecursively()
        }
    }

    private fun passwordSession(): ClientSession = connect().apply {
        addPasswordIdentity(TestEnv.PASSWORD)
        auth().verify(TIMEOUT)
    }

    /** Writes [command] to the shell and reads output until [expect] shows up. */
    private fun ChannelShell.run(command: String, expect: Regex): String {
        invertedIn.write("$command\n".toByteArray())
        invertedIn.flush()
        val output = StringBuilder()
        val buffer = ByteArray(8192)
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            if (invertedOut.available() > 0) {
                val n = invertedOut.read(buffer)
                output.append(String(buffer, 0, n))
                expect.find(output)?.let { return it.value }
            } else {
                Thread.sleep(20)
            }
        }
        error("Timed out waiting for $expect, got: $output")
    }

    private fun ClientSession.openPty(cols: Int, rows: Int): ChannelShell = createShellChannel().apply {
        ptyType = "xterm-256color"
        ptyColumns = cols
        ptyLines = rows
        open().verify(TIMEOUT)
    }

    @Test
    fun shellPtyResize() {
        passwordSession().use { session ->
            session.openPty(100, 30).use { shell ->
                assertEquals("30 100", shell.run("stty size", Regex("""\b30 100\b""")))
                shell.sendWindowChange(120, 40)
                assertEquals("40 120", shell.run("stty size", Regex("""\b40 120\b""")))
                spikeResult(LIB, "shell-pty-resize", "ok", "sendWindowChange(cols, rows)")
            }
        }
    }

    @Test
    fun exec() {
        passwordSession().use { session ->
            session.createExecChannel("uname -a; exit 3").use { channel ->
                val out = java.io.ByteArrayOutputStream()
                channel.out = out
                channel.open().verify(TIMEOUT)
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TIMEOUT)
                assertTrue(out.toString().startsWith("Linux"))
                assertEquals(3, channel.exitStatus)
                spikeResult(LIB, "exec", "ok", "exit status available")
            }
        }
    }

    private fun ClientSession.sftp(): SftpClient = SftpClientFactory.instance().createSftpClient(this)

    @Test
    fun sftpList() {
        passwordSession().use { session ->
            session.sftp().use { sftp ->
                val names = sftp.readDir("/etc").map { it.filename }
                assertTrue("ssh" in names)
                spikeResult(LIB, "sftp-list", "ok", "${names.size} entries in /etc")
            }
        }
    }

    private fun randomFile(sizeMb: Int): File = Files.createTempFile("spike", ".bin").toFile().apply {
        deleteOnExit()
        outputStream().buffered(1 shl 20).use { out ->
            val chunk = ByteArray(1 shl 20)
            repeat(sizeMb) { Random.nextBytes(chunk); out.write(chunk) }
        }
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256").let { md ->
        inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun SftpClient.upload(file: File, remote: String) =
        write(remote).use { out -> file.inputStream().use { it.copyTo(out, 1 shl 18) } }

    private fun SftpClient.download(remote: String, file: File) =
        read(remote).use { input -> file.outputStream().use { input.copyTo(it, 1 shl 18) } }

    private fun mbPerSecond(bytes: Long, nanos: Long) = "%.1f MB/s".format(bytes / 1_048_576.0 / (nanos / 1e9))

    @Test
    fun sftpThroughput() {
        val local = randomFile(100)
        val back = Files.createTempFile("spike-back", ".bin").toFile().apply { deleteOnExit() }
        passwordSession().use { session ->
            session.sftp().use { sftp ->
                val remote = "/home/dev/spike-mina-100.bin"
                val up = System.nanoTime().let { sftp.upload(local, remote); System.nanoTime() - it }
                val down = System.nanoTime().let { sftp.download(remote, back); System.nanoTime() - it }
                sftp.remove(remote)
                assertEquals(local.sha256(), back.sha256())
                spikeResult(LIB, "sftp-throughput", "ok", "up ${mbPerSecond(local.length(), up)}, down ${mbPerSecond(local.length(), down)}")
            }
        }
    }

    @Test
    fun sftpParallel() {
        val local = randomFile(25)
        passwordSession().use { session ->
            val start = System.nanoTime()
            runBlocking(Dispatchers.IO) {
                (1..4).map { i ->
                    async {
                        session.sftp().use { sftp ->
                            val remote = "/home/dev/spike-mina-par-$i.bin"
                            sftp.upload(local, remote)
                            val back = Files.createTempFile("spike-par", ".bin").toFile().apply { deleteOnExit() }
                            sftp.download(remote, back)
                            sftp.remove(remote)
                            back.sha256()
                        }
                    }
                }.awaitAll()
            }.forEach { assertEquals(local.sha256(), it) }
            val elapsed = System.nanoTime() - start
            spikeResult(LIB, "sftp-parallel", "ok", "4 channels x 25 MB up+down, total ${mbPerSecond(local.length() * 8, elapsed)}")
        }
    }

    @Test
    fun multiChannel() {
        passwordSession().use { session ->
            session.openPty(80, 24).use { a ->
                session.openPty(80, 24).use { b ->
                    session.sftp().use { sftp ->
                        // The arithmetic keeps the echoed command line from matching.
                        assertEquals("A-42", a.run("echo A-\$((40+2))", Regex("A-42")))
                        assertEquals("B-42", b.run("echo B-\$((40+2))", Regex("B-42")))
                        assertTrue(sftp.stat("/home/dev").isDirectory)
                        spikeResult(LIB, "multi-channel", "ok", "2 shells + 1 sftp on one session")
                    }
                }
            }
        }
    }

    @Test
    fun localForward() {
        passwordSession().use { session ->
            val bound = session.startLocalPortForwarding(SshdSocketAddress("127.0.0.1", 0), SshdSocketAddress("localhost", 22))
            Socket(bound.hostName, bound.port).use { socket ->
                val banner = socket.getInputStream().bufferedReader().readLine()
                assertTrue(banner.startsWith("SSH-2.0-OpenSSH"))
                spikeResult(LIB, "local-forward", "ok", banner)
            }
            session.stopLocalPortForwarding(bound)
        }
    }

    @Test
    fun securityKey() {
        // MINA parses sk-* public keys and verifies their signatures (server side), but has no
        // client signer that talks to an authenticator. Signing would go through the agent
        // or a custom Signature backed by a FIDO2 library.
        val parsesSkKeys = runCatching { Class.forName("org.apache.sshd.common.config.keys.u2f.SkED25519PublicKey") }.isSuccess
        val clientSigner = runCatching { Class.forName("org.apache.sshd.client.auth.pubkey.SkSignatureProvider") }.isSuccess
        spikeResult(LIB, "security-key", "partial", "parses sk keys=$parsesSkKeys, client signer=$clientSigner; via agent or custom Signature")
    }

    @Test
    fun hostKeyCallback() {
        var fingerprint = ""
        val c = newClient { _, _, key -> fingerprint = "${KeyUtils.getKeyType(key)} ${KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)}"; true }
        try {
            connect(c = c).use { session ->
                session.addPasswordIdentity(TestEnv.PASSWORD)
                session.auth().verify(TIMEOUT)
            }
        } finally {
            c.stop()
        }
        assertTrue(fingerprint.contains("SHA256:"))
        spikeResult(LIB, "host-key-callback", "ok", fingerprint)
    }

    @Test
    fun classpathFootprint() {
        val jars = System.getProperty("java.class.path").split(File.pathSeparator)
            .map(::File)
            .filter { f -> listOf("sshd-", "bcprov", "bcpkix", "bcutil", "slf4j-api").any { f.name.startsWith(it) } }
        val total = jars.sumOf { it.length() }
        spikeResult(LIB, "footprint", "info", "%.1f MB: ".format(total / 1_048_576.0) + jars.joinToString { it.name })
    }
}
