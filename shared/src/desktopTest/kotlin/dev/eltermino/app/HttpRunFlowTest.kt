package dev.eltermino.app

import dev.eltermino.core.data.HostGroup
import dev.eltermino.core.data.HttpAuth
import dev.eltermino.core.data.HttpCheck
import dev.eltermino.core.data.HttpCollection
import dev.eltermino.core.data.HttpRequest
import dev.eltermino.core.data.newId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 5's goal: a collection of checks runs on every host of a group and shows the
 * outcome per host, each host reached over its own SSH link (one of them behind a bastion).
 */
class HttpRunFlowTest {
    @Test
    fun aCollectionRunsOnEveryHostOfAGroup() {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        try {
            runBlocking {
                val actions = state.actions
                val identity = actions.keyIdentity()
                val group = HostGroup(newId(), "Servizi")
                state.services.workspace.update { it.copy(groups = it.groups + group) }
                val basic = actions.testHost("basic", TestEnv.BASIC_PORT, identity).copy(groupId = group.id).also { actions.saveHost(it) }
                val bastion = actions.testHost("bastion", TestEnv.BASTION_PORT, identity).copy(groupId = group.id).also { actions.saveHost(it) }
                val internal = actions.testHost("internal", 22, identity, address = "internal", jumpHostId = bastion.id).copy(groupId = group.id).also { actions.saveHost(it) }

                val health = HttpRequest(
                    newId(), "Salute", url = "http://localhost:{{port}}/health.json",
                    checks = listOf(HttpCheck("status", expected = "200"), HttpCheck("json", "status", "ok")),
                )
                val echo = HttpRequest(
                    newId(), "Eco", "POST", "http://localhost:{{port}}/cgi-bin/echo", bodyType = "text", body = "ciao",
                    auth = HttpAuth("bearer"), checks = listOf(HttpCheck("contains", expected = "POST")),
                )
                // The bastion's service "moved": its own port value makes it fail on purpose.
                val collection = HttpCollection(newId(), "Salute", listOf(health), mapOf("port" to "8080"), mapOf(bastion.id to mapOf("port" to "9")))
                actions.saveCollection(collection)
                val savedEcho = actions.saveRequest(collection.id, echo, newSecret = "token")
                val saved = state.services.workspace.workspace.value.httpCollection(collection.id)!!
                assertEquals(listOf(health.id, savedEcho.id), saved.requests.map { it.id })

                val hosts = state.services.workspace.workspace.value.hosts.filter { it.groupId == group.id }
                val run = state.requests.run(saved, hosts)
                withTimeout(90_000) { run.job!!.join() }
                val cells = run.cells.value
                assertEquals(hosts.size * 2, cells.size)

                for (host in listOf(basic, internal)) {
                    for (request in saved.requests) {
                        val outcome = assertIs<RunCell.Done>(cells[host.id to request.id]).outcome
                        assertIs<HttpOutcome.Answered>(outcome, (outcome as? HttpOutcome.Failed)?.message)
                        assertTrue(outcome.passed, "${request.name} on ${host.label}: " + outcome.checks.filterNot { it.passed }.map { it.actual })
                    }
                }
                val failed = assertIs<HttpOutcome.Failed>(assertIs<RunCell.Done>(cells[bastion.id to health.id]).outcome)
                assertTrue("localhost:9" in failed.message, failed.message)
            }
        } finally {
            state.sessions.closeAll()
            state.scope.cancel()
        }
    }

    @Test
    fun aRequestsTabSendsFromItsHostAndKeepsTheAnswers() {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        try {
            runBlocking {
                val basic = state.actions.testHost("basic", TestEnv.BASIC_PORT, state.actions.keyIdentity())
                val tab = state.sessions.openRequests(basic)
                val request = HttpRequest(newId(), "Salute", url = "localhost:8080/health.json", checks = listOf(HttpCheck("json", "host", "basic")))
                repeat(2) {
                    state.requests.send(tab, null, request)
                    withTimeout(30_000) { tab.sending.first { request.id !in it } }
                }
                val answers = tab.history.value.getValue(request.id)
                assertEquals(2, answers.size)
                answers.forEach { assertTrue(assertIs<HttpOutcome.Answered>(it).passed) }
                assertEquals(setOf(basic.id), withTimeout(10_000) { state.sessions.connectedHostIds.first { it.isNotEmpty() } })
            }
        } finally {
            state.sessions.closeAll()
            state.scope.cancel()
        }
    }
}
