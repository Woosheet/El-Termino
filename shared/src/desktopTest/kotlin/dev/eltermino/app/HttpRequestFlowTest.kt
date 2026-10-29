package dev.eltermino.app

import dev.eltermino.core.data.HttpCollection
import dev.eltermino.core.data.HttpPart
import dev.eltermino.core.data.HttpRequest
import dev.eltermino.core.ssh.AuthMethod
import dev.eltermino.core.ssh.ConnectTarget
import dev.eltermino.core.ssh.HostKeyVerifier
import dev.eltermino.core.ssh.SecretRef
import dev.eltermino.core.ssh.SecretResolver
import dev.eltermino.core.ssh.SshConnection
import dev.eltermino.core.ssh.mina.MinaSshConnector
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Requests to the HTTP service each testenv host keeps on its own loopback (see testenv/README.md). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpRequestFlowTest {
    private val connector = MinaSshConnector()
    private val trustAll = HostKeyVerifier { _, _, _ -> true }
    private val secrets = SecretResolver { ref -> if (ref.id == "password") TestEnv.PASSWORD.toByteArray() else TestEnv.key(ref.id).readBytes() }
    private val collection = HttpCollection("c", "Test", variables = mapOf("port" to "8080"))

    @AfterAll
    fun shutdown() = connector.shutdown()

    // Unit on purpose: JUnit skips test methods that return a value.
    private fun onBasic(block: suspend (SshConnection) -> Unit): Unit = runBlocking {
        val connection = connector.connect(ConnectTarget(TestEnv.HOST, TestEnv.BASIC_PORT, TestEnv.USER, listOf(AuthMethod.Password(SecretRef("password")))), trustAll, secrets)
        try { block(connection) } finally { connection.close() }
    }

    private suspend fun send(connection: SshConnection, request: HttpRequest) = sendRequest(connection, request, collection, "basic", { null })

    @Test
    fun getThroughTheTunnel() = onBasic { connection ->
        val response = send(connection, HttpRequest("r", "Salute", url = "http://localhost:{{port}}/health.json"))
        assertEquals(200, response.status)
        assertEquals("""{"status":"ok","host":"basic"}""", response.text)
        assertTrue(response.timings.totalMs >= (response.timings.firstByteMs ?: 0))
    }

    @Test
    fun postAFormThroughTheTunnelAndWithCurl() = onBasic { connection ->
        val request = HttpRequest("r", "Eco", "POST", "http://localhost:8080/cgi-bin/echo?x=1", bodyType = "form", body = "nome=Anna Rossi")
        assertEquals("POST x=1\nnome=Anna%20Rossi", send(connection, request).text)
        val curl = send(connection, request.copy(viaCurl = true))
        assertEquals(200, curl.status)
        assertEquals("POST x=1\nnome=Anna%20Rossi", curl.text)
        assertTrue(curl.headers.any { it.first.equals("Content-Type", ignoreCase = true) && it.second == "text/plain" })
    }

    @Test
    fun aMultipartFormCarriesTextAndAFileFromThisComputer() = onBasic { connection ->
        val file = java.io.File.createTempFile("dati", ".txt").apply { writeText("riga 1\nriga 2"); deleteOnExit() }
        val request = HttpRequest(
            "r", "Carica", "POST", "http://localhost:8080/cgi-bin/echo", bodyType = "multipart",
            parts = listOf(HttpPart("nota", "ciao {{port}}"), HttpPart("doc", file = file.absolutePath, fileName = "dati.txt")),
        )
        val body = sendRequest(connection, request, collection, "basic", { null }, readFile = { java.io.File(it).readBytes() }).text
        assertTrue(body.startsWith("POST "), body)
        assertTrue("name=\"nota\"" in body && "ciao 8080" in body, body)
        assertTrue("filename=\"dati.txt\"" in body && "Content-Type: text/plain" in body && "riga 1\nriga 2" in body, body)

        val gone = assertFailsWith<HttpRequestException> { sendRequest(connection, request, collection, "basic", { null }, readFile = { null }) }
        assertTrue("dati.txt" in gone.message!!, gone.message)
        assertFailsWith<HttpRequestException> { send(connection, request.copy(viaCurl = true)) }
        // Text fields alone are fine with curl on the server.
        val text = send(connection, request.copy(viaCurl = true, parts = request.parts.take(1))).text
        assertTrue("ciao 8080" in text, text)
    }

    @Test
    fun aClosedPortSaysWhereItFailed() = onBasic { connection ->
        val error = assertFailsWith<HttpRequestException> { send(connection, HttpRequest("r", "x", url = "http://localhost:9/")) }
        assertTrue("localhost:9" in error.message!!, error.message)
        assertFailsWith<HttpRequestException> { send(connection, HttpRequest("r", "x", url = "http://localhost:9/", viaCurl = true)) }
    }

    @Test
    fun theInternalHostAnswersBehindTheBastion() = runBlocking {
        val key = AuthMethod.PublicKey(SecretRef("id_ed25519"), null)
        val bastion = ConnectTarget(TestEnv.HOST, TestEnv.BASTION_PORT, TestEnv.USER, listOf(key))
        val connection = connector.connect(ConnectTarget("internal", 22, TestEnv.USER, listOf(key), jumpHosts = listOf(bastion)), trustAll, secrets)
        try {
            assertEquals("""{"status":"ok","host":"internal"}""", send(connection, HttpRequest("r", "x", url = "http://localhost:8080/health.json")).text)
        } finally {
            connection.close()
        }
    }
}
