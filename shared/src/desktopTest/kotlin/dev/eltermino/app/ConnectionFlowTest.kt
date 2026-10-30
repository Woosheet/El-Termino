package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.data.TrustedCa
import dev.eltermino.core.data.newId
import dev.eltermino.core.data.trust
import dev.eltermino.core.ssh.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Drives [SessionsController] the way the UI does, against the testenv servers: every
 * dialog is answered by [answer], and we check which dialogs appeared.
 */
class ConnectionFlowTest {
    private val services = testServices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actions = WorkspaceActions(services)
    private val sessions = SessionsController(services, actions, scope)
    private val asked = mutableListOf<String>()

    /** Default answers: trust new host keys, give testenv credentials. */
    private var answer: (PendingPrompt<*>) -> Unit = { prompt ->
        when (prompt) {
            is PendingPrompt.HostKey -> prompt.answer(true)
            is PendingPrompt.Secret -> prompt.answer(
                SecretAnswer((if ("Passphrase" in prompt.title) TestEnv.KEY_PASSPHRASE else TestEnv.PASSWORD).encodeToByteArray(), save = saveSecrets),
            )
            is PendingPrompt.Text -> prompt.answer(TestEnv.USER)
            is PendingPrompt.Interactive -> prompt.answer(prompt.prompts.map { if ("assword" in it.text) TestEnv.PASSWORD else TestEnv.totp() })
        }
    }
    private var saveSecrets = false

    init {
        scope.launch {
            sessions.prompter.prompt.filterNotNull().collect { prompt ->
                asked += when (prompt) {
                    is PendingPrompt.HostKey -> "hostkey:${prompt.host}:${prompt.port}" + if (prompt.previous != null) ":changed" else ""
                    is PendingPrompt.Secret -> "secret:${prompt.title}"
                    is PendingPrompt.Text -> "text:${prompt.title}"
                    is PendingPrompt.Interactive -> "interactive:" + prompt.prompts.joinToString { it.text.trim() }
                }
                answer(prompt)
            }
        }
    }

    @AfterEach
    fun tearDown() {
        sessions.tabs.value.forEach { sessions.close(it) }
        Thread.sleep(200)
        scope.cancel()
    }

    private suspend fun host(label: String, port: Int, username: String? = TestEnv.USER, identityId: String? = null, jumpHostId: String? = null) =
        Host(newId(), label, if (label == "internal") "internal" else TestEnv.HOST, if (label == "internal") 22 else port, username = username, identityId = identityId, jumpHostId = jumpHostId)
            .also { actions.saveHost(it) }

    private fun connectAndWait(host: Host): TabStatus = runBlocking {
        val tab = sessions.connect(host)
        withTimeout(30_000) { tab.status.first { it !is TabStatus.Connecting } }
    }

    private fun TabStatus.assertWorks() {
        assertIs<TabStatus.Open>(this, (this as? TabStatus.Failed)?.message)
        assertEquals("ok", runBlocking { connection.exec("echo ok").stdout.decodeToString().trim() })
    }

    @Test
    fun passwordIsAskedOnceThenSaved() = runBlocking {
        saveSecrets = true
        val host = host("basic", TestEnv.BASIC_PORT)
        connectAndWait(host).assertWorks()
        assertEquals(listOf("hostkey:127.0.0.1:2201", "secret:Password per dev@127.0.0.1"), asked, "host key first, then the password")

        val saved = services.workspace.workspace.value.host(host.id)
        val identity = assertNotNull(services.workspace.workspace.value.identity(saved?.identityId))
        assertNotNull(identity.passwordSecretId)

        // Tabs on one host share a connection: close it so the second login is a real one.
        val first = ((sessions.tabs.value.single() as SessionTab).status.value as TabStatus.Open).connection
        sessions.closeAll()
        withTimeout(10_000) { first.state.first { it is ConnectionState.Closed } }
        asked.clear()
        val second = connectAndWait(saved!!)
        second.assertWorks()
        assertNotSame(first, (second as TabStatus.Open).connection)
        assertEquals(emptyList(), asked, "second login needs no dialog")
    }

    private suspend fun trustTestCa(vararg patterns: String) {
        val key = TestEnv.key("host_ca.pub").readText().trim()
        val ca = TrustedCa(newId(), "Test", key, services.keyTool.publicKeyFingerprint(key), patterns.toList())
        services.workspace.update { it.copy(trustedCas = listOf(ca)) }
    }

    @Test
    fun caTrustedHostConnectsWithoutHostKeyPrompt() = runBlocking {
        trustTestCa("[127.0.0.1]:2201")
        connectAndWait(host("basic", TestEnv.BASIC_PORT)).assertWorks()
        assertTrue(asked.none { it.startsWith("hostkey") }, asked.toString())
    }

    @Test
    fun expiredCertificateOfTrustedCaIsRefusedWithoutAPrompt() = runBlocking {
        trustTestCa("[127.0.0.1]:2204")
        val status = connectAndWait(host("expired", TestEnv.EXPIRED_PORT))
        assertTrue("scaduto" in assertIs<TabStatus.Failed>(status).message)
        assertEquals(emptyList(), asked, "nothing to click through, and no password sent")
    }

    @Test
    fun expiredCertificateWithoutCaIsAnOrdinaryKey() = runBlocking {
        connectAndWait(host("expired", TestEnv.EXPIRED_PORT)).assertWorks()
        assertTrue("hostkey:127.0.0.1:2204" in asked)
    }

    @Test
    fun caCheckedOnEveryHop() = runBlocking {
        trustTestCa("[127.0.0.1]:2204")
        val jump = host("expired", TestEnv.EXPIRED_PORT)
        val status = connectAndWait(host("basic", TestEnv.BASIC_PORT, jumpHostId = jump.id))
        assertTrue("scaduto" in assertIs<TabStatus.Failed>(status).message)
    }

    @Test
    fun renewedKeyUnderCaReplacesThePin() = runBlocking {
        trustTestCa("[127.0.0.1]:2201")
        services.workspace.update { it.trust(KnownHost(TestEnv.HOST, TestEnv.BASIC_PORT, "ssh-ed25519", "SHA256:vecchia", "ssh-ed25519 AAAA", 0)) }
        val renewal = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { sessions.connections.keyRenewals.first() }
        connectAndWait(host("basic", TestEnv.BASIC_PORT)).assertWorks()
        assertEquals(TestEnv.HOST, withTimeout(5_000) { renewal.await() })
        assertTrue(services.workspace.workspace.value.knownHosts.none { it.fingerprintSha256 == "SHA256:vecchia" })
    }

    @Test
    fun otpGoesThroughKeyboardInteractive() {
        val host = runBlocking { host("otp", TestEnv.OTP_PORT) }
        // No stored password: the planner asks for one, then PAM asks again with the OTP.
        connectAndWait(host).assertWorks()
        assertTrue(asked.any { it.startsWith("interactive:") && "Verification code:" in it }, asked.toString())
    }

    @Test
    fun keyIdentityNeedsNoPassword() = runBlocking {
        val key = actions.importKey("test-ed25519", TestEnv.key("id_ed25519").readBytes())
        val identity = actions.saveIdentity(Identity(newId(), "dev con chiave", TestEnv.USER, keyId = key.id))
        val host = host("basic", TestEnv.BASIC_PORT, username = null, identityId = identity.id)
        connectAndWait(host).assertWorks()
        assertEquals(listOf("hostkey:127.0.0.1:2201"), asked)
    }

    @Test
    fun encryptedKeyAsksForItsPassphrase() = runBlocking {
        val key = actions.importKey("enc", TestEnv.key("id_ed25519_enc").readBytes(), TestEnv.KEY_PASSPHRASE.encodeToByteArray())
        val identity = actions.saveIdentity(Identity(newId(), "dev enc", TestEnv.USER, keyId = key.id))
        connectAndWait(host("basic", TestEnv.BASIC_PORT, null, identity.id)).assertWorks()
        assertTrue("secret:Passphrase della chiave enc" in asked, asked.toString())
    }

    @Test
    fun jumpHostVerifiesBothHops() = runBlocking {
        val key = actions.importKey("test-ed25519", TestEnv.key("id_ed25519").readBytes())
        val identity = actions.saveIdentity(Identity(newId(), "dev", TestEnv.USER, keyId = key.id))
        val bastion = host("bastion", TestEnv.BASTION_PORT, null, identity.id)
        val internal = host("internal", 22, null, identity.id, jumpHostId = bastion.id)
        connectAndWait(internal).assertWorks()
        assertEquals(listOf("hostkey:127.0.0.1:2203", "hostkey:internal:22"), asked)
    }

    @Test
    fun changedHostKeyIsFlaggedAndRefusing() = runBlocking {
        services.workspace.update {
            it.trust(KnownHost(TestEnv.HOST, TestEnv.BASIC_PORT, "ssh-ed25519", "SHA256:not-the-real-one", "ssh-ed25519 AAAA", 0))
        }
        answer = { prompt -> if (prompt is PendingPrompt.HostKey) prompt.answer(false) else prompt.cancel() }
        val status = connectAndWait(host("basic", TestEnv.BASIC_PORT))
        assertEquals(listOf("hostkey:127.0.0.1:2201:changed"), asked)
        assertIs<TabStatus.Failed>(status)
        assertTrue("rifiutata" in status.message, status.message)
    }

    @Test
    fun cancellingADialogStopsTheConnection() {
        answer = { it.cancel() }
        val status = connectAndWait(runBlocking { host("basic", TestEnv.BASIC_PORT) })
        assertIs<TabStatus.Failed>(status)
    }

    @Test
    fun importedSshConfigConnectsThroughItsJumpHost() = runBlocking {
        val keyPath = TestEnv.key("id_ed25519").absolutePath
        val config = """
            Host bastion
                HostName 127.0.0.1
                Port 2203
            Host internal
                ProxyJump bastion
            Host *
                User dev
                IdentityFile $keyPath
        """.trimIndent()
        val importer = ImportService(services, actions)
        val parsed = importer.parseSshConfig(config)
        val summary = importer.apply(parsed.hosts)
        assertEquals(2, summary.hostsAdded)
        assertEquals(1, summary.keysImported)

        val workspace = services.workspace.workspace.value
        val internal = workspace.hosts.single { it.label == "internal" }
        assertEquals("bastion", workspace.host(internal.jumpHostId)?.label)
        assertEquals(1, workspace.identities.size, "both hosts share the dev + key identity")

        connectAndWait(internal).assertWorks()

        val again = importer.apply(parsed.hosts)
        assertEquals(0, again.hostsAdded)
        assertEquals(2, again.duplicatesSkipped)
    }
}
