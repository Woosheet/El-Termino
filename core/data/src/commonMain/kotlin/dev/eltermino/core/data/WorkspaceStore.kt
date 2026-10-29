package dev.eltermino.core.data

import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface WorkspaceStore {
    val workspace: StateFlow<Workspace>

    /** Applies [transform] and persists the result before returning it. */
    suspend fun update(transform: (Workspace) -> Workspace): Workspace
}

@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

sealed interface KnownHostStatus {
    data object Unknown : KnownHostStatus
    data object Trusted : KnownHostStatus
    /** The server presents a different key than the one we stored: possible MITM. */
    data class Changed(val previous: KnownHost) : KnownHostStatus
}

fun Workspace.knownHostStatus(host: String, port: Int, keyType: String, fingerprintSha256: String): KnownHostStatus {
    val entries = knownHosts.filter { it.host.equals(host, ignoreCase = true) && it.port == port }
    return when {
        entries.any { it.fingerprintSha256 == fingerprintSha256 } -> KnownHostStatus.Trusted
        // A server can have one key per type; a new type alone isn't suspicious.
        entries.any { it.keyType == keyType } -> KnownHostStatus.Changed(entries.first { it.keyType == keyType })
        else -> KnownHostStatus.Unknown
    }
}

/** Trusting a key replaces any stored key of the same type for that host. */
fun Workspace.trust(entry: KnownHost): Workspace = copy(
    knownHosts = knownHosts.filterNot {
        it.host.equals(entry.host, ignoreCase = true) && it.port == entry.port && it.keyType == entry.keyType
    } + entry,
)

/** Group names from the root down to [groupId], for display as "Produzione / Web". */
fun Workspace.groupPath(groupId: String?): List<String> {
    val path = mutableListOf<String>()
    var current = group(groupId)
    while (current != null && path.size < 32) {
        path.add(0, current.name)
        current = group(current.parentId)
    }
    return path
}

/** The jump chain of [host], outermost first. Stops at cycles. */
fun Workspace.jumpChain(host: Host): List<Host> {
    val chain = mutableListOf<Host>()
    val seen = mutableSetOf(host.id)
    var next = host(host.jumpHostId)
    while (next != null && seen.add(next.id)) {
        chain.add(0, next)
        next = host(next.jumpHostId)
    }
    return chain
}
