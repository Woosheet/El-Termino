package dev.eltermino.app

import dev.eltermino.core.data.HttpPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpImportTest {
    @Test
    fun postmanFoldersBodiesAndAuth() {
        val imported = parsePostman(
            """
            {"info":{"name":"Shop","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "auth":{"type":"bearer","bearer":[{"key":"token","value":"{{token}}"}]},
             "variable":[{"key":"base","value":"http://localhost:8080"}],
             "item":[
               {"name":"Ordini","item":[
                 {"name":"Crea","request":{"method":"POST","url":{"raw":"{{base}}/orders","host":["{{base}}"]},
                   "header":[{"key":"X-Trace","value":"1"},{"key":"X-Old","value":"0","disabled":true}],
                   "body":{"mode":"raw","raw":"{\"id\":1}","options":{"raw":{"language":"json"}}}}}
               ]},
               {"name":"Login","request":{"method":"POST","url":"{{base}}/login",
                 "auth":{"type":"basic","basic":[{"key":"username","value":"anna"},{"key":"password","value":"pw"}]},
                 "body":{"mode":"urlencoded","urlencoded":[{"key":"a","value":"1"},{"key":"b","value":"2","disabled":true}]}}},
               {"name":"Carica","request":{"method":"POST","url":"{{base}}/up","body":{"mode":"formdata","formdata":[
                 {"key":"nota","value":"ciao","type":"text"},{"key":"file","type":"file","src":["/Users/anna/a.csv"]}]}}},
               {"name":"Salute","request":"{{base}}/health"}
             ]}
            """,
        )
        val c = imported.collection
        assertEquals("Shop", c.name)
        assertEquals(mapOf("base" to "http://localhost:8080"), c.variables)
        assertEquals(listOf("Ordini / Crea", "Login", "Carica", "Salute"), c.requests.map { it.name })
        val (create, login, upload, health) = c.requests
        assertEquals("multipart", upload.bodyType)
        assertEquals(listOf(HttpPart("nota", "ciao"), HttpPart("file", file = "/Users/anna/a.csv", fileName = "a.csv")), upload.parts)
        assertEquals("POST", create.method)
        assertEquals("{{base}}/orders", create.url)
        assertEquals(listOf(true, false), create.headers.map { it.enabled })
        assertEquals("json" to "{\"id\":1}", create.bodyType to create.body)
        assertEquals("bearer", create.auth.type)
        assertEquals("{{token}}", imported.secrets[create.id])
        assertEquals("basic" to "anna", login.auth.type to login.auth.username)
        assertEquals("pw", imported.secrets[login.id])
        assertEquals("form" to "a=1", login.bodyType to login.body)
        assertEquals("GET" to "{{base}}/health", health.method to health.url)
        assertFailsWith<HttpRequestException> { parsePostman("""{"name":"x"}""") }
    }

    @Test
    fun curlFromDocsAndBrowsers() {
        val pasted = parseCurl(
            """
            curl -sS -X PATCH 'http://localhost:8080/api/v1/items/7?full=1' \
              -H 'Content-Type: application/json' \
              -H "Authorization: Bearer abc.def" \
              --data-raw '{"name":"it'\''s"}' --compressed
            """.trimIndent(),
        )
        val r = pasted.request
        assertEquals("PATCH", r.method)
        assertEquals("http://localhost:8080/api/v1/items/7?full=1", r.url)
        assertEquals("api/v1/items/7", r.name)
        assertEquals(listOf("Content-Type"), r.headers.map { it.name })
        assertEquals("json" to """{"name":"it's"}""", r.bodyType to r.body)
        assertEquals("bearer" to "abc.def", r.auth.type to pasted.secret)

        val form = parseCurl("curl -u anna:pw -d a=1 -d b=2 http://h/login").request
        assertEquals("POST" to "a=1&b=2", form.method to form.body)
        assertEquals("application/x-www-form-urlencoded", form.headers.single { it.name == "Content-Type" }.value)
        assertEquals("anna", form.auth.username)

        val get = parseCurl("curl -G --data-urlencode q=x -o out.txt -XGET \"http://h/s\"").request
        assertEquals("GET" to "http://h/s?q=x", get.method to get.url)
        assertEquals("none", get.bodyType)
        assertNull(parseCurl("curl http://h/").secret)

        val upload = parseCurl("""curl -F 'nota=ciao' -F 'doc=@"/tmp/a b.pdf";type=application/pdf' --form-string 'raw=@x' http://h/up""").request
        assertEquals("POST" to "multipart", upload.method to upload.bodyType)
        assertEquals(listOf(HttpPart("nota", "ciao"), HttpPart("doc", file = "/tmp/a b.pdf", fileName = "a b.pdf"), HttpPart("raw", "@x")), upload.parts)
        assertFailsWith<HttpRequestException> { parseCurl("wget http://h/") }
        assertFailsWith<HttpRequestException> { parseCurl("curl -H 'x") }
    }
}
