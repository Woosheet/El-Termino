package dev.eltermino.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.sftp.client.SftpClientFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.time.Duration

/**
 * Phase 0 spike, run with `./gradlew :app-android:connectedDebugAndroidTest` while the
 * testenv servers are up. 10.0.2.2 is the host's loopback as seen from the emulator.
 */
@RunWith(AndroidJUnit4::class)
class MinaOnAndroidTest {
    private val timeout = Duration.ofSeconds(20)
    private lateinit var client: SshClient

    @Before
    fun setUp() {
        // Android ships a trimmed "BC" provider; MINA needs the full one.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        OsUtils.setAndroid(true)
        val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        PathUtils.setUserHomeFolderResolver { filesDir.toPath() }

        client = SshClient.setUpDefaultClient().apply {
            hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
            keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            start()
        }
    }

    @After
    fun tearDown() {
        client.stop()
    }

    private fun connect(): ClientSession = client.connect("dev", "10.0.2.2", 2201).verify(timeout).session

    @Test
    fun passwordExecAndSftp() {
        connect().use { session ->
            session.addPasswordIdentity("devpass")
            session.auth().verify(timeout)
            assertEquals("ok", session.executeRemoteCommand("echo ok").trim())
            SftpClientFactory.instance().createSftpClient(session).use { sftp ->
                assertTrue(sftp.readDir("/etc").any { it.filename == "ssh" })
            }
        }
    }

    @Test
    fun ed25519KeyGeneratedOnDevice() {
        val key = KeyUtils.generateKeyPair(KeyPairType, 256)
        val line = PublicKeyEntry.toString(key.public) + " android-spike"
        connect().use { session ->
            session.addPasswordIdentity("devpass")
            session.auth().verify(timeout)
            session.executeRemoteCommand("echo '$line' >> ~/.ssh/authorized_keys")
        }
        try {
            connect().use { session ->
                session.addPublicKeyIdentity(key)
                session.auth().verify(timeout)
                assertEquals("ok", session.executeRemoteCommand("echo ok").trim())
            }
        } finally {
            connect().use { session ->
                session.addPasswordIdentity("devpass")
                session.auth().verify(timeout)
                session.executeRemoteCommand("sed -i '/android-spike/d' ~/.ssh/authorized_keys")
            }
        }
    }

    private companion object {
        const val KeyPairType = "ssh-ed25519"
    }
}
