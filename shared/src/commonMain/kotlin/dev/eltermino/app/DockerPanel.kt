package dev.eltermino.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/** A Docker tab: the containers on a host, refreshed every few seconds while the tab is open. */
class DockerTab internal constructor(id: String, override val hostId: String, label: String) : Tab(id, label) {
    internal val mutableLease = MutableStateFlow<LinkLease?>(null)
    internal var job: Job? = null
    val lease: StateFlow<LinkLease?> = mutableLease.asStateFlow()

    internal val mutableContainers = MutableStateFlow<List<DockerContainer>?>(null)

    /** Null until the first answer. */
    val containers: StateFlow<List<DockerContainer>?> = mutableContainers.asStateFlow()

    internal val mutableError = MutableStateFlow<String?>(null)

    /** Why the list can't be read; clears by itself once it can. */
    val error: StateFlow<String?> = mutableError.asStateFlow()

    internal val mutableActionError = MutableStateFlow<String?>(null)

    /** Why the last start, stop or restart failed; stays until the next one. */
    val actionError: StateFlow<String?> = mutableActionError.asStateFlow()

    private val mutableBusy = MutableStateFlow<Set<String>>(emptySet())

    /** Containers with a start, stop or restart under way. */
    val busy: StateFlow<Set<String>> = mutableBusy.asStateFlow()

    /** Asks the loop for a fresh list now, after an action. */
    internal val refresh = Channel<Unit>(Channel.CONFLATED)

    internal fun busy(id: String, on: Boolean) = mutableBusy.update { if (on) it + id else it - id }

    internal companion object {
        val INTERVAL = 5.seconds
    }
}

data class DockerContainer(
    val id: String,
    val name: String,
    val image: String,
    /** "running", "exited", "paused", "restarting", "created"… as Docker says it. */
    val state: String,
    /** "Up 2 hours", "Exited (0) 3 hours ago". */
    val status: String,
    val ports: String,
    /** From `docker stats`, only for running containers: "0.37%", "23.4MiB / 7.65GiB". */
    val cpu: String? = null,
    val memory: String? = null,
) {
    val running: Boolean get() = state == "running"
}

class DockerException(message: String) : Exception(message)

internal const val DOCKER_LIST_COMMAND =
    "docker ps -a --no-trunc --format '{{json .}}' && echo @ && docker stats --no-stream --no-trunc --format '{{json .}}'"

private val json = Json { ignoreUnknownKeys = true }

/** Reads [DOCKER_LIST_COMMAND]'s output: one JSON object a line, containers then stats. */
internal fun parseContainers(text: String): List<DockerContainer> {
    val (ps, stats) = text.split(Regex("""(?m)^@\s*$""")).let { it[0] to it.getOrElse(1) { "" } }
    fun objects(part: String) = part.lines().map { it.trim() }.filter { it.startsWith("{") }.map { json.parseToJsonElement(it).jsonObject }
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
    val usage = objects(stats).associateBy { it.text("ID") }
    return objects(ps).map { row ->
        val id = row.text("ID")
        val stat = usage[id]
        DockerContainer(
            id = id,
            // Several names come comma separated; the first is the one people use.
            name = row.text("Names").substringBefore(','),
            image = row.text("Image"),
            state = row.text("State"),
            status = row.text("Status"),
            ports = row.text("Ports"),
            cpu = stat?.text("CPUPerc"),
            memory = stat?.text("MemUsage"),
        )
    }.sortedWith(compareBy({ !it.running }, { it.name }))
}

/** What went wrong, from Docker's own words, in terms of what to do about it. */
internal fun dockerError(hostLabel: String, exitCode: Int?, stderr: String): String = when {
    exitCode == 127 || "command not found" in stderr ->
        "Docker non è installato su $hostLabel"
    "permission denied" in stderr && "docker.sock" in stderr ->
        "L'utente non può usare Docker su $hostLabel: va aggiunto al gruppo docker"
    "Cannot connect to the Docker daemon" in stderr -> "Docker non è avviato su $hostLabel"
    else -> stderr.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: "docker è uscito con codice $exitCode"
}

/** The verbs a container takes from the panel. */
enum class DockerAction(val verb: String) { Start("start"), Stop("stop"), Restart("restart") }

/** Lists [tab]'s containers every few seconds over [lease]'s link, and sooner after an action. */
internal suspend fun watchDocker(tab: DockerTab, lease: LinkLease) {
    lease.link.state.collectLatest { state ->
        when (state) {
            is LinkState.Up -> while (true) {
                try {
                    val result = state.connection.exec(DOCKER_LIST_COMMAND)
                    if (result.exitCode != 0) throw DockerException(dockerError(tab.label, result.exitCode, result.stderr.decodeToString()))
                    tab.mutableContainers.value = parseContainers(result.stdout.decodeToString())
                    tab.mutableError.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tab.mutableError.value = if (e is DockerException) e.message else describe(e)
                }
                withTimeoutOrNull(DockerTab.INTERVAL) { tab.refresh.receive() }
            }
            is LinkState.Down -> tab.mutableError.value = state.message
            is LinkState.Retrying -> tab.mutableError.value = "Riconnessione: ${state.message}"
            LinkState.Connecting -> Unit
        }
    }
}

/** Runs [action] on [container]; a failure lands in the tab's error. */
internal suspend fun runDockerAction(tab: DockerTab, lease: LinkLease, container: DockerContainer, action: DockerAction) {
    val connection = (lease.link.state.value as? LinkState.Up)?.connection
    if (connection == null) {
        tab.mutableActionError.value = "${tab.label} non è collegato"
        return
    }
    tab.mutableActionError.value = null
    tab.busy(container.id, true)
    try {
        val result = connection.exec("docker ${action.verb} ${shellQuote(container.id)}")
        if (result.exitCode != 0) {
            tab.mutableActionError.value = "${container.name}: " + dockerError(tab.label, result.exitCode, result.stderr.decodeToString())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        tab.mutableActionError.value = "${container.name}: " + describe(e)
    } finally {
        tab.busy(container.id, false)
        tab.refresh.trySend(Unit)
    }
}

/** Follows [container]'s log in a terminal, starting from its last 200 lines. */
internal fun dockerLogsCommand(container: DockerContainer) = "docker logs -f --tail 200 ${shellQuote(container.name)}"

/** A shell inside [container]: bash where there is one, sh otherwise. */
internal fun dockerShellCommand(container: DockerContainer) =
    "docker exec -it ${shellQuote(container.name)} sh -c 'command -v bash >/dev/null && exec bash || exec sh'"
