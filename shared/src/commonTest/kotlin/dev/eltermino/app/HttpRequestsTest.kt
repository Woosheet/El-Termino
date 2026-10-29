package dev.eltermino.app

import dev.eltermino.core.data.HttpAuth
import dev.eltermino.core.data.HttpCheck
import dev.eltermino.core.data.HttpCollection
import dev.eltermino.core.data.HttpHeader
import dev.eltermino.core.data.HttpRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpRequestsTest {
    private val collection = HttpCollection(
        id = "c", name = "API",
        variables = mapOf("port" to "8080", "user" to "anna"),
        hostVariables = mapOf("web-02" to mapOf("port" to "9090")),
    )
    private val secrets = mapOf("s" to "t0k'en")
    private suspend fun prep(request: HttpRequest, host: String = "web-01") = prepare(request, collection, host) { secrets[it]?.encodeToByteArray() }

    @Test
    fun hostValuesWinOverTheCollectionDefaults() = runTest {
        val request = HttpRequest("r", "Salute", url = "localhost:{{port}}/health")
        assertEquals("http://localhost:8080/health", prep(request).url)
        assertEquals("http://localhost:9090/health", prep(request, "web-02").url)
    }

    @Test
    fun aMissingVariableIsNamed() = runTest {
        val error = assertFailsWith<HttpRequestException> { prep(HttpRequest("r", "x", url = "http://localhost/{{path}}")) }
        assertEquals("Manca il valore di {{path}} per questo host", error.message)
    }

    @Test
    fun authBecomesAHeaderFromTheVault() = runTest {
        fun auth(type: String) = HttpRequest("r", "x", url = "http://h/", auth = HttpAuth(type, username = "{{user}}", headerName = "X-Key", secretId = "s"))
        assertEquals("Authorization" to "Bearer t0k'en", prep(auth("bearer")).headers.single())
        assertEquals("Authorization" to "Basic YW5uYTp0MGsnZW4=", prep(auth("basic")).headers.single())
        assertEquals("X-Key" to "t0k'en", prep(auth("apikey")).headers.single())
        val gone = auth("bearer").copy(auth = HttpAuth("bearer", secretId = "missing"))
        assertFailsWith<HttpRequestException> { prep(gone) }
    }

    @Test
    fun bodiesGetTheirContentTypeUnlessOneIsSet() = runTest {
        val form = prep(HttpRequest("r", "x", "POST", "http://h/", bodyType = "form", body = "nome=Anna Rossi\ncittà=Bari\n"))
        assertEquals("nome=Anna%20Rossi&citt%C3%A0=Bari", form.body)
        assertEquals("Content-Type" to "application/x-www-form-urlencoded", form.headers.single())
        val json = prep(HttpRequest("r", "x", "post", "http://h/", headers = listOf(HttpHeader("content-type", "application/vnd+json"), HttpHeader("X-Off", "1", enabled = false)), bodyType = "json", body = "{}"))
        assertEquals("POST", json.method)
        assertEquals(listOf("content-type" to "application/vnd+json"), json.headers)
    }

    @Test
    fun tunnelTargetsReadHostAndPort() {
        assertEquals("localhost" to 8080, tunnelTarget("http://localhost:8080/x?y=1"))
        assertEquals("api.local" to 443, tunnelTarget("https://user:pw@api.local/v1"))
        assertEquals("::1" to 80, tunnelTarget("http://[::1]/"))
        assertFailsWith<HttpRequestException> { tunnelTarget("http://h:99999/") }
    }

    @Test
    fun curlCommandsQuoteForTheShell() {
        val request = PreparedRequest("POST", "http://h/a?b=1&c", listOf("X-Name" to "it's"), "{\"a\":1}")
        assertEquals("""curl -sS -X 'POST' -H 'X-Name: it'\''s' --data-binary '{"a":1}' 'http://h/a?b=1&c'""", curlCommand(request))
        assertEquals("curl -sS 'http://h/'", curlCommand(PreparedRequest("GET", "http://h/", emptyList(), null)))
    }

    @Test
    fun curlOutputSkipsInterimAnswers() {
        val raw = "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 201 Created\r\nContent-Type: text/plain\r\nX-A: b:c\r\n\r\nfatto\n__ELTERMINO_CURL__ 0.001 0.012 0.020"
        val response = parseCurlOutput(raw.encodeToByteArray())
        assertEquals(201, response.status)
        assertEquals("Created", response.reason)
        assertEquals(listOf("Content-Type" to "text/plain", "X-A" to "b:c"), response.headers)
        assertEquals("fatto", response.text)
        assertEquals(HttpTimings(1, 12, 20), response.timings)
    }

    @Test
    fun checksReadStatusBodyJsonAndTime() {
        val response = HttpResponse(200, "OK", emptyList(), """{"data":{"items":[{"id":7,"ok":true}]},"n":null}""".encodeToByteArray(), HttpTimings(null, null, 40))
        fun check(kind: String, expected: String, path: String = "") = HttpCheck(kind, path, expected).evaluate(response)
        assertTrue(check("status", "200").passed)
        assertFalse(check("status", "404").passed)
        assertTrue(check("contains", "\"ok\":true").passed)
        assertTrue(check("json", "7", "data.items[0].id").passed)
        assertTrue(check("json", "true", "$.data.items[0].ok").passed)
        assertEquals("campo assente", check("json", "1", "data.items[3]").actual)
        assertEquals("null", check("json", "x", "n").actual)
        assertTrue(check("time", "50").passed)
        assertFalse(check("time", "40").passed)
    }
}
