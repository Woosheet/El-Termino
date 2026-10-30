package dev.eltermino.app

import dev.eltermino.core.data.HttpCheck
import dev.eltermino.core.data.HttpCollection
import dev.eltermino.core.data.HttpRequest
import dev.eltermino.core.data.TrustedHttpCert
import dev.eltermino.core.ssh.SshConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A request ready to go out: variables filled in, auth turned into a header, body encoded. */
data class PreparedRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: String?,
    /** A multipart form instead of [body]. */
    val parts: List<PreparedPart>? = null,
    val followRedirects: Boolean = false,
    /** SHA-256 of the certificates accepted by hand for this address. */
    val trustedCerts: Set<String> = emptySet(),
)

/** A form field: [value] as text, or the file at [file], read only when sending. */
data class PreparedPart(val name: String, val value: String, val file: String? = null, val fileName: String? = null)

class HttpResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val timings: HttpTimings,
    /** Where a followed redirect wanted to go, had the tunnel been able to or the chain not too long. */
    val redirectNotFollowed: String? = null,
) {
    val text: String by lazy { body.decodeToString() }
}

/** Milliseconds. [tunnelMs] is opening the tunnel and connecting through it; curl reports its own connect time there. */
data class HttpTimings(val tunnelMs: Long?, val firstByteMs: Long?, val totalMs: Long)

/** Something the user can fix: a missing variable or secret, a bad URL, a server without curl. */
class HttpRequestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The system doesn't trust this HTTPS certificate; the user may accept it by hand. */
class HttpCertificateException(val host: String, val port: Int, val sha256: String, val subject: String, val reason: String) :
    Exception("Certificato non attendibile per $host:$port: $reason")

/** The values `{{name}}` takes on [hostId]: the collection's defaults, then the host's own. */
fun HttpCollection.valuesFor(hostId: String?): Map<String, String> = variables + (hostId?.let { hostVariables[it] }.orEmpty())

/** Every `{{name}}` the request uses, in order. */
fun HttpRequest.variables(): List<String> = buildList {
    add(url)
    headers.forEach { add(it.name); add(it.value) }
    add(body)
    parts.filter { it.enabled }.forEach { add(it.name); if (it.file == null) add(it.value) }
    add(auth.username)
    add(auth.headerName)
}.flatMap(::snippetVariables).distinct()

/**
 * Fills in [request] for [hostId]. [secret] reads the vault; null means the secret is gone.
 * Throws [HttpRequestException] for what the user has to fix first.
 */
suspend fun prepare(request: HttpRequest, collection: HttpCollection?, hostId: String?, secret: suspend (String) -> ByteArray?): PreparedRequest {
    val values = collection?.valuesFor(hostId).orEmpty()
    val missing = request.variables().filter { it !in values }
    if (missing.isNotEmpty()) {
        throw HttpRequestException("Manca il valore di " + missing.joinToString { "{{$it}}" } + " per questo host")
    }
    fun fill(text: String) = fillSnippet(text, values)

    var url = fill(request.url).trim()
    if (url.isEmpty()) throw HttpRequestException("Scrivi l'indirizzo della richiesta")
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) url = "http://$url"

    val headers = request.headers.filter { it.enabled && it.name.isNotBlank() }.map { fill(it.name).trim() to fill(it.value) }.toMutableList()
    fun hasHeader(name: String) = headers.any { it.first.equals(name, ignoreCase = true) }

    val auth = request.auth
    if (auth.type != "none") {
        // Imported tokens are often `{{token}}`, so the secret takes variables too.
        val value = auth.secretId?.let { secret(it) }?.decodeToString()?.let(::fill)
            ?: throw HttpRequestException("Il segreto dell'autenticazione non è più nel vault: inseriscilo di nuovo")
        when (auth.type) {
            "bearer" -> headers += "Authorization" to "Bearer $value"
            "basic" -> headers += "Authorization" to "Basic " + Base64.encode("${fill(auth.username)}:$value".encodeToByteArray())
            "apikey" -> headers += fill(auth.headerName).ifBlank { "X-API-Key" } to value
        }
    }

    val body = when (request.bodyType) {
        "json" -> fill(request.body).also { if (!hasHeader("Content-Type")) headers += "Content-Type" to "application/json" }
        "text" -> fill(request.body).also { if (!hasHeader("Content-Type")) headers += "Content-Type" to "text/plain; charset=utf-8" }
        "form" -> formBody(fill(request.body)).also { if (!hasHeader("Content-Type")) headers += "Content-Type" to "application/x-www-form-urlencoded" }
        else -> null
    }
    // The client writes the multipart Content-Type itself, with its boundary.
    val parts = if (request.bodyType != "multipart") null else request.parts.filter { it.enabled && it.name.isNotBlank() }.map { part ->
        val file = part.file
        if (file != null) PreparedPart(fill(part.name), "", file, part.fileName ?: file.substringAfterLast('/'))
        else PreparedPart(fill(part.name), fill(part.value))
    }
    if (parts != null) headers.removeAll { it.first.equals("Content-Type", ignoreCase = true) }
    return PreparedRequest(request.method.trim().uppercase().ifEmpty { "GET" }, url, headers, body, parts, request.followRedirects)
}

/** One `name=value` a line, as `name=value&…` with both sides percent-encoded. */
internal fun formBody(lines: String): String = lines.lineSequence()
    .filter { it.isNotBlank() }
    .joinToString("&") { line ->
        val name = line.substringBefore('=').trim()
        val value = if ('=' in line) line.substringAfter('=') else ""
        percentEncode(name) + "=" + percentEncode(value)
    }

internal fun percentEncode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val c = byte.toInt() and 0xff
        if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar())
        else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
}

/** Host and port of [url], as the tunnel needs them. */
internal fun tunnelTarget(url: String): Pair<String, Int> {
    val https = url.startsWith("https://", ignoreCase = true)
    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    val host: String
    val port: String?
    if (authority.startsWith("[")) {
        host = authority.substringAfter('[').substringBefore(']')
        port = authority.substringAfter("]", "").removePrefix(":").ifEmpty { null }
    } else {
        host = authority.substringBefore(':')
        port = if (':' in authority) authority.substringAfter(':') else null
    }
    if (host.isEmpty()) throw HttpRequestException("L'indirizzo $url non ha un host")
    val number = port?.toIntOrNull()?.takeIf { it in 1..65535 } ?: if (port != null) throw HttpRequestException("La porta $port non è valida") else if (https) 443 else 80
    return host to number
}

/** Sends from this device through a tunnel on [connection]; implemented with OkHttp on the JVM. */
internal expect suspend fun sendThroughTunnel(connection: SshConnection, request: PreparedRequest, timeout: Duration, readFile: (String) -> ByteArray?): HttpResponse

/** [request] as a `curl` command line, quoted for a POSIX shell. */
fun curlCommand(request: PreparedRequest, extra: List<String> = emptyList()): String = buildList {
    add("curl")
    add("-sS")
    if (request.method != "GET" || request.body != null || request.parts != null) { add("-X"); add(shellQuote(request.method)) }
    request.headers.forEach { (name, value) -> add("-H"); add(shellQuote("$name: $value")) }
    request.body?.let { add("--data-binary"); add(shellQuote(it)) }
    request.parts?.forEach { part ->
        // --form-string, so a value starting with @ or < stays text.
        if (part.file == null) { add("--form-string"); add(shellQuote("${part.name}=${part.value}")) }
        else { add("-F"); add(shellQuote("${part.name}=@\"${part.file}\";filename=\"${part.fileName}\"")) }
    }
    addAll(extra)
    add(shellQuote(request.url))
}.joinToString(" ")

internal fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"

private const val CURL_MARK = "\n__ELTERMINO_CURL__ "

/** Runs `curl` on the server, to see exactly what the machine sees: its DNS, proxy and certificates. */
suspend fun sendWithCurl(connection: SshConnection, request: PreparedRequest, timeout: Duration = 30.seconds): HttpResponse {
    if (request.parts.orEmpty().any { it.file != null }) {
        throw HttpRequestException("curl sul server non vede i file di questo dispositivo: togli \"Esegui con curl sul server\" per mandarli")
    }
    val extra = listOf("-D", "-", "-o", "-", "--max-time", timeout.inWholeSeconds.toString(), "-w", shellQuote("${CURL_MARK}%{time_connect} %{time_starttransfer} %{time_total}"))
    val result = connection.exec(curlCommand(request, extra))
    val stderr = result.stderr.decodeToString().trim()
    when (result.exitCode) {
        0 -> Unit
        127 -> throw HttpRequestException("curl non è installato sul server")
        else -> throw HttpRequestException(stderr.ifEmpty { "curl è uscito con il codice ${result.exitCode}" })
    }
    return parseCurlOutput(result.stdout)
}

/** Headers (`-D -`), then the body, then the timings line written by `-w`. */
internal fun parseCurlOutput(output: ByteArray): HttpResponse {
    val mark = CURL_MARK.encodeToByteArray()
    val markAt = output.lastIndexOf(mark)
    if (markAt < 0) throw HttpRequestException("Risposta di curl non riconosciuta")
    val times = output.copyOfRange(markAt + mark.size, output.size).decodeToString().trim().split(' ').map { (it.toDoubleOrNull() ?: 0.0) * 1000 }
    var rest = output.copyOfRange(0, markAt)
    // Interim 1xx answers come first, each with its own blank line.
    while (true) {
        val end = rest.indexOf("\r\n\r\n".encodeToByteArray()).takeIf { it >= 0 } ?: throw HttpRequestException("Risposta di curl senza intestazioni")
        val lines = rest.copyOfRange(0, end).decodeToString().split("\r\n")
        rest = rest.copyOfRange(end + 4, rest.size)
        val status = lines.first().split(' ', limit = 3)
        val code = status.getOrNull(1)?.toIntOrNull() ?: throw HttpRequestException("Riga di stato non valida: ${lines.first()}")
        if (code in 100..199) continue
        val headers = lines.drop(1).filter { ':' in it }.map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        return HttpResponse(code, status.getOrElse(2) { "" }, headers, rest, HttpTimings(times.getOrNull(0)?.toLong(), times.getOrNull(1)?.toLong(), times.getOrNull(2)?.toLong() ?: 0))
    }
}

private fun ByteArray.indexOf(needle: ByteArray, from: Int = 0): Int {
    outer@ for (i in from..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

private fun ByteArray.lastIndexOf(needle: ByteArray): Int {
    outer@ for (i in size - needle.size downTo 0) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

/** Prepares [request] for [hostId] and sends it over [connection], through the tunnel or with curl on the server. */
suspend fun sendRequest(
    connection: SshConnection,
    request: HttpRequest,
    collection: HttpCollection?,
    hostId: String?,
    secret: suspend (String) -> ByteArray?,
    timeout: Duration = 30.seconds,
    readFile: (String) -> ByteArray? = { null },
    trustedCerts: List<TrustedHttpCert> = emptyList(),
): HttpResponse {
    val prepared = prepare(request, collection, hostId, secret).let { ready ->
        val (host, port) = tunnelTarget(ready.url)
        ready.copy(trustedCerts = trustedCerts.filter { it.host.equals(host, ignoreCase = true) && it.port == port }.mapTo(mutableSetOf()) { it.sha256 })
    }
    return if (request.viaCurl) sendWithCurl(connection, prepared, timeout) else sendThroughTunnel(connection, prepared, timeout, readFile)
}

class CheckResult(val check: HttpCheck, val passed: Boolean, val actual: String)

/** How a check reads in the editor and in the results: "Stato uguale a 200". */
fun HttpCheck.describe(): String = when (kind) {
    "status" -> "Stato uguale a $expected"
    "contains" -> "Il corpo contiene \"$expected\""
    "json" -> "Il campo $path è uguale a $expected"
    "time" -> "Tempo sotto $expected ms"
    else -> kind
}

fun HttpCheck.evaluate(response: HttpResponse): CheckResult = when (kind) {
    "status" -> CheckResult(this, response.status.toString() == expected.trim(), response.status.toString())
    "contains" -> (expected in response.text).let { CheckResult(this, it, if (it) "trovato" else "non trovato") }
    "json" -> {
        val value = runCatching { Json.parseToJsonElement(response.text) }.getOrNull()?.let { jsonField(it, path) }
        when (value) {
            null -> CheckResult(this, false, "campo assente")
            else -> {
                val actual = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: value.toString()
                CheckResult(this, actual == expected.trim(), actual)
            }
        }
    }
    "time" -> {
        val limit = expected.trim().toLongOrNull()
        CheckResult(this, limit != null && response.timings.totalMs < limit, "${response.timings.totalMs} ms")
    }
    else -> CheckResult(this, false, "verifica sconosciuta")
}

/** Follows `data.items[0].id` into [root]; null when a step isn't there. */
internal fun jsonField(root: JsonElement, path: String): JsonElement? {
    var current: JsonElement = root
    val steps = Regex("""([^.\[\]]+)|\[(\d+)]""").findAll(path.trim().removePrefix("$").removePrefix("."))
    for (step in steps) {
        val (name, index) = step.destructured
        current = when {
            index.isNotEmpty() -> (current as? JsonArray)?.getOrNull(index.toInt()) ?: return null
            else -> (current as? JsonObject)?.get(name) ?: return null
        }
    }
    return current
}
