package dev.eltermino.app

import dev.eltermino.core.ssh.ExecResult
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
import kotlinx.serialization.json.jsonArray
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

    private val mutableSection = MutableStateFlow(DockerSection.Containers)

    /** What's on screen, which is also all that gets read besides the containers. */
    val section: StateFlow<DockerSection> = mutableSection.asStateFlow()

    internal val mutableResources = MutableStateFlow<Map<DockerKind, List<DockerResource>>>(emptyMap())

    /** Images, volumes and networks, each from when its section was last open. */
    val resources: StateFlow<Map<DockerKind, List<DockerResource>>> = mutableResources.asStateFlow()

    internal val mutableComposeProjects = MutableStateFlow<List<ComposeProject>?>(null)
    val composeProjects: StateFlow<List<ComposeProject>?> = mutableComposeProjects.asStateFlow()

    internal val mutableComposeAvailable = MutableStateFlow(false)

    /** Whether this host has `docker compose`; the section shows only if so. */
    val composeAvailable: StateFlow<Boolean> = mutableComposeAvailable.asStateFlow()

    internal val mutableConfirmation = MutableStateFlow<DockerConfirmation?>(null)

    /** Something that can't be undone, waiting for a yes. */
    val confirmation: StateFlow<DockerConfirmation?> = mutableConfirmation.asStateFlow()

    fun select(section: DockerSection) {
        mutableSection.value = section
        refresh.trySend(Unit)
    }

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

    /** Docker refuses a running one: it has to be stopped first. */
    val removable: Boolean get() = !running
}

enum class DockerKind(internal val command: String, internal val list: String) {
    Container("rm", ""),
    Image("image rm", "docker image ls --no-trunc --format '{{json .}}'"),
    Volume("volume rm", "docker volume ls --format '{{json .}}'"),
    Network("network rm", "docker network ls --no-trunc --format '{{json .}}'"),
}

enum class DockerSection(val kind: DockerKind?) {
    Containers(null), Images(DockerKind.Image), Volumes(DockerKind.Volume), Networks(DockerKind.Network), Compose(null)
}

/** An image, volume or network; [id] is what to name it in a command. */
data class DockerResource(val kind: DockerKind, val id: String, val name: String, val detail: String, val removable: Boolean = true)

data class ComposeProject(val name: String, val status: String, val configFiles: List<String>)

enum class ComposeAction(internal val verb: String) { Up("up -d"), Down("down"), Restart("restart") }

/** A question for the user; [command] runs on yes. */
class DockerConfirmation(val title: String, val message: String, val action: String, internal val busyId: String, internal val label: String, internal val command: String)

class DockerException(message: String) : Exception(message)

internal const val DOCKER_LIST_COMMAND =
    "docker ps -a --no-trunc --format '{{json .}}' && echo @ && docker stats --no-stream --no-trunc --format '{{json .}}'"

private val json = Json { ignoreUnknownKeys = true }

/** Reads [DOCKER_LIST_COMMAND]'s output: one JSON object a line, containers then stats. */
internal fun parseContainers(text: String): List<DockerContainer> {
    val (ps, stats) = text.split(Regex("""(?m)^@\s*$""")).let { it[0] to it.getOrElse(1) { "" } }
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

private fun objects(part: String) = part.lines().map { it.trim() }.filter { it.startsWith("{") }.map { json.parseToJsonElement(it).jsonObject }

private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()

/** Reads what [DockerKind.list] printed. */
internal fun parseResources(kind: DockerKind, text: String): List<DockerResource> = objects(text).map { row ->
    when (kind) {
        DockerKind.Image -> {
            val tagged = row.text("Repository") != "<none>" && row.text("Tag") != "<none>"
            // An id can't remove an image with two tags, so the tag it is, where there is one.
            val name = if (tagged) "${row.text("Repository")}:${row.text("Tag")}" else row.text("ID")
            DockerResource(kind, name, name, row.text("Size"))
        }
        DockerKind.Volume -> DockerResource(kind, row.text("Name"), row.text("Name"), row.text("Driver"))
        else -> DockerResource(kind, row.text("ID"), row.text("Name"), row.text("Driver"), row.text("Name") !in DEFAULT_NETWORKS)
    }
}.sortedBy { it.name }

private val DEFAULT_NETWORKS = setOf("bridge", "host", "none")

internal const val COMPOSE_PROBE_COMMAND = "docker compose version"
internal const val COMPOSE_LIST_COMMAND = "docker compose ls -a --format json"

/** Reads [COMPOSE_LIST_COMMAND]'s output, a single JSON array. */
internal fun parseComposeProjects(text: String): List<ComposeProject> {
    if (text.isBlank()) return emptyList()
    return json.parseToJsonElement(text).jsonArray.map { element ->
        val row = element.jsonObject
        ComposeProject(row.text("Name"), row.text("Status"), row.text("ConfigFiles").split(',').filter { it.isNotBlank() })
    }
}

internal fun dockerRemoveCommand(kind: DockerKind, id: String) = "docker ${kind.command} ${shellQuote(id)}"

internal fun dockerComposeCommand(project: ComposeProject, action: ComposeAction): String {
    val files = project.configFiles.joinToString("") { " -f ${shellQuote(it)}" }
    return "docker compose --project-name ${shellQuote(project.name)}$files ${action.verb}"
}

/** What went wrong, from Docker's own words, in terms of what to do about it. */
internal fun dockerError(hostLabel: String, exitCode: Int?, stderr: String): String = when {
    exitCode == 127 || "command not found" in stderr ->
        "Docker non è installato su $hostLabel"
    "permission denied" in stderr && "docker.sock" in stderr ->
        "L'utente non può usare Docker su $hostLabel: va aggiunto al gruppo docker"
    "Cannot connect to the Docker daemon" in stderr -> "Docker non è avviato su $hostLabel"
    " is using " in stderr || "volume is in use" in stderr || "active endpoints" in stderr ->
        "È ancora in uso: ferma e rimuovi prima ciò che lo usa"
    else -> stderr.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: "docker è uscito con codice $exitCode"
}

/** The verbs a container takes from the panel. */
enum class DockerAction(val verb: String) { Start("start"), Stop("stop"), Restart("restart") }

/** Lists [tab]'s containers every few seconds over [lease]'s link, and sooner after an action. */
internal suspend fun watchDocker(tab: DockerTab, lease: LinkLease) {
    lease.link.state.collectLatest { state ->
        when (state) {
            is LinkState.Up -> {
                var probed = false
                while (true) {
                    try {
                        fun check(result: ExecResult) = result.also {
                            if (it.exitCode != 0) throw DockerException(dockerError(tab.label, it.exitCode, it.stderr.decodeToString()))
                        }.stdout.decodeToString()
                        tab.mutableContainers.value = parseContainers(check(state.connection.exec(DOCKER_LIST_COMMAND)))
                        if (!probed) {
                            tab.mutableComposeAvailable.value = state.connection.exec(COMPOSE_PROBE_COMMAND).exitCode == 0
                            probed = true
                        }
                        val section = tab.section.value
                        val kind = section.kind
                        if (kind != null) {
                            val list = parseResources(kind, check(state.connection.exec(kind.list)))
                            tab.mutableResources.update { it + (kind to list) }
                        } else if (section == DockerSection.Compose) {
                            tab.mutableComposeProjects.value = parseComposeProjects(check(state.connection.exec(COMPOSE_LIST_COMMAND)))
                        }
                        tab.mutableError.value = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        tab.mutableError.value = if (e is DockerException) e.message else describe(e)
                    }
                    withTimeoutOrNull(DockerTab.INTERVAL) { tab.refresh.receive() }
                }
            }
            is LinkState.Down -> tab.mutableError.value = state.message
            is LinkState.Retrying -> tab.mutableError.value = "Riconnessione: ${state.message}"
            LinkState.Connecting -> Unit
        }
    }
}

/** Runs [action] on [container]; a failure lands in the tab's error. */
internal suspend fun runDockerAction(tab: DockerTab, lease: LinkLease, container: DockerContainer, action: DockerAction) =
    runDockerCommand(tab, lease, container.id, container.name, "docker ${action.verb} ${shellQuote(container.id)}")

/** Runs [command] with [busyId] showing as busy; a failure lands in the tab's error, after [label]. */
internal suspend fun runDockerCommand(tab: DockerTab, lease: LinkLease, busyId: String, label: String, command: String) {
    val connection = (lease.link.state.value as? LinkState.Up)?.connection
    if (connection == null) {
        tab.mutableActionError.value = "${tab.label} non è collegato"
        return
    }
    tab.mutableActionError.value = null
    tab.busy(busyId, true)
    try {
        val result = connection.exec(command)
        if (result.exitCode != 0) {
            tab.mutableActionError.value = "$label: " + dockerError(tab.label, result.exitCode, result.stderr.decodeToString())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        tab.mutableActionError.value = "$label: " + describe(e)
    } finally {
        tab.busy(busyId, false)
        tab.refresh.trySend(Unit)
    }
}

/** Follows [container]'s log in a terminal, starting from its last 200 lines. */
internal fun dockerLogsCommand(container: DockerContainer) = "docker logs -f --tail 200 ${shellQuote(container.name)}"

/** A shell inside [container]: bash where there is one, sh otherwise. */
internal fun dockerShellCommand(container: DockerContainer) =
    "docker exec -it ${shellQuote(container.name)} sh -c 'command -v bash >/dev/null && exec bash || exec sh'"
