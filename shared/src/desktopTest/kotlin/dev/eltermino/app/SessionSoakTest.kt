package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Ten sessions on three hosts (one behind the bastion), all printing nonstop. The Phase 2
 * goal is hours: `./gradlew :shared:desktopTest --tests '*SessionSoakTest*' -PsoakSeconds=14400`.
 */
class SessionSoakTest {
    @Test
    fun tenBusySessionsStayUp(): Unit = runBlocking {
        val seconds = System.getProperty("soak.seconds")?.toLongOrNull() ?: 20
        val services = testServices()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = WorkspaceActions(services)
        val sessions = SessionsController(services, actions, scope)
        scope.launch { sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() } }

        val key = actions.importKey("test-ed25519", TestEnv.key("id_ed25519").readBytes())
        val identity = actions.saveIdentity(Identity(newId(), "dev", TestEnv.USER, keyId = key.id))
        val basic = Host(newId(), "basic", TestEnv.HOST, TestEnv.BASIC_PORT, identityId = identity.id).also { actions.saveHost(it) }
        val bastion = Host(newId(), "bastion", TestEnv.HOST, TestEnv.BASTION_PORT, identityId = identity.id).also { actions.saveHost(it) }
        val internal = Host(newId(), "internal", "internal", 22, identityId = identity.id, jumpHostId = bastion.id).also { actions.saveHost(it) }

        val tabs = List(4) { sessions.connect(basic) } + List(3) { sessions.connect(bastion) } + List(3) { sessions.connect(internal) }
        val open = tabs.map { tab ->
            val status = withTimeout(60_000) { tab.status.first { it !is TabStatus.Connecting } }
            assertIs<TabStatus.Open>(status, (status as? TabStatus.Failed)?.message)
        }
        open.forEach { it.terminal.type("while :; do date +%s%N; sleep 0.2; done\n") }

        val threadsAtStart = Thread.activeCount()
        val deadline = System.currentTimeMillis() + seconds * 1000
        var lastText = open.map { "" }
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(minOf(5_000, seconds * 1000 / 4))
            tabs.forEachIndexed { i, tab -> assertIs<TabStatus.Open>(tab.status.value, "tab $i dropped: ${tab.status.value}") }
            val text = open.map { it.terminal.snapshotText().takeLast(200) }
            text.forEachIndexed { i, t -> assertTrue(t != lastText[i], "tab $i stopped printing") }
            lastText = text
        }

        fun interrupt(i: Int) {
            open[i].terminal.sendChar('c'.code, dev.eltermino.core.terminal.KeyModifiers.Ctrl)
            open[i].terminal.type("echo done-$i\n")
        }
        open.indices.forEach(::interrupt)
        var retries = 0
        open.forEachIndexed { i, it ->
            // Bash can lose a Ctrl+C that lands between two commands of the loop, with any SSH
            // client and more often under load. The next one also drops the queued echo, so both go again.
            var nextTry = System.currentTimeMillis() + 2_000
            eventually({
                "tab $i at ${System.currentTimeMillis()} (${tab(tabs, i)}): " + it.terminal.snapshotText().trimEnd().takeLast(300)
            }) {
                val answered = "done-$i" in it.terminal.snapshotText().substringAfterLast("echo done-$i")
                if (!answered && System.currentTimeMillis() > nextTry) {
                    retries++
                    nextTry += 2_000
                    interrupt(i)
                }
                answered
            }
        }
        println("soak: ${tabs.size} sessions for ${seconds}s on 3 connections; threads ${threadsAtStart} -> ${Thread.activeCount()}; Ctrl+C repeated $retries times")
        sessions.closeAll()
        Thread.sleep(300)
        scope.cancel()
    }

    private fun tab(tabs: List<SessionTab>, i: Int) = tabs[i].status.value.let { status ->
        "${tabs[i].label} ${status::class.simpleName} closed=${(status as? TabStatus.Open)?.terminal?.closed?.value}"
    }
}
