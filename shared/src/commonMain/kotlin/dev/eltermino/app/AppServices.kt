package dev.eltermino.app

import dev.eltermino.core.data.WorkspaceStore
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
)

/** What differs between desktop and Android. */
interface Platform {
    val isDesktop: Boolean

    /** Lets the user choose a file; null when they cancel. */
    suspend fun pickFile(title: String): PickedFile?

    /** `~/.ssh/config`, or null where there's none (always on Android). */
    fun readSshConfig(): String?

    /**
     * Resolves an `Include` pattern relative to `~/.ssh`, globs included, as
     * (path, contents) pairs.
     */
    fun resolveSshInclude(pattern: String): List<Pair<String, String>>

    /** Reads a local path such as an `IdentityFile`, expanding `~`; null if unreadable. */
    fun readLocalFile(path: String): ByteArray?
}

class PickedFile(val name: String, val bytes: ByteArray)
