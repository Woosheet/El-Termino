package dev.eltermino.app

import dev.eltermino.core.data.HttpAuth
import dev.eltermino.core.data.HttpCheck
import dev.eltermino.core.data.HttpHeader
import dev.eltermino.core.data.HttpPart
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenApiImportTest {
    private val openApi3 = """
        {"openapi":"3.0.1","info":{"title":"Negozio","version":"1.0"},
         "servers":[{"url":"/api/{version}","variables":{"version":{"default":"v1"}}}],
         "security":[{"bearerAuth":[]}],
         "components":{
           "securitySchemes":{"bearerAuth":{"type":"http","scheme":"bearer"},"chiave":{"type":"apiKey","in":"header","name":"X-Chiave"}},
           "schemas":{
             "Ordine":{"type":"object","properties":{"id":{"type":"integer","example":42},"cliente":{"${'$'}ref":"#/components/schemas/Cliente"},
               "righe":{"type":"array","items":{"${'$'}ref":"#/components/schemas/Riga"}},"stato":{"type":"string","enum":["nuovo","spedito"]},
               "creato":{"type":"string","format":"date-time"}}},
             "Cliente":{"allOf":[{"${'$'}ref":"#/components/schemas/Persona"},{"type":"object","properties":{"vip":{"type":"boolean"}}}]},
             "Persona":{"type":"object","properties":{"nome":{"type":"string","example":"Anna"}}},
             "Riga":{"type":"object","properties":{"sku":{"type":"string"},"qta":{"type":"integer"}}}
           },
           "parameters":{"Id":{"name":"id","in":"path","required":true,"schema":{"type":"integer","example":7}}}
         },
         "paths":{
           "/ordini":{
             "get":{"tags":["Ordini"],"summary":"Elenco","parameters":[
               {"name":"pagina","in":"query","required":true,"schema":{"type":"integer","default":1}},
               {"name":"filtro","in":"query","schema":{"type":"string"}},
               {"name":"X-Tenant","in":"header","required":true,"schema":{"type":"string"}}],
               "responses":{"200":{"description":"ok"}}},
             "post":{"tags":["Ordini"],"operationId":"creaOrdine",
               "requestBody":{"content":{"application/json":{"schema":{"${'$'}ref":"#/components/schemas/Ordine"}}}},
               "responses":{"201":{"description":"creato"}}}
           },
           "/ordini/{id}":{
             "parameters":[{"${'$'}ref":"#/components/parameters/Id"}],
             "delete":{"tags":["Ordini"],"summary":"Cancella","security":[{"chiave":[]}],"responses":{"204":{"description":""}}}
           },
           "/salute":{"get":{"summary":"Salute","security":[],"responses":{"default":{"description":""}}}},
           "/allegati":{"post":{"summary":"Carica","requestBody":{"content":{"multipart/form-data":{"schema":{"type":"object",
             "properties":{"file":{"type":"string","format":"binary"},"nota":{"type":"string","example":"ciao"}}}}}},
             "responses":{"200":{"description":""}}}}
         }}
    """.trimIndent()

    @Test
    fun openApi3OperationsBecomeRequests() {
        val imported = parseOpenApi(openApi3, sourceUrl = "http://localhost:8080/v3/api-docs")
        val c = imported.collection
        assertEquals("Negozio", c.name)
        assertEquals(mapOf("baseUrl" to "http://localhost:8080/api/v1", "pagina" to "1", "id" to "7"), c.variables)
        assertEquals(listOf("Ordini / Elenco", "Ordini / creaOrdine", "Ordini / Cancella", "Salute", "Carica"), c.requests.map { it.name })
        val (list, create, delete, health, upload) = c.requests

        assertEquals("GET", list.method)
        assertEquals("{{baseUrl}}/ordini?pagina={{pagina}}", list.url, "optional query parameters stay out")
        assertEquals(listOf(HttpHeader("X-Tenant", "{{X-Tenant}}")), list.headers)
        assertEquals("bearer", list.auth.type)
        assertEquals("{{token}}", imported.secrets[list.id])
        assertEquals(listOf(HttpCheck("status", expected = "200")), list.checks)

        assertEquals("POST", create.method)
        assertEquals("json", create.bodyType)
        assertEquals(
            Json.parseToJsonElement("""{"id":42,"cliente":{"nome":"Anna","vip":false},"righe":[{"sku":"string","qta":0}],"stato":"nuovo","creato":"2026-01-01T00:00:00Z"}"""),
            Json.parseToJsonElement(create.body),
        )
        assertEquals("201", create.checks.single().expected)

        assertEquals("DELETE", delete.method)
        assertEquals("{{baseUrl}}/ordini/{{id}}", delete.url)
        assertEquals(HttpAuth("apikey", headerName = "X-Chiave"), delete.auth)
        assertEquals("{{apiKey}}", imported.secrets[delete.id])

        assertEquals(HttpAuth(), health.auth, "an empty security list turns auth off")
        assertTrue(health.checks.isEmpty(), "only a default response: nothing to check")

        assertEquals("multipart", upload.bodyType)
        assertEquals(listOf(HttpPart("file"), HttpPart("nota", "ciao")), upload.parts)
    }

    @Test
    fun swagger2HostBodiesAndKeys() {
        val imported = parseOpenApi(
            """
            {"swagger":"2.0","info":{"title":"Vecchia API"},"host":"api.interna:9000","basePath":"/v2","schemes":["https","http"],
             "securityDefinitions":{"chiave":{"type":"apiKey","in":"query","name":"api_key"},"base":{"type":"basic"}},
             "definitions":{"Utente":{"type":"object","properties":{"nome":{"type":"string"},"anni":{"type":"integer"}}}},
             "paths":{
               "/utenti":{"post":{"summary":"Nuovo utente","security":[{"base":[]}],
                 "parameters":[{"in":"body","name":"corpo","schema":{"${'$'}ref":"#/definitions/Utente"}}],"responses":{"200":{"description":""}}}},
               "/utenti/{uid}/foto":{"post":{"summary":"Foto","consumes":["multipart/form-data"],"security":[{"chiave":[]}],
                 "parameters":[{"in":"path","name":"uid","type":"string","required":true},{"in":"formData","name":"foto","type":"file"},
                   {"in":"formData","name":"titolo","type":"string","x-example":"mare"}],"responses":{"201":{"description":""}}}},
               "/login":{"post":{"summary":"Login","consumes":["application/x-www-form-urlencoded"],
                 "parameters":[{"in":"formData","name":"utente","type":"string","required":true},{"in":"formData","name":"password","type":"string","required":true}],
                 "responses":{"200":{"description":""}}}}
             }}
            """.trimIndent(),
        )
        val c = imported.collection
        assertEquals("Vecchia API", c.name)
        assertEquals(mapOf("baseUrl" to "http://api.interna:9000/v2"), c.variables, "plain http first: the tunnel reaches it")
        val (user, photo, login) = c.requests

        assertEquals("json", user.bodyType)
        assertEquals(Json.parseToJsonElement("""{"nome":"string","anni":0}"""), Json.parseToJsonElement(user.body))
        assertEquals(HttpAuth("basic", username = "{{username}}"), user.auth)
        assertEquals("{{password}}", imported.secrets[user.id])

        assertEquals("{{baseUrl}}/utenti/{{uid}}/foto?api_key={{apiKey}}", photo.url, "a key in the query goes in the URL")
        assertEquals(HttpAuth(), photo.auth)
        assertEquals(listOf(HttpPart("foto"), HttpPart("titolo", "mare")), photo.parts)

        assertEquals("form", login.bodyType)
        assertEquals("utente=\npassword=", login.body)
    }

    @Test
    fun withoutAServerTheSourceGivesTheAddress() {
        val spec = """{"openapi":"3.1.0","info":{"title":"T"},"paths":{"/x":{"get":{"responses":{"200":{"description":""}}}}}}"""
        assertEquals("http://10.0.0.5:9200", parseOpenApi(spec, "http://10.0.0.5:9200/openapi.json").collection.variables["baseUrl"])
        assertEquals("http://localhost:8080", parseOpenApi(spec).collection.variables["baseUrl"])
        assertEquals("GET /x", parseOpenApi(spec).collection.requests.single().name)
    }

    @Test
    fun yamlAndOtherFilesAreRefusedWithAReason() {
        val yaml = "openapi: 3.0.0\ninfo:\n  title: X\npaths: {}\n"
        assertTrue(isOpenApiYaml(yaml))
        assertFalse(isOpenApi(yaml))
        assertTrue(assertFailsWith<HttpRequestException> { parseOpenApi(yaml) }.message!!.contains("YAML"))
        assertFalse(isOpenApi("""{"info":{"name":"Postman"},"item":[]}"""))
        assertFailsWith<HttpRequestException> { parseOpenApi("""{"info":{}}""") }
        assertFailsWith<HttpRequestException> { parseOpenApi("""{"openapi":"3.0.0","paths":{}}""") }
    }
}
