package dev.eltermino.app

import dev.eltermino.core.data.HttpAuth
import dev.eltermino.core.data.HttpCollection
import dev.eltermino.core.data.HttpHeader
import dev.eltermino.core.data.HttpRequest
import dev.eltermino.core.data.newId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A collection read from a file; [secrets] (request id to token or password) still have to go to the vault. */
class ImportedCollection(val collection: HttpCollection, val secrets: Map<String, String>)

class ImportedRequest(val request: HttpRequest, val secret: String?)

/** A Postman v2.1 collection: folders become "Cartella / richiesta", script tests are left out. */
fun parsePostman(text: String): ImportedCollection {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw HttpRequestException("Il file non è un JSON valido")
    val info = root["info"] as? JsonObject
    val items = root["item"] as? JsonArray
    if (info == null || items == null) throw HttpRequestException("Non è una collezione di Postman (mancano info e item)")
    val secrets = mutableMapOf<String, String>()
    val requests = mutableListOf<HttpRequest>()
    val collectionAuth = root["auth"] as? JsonObject

    fun walk(items: JsonArray, prefix: String, inheritedAuth: JsonObject?) {
        for (item in items.filterIsInstance<JsonObject>()) {
            val name = item.string("name") ?: "Senza nome"
            val folderAuth = (item["auth"] as? JsonObject) ?: inheritedAuth
            val children = item["item"] as? JsonArray
            if (children != null) {
                walk(children, "$prefix$name / ", folderAuth)
                continue
            }
            val request = item["request"] ?: continue
            val id = newId()
            val (auth, secret) = postmanAuth(((request as? JsonObject)?.get("auth") as? JsonObject) ?: folderAuth)
            secret?.let { secrets[id] = it }
            requests += if (request is JsonPrimitive) {
                HttpRequest(id, "$prefix$name", url = request.content)
            } else {
                request as JsonObject
                val (bodyType, body) = postmanBody(request["body"] as? JsonObject)
                HttpRequest(
                    id = id,
                    name = "$prefix$name",
                    method = request.string("method") ?: "GET",
                    url = postmanUrl(request["url"]),
                    headers = (request["header"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map {
                        HttpHeader(it.string("key").orEmpty(), it.string("value").orEmpty(), enabled = it.flag("disabled") != true)
                    },
                    bodyType = bodyType,
                    body = body,
                    auth = auth,
                )
            }
        }
    }
    walk(items, "", collectionAuth)

    val variables = (root["variable"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .mapNotNull { v -> v.string("key")?.let { it to v.string("value").orEmpty() } }
        .toMap()
    return ImportedCollection(HttpCollection(newId(), info.string("name") ?: "Collezione importata", requests, variables), secrets)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun postmanUrl(url: JsonElement?): String = when (url) {
    is JsonPrimitive -> url.content
    is JsonObject -> url.string("raw").orEmpty()
    else -> ""
}

private fun postmanBody(body: JsonObject?): Pair<String, String> {
    if (body == null) return "none" to ""
    fun pairs(key: String) = (body[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .filter { it.flag("disabled") != true && it.string("type") != "file" }
        .joinToString("\n") { "${it.string("key").orEmpty()}=${it.string("value").orEmpty()}" }
    return when (body.string("mode")) {
        "raw" -> {
            val raw = body.string("raw").orEmpty()
            val language = ((body["options"] as? JsonObject)?.get("raw") as? JsonObject)?.string("language")
            (if (language == "json" || raw.trimStart().startsWith("{") || raw.trimStart().startsWith("[")) "json" else "text") to raw
        }
        "urlencoded" -> "form" to pairs("urlencoded")
        // Multipart isn't supported: its text fields go as a form.
        "formdata" -> "form" to pairs("formdata")
        else -> "none" to ""
    }
}

/** Postman keeps auth as a list of key/value pairs per type. */
private fun postmanAuth(auth: JsonObject?): Pair<HttpAuth, String?> {
    val type = auth?.string("type") ?: return HttpAuth() to null
    val values = (auth[type] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .mapNotNull { v -> v.string("key")?.let { it to v.string("value").orEmpty() } }
        .toMap()
    return when (type) {
        "bearer" -> HttpAuth("bearer") to values["token"]
        "basic" -> HttpAuth("basic", username = values["username"].orEmpty()) to values["password"]
        // An API key sent in the query has no place here; it'd have to go in the URL by hand.
        "apikey" -> if (values["in"] == "query") HttpAuth() to null else HttpAuth("apikey", headerName = values["key"] ?: "X-API-Key") to values["value"]
        else -> HttpAuth() to null
    }
}

/** A `curl` command pasted from a terminal, a browser or the docs of an API. */
fun parseCurl(text: String): ImportedRequest {
    val words = shellWords(text)
    if (words.firstOrNull() != "curl") throw HttpRequestException("Incolla un comando che inizia con curl")
    var method: String? = null
    var url: String? = null
    val headers = mutableListOf<HttpHeader>()
    val data = mutableListOf<String>()
    var json = false
    var get = false
    var user: String? = null
    val withValue = setOf("-X", "--request", "-H", "--header", "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode", "--json", "-u", "--user", "--url", "-A", "--user-agent", "-b", "--cookie", "-e", "--referer")
    // Options we don't use but whose value must not be taken for the URL.
    val skipValue = setOf("-o", "--output", "-w", "--write-out", "-m", "--max-time", "--connect-timeout", "--retry", "-x", "--proxy", "--cacert", "--cert", "--key", "-c", "--cookie-jar", "-D", "--dump-header")

    var i = 1
    while (i < words.size) {
        var word = words[i++]
        var value: String? = null
        // -XPOST, -H'…': a short option with its value attached.
        if (!word.startsWith("--") && word.length > 2 && word.startsWith("-") && word.take(2) in withValue) {
            value = word.drop(2)
            word = word.take(2)
        } else if (word.startsWith("--") && '=' in word && word.substringBefore('=') in withValue) {
            value = word.substringAfter('=')
            word = word.substringBefore('=')
        }
        fun next(): String = value ?: words.getOrNull(i++) ?: throw HttpRequestException("Manca il valore di $word")
        when (word) {
            "-X", "--request" -> method = next().uppercase()
            "-H", "--header" -> next().let { headers += HttpHeader(it.substringBefore(':').trim(), it.substringAfter(':', "").trim()) }
            "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode" -> data += next()
            "--json" -> { data += next(); json = true }
            "-u", "--user" -> user = next()
            "--url" -> url = next()
            "-A", "--user-agent" -> headers += HttpHeader("User-Agent", next())
            "-b", "--cookie" -> headers += HttpHeader("Cookie", next())
            "-e", "--referer" -> headers += HttpHeader("Referer", next())
            "-G", "--get" -> get = true
            "-I", "--head" -> method = "HEAD"
            in skipValue -> next()
            else -> if (!word.startsWith("-") && url == null) url = word
        }
    }
    if (url == null) throw HttpRequestException("Nel comando non c'è l'indirizzo")

    var body = data.joinToString("&")
    if (get && data.isNotEmpty()) {
        url += (if ('?' in url) "&" else "?") + body
        body = ""
    }
    var auth = HttpAuth()
    var secret: String? = null
    // A token found in a header goes to the vault, not into the saved request.
    headers.firstOrNull { it.name.equals("Authorization", ignoreCase = true) && it.value.startsWith("Bearer ", ignoreCase = true) }?.let {
        headers -= it
        auth = HttpAuth("bearer")
        secret = it.value.substring(7).trim()
    }
    user?.let {
        auth = HttpAuth("basic", username = it.substringBefore(':'))
        secret = it.substringAfter(':', "")
    }
    val hasContentType = headers.any { it.name.equals("Content-Type", ignoreCase = true) }
    val bodyType = when {
        body.isEmpty() -> "none"
        json || body.trimStart().startsWith("{") || body.trimStart().startsWith("[") -> "json"
        else -> "text".also { if (!hasContentType) headers += HttpHeader("Content-Type", "application/x-www-form-urlencoded") }
    }
    if (json && !hasContentType) headers += HttpHeader("Content-Type", "application/json")
    val name = url.substringAfter("://").substringBefore('?').substringAfter('/', "").ifEmpty { url.substringAfter("://") }
    return ImportedRequest(
        HttpRequest(newId(), name, method ?: if (body.isNotEmpty()) "POST" else "GET", url, headers, bodyType, body, auth),
        secret,
    )
}

/** Splits like a POSIX shell: quotes, backslashes and `\` line continuations. */
internal fun shellWords(text: String): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    var inWord = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && (text[i + 1] == '\n' || text[i + 1] == '\r') -> {
                i++
                if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            }
            c == '\\' && i + 1 < text.length -> { current.append(text[++i]); inWord = true }
            c == '\'' -> {
                val end = text.indexOf('\'', i + 1).takeIf { it >= 0 } ?: throw HttpRequestException("Un apice ' non è chiuso")
                current.append(text, i + 1, end)
                i = end
                inWord = true
            }
            c == '"' -> {
                i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in "\"\\$`\n") i++
                    current.append(text[i])
                    i++
                }
                if (i >= text.length) throw HttpRequestException("Una virgoletta \" non è chiusa")
                inWord = true
            }
            c.isWhitespace() -> if (inWord) {
                words += current.toString()
                current.clear()
                inWord = false
            }
            else -> { current.append(c); inWord = true }
        }
        i++
    }
    if (inWord) words += current.toString()
    return words
}
