package dev.eltermino.spikes.cbssh

import dev.eltermino.spikes.TestEnv
import dev.eltermino.spikes.spikeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.KnownHostsVerifier
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SshCertificate
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.getOrThrow
import org.connectbot.sshlib.sk.SkAlgorithm
import org.connectbot.sshlib.sk.SkAuthHelpers
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val LIB = "cbssh"
private const val CHUNK = 32 * 1024
private const val IN_FLIGHT = 32

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CbsshSpikeTest {

    private fun spike(check: String, block: suspend () -> String) = runBlocking(Dispatchers.IO) {
        try {
            val details = block()
            spikeResult(LIB, check, "ok", details)
        } catch (e: org.opentest4j.TestAbortedException) {
            throw e
        } catch (e: Throwable) {
            spikeResult(LIB, check, "fail", "${e::class.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun unsupported(check: String, why: String): Nothing {
        spikeResult(LIB, check, "unsupported", why)
        Assumptions.abort<Nothing>(why)
        error("unreachable")
    }

    @Test @Order(1)
    fun password() = spike("password") {
        val client = connected()
        val result = client.authenticatePassword(TestEnv.USER, TestEnv.PASSWORD)
        assertIs<AuthResult.Success>(result)
        val (out, code) = client.run("whoami")
        val info = client.connectionInfo
        client.disconnect()
        assertEquals("dev", out.trim()); assertEquals(0L, code)
        "kex=${info?.kexAlgorithm} cipher=${info?.encryptionAlgorithmC2S} pq=${info?.isPostQuantumSecure}"
    }

    @Test @Order(2)
    fun keyEd25519() = spike("key-ed25519") { keyLogin("id_ed25519") }

    @Test @Order(3)
    fun keyRsa() = spike("key-rsa") {
        // OpenSSH 10 rejects plain ssh-rsa signatures, so a successful login proves rsa-sha2-*.
        keyLogin("id_rsa") + " (server refuses ssh-rsa/SHA-1, so rsa-sha2 was used)"
    }

    @Test @Order(4)
    fun keyEcdsa() = spike("key-ecdsa") { keyLogin("id_ecdsa") }

    @Test @Order(5)
    fun keyEd25519Passphrase() = spike("key-ed25519-passphrase") {
        val client = connected()
        val encrypted = client.isPrivateKeyEncrypted(TestEnv.key("id_ed25519_enc").readText())
        val wrong = client.authenticatePublicKey(TestEnv.USER, TestEnv.key("id_ed25519_enc").readText(), "wrong")
        client.keyAuth("id_ed25519_enc", TestEnv.KEY_PASSPHRASE)
        client.disconnect()
        assertTrue(encrypted)
        "isPrivateKeyEncrypted=$encrypted, wrong passphrase -> ${wrong::class.simpleName}"
    }

    private suspend fun keyLogin(key: String): String {
        val client = connected()
        client.keyAuth(key)
        val (out, code) = client.run("echo hello")
        client.disconnect()
        assertEquals("hello", out.trim()); assertEquals(0L, code)
        return "login + exec ok"
    }

    @Test @Order(6)
    fun keyboardInteractiveOtp() = spike("keyboard-interactive-otp") {
        val client = connected(config(port = TestEnv.OTP_PORT))
        val seenPrompts = mutableListOf<String>()
        val result = client.authenticateKeyboardInteractive(
            TestEnv.USER,
            object : KeyboardInteractiveCallback {
                override suspend fun onInfoRequest(
                    name: String,
                    instruction: String,
                    prompts: List<KeyboardInteractiveCallback.Prompt>,
                    respond: suspend (responses: List<String>) -> Unit,
                ) {
                    seenPrompts += prompts.map { it.text.trim() }
                    respond(prompts.map { if ("erification" in it.text) TestEnv.totp() else TestEnv.PASSWORD })
                }
            },
        )
        assertIs<AuthResult.Success>(result, "result=$result prompts=$seenPrompts")
        val (out, _) = client.run("whoami")
        client.disconnect()
        assertEquals("dev", out.trim())
        "prompts=$seenPrompts"
    }

    @Test @Order(7)
    fun jumpHost() = spike("jump-host") {
        val bastion = connected(config(port = TestEnv.BASTION_PORT))
        bastion.keyAuth()
        val tunnel = checkNotNull(bastion.openDirectTcpipTransport(TestEnv.INTERNAL_HOST, TestEnv.INTERNAL_PORT))
        val internal = connected(config(transport = tunnel))
        internal.keyAuth()
        val (hostname, _) = internal.run("hostname")
        val (bastionName, _) = bastion.run("hostname")
        internal.disconnect(); bastion.disconnect()
        assertTrue(hostname.isNotBlank() && hostname != bastionName)
        "via openDirectTcpipTransport() as the second client's TransportFactory; internal=${hostname.trim()}"
    }

    @Test @Order(8)
    fun userCertificate() = spike("user-certificate") {
        val client = connected()
        val cert = SshCertificate.decode(TestEnv.key("id_cert-cert.pub").readText())
        val result = client.authenticatePublicKey(TestEnv.USER, TestEnv.key("id_cert").readText(), cert)
        assertIs<AuthResult.Success>(result, "result=$result")
        val (out, _) = client.run("whoami")
        client.disconnect()
        assertEquals("dev", out.trim())
        "type=${cert.type} principals=${cert.principals} algo=${cert.algorithmName}"
    }

    @Test @Order(9)
    fun hostCertificate() = spike("host-certificate") {
        val knownHosts = TestEnv.key("known_hosts_ca")
        val good = SshClient(
            config(verifier = KnownHostsVerifier(knownHosts, TestEnv.HOST, TestEnv.BASIC_PORT)) { enableCertificateHostKeys() },
        )
        val goodResult = good.connect()
        val negotiated = good.connectionInfo?.serverHostKeyAlgorithm
        good.disconnect()
        assertIs<ConnectResult.Success>(goodResult, "with the right CA: $goodResult")

        // Same server, but trusting the *user* CA as host CA: must be refused.
        val wrongCa = File(TestEnv.keys.parentFile, "../spikes/cbssh/build/known_hosts_wrong_ca").canonicalFile
        wrongCa.parentFile.mkdirs()
        wrongCa.writeText("@cert-authority [127.0.0.1]:${TestEnv.BASIC_PORT} ${TestEnv.key("user_ca.pub").readText().trim()}\n")
        val bad = SshClient(
            config(verifier = KnownHostsVerifier(wrongCa, TestEnv.HOST, TestEnv.BASIC_PORT)) { enableCertificateHostKeys() },
        )
        val badResult = bad.connect()
        bad.disconnect()
        assertTrue(badResult !is ConnectResult.Success, "wrong CA accepted: $badResult")
        "hostKeyAlg=$negotiated, wrong CA -> ${badResult::class.simpleName}; needs enableCertificateHostKeys()"
    }

    @Test @Order(10)
    fun agent() = spike("agent") {
        val dir = File(TestEnv.dir, "../spikes/cbssh/build").canonicalFile.apply { mkdirs() }
        val sock = File(dir, "agent.sock").apply { delete() }
        val started = ProcessBuilder("ssh-agent", "-s", "-a", sock.absolutePath).redirectErrorStream(true).start()
        val env = started.inputStream.bufferedReader().readText()
        started.waitFor(10, TimeUnit.SECONDS)
        val pid = Regex("SSH_AGENT_PID=(\\d+)").find(env)?.groupValues?.get(1) ?: error("ssh-agent did not start: $env")
        try {
            val add = ProcessBuilder("ssh-add", TestEnv.key("id_ed25519").absolutePath)
                .redirectErrorStream(true)
                .apply { environment()["SSH_AUTH_SOCK"] = sock.absolutePath }
                .start()
            val addOut = add.inputStream.bufferedReader().readText()
            check(add.waitFor() == 0) { "ssh-add failed: $addOut" }

            val client = connected()
            val result = client.authenticate(TestEnv.USER, AgentAuthHandler(sock))
            assertIs<AuthResult.Success>(result, "result=$result")
            val (out, _) = client.run("whoami")
            client.disconnect()
            assertEquals("dev", out.trim())
            "no built-in agent client: ~60-line AgentAuthHandler (AuthHandler + JDK unix socket) on top"
        } finally {
            ProcessBuilder("kill", pid).start().waitFor()
            sock.delete()
        }
    }

    @Test @Order(11)
    fun shellPtyResize() = spike("shell-pty-resize") {
        val client = connected()
        client.keyAuth()
        val shell = checkNotNull(client.openSession())
        check(shell.requestPty("xterm-256color", 100, 30))
        check(shell.requestShell())
        // Quoted markers: the PTY echo shows "__A""__", only the command output shows __A__.
        shell.write("stty size; echo \"__A\"\"__\"\n".toByteArray())
        val first = shell.readUntil("\n__A__")
        check(shell.resizeTerminal(120, 40, 0, 0)) { "resize rejected" }
        shell.write("stty size; echo \"__B\"\"__\"\n".toByteArray())
        val second = shell.readUntil("\n__B__")
        shell.close(); client.disconnect()
        assertTrue("30 100" in first, first)
        assertTrue("40 120" in second, second)
        "stty: 30 100 -> 40 120"
    }

    @Test @Order(12)
    fun exec() = spike("exec") {
        val client = connected()
        client.keyAuth()
        val (out, code) = client.run("uname -a")
        val (_, failing) = client.run("exit 3")
        client.disconnect()
        assertEquals(0L, code); assertEquals(3L, failing)
        "exit=$code / 3, '${out.trim().take(40)}...'"
    }

    @Test @Order(13)
    fun sftpList() = spike("sftp-list") {
        val client = connected()
        client.keyAuth()
        val sftp = client.openSftp().getOrThrow()
        val entries = sftp.listdir("/etc").getOrThrow()
        val version = sftp.protocolVersion
        val ext = sftp.extensions
        sftp.close(); client.disconnect()
        assertTrue(entries.any { it.filename == "passwd" })
        "${entries.size} entries, SFTP v$version, extensions=${ext.sorted()}"
    }

    @Test @Order(14)
    fun sftpThroughput() = spike("sftp-throughput") {
        val local = tempRandomFile("up-100m.bin", 100L * 1024 * 1024)
        val back = File(local.parentFile, "down-100m.bin")
        val runs = listOf(
            Triple("default cipher", 32 * 1024, 32),
            Triple("default cipher", 128 * 1024, 16),
            Triple("aes128-gcm@openssh.com", 128 * 1024, 16),
        )
        runs.map { (cipher, chunk, inFlight) ->
            val cfg = if (cipher.startsWith("default")) config() else config { encryptionAlgorithms = cipher }
            val client = connected(cfg)
            client.keyAuth()
            val negotiated = client.connectionInfo?.encryptionAlgorithmC2S
            val sftp = client.openSftp().getOrThrow()
            val remote = "/home/dev/spike-cbssh-100m.bin"
            back.delete()
            val upMs = timed { upload(sftp, local, remote, chunk, inFlight) }
            val downMs = timed { download(sftp, remote, back, chunk, inFlight) }
            sftp.remove(remote)
            sftp.close(); client.disconnect()
            assertEquals(sha256(local), sha256(back), "checksum mismatch")
            "$negotiated ${chunk / 1024}KBx$inFlight: up ${mbps(local.length(), upMs)} / down ${mbps(local.length(), downMs)} MB/s"
        }.joinToString("; ") + " (100 MB, sha256 match)"
    }

    @Test @Order(15)
    fun sftpParallel() = spike("sftp-parallel") {
        val size = 25L * 1024 * 1024
        val files = (1..4).map { tempRandomFile("par-$it.bin", size) }
        val client = connected()
        client.keyAuth()
        val sessions = files.map { client.openSftp().getOrThrow() }
        val ms = timed {
            coroutineScope {
                files.zip(sessions).mapIndexed { i, (file, sftp) ->
                    async { upload(sftp, file, "/home/dev/spike-cbssh-par-$i.bin", 128 * 1024, 16) }
                }.awaitAll()
            }
        }
        val sizes = sessions.mapIndexed { i, sftp -> sftp.stat("/home/dev/spike-cbssh-par-$i.bin").getOrThrow().size }
        sessions.forEachIndexed { i, sftp -> sftp.remove("/home/dev/spike-cbssh-par-$i.bin"); sftp.close() }
        client.disconnect()
        assertTrue(sizes.all { it == size }, "sizes=$sizes")
        "4 SFTP channels x 25 MB upload (128KBx16 each) on one connection: ${mbps(4 * size, ms)} MB/s total"
    }

    @Test @Order(16)
    fun multiChannel() = spike("multi-channel") {
        val client = connected()
        client.keyAuth()
        val shells = (1..2).map { n ->
            checkNotNull(client.openSession()).also {
                check(it.requestPty("xterm-256color", 80, 24)); check(it.requestShell())
            }
        }
        val sftp = client.openSftp().getOrThrow()
        coroutineScope {
            shells.forEachIndexed { i, shell -> launch { shell.write("echo \"shell-$i\"\"-alive\"\n".toByteArray()) } }
        }
        val outs = shells.mapIndexed { i, shell -> shell.readUntil("shell-$i-alive\r\n") }
        val home = sftp.listdir("/home/dev").getOrThrow()
        val (execOut, _) = client.run("echo exec-alive")
        shells.forEach { it.close() }; sftp.close(); client.disconnect()
        assertTrue(outs.size == 2 && execOut.trim() == "exec-alive")
        "2 shells + 1 sftp (${home.size} entries) + 1 exec on one connection"
    }

    @Test @Order(17)
    fun localForward() = spike("local-forward") {
        val client = connected()
        client.keyAuth()
        val forward = checkNotNull(client.localPortForward(0, "localhost", 22))
        val banner = withContext(Dispatchers.IO) {
            Socket("127.0.0.1", forward.boundPort).use { it.getInputStream().bufferedReader().readLine() }
        }
        forward.stop(); client.disconnect()
        assertTrue(banner.startsWith("SSH-2.0-OpenSSH"), banner)
        "127.0.0.1:${forward.boundPort} -> localhost:22 read '$banner'; remote and dynamic (SOCKS5) also in API"
    }

    @Test @Order(18)
    fun securityKey() = spike("security-key") {
        // No hardware here: check the hook exists and the key blob encodes.
        val key = SkAuthHelpers.buildAuthPublicKey(SkAlgorithm.ED25519, ByteArray(32) { 7 }, "ssh:")
        assertEquals("sk-ssh-ed25519@openssh.com", key.algorithmName)
        "AuthHandler.onPublicKeysNeeded() returns SkAuthHelpers.buildAuthPublicKey(...); " +
            "onSignatureRequest() calls our CTAP2 stack and returns SkSignatureBlob.pack(...). " +
            "Algorithms: ${SkAlgorithm.entries.map { it.sshName }}. Not exercised with hardware."
    }

    @Test @Order(19)
    fun hostKeyCallback() = spike("host-key-callback") {
        val recorder = RecordingVerifier()
        val client = connected(config(verifier = recorder))
        client.disconnect()
        val (type, fingerprint) = recorder.seen.single()
        val expected = fingerprintOfPubFile(TestEnv.key("host_ed25519.pub"))
        assertEquals("ssh-ed25519", type)
        assertEquals(expected, fingerprint)

        val rejected = SshClient(config(verifier = RecordingVerifier(accept = false))).connect()
        assertIs<ConnectResult.HostKeyRejected>(rejected)
        "suspend verify(PublicKey(type, blob)); KeyFingerprint.sha256 = $fingerprint matches ssh-keygen; reject -> HostKeyRejected"
    }

    // --- transfer helpers -------------------------------------------------------------

    private suspend fun upload(sftp: SftpClient, file: File, remote: String, chunk: Int = CHUNK, inFlight: Int = IN_FLIGHT) {
        val handle = sftp.open(remote, setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, SftpOpenFlag.TRUNCATE)).getOrThrow()
        val gate = Semaphore(inFlight)
        coroutineScope {
            file.inputStream().buffered(1 shl 20).use { input ->
                var offset = 0L
                while (true) {
                    val buf = ByteArray(chunk)
                    val n = input.readNBytes(buf, 0, chunk)
                    if (n <= 0) break
                    val at = offset
                    val data = if (n == chunk) buf else buf.copyOf(n)
                    gate.acquire()
                    launch {
                        try { sftp.write(handle, at, data).getOrThrow() } finally { gate.release() }
                    }
                    offset += n
                }
            }
        }
        sftp.close(handle).getOrThrow()
    }

    private suspend fun download(sftp: SftpClient, remote: String, target: File, chunk: Int = CHUNK, inFlight: Int = IN_FLIGHT) {
        val size = checkNotNull(sftp.stat(remote).getOrThrow().size)
        val handle = sftp.open(remote, setOf(SftpOpenFlag.READ)).getOrThrow()
        val gate = Semaphore(inFlight)
        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(size)
            coroutineScope {
                var offset = 0L
                while (offset < size) {
                    val at = offset
                    gate.acquire()
                    launch {
                        try {
                            var pos = at
                            val end = minOf(at + chunk, size)
                            while (pos < end) {
                                val result = sftp.read(handle, pos, (end - pos).toInt())
                                val bytes = (result as? SftpResult.Success)?.value ?: error("read at $pos: $result")
                                synchronized(raf) { raf.seek(pos); raf.write(bytes) }
                                pos += bytes.size
                            }
                        } finally { gate.release() }
                    }
                    offset += chunk
                }
            }
        }
        sftp.close(handle).getOrThrow()
    }

    private fun tempRandomFile(name: String, size: Long): File {
        val dir = File(TestEnv.dir, "../spikes/cbssh/build/tmp-data").canonicalFile.apply { mkdirs() }
        val file = File(dir, name)
        if (file.length() != size) {
            val rnd = Random(name.hashCode())
            file.outputStream().buffered(1 shl 20).use { out ->
                val buf = ByteArray(1 shl 20)
                var left = size
                while (left > 0) {
                    rnd.nextBytes(buf)
                    val n = minOf(left, buf.size.toLong()).toInt()
                    out.write(buf, 0, n); left -= n
                }
            }
        }
        return file
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream().buffered(1 shl 20), md).use { input ->
            val buf = ByteArray(1 shl 20)
            while (input.read(buf) >= 0) Unit
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun timed(block: suspend () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun mbps(bytes: Long, ms: Long) = "%.1f".format(bytes / 1024.0 / 1024.0 / (ms / 1000.0))
}
