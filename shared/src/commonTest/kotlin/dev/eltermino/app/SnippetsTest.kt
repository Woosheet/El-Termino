package dev.eltermino.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetsTest {
    @Test
    fun variablesAreFoundOnceInOrder() {
        assertEquals(
            listOf("servizio", "righe"),
            snippetVariables("journalctl -u {{servizio}} -n {{ righe }} && systemctl status {{servizio}}"),
        )
        assertEquals(emptyList(), snippetVariables("echo \${HOME} {} {{}}"))
    }

    @Test
    fun fillingReplacesEveryOccurrence() {
        assertEquals(
            "journalctl -u nginx -n 50 && systemctl status nginx",
            fillSnippet("journalctl -u {{servizio}} -n {{ righe }} && systemctl status {{servizio}}", mapOf("servizio" to "nginx", "righe" to "50")),
        )
    }

    @Test
    fun missingValuesStayVisible() {
        assertEquals("ping {{host}}", fillSnippet("ping {{host}}", emptyMap()))
    }
}
