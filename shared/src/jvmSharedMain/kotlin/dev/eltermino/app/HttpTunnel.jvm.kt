package dev.eltermino.app

import dev.eltermino.core.ssh.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.TimeSource

private val base = OkHttpClient.Builder()
    // Each request has its own tunnel, so nothing is worth keeping for the next one.
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    // A redirect may point to another host, which this tunnel doesn't reach: show it instead.
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    // The system proxy would see the request before the tunnel does.
    .proxy(Proxy.NO_PROXY)
    // The name is resolved by the server at the other end; locally any address will do.
    .dns { listOf(InetAddress.getLoopbackAddress()) }
    .build()

internal actual suspend fun sendThroughTunnel(connection: SshConnection, request: PreparedRequest, timeout: Duration, readFile: (String) -> ByteArray?): HttpResponse {
    val (host, port) = tunnelTarget(request.url)
    val start = TimeSource.Monotonic.markNow()
    val forward = connection.forwardLocal(host, port)
    try {
        var connected: Long? = null
        var firstByte: Long? = null
        val client = base.newBuilder()
            .socketFactory(TunnelSocketFactory(forward.localPort))
            .callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?) {
                    connected = start.elapsedNow().inWholeMilliseconds
                }

                override fun responseHeadersStart(call: Call) {
                    firstByte = start.elapsedNow().inWholeMilliseconds
                }
            })
            .build()
        val contentType = request.headers.lastOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
        val body = request.parts?.let { multipart(it, readFile) }
            ?: request.body?.toRequestBody(contentType?.toMediaTypeOrNull())
            ?: if (request.method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(null) else null
        val built = try {
            Request.Builder().url(request.url).apply {
                request.headers.forEach { (name, value) -> addHeader(name, value) }
                method(request.method, body)
            }.build()
        } catch (e: IllegalArgumentException) {
            throw HttpRequestException(e.message ?: "Richiesta non valida", e)
        }
        val response = client.newCall(built).await()
        return response.use {
            val bytes = withContext(Dispatchers.IO) { it.body.bytes() }
            HttpResponse(
                status = it.code,
                reason = it.message,
                headers = it.headers.map { (name, value) -> name to value },
                body = bytes,
                timings = HttpTimings(connected, firstByte, start.elapsedNow().inWholeMilliseconds),
            )
        }
    } catch (e: IOException) {
        throw HttpRequestException("Nessuna risposta da $host:$port attraverso ${connection.target.host}: ${e.message ?: e::class.simpleName}", e)
    } finally {
        forward.close()
    }
}

private suspend fun multipart(parts: List<PreparedPart>, readFile: (String) -> ByteArray?): MultipartBody = withContext(Dispatchers.IO) {
    MultipartBody.Builder().setType(MultipartBody.FORM).apply {
        parts.forEach { part ->
            if (part.file == null) {
                addFormDataPart(part.name, part.value)
            } else {
                val bytes = readFile(part.file) ?: throw HttpRequestException("Non riesco più a leggere ${part.fileName}: sceglilo di nuovo")
                val type = (URLConnection.guessContentTypeFromName(part.fileName) ?: "application/octet-stream").toMediaTypeOrNull()
                addFormDataPart(part.name, part.fileName, bytes.toRequestBody(type))
            }
        }
    }.build()
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
    continuation.invokeOnCancellation { cancel() }
}

/** Every socket goes to the local end of the tunnel, whatever address OkHttp asks for. */
private class TunnelSocketFactory(private val port: Int) : SocketFactory() {
    private val local = InetSocketAddress(InetAddress.getLoopbackAddress(), port)

    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) = super.connect(local, timeout)
    }

    override fun createSocket(host: String?, port: Int) = createSocket().apply { connect(local) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = createSocket(host, port)
    override fun createSocket(host: InetAddress?, port: Int) = createSocket().apply { connect(local) }
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = createSocket(address, port)
}
