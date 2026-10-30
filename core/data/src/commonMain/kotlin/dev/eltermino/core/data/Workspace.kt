package dev.eltermino.core.data

import kotlinx.serialization.Serializable

/**
 * Everything the user configures, saved as one JSON document. Secrets never appear
 * here: fields ending in `SecretId` point into the vault.
 */
@Serializable
data class Workspace(
    val version: Int = CURRENT_VERSION,
    val hosts: List<Host> = emptyList(),
    val groups: List<HostGroup> = emptyList(),
    val identities: List<Identity> = emptyList(),
    val keys: List<SshKeyEntry> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    /** Saved HTTP requests, sent from a host through its SSH connection. */
    val httpCollections: List<HttpCollection> = emptyList(),
    /** Tab layouts saved by name, to reopen with one click. */
    val savedLayouts: List<SavedLayout> = emptyList(),
    /** What was open last time, offered again at the next start. */
    val lastSession: SavedLayout? = null,
    val ui: UiSettings = UiSettings(),
) {
    fun host(id: String?): Host? = id?.let { hosts.firstOrNull { h -> h.id == it } }
    fun group(id: String?): HostGroup? = id?.let { groups.firstOrNull { g -> g.id == it } }
    fun identity(id: String?): Identity? = id?.let { identities.firstOrNull { i -> i.id == it } }
    fun key(id: String?): SshKeyEntry? = id?.let { keys.firstOrNull { k -> k.id == it } }
    fun snippet(id: String?): Snippet? = id?.let { snippets.firstOrNull { s -> s.id == it } }
    fun httpCollection(id: String?): HttpCollection? = id?.let { httpCollections.firstOrNull { c -> c.id == it } }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class Host(
    val id: String,
    val label: String,
    val address: String,
    val port: Int = 22,
    val groupId: String? = null,
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    /** Overrides the identity's username when set. */
    val username: String? = null,
    val identityId: String? = null,
    /** The host to go through first; that host may have its own jump host. */
    val jumpHostId: String? = null,
    val notes: String = "",
    val lastConnectedEpochSeconds: Long? = null,
    /** Folders saved in the file panel. */
    val bookmarks: List<String> = emptyList(),
    val forwards: List<PortForwardRule> = emptyList(),
)

/**
 * A port forward saved on a host. Ports listen on loopback only: this device's for "local" and
 * "dynamic", the server's for "remote".
 */
@Serializable
data class PortForwardRule(
    val id: String,
    val name: String = "",
    /** "local" (here → server side), "remote" (server → this side) or "dynamic" (SOCKS proxy). */
    val kind: String = "local",
    /** The port that listens: here for local and dynamic, on the server for remote. */
    val bindPort: Int,
    /** Where connections go: as the server sees it for local, as this device sees it for remote. Unused for dynamic. */
    val targetHost: String = "localhost",
    val targetPort: Int = 0,
    /** Started whenever the app connects to the host. */
    val autoStart: Boolean = false,
)

@Serializable
data class HostGroup(
    val id: String,
    val name: String,
    val parentId: String? = null,
)

/** Credentials that several hosts can share, as Termius identities do. */
@Serializable
data class Identity(
    val id: String,
    val label: String,
    val username: String,
    val passwordSecretId: String? = null,
    val keyId: String? = null,
)

@Serializable
data class SshKeyEntry(
    val id: String,
    val label: String,
    val type: String,
    /** `authorized_keys` line. */
    val publicKey: String,
    val fingerprintSha256: String,
    val privateKeySecretId: String,
    val encrypted: Boolean,
    /** Saved only when the user chose to remember it. */
    val passphraseSecretId: String? = null,
    /** OpenSSH `-cert.pub` contents. */
    val certificate: String? = null,
)

@Serializable
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprintSha256: String,
    val publicKey: String,
    val addedAtEpochSeconds: Long,
)

/** A saved command. `{{name}}` placeholders are asked for each time it runs. */
@Serializable
data class Snippet(
    val id: String,
    val name: String,
    val command: String,
    /** Presses Enter after inserting the command; otherwise it waits on the prompt. */
    val run: Boolean = true,
)

/**
 * Requests that belong together, usable on any host. `{{name}}` in a request takes the
 * host's value from [hostVariables], or else the collection's default from [variables].
 */
@Serializable
data class HttpCollection(
    val id: String,
    val name: String,
    val requests: List<HttpRequest> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    /** Host id to that host's values. */
    val hostVariables: Map<String, Map<String, String>> = emptyMap(),
)

@Serializable
data class HttpRequest(
    val id: String,
    val name: String,
    val method: String = "GET",
    /** As the server sees it: `http://localhost:8080/health` is a service on the host itself. */
    val url: String = "",
    val headers: List<HttpHeader> = emptyList(),
    /** "none", "json", "form" (one `name=value` a line), "text" or "multipart" (see [parts]). */
    val bodyType: String = "none",
    val body: String = "",
    /** The fields of a multipart form, text or files. */
    val parts: List<HttpPart> = emptyList(),
    val auth: HttpAuth = HttpAuth(),
    /** Runs `curl` on the server instead of sending through the tunnel. */
    val viaCurl: Boolean = false,
    val checks: List<HttpCheck> = emptyList(),
)

/** A multipart field: [value] as text, or the file at [file] when it's set. */
@Serializable
data class HttpPart(
    val name: String,
    val value: String = "",
    /** A path on this device, or a `content://` URI on Android. */
    val file: String? = null,
    /** The name the server sees for [file]. */
    val fileName: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class HttpHeader(val name: String, val value: String, val enabled: Boolean = true)

@Serializable
data class HttpAuth(
    /** "none", "bearer", "basic" or "apikey". */
    val type: String = "none",
    /** The Basic username. */
    val username: String = "",
    /** The header that carries an API key. */
    val headerName: String = "X-API-Key",
    /** The token, password or key, kept in the vault. */
    val secretId: String? = null,
)

/** A condition a response must meet, checked without writing code. */
@Serializable
data class HttpCheck(
    /** "status" (equals), "contains" (body text), "json" (field equals) or "time" (under N ms). */
    val kind: String,
    /** The JSON field for "json", like `data.items[0].id`. */
    val path: String = "",
    val expected: String = "",
)

/**
 * Tabs, their panes and their groups, without the connections: reopening logs in again.
 * Hosts are referenced by id, so a deleted host just leaves its pane out.
 */
@Serializable
data class SavedLayout(
    val id: String,
    val name: String,
    val tabs: List<SavedTab>,
    val groups: List<SavedGroup> = emptyList(),
)

@Serializable
data class SavedTab(
    /** `SplitLayout` name: Single, Columns, Rows, Grid. */
    val layout: String,
    val panes: List<SavedPane?>,
    val focused: Int = 0,
    val groupId: String? = null,
)

@Serializable
data class SavedPane(
    /** "terminal", "files", "editor" or "requests". */
    val kind: String,
    /** Null for this device's files. */
    val hostId: String?,
    /** The folder of a file panel or the file of an editor. */
    val path: String? = null,
)

@Serializable
data class SavedGroup(
    val id: String,
    val name: String,
    /** `GroupColor` name. */
    val color: String,
    val collapsed: Boolean = false,
)

/** Layout choices the user makes by dragging, kept across restarts. */
@Serializable
data class UiSettings(
    val sidebarWidthDp: Int = 260,
    /** False when the user has folded the sidebar away, on desktop. */
    val sidebarOpen: Boolean = true,
    /** False when the user has folded the sidebar's tools down, leaving the room to the hosts. */
    val toolsOpen: Boolean = true,
    /** Folders saved in this device's file panel. */
    val localBookmarks: List<String> = emptyList(),
    /** A theme id, or "system" to follow the OS: Carta by day, Notte in dark mode. */
    val theme: String = "system",
    /** The app icon's `AppIconStyle` id, shown in the Dock, taskbar and window. */
    val appIcon: String = "bigote",
    /** System notifications when a transfer finishes or fails. */
    val notifyTransfers: Boolean = true,
    /** Look for a new version at startup, in the installed app. */
    val checkUpdates: Boolean = true,
    /** Set once the first-run welcome has been answered. */
    val welcomeDone: Boolean = false,
    /** Every terminal records what it shows from the moment it opens. */
    val recordSessions: Boolean = false,
    /** Recordings older than this many days are deleted at startup; 0 keeps them. */
    val recordingDays: Int = 30,
)
