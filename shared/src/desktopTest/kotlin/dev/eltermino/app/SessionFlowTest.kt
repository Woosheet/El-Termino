package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.newId
import dev.eltermino.core.ssh.ConnectionState
import dev.eltermino.core.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Phase 2 against the testenv servers: shared connections, reconnects, broadcast, snippets. */
class SessionFlowTest {
    private val services = testServices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actions = WorkspaceActions(services)
    private val sessions = SessionsController(services, actions, scope, ReconnectPolicy(List(40) { 150.milliseconds }))
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        scope.launch {
            sessions.prompter.prompt.filterNotNull().collect { prompt ->
                when (prompt) {
                    is PendingPrompt.HostKey -> {
                        asked += "hostkey:${prompt.host}:${prompt.port}"
                        prompt.answer(true)
                    }
                    is PendingPrompt.Secret -> {
                        asked += "secret"
                        prompt.answer(SecretAnswer(TestEnv.PASSWORD.encodeToByteArray(), save = false))
                    }
                    else -> prompt.cancel()
                }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        sessions.closeAll()
        Thread.sleep(300)
        scope.cancel()
    }

    private suspend fun keyIdentity(): Identity {
        val key = actions.importKey("test-ed25519", TestEnv.key("id_ed25519").readBytes())
        return actions.saveIdentity(Identity(newId(), "dev", TestEnv.USER, keyId = key.id))
    }

    private suspend fun host(label: String, port: Int, identity: Identity?, address: String = TestEnv.HOST, jumpHostId: String? = null) =
        Host(newId(), label, address, port, username = if (identity == null) TestEnv.USER else null, identityId = identity?.id, jumpHostId = jumpHostId)
            .also { actions.saveHost(it) }

    private fun awaitOpen(tab: SessionTab): TabStatus.Open = runBlocking {
        val status = withTimeout(30_000) { tab.status.first { it is TabStatus.Open || it is TabStatus.Failed || it is TabStatus.Closed } }
        assertIs<TabStatus.Open>(status, (status as? TabStatus.Failed)?.message ?: (status as? TabStatus.Closed)?.message)
    }

    private fun SshConnection.run(command: String) = runBlocking { exec(command).stdout.decodeToString().trim() }

    @Test
    fun secondTabSharesTheConnectionWithoutLoggingInAgain() {
        val host = runBlocking { host("basic", TestEnv.BASIC_PORT, identity = null) }
        // Both at once: the second tab waits for the first login instead of starting its own.
        val first = sessions.connect(host)
        val second = sessions.connect(host)
        val a = awaitOpen(first)
        val b = awaitOpen(second)
        assertSame(a.connection, b.connection)
        assertNotSame(a.terminal, b.terminal)
        assertEquals(1, asked.count { it == "secret" }, asked.toString())

        val third = sessions.duplicate(second)!!
        assertSame(a.connection, awaitOpen(third).connection)
        assertEquals(1, asked.count { it == "secret" }, "a duplicate needs no login")
        assertEquals(listOf("basic · 1", "basic · 2", "basic · 3"), sessions.tabs.value.titles().values.toList())
    }

    @Test
    fun exitingAShellClosesOnlyItsTab() {
        val host = runBlocking { host("basic", TestEnv.BASIC_PORT, keyIdentity()) }
        val first = sessions.connect(host)
        val second = sessions.connect(host)
        val a = awaitOpen(first)
        awaitOpen(second)

        a.terminal.type("exit\n")
        val closed = runBlocking { withTimeout(10_000) { first.status.first { it is TabStatus.Closed } } } as TabStatus.Closed
        assertTrue("terminata" in closed.message, closed.message)
        assertIs<TabStatus.Open>(second.status.value)
        assertEquals("ok", a.connection.run("echo ok"), "the other tab keeps the connection")

        sessions.close(second)
        eventually({ "the last tab closes the connection" }) { a.connection.state.value is ConnectionState.Closed }
    }

    @Test
    fun droppedConnectionComesBackInTheSameTerminal() = TestProxy(TestEnv.BASIC_PORT).use { proxy ->
        val host = runBlocking { host("via-proxy", proxy.port, keyIdentity()) }
        val tab = sessions.connect(host)
        val before = awaitOpen(tab)
        before.terminal.type("echo before-drop\n")
        eventually({ before.terminal.snapshotText() }) { "before-drop" in before.terminal.snapshotText().substringAfter("echo before-drop") }

        proxy.up = false
        proxy.cut()
        // Still down: it keeps trying.
        runBlocking { withTimeout(15_000) { tab.status.first { it is TabStatus.Reconnecting && it.attempt >= 3 } } }
        proxy.up = true

        val after = awaitOpen(tab)
        assertSame(before.terminal, after.terminal, "same screen and scrollback")
        assertNotSame(before.connection, after.connection)
        assertEquals("ok", after.connection.run("echo ok"))
        val text = after.terminal.snapshotText()
        assertTrue("before-drop" in text && "Riconnesso" in text, text)
        assertEquals(1, asked.count { it.startsWith("hostkey:") }, "the key is trusted from the first time")

        after.terminal.type("echo after-reconnect\n")
        eventually({ after.terminal.snapshotText() }) { "after-reconnect" in after.terminal.snapshotText().substringAfter("echo after-reconnect") }
    }

    @Test
    fun broadcastReachesTheChosenTabsOnly() {
        val identity = runBlocking { keyIdentity() }
        val basic = runBlocking { host("basic", TestEnv.BASIC_PORT, identity) }
        val bastion = runBlocking { host("bastion", TestEnv.BASTION_PORT, identity) }
        val a = sessions.connect(basic)
        val left = sessions.connect(basic)
        val b = sessions.connect(bastion)
        val openA = awaitOpen(a)
        awaitOpen(left)
        val openB = awaitOpen(b)

        sessions.setBroadcast(setOf(a.id, b.id))
        Thread.sleep(300)
        val file = "/tmp/bc-${newId()}"
        // Each shell appends its own PID: one line per host means one shell each.
        openA.terminal.type("echo \$\$ >> $file\n")
        eventually({ "bastion: " + openB.connection.run("cat $file") }) {
            openB.connection.run("wc -l < $file 2>/dev/null") == "1" && openA.connection.run("wc -l < $file 2>/dev/null") == "1"
        }
        Thread.sleep(500)
        assertEquals("1", openA.connection.run("wc -l < $file"), "the tab outside the broadcast got nothing")

        sessions.close(b)
        assertEquals(emptySet(), sessions.broadcast.value, "one member left is no broadcast")
    }

    @Test
    fun snippetRunsInANewSessionOnEachHost() {
        val identity = runBlocking { keyIdentity() }
        val hosts = runBlocking { listOf(host("basic", TestEnv.BASIC_PORT, identity), host("bastion", TestEnv.BASTION_PORT, identity)) }
        val file = "/tmp/snip-${newId()}"
        val command = fillSnippet("echo {{parola}} > $file", mapOf("parola" to "ciao"))
        sessions.runSnippet(command, run = true, SnippetTarget.Hosts(hosts.map { it.id }))

        eventually({ "tabs: ${sessions.tabs.value.size}" }) { sessions.tabs.value.size == 2 }
        val connections = sessions.tabs.value.filterIsInstance<SessionTab>().map { awaitOpen(it).connection }
        eventually({ connections.joinToString { it.run("cat $file 2>&1") } }) { connections.all { it.run("cat $file 2>/dev/null") == "ciao" } }
    }

    @Test
    fun hostsBehindTheSameJumpHostEachGetTheirOwnLink() {
        val identity = runBlocking { keyIdentity() }
        val bastion = runBlocking { host("bastion", TestEnv.BASTION_PORT, identity) }
        val internal = runBlocking { host("internal", 22, identity, address = "internal", jumpHostId = bastion.id) }
        val direct = awaitOpen(sessions.connect(bastion))
        val through = awaitOpen(sessions.connect(internal))
        assertNotSame(direct.connection, through.connection)
        assertEquals("ok", through.connection.run("echo ok"))
        assertEquals("ok", direct.connection.run("echo ok"))
    }
}
