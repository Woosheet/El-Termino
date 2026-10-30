package dev.eltermino.app

import dev.eltermino.core.data.WorkspaceStore
import dev.eltermino.core.ssh.RemoteFile
import dev.eltermino.core.ssh.SshConnector
import dev.eltermino.core.ssh.SshKeyTool
import dev.eltermino.core.vault.Vault

/** Everything the UI needs, built by each app's entry point. */
class AppServices(
    val workspace: WorkspaceStore,
    val vault: Vault,
    val connector: SshConnector,
    val keyTool: SshKeyTool,
    val platform: Platform,
    /** This device's files for the local panel and transfers; null where there are none. */
    val localFiles: FileSystem? = null,
    /** Where terminal recordings go; null where they can't be kept. */
    val recordings: RecordingStore? = null,
    /** Keeps the encrypted copy of what a restore is about to change. */
    saveSafetyCopy: suspend (ByteArray) -> Unit = {},
) {
    val backup = BackupService(workspace, vault, platform.appVersion, saveSafetyCopy)
}

/** What differs between desktop and Android. */
interface Platform {
    val isDesktop: Boolean

    /** Picks the shortcuts the app shows: ⌘ on macOS, Ctrl elsewhere. */
    val isMacOs: Boolean get() = false

    /** Lets the user choose a file; null when they cancel. */
    suspend fun pickFile(title: String): PickedFile?

    /** Absolute paths of files chosen for upload, where the device has paths; empty otherwise. */
    suspend fun pickLocalFiles(title: String): List<String> = emptyList()

    /** Whether [pickDocuments] and [createDocument] reach files outside the app's folder. */
    val hasDocumentPicker: Boolean get() = false

    /** Documents from the system picker, readable through [AppServices.localFiles]; empty when cancelled. */
    suspend fun pickDocuments(title: String): List<RemoteFile> = emptyList()

    /** A new document named by the user, writable through [AppServices.localFiles], as a path; null when cancelled. */
    suspend fun createDocument(name: String): String? = null

    /** One file to send later, as a reference [readLocalFile] can read again; null when cancelled. */
    suspend fun pickAttachment(title: String): AttachedFile? =
        pickLocalFiles(title).firstOrNull()?.let { AttachedFile(it, it.substringAfterLast('/')) }

    /** Whether a window of the app is the one the user is looking at. */
    val isInForeground: Boolean get() = true

    /** A system notification, for things the user may want to know from another window. */
    fun notify(title: String, message: String) {}

    /**
     * Termius's own data on this computer, if Termius is installed here. Reading its key
     * from the system keychain makes the system ask the user first.
     */
    suspend fun readTermius(): TermiusScan = TermiusScan.NotFound

    /** `~/.ssh/config`, or null where there's none (always on Android). */
    fun readSshConfig(): String?

    /** `~/.ssh/known_hosts`, or null where there's none (always on Android). */
    fun readKnownHosts(): String? = null

    /**
     * Resolves an `Include` pattern relative to `~/.ssh`, globs included, as
     * (path, contents) pairs.
     */
    fun resolveSshInclude(pattern: String): List<Pair<String, String>>

    /** Reads a local path such as an `IdentityFile`, expanding `~`; null if unreadable. */
    fun readLocalFile(path: String): ByteArray?

    /** This build's version; null for a development build, which doesn't look for updates. */
    val appVersion: String? get() = null

    /** How the name of the release file that installs the app here ends; null where it can't install itself. */
    val updateAssetSuffix: String? get() = null

    /** Where to ask for the latest release. */
    val releasesUrl: String get() = LATEST_RELEASE_URL

    /** The body of a GET request to [url]. */
    suspend fun fetchText(url: String): String = throw UnsupportedOperationException("No network here")

    /**
     * Downloads [asset] and puts version [version] next to this app, reporting progress from
     * 0 to 1; it takes over once the app closes. Throws with a message for the user.
     */
    suspend fun installUpdate(asset: ReleaseAsset, version: String, progress: (Float) -> Unit): Unit =
        throw UnsupportedOperationException("This app doesn't install updates")

    /** Has the version [installUpdate] put in place open as soon as this one has closed. */
    fun openUpdateOnExit() {}

    /** Opens [url] in the browser. */
    fun openUrl(url: String) {}

    /** Opens a private copy of a remote file with the system's app for it; null where there's no such thing. */
    suspend fun openExternally(name: String, bytes: ByteArray): ExternalFile? = null

    /** Whether [authenticate] can ask for a fingerprint or the system password here. */
    val biometricAvailable: Boolean get() = false

    /** Asks the system who's there; true when it's the owner. */
    suspend fun authenticate(reason: String): Boolean = false

    /** Lets the user save [bytes] under a name of their choice; false when cancelled or unsupported. */
    suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean = false
}

class PickedFile(val name: String, val bytes: ByteArray)

class AttachedFile(val reference: String, val name: String)

/** What looking for Termius on this device found. */
sealed interface TermiusScan {
    data object NotFound : TermiusScan

    /** Termius is here but its data can't be read; [details] lists store and field names only. */
    data class Unreadable(val reason: String, val details: String) : TermiusScan

    data class Found(val result: dev.eltermino.core.importer.ImportResult, val details: String) : TermiusScan
}
