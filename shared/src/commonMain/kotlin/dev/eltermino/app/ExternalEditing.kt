package dev.eltermino.app

import dev.eltermino.core.ssh.RemoteFile
import kotlinx.coroutines.CancellationException

/** Most that goes out by dragging, in total: it's downloaded while the mouse button is released. */
const val DRAG_OUT_LIMIT_BYTES = 200L * 1024 * 1024

/** Held in memory while uploading each save, so it's bounded like a drag. */
const val EXTERNAL_EDIT_LIMIT_BYTES = DRAG_OUT_LIMIT_BYTES

/** A private local copy of a remote file, open in some app of the system. */
interface ExternalFile {
    val path: String

    /** The next save; null once the file is gone or [finish] was called and nothing is left. */
    suspend fun awaitChange(): ByteArray?

    /** The last save still counts, then [awaitChange] ends. */
    fun finish()

    /** Deletes the copy. */
    fun close()
}

/**
 * Uploads every save until [file] ends. A refused or failed one waits for the next save; if
 * none comes, the copy stays, as the only place those changes are.
 */
internal suspend fun followExternalFile(
    editor: RemoteEditor,
    file: ExternalFile,
    conflict: suspend (RemoteFile) -> Boolean,
    failed: (Throwable) -> Unit = {},
    saved: () -> Unit,
) {
    var unsaved = false
    try {
        while (true) {
            val bytes = file.awaitChange() ?: break
            unsaved = true
            try {
                var result = editor.save(bytes)
                if (result is RemoteEditor.SaveResult.ChangedOnServer && conflict(result.current)) {
                    result = editor.save(bytes, force = true)
                }
                if (result is RemoteEditor.SaveResult.Saved) {
                    unsaved = false
                    saved()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed(e)
            }
        }
    } finally {
        if (!unsaved) file.close()
    }
}

/** Whether opening [name] with the system's app for it would run it instead of showing it. */
fun runsWhenOpened(name: String, windows: Boolean): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in RUN_EVERYWHERE || windows && extension in RUN_ON_WINDOWS
}

private val RUN_EVERYWHERE = setOf("jar", "app", "command", "tool", "pkg", "dmg", "scpt", "desktop", "appimage")
private val RUN_ON_WINDOWS = setOf("bat", "cmd", "com", "exe", "msi", "scr", "pif", "ps1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta", "lnk", "reg", "cpl", "url")

/** Why [entries] can't be dragged out of the app; null when they can. */
fun dragOutProblem(entries: List<RemoteFile>): String? = when {
    entries.any { it.type != RemoteFile.Type.File } -> "Le cartelle non si trascinano fuori dall'app: usa \"Copia in…\""
    entries.sumOf { it.size } > DRAG_OUT_LIMIT_BYTES -> "Oltre 200 MB non si trascina fuori dall'app: usa \"Copia in…\""
    else -> null
}
