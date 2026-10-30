package dev.eltermino.core.data

import kotlinx.serialization.Serializable

/** What a backup file holds once decrypted. [secrets] are vault entries in base64, by id. */
@Serializable
class BackupContent(
    val formatVersion: Int,
    val appVersion: String? = null,
    val workspace: Workspace,
    val secrets: Map<String, String> = emptyMap(),
) {
    companion object {
        const val CURRENT_FORMAT = 1
    }
}

/** The workspace as it goes into a backup: settings of this device stay here. */
fun Workspace.forBackup(): Workspace = copy(ui = UiSettings(), lastSession = null)

/** Items of [backup] replace those with the same id; the rest of both is kept. */
fun Workspace.mergedWith(backup: Workspace): Workspace = copy(
    hosts = mergeById(hosts, backup.hosts) { it.id },
    groups = mergeById(groups, backup.groups) { it.id },
    identities = mergeById(identities, backup.identities) { it.id },
    keys = mergeById(keys, backup.keys) { it.id },
    knownHosts = knownHosts + backup.knownHosts.filter { pin -> knownHosts.none { it.sameSlot(pin) } },
    trustedCas = mergeById(trustedCas, backup.trustedCas) { it.id },
    snippets = mergeById(snippets, backup.snippets) { it.id },
    httpCollections = mergeById(httpCollections, backup.httpCollections) { it.id },
    savedLayouts = mergeById(savedLayouts, backup.savedLayouts) { it.id },
)

/** [backup] in place of everything but this device's settings. */
fun Workspace.replacedBy(backup: Workspace): Workspace {
    val hostIds = backup.hosts.mapTo(mutableSetOf()) { it.id }
    val session = lastSession?.takeIf { layout ->
        layout.tabs.all { tab -> tab.panes.all { pane -> pane?.hostId.let { it == null || it in hostIds } } }
    }
    return backup.copy(version = version, ui = ui, lastSession = session)
}

/** Keys of [backup] that "merge" would drop because a different one is pinned here. */
fun Workspace.knownHostConflicts(backup: Workspace): List<KnownHost> = backup.knownHosts.filter { pin ->
    knownHosts.any { it.sameSlot(pin) && it.fingerprintSha256 != pin.fingerprintSha256 }
}

private fun KnownHost.sameSlot(other: KnownHost) =
    host.equals(other.host, ignoreCase = true) && port == other.port && keyType == other.keyType

private fun <T> mergeById(local: List<T>, backup: List<T>, id: (T) -> String): List<T> {
    val incoming = backup.associateBy(id)
    val localIds = local.mapTo(mutableSetOf(), id)
    return local.map { incoming[id(it)] ?: it } + backup.filter { id(it) !in localIds }
}
