package dev.eltermino.app

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A service's own OpenAPI description, fetched from its host through the tunnel, imported and sent. */
class OpenApiFlowTest {
    private fun withRequestsTab(block: suspend (AppState, RequestsTab) -> Unit) {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        try {
            runBlocking {
                val basic = state.actions.testHost("basic", TestEnv.BASIC_PORT, state.actions.keyIdentity())
                block(state, state.sessions.openRequests(basic))
            }
        } finally {
            state.sessions.closeAll()
            state.scope.cancel()
        }
    }

    @Test
    fun theHostsDescriptionImportsAndItsRequestsPass() = withRequestsTab { state, tab ->
        // Only the address: the usual places are tried until /openapi.json answers.
        val (url, text) = state.requests.fetchApiDescription(tab, "localhost:8080")
        assertEquals("http://localhost:8080/openapi.json", url)

        val collection = state.actions.importCollection(parseOpenApi(text, url))
        assertEquals("Servizio di prova", collection.name)
        assertEquals("http://localhost:8080", collection.variables["baseUrl"])
        assertEquals(listOf("Stato / Salute", "Eco / Eco"), collection.requests.map { it.name })

        for (request in collection.requests) {
            state.requests.send(tab, collection, request)
            withTimeout(30_000) { tab.sending.first { request.id !in it } }
            val outcome = assertIs<HttpOutcome.Answered>(tab.history.value.getValue(request.id).last(), request.name)
            assertTrue(outcome.passed, "${request.name}: " + outcome.checks.filterNot { it.passed }.map { it.actual })
        }
        val echo = assertIs<HttpOutcome.Answered>(tab.history.value.getValue(collection.requests[1].id).last()).response.text
        assertTrue(echo.startsWith("POST x=1") && "nome=Anna" in echo, echo)
    }

    @Test
    fun anAddressWithoutADescriptionSaysWhatItTried() = withRequestsTab { state, tab ->
        val error = assertFailsWith<HttpRequestException> { state.requests.fetchApiDescription(tab, "http://localhost:8080/health.json") }
        assertTrue("/v3/api-docs" in error.message!! && "/openapi.json" in error.message!!, error.message)
    }
}
