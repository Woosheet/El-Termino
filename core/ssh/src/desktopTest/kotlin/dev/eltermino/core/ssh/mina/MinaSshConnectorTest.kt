package dev.eltermino.core.ssh.mina

import dev.eltermino.core.ssh.AuthMethod
import dev.eltermino.core.ssh.ConnectTarget
import dev.eltermino.core.ssh.ConnectionState
import dev.eltermino.core.ssh.KeyboardInteractiveResponder
import dev.eltermino.core.ssh.PtyConfig
import dev.eltermino.core.ssh.RemoteFile
import dev.eltermino.core.ssh.ShellChannel
import dev.eltermino.core.ssh.SshConnection
import dev.eltermino.core.ssh.SshException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinaSshConnectorTest {
    private val connector = MinaSshConnector()

    @AfterAll
    fun shutdown() = connector.shutdown()

    private suspend fun connect(target: ConnectTarget, secrets: TestSecrets = TestSecrets()) =
        connector.connect(target, RecordingVerifier(), secrets)

    private suspend fun SshConnection.echo(): String = exec("echo ok").stdout.decodeToString().trim()

    private fun loginWith(vararg auth: AuthMethod) = runBlocking {
        val connection = connect(TestServers.basic(*auth))
        try {
            assertEquals("ok", connection.echo())
        } finally {
            connection.close()
        }
    }

    @Test fun password() = loginWith(TestServers.passwordAuth())

    @Test fun keyEd25519() = loginWith(TestServers.keyAuth("id_ed25519"))

    // OpenSSH 10 refuses SHA-1 "ssh-rsa" signatures, so this proves rsa-sha2-*.
    @Test fun keyRsa() = loginWith(TestServers.keyAuth("id_rsa"))

    @Test fun keyEcdsa() = loginWith(TestServers.keyAuth("id_ecdsa"))

    @Test fun keyWithPassphrase() = loginWith(TestServers.keyAuth("id_ed25519_enc", passphrase = true))

    @Test
    fun wrongKeyFallsBackToPassword() = loginWith(TestServers.keyAuth("id_cert"), TestServers.passwordAuth())

    @Test
    fun userCertificate() = loginWith(
        TestServers.keyAuth("id_cert", certificate = TestServers.keyFile("id_cert-cert.pub").readText()),
    )

    @Test
    fun keyboardInteractiveOtp() = runBlocking {
        val prompts = mutableListOf<String>()
        val responder = KeyboardInteractiveResponder { _, _, asked ->
            prompts += asked.map { it.text.trim() }
            asked.map { if (it.text.contains("password", ignoreCase = true)) TestServers.PASSWORD else TestServers.totp() }
        }
        val target = ConnectTarget(TestServers.HOST, TestServers.OTP, TestServers.USER, listOf(AuthMethod.KeyboardInteractive(responder)))
        val connection = connect(target)
        try {
            assertEquals("ok", connection.echo())
            assertEquals(listOf("Password:", "Verification code:"), prompts)
        } finally {
            connection.close()
        }
    }

    @Test
    fun wrongPasswordFails() = runBlocking {
        val secrets = TestSecrets(mapOf("password" to "nope".toByteArray()))
        assertFailsWith<SshException.AuthFailed> { connect(TestServers.basic(TestServers.passwordAuth()), secrets) }
        Unit
    }

    @Test
    fun unreachableHostFails() = runBlocking {
        val target = ConnectTarget(TestServers.HOST, 1, TestServers.USER, listOf(TestServers.passwordAuth()))
        assertFailsWith<SshException.ConnectFailed> { connect(target) }
        Unit
    }

    @Test
    fun jumpHost() = runBlocking {
        val key = TestServers.keyAuth("id_ed25519")
        val bastion = ConnectTarget(TestServers.HOST, TestServers.BASTION, TestServers.USER, listOf(key))
        val target = ConnectTarget(TestServers.INTERNAL_HOST, 22, TestServers.USER, listOf(key), jumpHosts = listOf(bastion))
        val verifier = RecordingVerifier()
        val connection = connector.connect(target, verifier, TestSecrets())
        try {
            assertTrue(connection.exec("hostname").stdout.decodeToString().isNotBlank())
            assertEquals(listOf(TestServers.HOST to TestServers.BASTION, TestServers.INTERNAL_HOST to 22), verifier.seen.map { it.first to it.second })
            verifier.seen.forEach { (_, _, fingerprint) -> assertTrue(fingerprint.sha256.startsWith("SHA256:")) }
        } finally {
            connection.close()
        }
        assertIs<ConnectionState.Closed>(connection.state.value)
    }

    @Test
    fun rejectedHostKeyNeverAuthenticates() = runBlocking {
        val secrets = TestSecrets()
        var asked = false
        val responder = KeyboardInteractiveResponder { _, _, _ -> asked = true; emptyList() }
        val target = TestServers.basic(TestServers.passwordAuth(), AuthMethod.KeyboardInteractive(responder))
        val error = assertFailsWith<SshException.HostKeyRejected> { connector.connect(target, RecordingVerifier(accept = false), secrets) }
        assertEquals(TestServers.BASIC, error.port)
        // A certificate is reduced to the key it certifies.
        assertEquals("ssh-ed25519", error.fingerprint.keyType)
        assertTrue(error.fingerprint.publicKey.startsWith("ssh-ed25519 "))
        assertEquals(0, secrets.calls.get(), "no secret may be read before the host key is accepted")
        assertTrue(!asked)
    }

    @Test
    fun hostFingerprintMatchesSshKeygen() = runBlocking {
        val verifier = RecordingVerifier()
        val connection = connector.connect(TestServers.basic(TestServers.passwordAuth()), verifier, TestSecrets())
        connection.close()
        val expected = sshKeygenFingerprint(TestServers.keyFile("host_ed25519.pub"))
        assertEquals(expected, verifier.seen.single().third.sha256)
    }

    /** Collects a shell's output in the background so tests can wait for text. */
    private class ShellReader(shell: ShellChannel, scope: CoroutineScope) {
        val text = StringBuffer()
        val job: Job = scope.launch { shell.output.collect { text.append(it.decodeToString()) } }

        suspend fun await(marker: Regex) = withTimeout(10.seconds) {
            while (marker.find(text) == null) delay(20)
        }
    }

    @Test
    fun shellWriteAndResize() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        try {
            val shell = connection.openShell(PtyConfig(cols = 100, rows = 30))
            val reader = ShellReader(shell, this)
            shell.write("stty size\n".toByteArray())
            reader.await(Regex("""\b30 100\b"""))
            shell.resize(cols = 120, rows = 40)
            shell.write("stty size\n".toByteArray())
            reader.await(Regex("""\b40 120\b"""))
            shell.write("exit\n".toByteArray())
            withTimeout(10.seconds) { reader.job.join() }
        } finally {
            connection.close()
        }
    }

    /** A raw HTTP GET through [connection]'s tunnel to the test service on the server's own loopback. */
    private suspend fun SshConnection.getThroughTunnel(path: String): String {
        val forward = forwardLocal("localhost", 8080)
        try {
            return java.net.Socket("127.0.0.1", forward.port).use { socket ->
                socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes().decodeToString()
            }
        } finally {
            forward.close()
        }
    }

    @Test
    fun localForwardReachesTheServersOwnLoopback() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        try {
            val response = connection.getThroughTunnel("/health.json")
            assertTrue(response.startsWith("HTTP/1.") && " 200 " in response.lineSequence().first(), response)
            assertTrue(response.endsWith("""{"status":"ok","host":"basic"}"""), response)
        } finally {
            connection.close()
        }
    }

    @Test
    fun localForwardListensOnTheChosenPort() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        try {
            val free = java.net.ServerSocket(0).use { it.localPort }
            val forward = connection.forwardLocal("localhost", 8080, bindPort = free)
            try {
                assertEquals(free, forward.port)
                val response = java.net.Socket("127.0.0.1", free).use { socket ->
                    socket.getOutputStream().write("GET /health.json HTTP/1.0\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes().decodeToString()
                }
                assertTrue(response.endsWith("""{"status":"ok","host":"basic"}"""), response)
            } finally {
                forward.close()
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun remoteForwardLetsTheServerReachThisMachine() = runBlocking {
        val web = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = "hello from the client".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        try {
            val forward = connection.forwardRemote(0, "127.0.0.1", web.address.port)
            try {
                assertTrue(forward.port > 0)
                val result = connection.exec("curl -sS --max-time 10 http://127.0.0.1:${forward.port}/")
                assertEquals("hello from the client", result.stdout.decodeToString(), "exit ${result.exitCode}: ${result.stderr.decodeToString()}")
            } finally {
                forward.close()
            }
        } finally {
            connection.close()
            web.stop(0)
        }
    }

    @Test
    fun dynamicForwardIsASocksProxyOutOfTheServer() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        try {
            val forward = connection.forwardDynamic(0)
            try {
                val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", forward.port))
                val response = java.net.Socket(proxy).use { socket ->
                    // Unresolved, so the name is looked up by the server: its own localhost.
                    socket.connect(java.net.InetSocketAddress.createUnresolved("localhost", 8080), 10_000)
                    socket.getOutputStream().write("GET /health.json HTTP/1.0\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes().decodeToString()
                }
                assertTrue(response.endsWith("""{"status":"ok","host":"basic"}"""), response)
            } finally {
                forward.close()
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun localForwardWorksBehindAJumpHost() = runBlocking {
        val key = TestServers.keyAuth("id_ed25519")
        val bastion = ConnectTarget(TestServers.HOST, TestServers.BASTION, TestServers.USER, listOf(key))
        val connection = connect(ConnectTarget(TestServers.INTERNAL_HOST, 22, TestServers.USER, listOf(key), jumpHosts = listOf(bastion)))
        try {
            assertTrue(connection.getThroughTunnel("/health.json").endsWith("""{"status":"ok","host":"internal"}"""))
        } finally {
            connection.close()
        }
    }

    @Test
    fun execExitCode() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        try {
            val result = connection.exec("uname -s; echo err >&2; exit 3")
            assertEquals(3, result.exitCode)
            assertEquals("Linux", result.stdout.decodeToString().trim())
            assertEquals("err", result.stderr.decodeToString().trim())
        } finally {
            connection.close()
        }
    }

    @Test
    fun sftpOperations() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        val base = "/home/dev/core-ssh-test-${System.nanoTime()}"
        try {
            val sftp = connection.openSftp()
            sftp.mkdir(base)
            sftp.mkdir("$base/sub")
            connection.exec("printf hi > $base/file.txt && ln -s file.txt $base/link && touch $base/sub/inner")

            val entries = sftp.list(base).associateBy { it.name }
            assertEquals(setOf("file.txt", "link", "sub"), entries.keys)
            assertEquals(RemoteFile.Type.File, entries.getValue("file.txt").type)
            assertEquals(2, entries.getValue("file.txt").size)
            assertEquals(RemoteFile.Type.Directory, entries.getValue("sub").type)
            assertEquals(RemoteFile.Type.Symlink, entries.getValue("link").type)
            assertEquals("file.txt", entries.getValue("link").linkTarget)
            assertEquals("$base/file.txt", entries.getValue("file.txt").path)

            sftp.rename("$base/file.txt", "$base/renamed.txt")
            sftp.chmod("$base/renamed.txt", "600".toInt(8))
            val renamed = sftp.stat("$base/renamed.txt")
            assertEquals("renamed.txt", renamed.name)
            assertEquals("600".toInt(8), renamed.permissions)
            assertTrue(renamed.modifiedEpochSeconds > 0)

            assertFailsWith<SshException.ChannelFailed> { sftp.delete(base, recursive = false) }
            sftp.delete(base, recursive = true)
            assertFailsWith<SshException.ChannelFailed> { sftp.stat(base) }
            sftp.close()
        } finally {
            connection.exec("rm -rf $base")
            connection.close()
        }
    }

    @Test
    fun sftpStreamsResumeFromAnOffset() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        val path = "/home/dev/core-ssh-stream-${System.nanoTime()}.bin"
        try {
            val sftp = connection.openSftp()
            assertEquals("/home/dev", sftp.canonicalPath("."))
            kotlin.test.assertNull(sftp.find("$path.missing"))
            val data = ByteArray(300_000) { (it % 251).toByte() }

            // An upload cut after 100 000 bytes, then resumed.
            sftp.openWrite(path).apply { write(data, 100_000); close() }
            assertEquals(100_000, sftp.stat(path).size)
            sftp.openWrite(path, offset = 100_000).apply {
                write(data.copyOfRange(100_000, data.size))
                close()
            }
            assertEquals(data.size.toLong(), sftp.stat(path).size)

            // Reading from an offset returns exactly the tail.
            val source = sftp.openRead(path, offset = 250_000)
            val tail = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                tail.write(buffer, 0, n)
            }
            source.close()
            assertTrue(tail.toByteArray().contentEquals(data.copyOfRange(250_000, data.size)))
            assertEquals(connection.exec("sha256sum < $path").stdout.decodeToString().substringBefore(' '), sha256(data))

            // Starting over truncates.
            sftp.openWrite(path).apply { write("short".encodeToByteArray()); close() }
            assertEquals(5, sftp.stat(path).size)

            sftp.setModified(path, 1_700_000_000)
            assertEquals(1_700_000_000, sftp.stat(path).modifiedEpochSeconds)

            sftp.openWrite("$path.new").apply { write("new".encodeToByteArray()); close() }
            sftp.rename("$path.new", path, overwrite = true)
            assertEquals(3, sftp.stat(path).size)
            sftp.close()
        } finally {
            connection.exec("rm -f $path $path.new")
            connection.close()
        }
    }

    private fun sha256(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun manyChannelsOnOneConnection() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        try {
            val a = connection.openShell()
            val b = connection.openShell()
            val sftp = connection.openSftp()
            val readerA = ShellReader(a, this)
            val readerB = ShellReader(b, this)
            // The arithmetic keeps the echoed command line from matching.
            a.write("echo A-\$((40+2))\n".toByteArray())
            b.write("echo B-\$((40+2))\n".toByteArray())
            readerA.await(Regex("A-42"))
            readerB.await(Regex("B-42"))
            assertEquals(RemoteFile.Type.Directory, sftp.stat("/home/dev").type)
            assertEquals("ok", connection.echo())
            a.close()
            b.close()
            sftp.close()
        } finally {
            connection.close()
        }
    }

    @Test
    fun closeMakesStateClosed() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        assertIs<ConnectionState.Connected>(connection.state.value)
        connection.close()
        assertIs<ConnectionState.Closed>(connection.state.value)
        assertFailsWith<SshException.ChannelFailed> { connection.openShell() }
        Unit
    }

    @Test
    fun serverSideDropClosesEverything() = runBlocking {
        val connection = connect(TestServers.basic(TestServers.passwordAuth()))
        val shell = connection.openShell()
        val reader = ShellReader(shell, CoroutineScope(Dispatchers.IO))
        // Kills the sshd process serving this session, as a network drop would.
        shell.write("kill -9 \$PPID\n".toByteArray())
        withTimeout(10.seconds) { connection.state.first { it is ConnectionState.Closed } }
        withTimeout(10.seconds) { reader.job.join() }
        Unit
    }

    @Test
    fun generatedKeyLogsIn() = runBlocking {
        val generated = MinaKeyTool().generateEd25519("core-ssh-test-${System.nanoTime()}")
        val comment = generated.info.publicKey.substringAfterLast(' ')
        val admin = connect(TestServers.basic(TestServers.passwordAuth()))
        try {
            admin.exec("echo '${generated.info.publicKey}' >> ~/.ssh/authorized_keys")
            val secrets = TestSecrets(mapOf("generated" to generated.privateKey))
            val target = TestServers.basic(AuthMethod.PublicKey(dev.eltermino.core.ssh.SecretRef("generated")))
            val connection = connect(target, secrets)
            assertEquals("ok", connection.echo())
            connection.close()
        } finally {
            admin.exec("sed -i '/$comment/d' ~/.ssh/authorized_keys")
            admin.close()
        }
        assertContentEquals(generated.privateKey.copyOfRange(0, 35), "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray())
    }
}

internal fun sshKeygenFingerprint(file: java.io.File): String {
    val process = ProcessBuilder("ssh-keygen", "-l", "-E", "sha256", "-f", file.path).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
    return output.trim().split(Regex("\\s+"))[1]
}
