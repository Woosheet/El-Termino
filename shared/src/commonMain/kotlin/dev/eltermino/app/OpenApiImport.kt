package dev.eltermino.app

import dev.eltermino.core.data.HttpAuth
import dev.eltermino.core.data.HttpCheck
import dev.eltermino.core.data.HttpCollection
import dev.eltermino.core.data.HttpHeader
import dev.eltermino.core.data.HttpPart
import dev.eltermino.core.data.HttpRequest
import dev.eltermino.core.data.newId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** Where APIs usually publish their description, tried in order on a host: Spring, FastAPI, Swagger 2, ASP.NET and others. */
val API_DESCRIPTION_PATHS = listOf("/v3/api-docs", "/openapi.json", "/swagger.json", "/v2/api-docs", "/swagger/v1/swagger.json", "/api-docs", "/api/openapi.json")

/** Whether [text] is an OpenAPI 3 or Swagger 2 description in JSON. */
fun isOpenApi(text: String): Boolean =
    (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.let { "openapi" in it || "swagger" in it } == true

/** Whether [text] looks like an OpenAPI or Swagger description in YAML, which isn't read yet. */
fun isOpenApiYaml(text: String): Boolean = Regex("""(?m)^(openapi|swagger)\s*:""").containsMatchIn(text)

/**
 * An OpenAPI 3 or Swagger 2 description in JSON, one request per operation, named "tag / summary"
 * like Postman's folders. URLs start with `{{baseUrl}}`, which defaults to the first server; a relative
 * one is resolved against [sourceUrl], where the description came from. Path parameters and required
 * query and header parameters become `{{variables}}`, defaulting to the documented example where there
 * is one. Bodies get their example, or one built from the schema. Secured operations get their auth with
 * a secret like `{{token}}`, to set per host, and every request checks for its documented success status.
 */
fun parseOpenApi(text: String, sourceUrl: String? = null): ImportedCollection {
    if (isOpenApiYaml(text) && !text.trimStart().startsWith("{")) {
        throw HttpRequestException("È una descrizione in YAML: per ora si importa quella in JSON, che di solito è su /v3/api-docs o /openapi.json")
    }
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw HttpRequestException("Il file non è un JSON valido")
    val swagger2 = root.string("swagger")?.startsWith("2") == true
    if (!swagger2 && root.string("openapi")?.startsWith("3") != true) {
        throw HttpRequestException("Non è una descrizione OpenAPI 3 o Swagger 2 (manca il campo openapi o swagger)")
    }
    val spec = Spec(root, swagger2)
    val paths = root["paths"] as? JsonObject ?: throw HttpRequestException("La descrizione non ha percorsi (paths)")

    val variables = linkedMapOf("baseUrl" to spec.baseUrl(sourceUrl))
    val secrets = mutableMapOf<String, String>()
    val requests = mutableListOf<HttpRequest>()
    for ((path, item) in paths) {
        val pathItem = spec.resolve(item) as? JsonObject ?: continue
        for (method in METHODS) {
            val operation = pathItem[method] as? JsonObject ?: continue
            val id = newId()
            val parameters = spec.parameters(pathItem, operation)
            parameters.filter { it.location == "path" || it.required }.forEach { p -> p.example?.let { variables.putIfAbsent(p.name, it) } }

            val query = parameters.filter { it.location == "query" && it.required }
            val url = "{{baseUrl}}" + path.replace(Regex("""\{([^{}]+)\}"""), "{{$1}}") +
                query.joinToString("&", prefix = if (query.isEmpty()) "" else "?") { "${it.name}={{${it.name}}}" }
            val headers = parameters.filter { it.location == "header" && it.required }.map { HttpHeader(it.name, "{{${it.name}}}") }
            val body = spec.body(operation, parameters)
            val (auth, secret, extraQuery) = spec.auth(operation)
            secret?.let { secrets[id] = it }

            val tag = (operation["tags"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            val title = operation.string("summary")?.trim()?.takeIf { it.isNotEmpty() } ?: operation.string("operationId") ?: "${method.uppercase()} $path"
            requests += HttpRequest(
                id = id,
                name = if (tag != null) "$tag / $title" else title,
                method = method.uppercase(),
                url = url + extraQuery.let { if (it.isEmpty()) "" else (if ('?' in url) "&" else "?") + it },
                headers = headers,
                bodyType = body.type,
                body = body.text,
                parts = body.parts,
                auth = auth,
                checks = successStatus(operation)?.let { listOf(HttpCheck("status", expected = it)) }.orEmpty(),
            )
        }
    }
    if (requests.isEmpty()) throw HttpRequestException("La descrizione non ha operazioni da importare")
    val info = root["info"] as? JsonObject
    return ImportedCollection(HttpCollection(newId(), info?.string("title") ?: "API importata", requests, variables), secrets)
}

private val METHODS = listOf("get", "post", "put", "patch", "delete", "head", "options")

private val pretty = Json { prettyPrint = true }

private class Parameter(val name: String, val location: String, val required: Boolean, val example: String?, val schema: JsonObject?, val type: String?)

private class Body(val type: String = "none", val text: String = "", val parts: List<HttpPart> = emptyList())

/** The first documented 2xx status; none when the description only gives "default" or ranges like "2XX". */
private fun successStatus(operation: JsonObject): String? =
    (operation["responses"] as? JsonObject)?.keys?.firstOrNull { it.length == 3 && it.startsWith("2") && it.all(Char::isDigit) }

private class Spec(val root: JsonObject, val swagger2: Boolean) {
    /** Follows a local `$ref` such as `#/components/schemas/Pet`; anything else comes back as it is. */
    fun resolve(element: JsonElement?, depth: Int = 0): JsonElement? {
        val ref = (element as? JsonObject)?.string("\$ref") ?: return element
        if (!ref.startsWith("#/") || depth > 20) return null
        var node: JsonElement? = root
        for (segment in ref.removePrefix("#/").split('/')) {
            node = (node as? JsonObject)?.get(segment.replace("~1", "/").replace("~0", "~"))
        }
        return resolve(node, depth + 1)
    }

    fun baseUrl(sourceUrl: String?): String {
        val origin = sourceUrl?.let { Regex("""^(https?://[^/]+)""").find(it)?.value }
        if (swagger2) {
            val scheme = (root["schemes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.let { if ("http" in it) "http" else it.firstOrNull() }
            val host = root.string("host")
            val basePath = root.string("basePath").orEmpty().trimEnd('/')
            return when {
                host != null -> "${scheme ?: "http"}://$host$basePath"
                origin != null -> origin + basePath
                else -> "http://localhost:8080$basePath"
            }
        }
        val server = (root["servers"] as? JsonArray)?.firstOrNull() as? JsonObject
        var url = server?.string("url") ?: "/"
        (server?.get("variables") as? JsonObject)?.forEach { (name, variable) ->
            (variable as? JsonObject)?.string("default")?.let { url = url.replace("{$name}", it) }
        }
        url = url.trimEnd('/')
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> (origin ?: "http://localhost:8080") + url
        }
    }

    /** The path's parameters and the operation's own, the latter winning on the same name and place. */
    fun parameters(pathItem: JsonObject, operation: JsonObject): List<Parameter> {
        fun read(list: JsonElement?) = (list as? JsonArray).orEmpty().mapNotNull { resolve(it) as? JsonObject }.mapNotNull { p ->
            val name = p.string("name") ?: return@mapNotNull null
            val location = p.string("in") ?: return@mapNotNull null
            val schema = resolve(p["schema"]) as? JsonObject
            Parameter(
                name = name,
                location = location,
                required = location == "path" || (p["required"] as? JsonPrimitive)?.contentOrNull == "true",
                example = scalar(p["example"]) ?: scalar(p["x-example"]) ?: scalar(schema?.get("example")) ?: scalar(schema?.get("default"))
                    ?: scalar(p["default"]) ?: scalar((schema?.get("enum") ?: p["enum"]) .let { (it as? JsonArray)?.firstOrNull() }),
                schema = schema,
                type = p.string("type"),
            )
        }
        val own = read(operation["parameters"])
        return read(pathItem["parameters"]).filter { shared -> own.none { it.name == shared.name && it.location == shared.location } } + own
    }

    fun body(operation: JsonObject, parameters: List<Parameter>): Body {
        if (swagger2) {
            parameters.firstOrNull { it.location == "body" }?.let { p ->
                val example = p.schema?.let { sample(it) } ?: return Body()
                return Body("json", pretty.encodeToString(JsonElement.serializer(), example))
            }
            val form = parameters.filter { it.location == "formData" }
            if (form.isEmpty()) return Body()
            val consumes = ((operation["consumes"] ?: root["consumes"]) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            return if (form.any { it.type == "file" } || "multipart/form-data" in consumes) {
                Body("multipart", parts = form.map { if (it.type == "file") HttpPart(it.name) else HttpPart(it.name, it.example.orEmpty()) })
            } else {
                Body("form", form.joinToString("\n") { "${it.name}=${it.example.orEmpty()}" })
            }
        }
        val content = (resolve(operation["requestBody"]) as? JsonObject)?.get("content") as? JsonObject ?: return Body()
        val (type, media) = content.entries.firstOrNull { it.key.contains("json") }?.let { "json" to it.value }
            ?: content["application/x-www-form-urlencoded"]?.let { "form" to it }
            ?: content["multipart/form-data"]?.let { "multipart" to it }
            ?: content.entries.firstOrNull { it.key.startsWith("text/") }?.let { "text" to it.value }
            ?: return Body()
        val mediaObject = media as? JsonObject ?: return Body(type)
        val schema = resolve(mediaObject["schema"]) as? JsonObject
        val example = mediaObject["example"]
            ?: ((mediaObject["examples"] as? JsonObject)?.values?.firstOrNull()?.let { resolve(it) as? JsonObject })?.get("value")
            ?: schema?.let { sample(it) }
        return when (type) {
            "json" -> Body("json", example?.let { pretty.encodeToString(JsonElement.serializer(), it) }.orEmpty())
            "text" -> Body("text", example?.let { scalar(it) ?: it.toString() }.orEmpty())
            else -> {
                val fields = properties(schema)
                if (type == "form") Body("form", fields.joinToString("\n") { (name, field) -> "$name=${scalar(sample(field)).orEmpty()}" })
                else Body("multipart", parts = fields.map { (name, field) -> if (field.string("format") == "binary") HttpPart(name) else HttpPart(name, scalar(sample(field)).orEmpty()) })
            }
        }
    }

    private fun properties(schema: JsonObject?): List<Pair<String, JsonObject>> =
        (merged(schema)?.get("properties") as? JsonObject).orEmpty().mapNotNull { (name, value) -> (resolve(value) as? JsonObject)?.let { name to it } }

    /** `allOf` folded into one schema, so its properties can be listed together. */
    private fun merged(schema: JsonObject?, depth: Int = 0): JsonObject? {
        val all = (schema?.get("allOf") as? JsonArray) ?: return schema
        if (depth > 10) return schema
        val properties = mutableMapOf<String, JsonElement>()
        all.mapNotNull { merged(resolve(it) as? JsonObject, depth + 1) }.forEach { part ->
            (part["properties"] as? JsonObject)?.let { properties += it }
        }
        (schema["properties"] as? JsonObject)?.let { properties += it }
        return JsonObject(schema + ("properties" to JsonObject(properties)))
    }

    /** A value that fits [schema]: its example or default if it has one, otherwise a plain one of the right type. */
    fun sample(schema: JsonObject, depth: Int = 0): JsonElement {
        if (depth > 8) return JsonNull
        schema["example"]?.let { return it }
        schema["default"]?.let { return it }
        (schema["enum"] as? JsonArray)?.firstOrNull()?.let { return it }
        ((schema["oneOf"] ?: schema["anyOf"]) as? JsonArray)?.firstOrNull()?.let { option ->
            return (resolve(option) as? JsonObject)?.let { sample(it, depth + 1) } ?: JsonNull
        }
        val full = merged(schema, depth) ?: schema
        val type = full.string("type") ?: if ("properties" in full) "object" else if ("items" in full) "array" else null
        return when (type) {
            "object" -> buildJsonObject {
                properties(full).forEach { (name, field) -> put(name, sample(field, depth + 1)) }
            }
            "array" -> JsonArray(listOfNotNull((resolve(full["items"]) as? JsonObject)?.let { sample(it, depth + 1) }))
            "integer" -> JsonPrimitive(0)
            "number" -> JsonPrimitive(0.0)
            "boolean" -> JsonPrimitive(false)
            "string" -> JsonPrimitive(
                when (full.string("format")) {
                    "date-time" -> "2026-01-01T00:00:00Z"
                    "date" -> "2026-01-01"
                    "uuid" -> "00000000-0000-0000-0000-000000000000"
                    "email" -> "nome@example.com"
                    "uri", "url" -> "https://example.com"
                    else -> "string"
                },
            )
            else -> JsonNull
        }
    }

    /** The auth of the operation's first security requirement, its secret, and any key that goes in the query. */
    fun auth(operation: JsonObject): Triple<HttpAuth, String?, String> {
        val requirements = (operation["security"] ?: root["security"]) as? JsonArray ?: return Triple(HttpAuth(), null, "")
        val name = (requirements.firstOrNull() as? JsonObject)?.keys?.firstOrNull() ?: return Triple(HttpAuth(), null, "")
        val schemes = if (swagger2) root["securityDefinitions"] as? JsonObject else (root["components"] as? JsonObject)?.get("securitySchemes") as? JsonObject
        val scheme = resolve(schemes?.get(name)) as? JsonObject ?: return Triple(HttpAuth(), null, "")
        return when (scheme.string("type")) {
            "http" -> when (scheme.string("scheme")?.lowercase()) {
                "basic" -> Triple(HttpAuth("basic", username = "{{username}}"), "{{password}}", "")
                else -> Triple(HttpAuth("bearer"), "{{token}}", "")
            }
            "basic" -> Triple(HttpAuth("basic", username = "{{username}}"), "{{password}}", "")
            "apiKey" -> {
                val keyName = scheme.string("name") ?: "X-API-Key"
                when (scheme.string("in")) {
                    "query" -> Triple(HttpAuth(), null, "$keyName={{apiKey}}")
                    "header" -> Triple(HttpAuth("apikey", headerName = keyName), "{{apiKey}}", "")
                    else -> Triple(HttpAuth(), null, "")
                }
            }
            "oauth2", "openIdConnect" -> Triple(HttpAuth("bearer"), "{{token}}", "")
            else -> Triple(HttpAuth(), null, "")
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** A JSON scalar as text; objects, arrays and null give nothing. */
private fun scalar(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
