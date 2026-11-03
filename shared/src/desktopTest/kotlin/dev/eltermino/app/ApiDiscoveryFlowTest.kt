package dev.eltermino.app

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Looking for the APIs a testenv server runs, as the import dialog does the first time. */
class ApiDiscoveryFlowTest {
    @Test
    fun findsTheServiceAndItsDescriptionAndKeepsThem() {
        val state = AppState(testServices())
        state.scope.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { if (it is PendingPrompt.HostKey) it.answer(true) else it.cancel() }
        }
        try {
            runBlocking {
                val basic = state.actions.testHost("basic", TestEnv.BASIC_PORT, state.actions.keyIdentity())
                val tab = state.sessions.openRequests(basic)
                val steps = mutableListOf<String>()
                val found = state.requests.discoverApis(tab) { synchronized(steps) { steps += it } }

                // busybox httpd on 127.0.0.1:8080 serves /openapi.json; sshd's port is never asked.
                val api = assertNotNull(found.apis.firstOrNull { it.port == 8080 }, "found ${found.apis}")
                assertEquals("http://127.0.0.1:8080", api.baseUrl)
                assertEquals("http://127.0.0.1:8080/openapi.json", api.descriptionUrl)
                assertEquals("Servizio di prova", api.title)
                assertEquals(2, api.requests)
                assertTrue(found.apis.none { it.port == 22 }, "sshd was probed")
                assertTrue("Leggo le porte in ascolto" in steps && "Provo la porta 8080" in steps, "$steps")
                assertEquals(found, state.services.workspace.workspace.value.apiDiscoveries[basic.id], "kept for the next time")

                // What it found imports like any description.
                val (url, text) = state.requests.fetchApiDescription(tab, api.descriptionUrl!!)
                assertEquals(2, parseOpenApi(text, url).collection.requests.size)
            }
        } finally {
            state.sessions.closeAll()
            state.scope.cancel()
        }
    }
}
