package dev.eltermino.app

import dev.eltermino.core.data.Host
import dev.eltermino.core.data.HttpCollection
import dev.eltermino.core.data.HttpHeader
import dev.eltermino.core.data.HttpRequest
import dev.eltermino.core.data.TrustedHttpCert
import dev.eltermino.core.data.newId
import dev.eltermino.core.ssh.SshConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

/** A Requests tab: the collections, sent from one host through its shared link. */
class RequestsTab internal constructor(id: String, override val hostId: String, label: String) : Tab(id, label) {
    internal val mutableLease = MutableStateFlow<LinkLease?>(null)

    /** Holds the lease until the tab closes. */
    internal var job: Job? = null

    /** Null until the tab has its share of the host's link. */
    val lease: StateFlow<LinkLease?> = mutableLease.asStateFlow()

    /** The request open in the editor. */
    val selected = MutableStateFlow<String?>(null)

    private val mutableHistory = MutableStateFlow<Map<String, List<HttpOutcome>>>(emptyMap())

    /**
     * The last answers to each request on this host, newest first. Kept in memory only:
     * bodies can hold tokens, and the workspace file isn't encrypted.
     */
    val history: StateFlow<Map<String, List<HttpOutcome>>> = mutableHistory.asStateFlow()

    private val mutableSending = MutableStateFlow<Set<String>>(emptySet())
    val sending: StateFlow<Set<String>> = mutableSending.asStateFlow()

    internal fun started(requestId: String) = mutableSending.update { it + requestId }

    internal fun finished(requestId: String, outcome: HttpOutcome) {
        mutableHistory.update { it + (requestId to (listOf(outcome) + it[requestId].orEmpty()).take(HISTORY)) }
        mutableSending.update { it - requestId }
    }

    private companion object {
        const val HISTORY = 10
    }
}

sealed interface HttpOutcome {
    val atEpochSeconds: Long

    class Answered(val response: HttpResponse, val checks: List<CheckResult>, override val atEpochSeconds: Long) : HttpOutcome {
        val passed: Boolean get() = checks.all { it.passed }
    }

    class Failed(
        val message: String,
        override val atEpochSeconds: Long,
        /** Set when accepting a certificate would make the request work. */
        val certificate: HttpCertificateException? = null,
    ) : HttpOutcome
}

/** One cell of a collection run: a request on a host. */
sealed interface RunCell {
    data object Waiting : RunCell
    data object Running : RunCell
    class Done(val outcome: HttpOutcome) : RunCell
}

/** A collection running on several hosts at once; [cells] fills in as answers arrive. */
class CollectionRun internal constructor(val collection: HttpCollection, val hosts: List<Host>) {
    private val mutableCells = MutableStateFlow<Map<Pair<String, String>, RunCell>>(emptyMap())

    /** (host id, request id) to how that request went on that host. */
    val cells: StateFlow<Map<Pair<String, String>, RunCell>> = mutableCells.asStateFlow()
    internal var job: Job? = null

    val finished: Boolean get() = job?.isCompleted == true

    internal fun set(hostId: String, requestId: String, cell: RunCell) = mutableCells.update { it + ((hostId to requestId) to cell) }

    fun cancel() {
        job?.cancel()
    }
}

/** Sends requests over the hosts' shared links, from a tab or across a whole group. */
class HttpRunner(private val services: AppServices, private val connections: Connections, private val scope: CoroutineScope) {
    private suspend fun secret(id: String) = services.vault.get(id)

    private fun trustedCerts() = services.workspace.workspace.value.trustedHttpCerts

    /** From now on [problem]'s certificate is good for its address. */
    suspend fun acceptCertificate(problem: HttpCertificateException) {
        val accepted = TrustedHttpCert(problem.host, problem.port, problem.sha256)
        services.workspace.update { ws ->
            ws.copy(trustedHttpCerts = ws.trustedHttpCerts.filterNot { it.host == accepted.host && it.port == accepted.port } + accepted)
        }
    }

    /** Sends [request] from [tab]'s host; the answer goes to the tab's history. */
    fun send(tab: RequestsTab, collection: HttpCollection?, request: HttpRequest) {
        if (request.id in tab.sending.value) return
        tab.started(request.id)
        scope.launch {
            val outcome = outcome(request) {
                val lease = tab.lease.filterNotNull().first()
                sendRequest(connectionOf(lease), request, collection, tab.hostId, ::secret, readFile = services.platform::readLocalFile, trustedCerts = trustedCerts())
            }
            tab.finished(request.id, outcome)
        }
    }

    /**
     * Runs every request of [collection] on each of [hosts]: hosts in parallel, requests in
     * order, each host over its own link. A host that can't connect fails all its cells.
     */
    fun run(collection: HttpCollection, hosts: List<Host>): CollectionRun {
        val run = CollectionRun(collection, hosts)
        hosts.forEach { host -> collection.requests.forEach { run.set(host.id, it.id, RunCell.Waiting) } }
        run.job = scope.launch {
            hosts.forEach { host ->
                launch {
                    val lease = connections.acquire(host)
                    try {
                        val connection = try {
                            connectionOf(lease)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val failed = HttpOutcome.Failed(message(e), nowEpochSeconds())
                            collection.requests.forEach { run.set(host.id, it.id, RunCell.Done(failed)) }
                            return@launch
                        }
                        for (request in collection.requests) {
                            run.set(host.id, request.id, RunCell.Running)
                            val outcome = outcome(request) { sendRequest(connection, request, collection, host.id, ::secret, readFile = services.platform::readLocalFile, trustedCerts = trustedCerts()) }
                            run.set(host.id, request.id, RunCell.Done(outcome))
                        }
                    } finally {
                        withContext(NonCancellable) { lease.release() }
                    }
                }
            }
        }
        return run
    }

    /**
     * The API description [tab]'s host serves at [address], through the tunnel, as the URL that
     * answered and its text. Without a path, or when the path isn't the description itself, it
     * tries the usual places in [API_DESCRIPTION_PATHS] under it.
     */
    suspend fun fetchApiDescription(tab: RequestsTab, address: String): Pair<String, String> {
        val base = address.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        val hasPath = Regex("""^https?://[^/]+/.+""").matches(base)
        val connection = connectionOf(tab.lease.filterNotNull().first())
        var last: String? = null
        for (url in (if (hasPath) listOf(base) else emptyList()) + API_DESCRIPTION_PATHS.map { base + it }) {
            val response = try {
                sendRequest(connection, HttpRequest(newId(), "API", url = url, headers = listOf(HttpHeader("Accept", "application/json"))), null, tab.hostId, ::secret)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = message(e)
                continue
            }
            if (response.status == 200 && (isOpenApi(response.text) || isOpenApiYaml(response.text))) return url to response.text
            last = "$url risponde ${response.status}"
        }
        throw HttpRequestException("Non trovo una descrizione OpenAPI su $base: ho provato anche ${API_DESCRIPTION_PATHS.joinToString(", ")}" + (last?.let { ". L'ultima risposta: $it" } ?: ""))
    }

    private suspend fun connectionOf(lease: LinkLease): SshConnection {
        val state = withTimeoutOrNull(2.minutes) { lease.link.state.first { it is LinkState.Up || it is LinkState.Down } }
        return when (state) {
            is LinkState.Up -> state.connection
            is LinkState.Down -> throw HttpRequestException(state.message)
            else -> throw HttpRequestException("La connessione all'host non è arrivata in tempo")
        }
    }

    private suspend fun outcome(request: HttpRequest, send: suspend () -> HttpResponse): HttpOutcome = try {
        val response = send()
        HttpOutcome.Answered(response, request.checks.map { it.evaluate(response) }, nowEpochSeconds())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpCertificateException) {
        HttpOutcome.Failed(e.message.orEmpty(), nowEpochSeconds(), e)
    } catch (e: Exception) {
        HttpOutcome.Failed(message(e), nowEpochSeconds())
    }

    private fun message(e: Exception) = if (e is HttpRequestException) e.message ?: "Richiesta non riuscita" else failureMessage(e)
}
