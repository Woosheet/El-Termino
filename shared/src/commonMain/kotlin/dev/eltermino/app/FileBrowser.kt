package dev.eltermino.app

import dev.eltermino.core.data.newId
import dev.eltermino.core.ssh.RemoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FileSortColumn { Name, Size, Modified }

data class FileSort(val column: FileSortColumn = FileSortColumn.Name, val descending: Boolean = false)

/** The folder a file panel shows and what can be done there. Errors land in [message]. */
class FileBrowser internal constructor(
    val endpoint: Endpoint,
    private val scope: CoroutineScope,
    private val open: suspend () -> FileSystem,
    private val onClose: suspend (FileSystem) -> Unit = {},
    /** Where to start instead of the home folder, as when reopening a saved layout. */
    private val startPath: String? = null,
) {
    private val fs = CompletableDeferred<FileSystem>()

    private val mutablePath = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = mutablePath.asStateFlow()

    private val mutableEntries = MutableStateFlow<List<RemoteFile>>(emptyList())
    private val mutableLoading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()

    /** A connection or permission problem with the current folder. */
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    /** A failed operation, shown until dismissed; the folder stays as it was. */
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    val showHidden = MutableStateFlow(false)
    val sort = MutableStateFlow(FileSort())
    val selection = MutableStateFlow<Set<String>>(emptySet())

    /** Everything in [path]; [visible] filters and orders it for display. */
    val entries: StateFlow<List<RemoteFile>> = mutableEntries.asStateFlow()

    private var loadJob: Job? = null

    init {
        scope.launch {
            try {
                val opened = open()
                fs.complete(opened)
                load(startPath ?: opened.home())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fs.completeExceptionally(e)
                mutableError.value = failureMessage(e)
                mutableLoading.value = false
            }
        }
    }

    /** Hidden files only when asked, folders first, then [sort]. */
    fun visible(entries: List<RemoteFile>, hidden: Boolean, sort: FileSort): List<RemoteFile> {
        val shown = if (hidden) entries else entries.filterNot { it.name.startsWith(".") }
        val order = when (sort.column) {
            FileSortColumn.Name -> compareBy<RemoteFile> { it.name.lowercase() }
            FileSortColumn.Size -> compareBy<RemoteFile> { it.size }.thenBy { it.name.lowercase() }
            FileSortColumn.Modified -> compareBy<RemoteFile> { it.modifiedEpochSeconds }.thenBy { it.name.lowercase() }
        }
        val sorted = shown.sortedWith(if (sort.descending) order.reversed() else order)
        return sorted.filter { it.isDirectory } + sorted.filterNot { it.isDirectory }
    }

    fun open(path: String) = load(path)

    fun up() = path.value?.let { load(parentPath(it)) }

    fun refresh() = path.value?.let { load(it, keepSelection = true) }

    /** A folder opens; a symlink opens if it points at one. Returns false for a file. */
    suspend fun enter(entry: RemoteFile): Boolean {
        if (entry.isDirectory) {
            load(entry.path)
            return true
        }
        if (entry.type != RemoteFile.Type.Symlink) return false
        val target = fs.await()
        val listable = runCatching { target.list(entry.path) }.isSuccess
        if (listable) load(entry.path)
        return listable
    }

    private fun load(path: String, keepSelection: Boolean = false) {
        loadJob?.cancel()
        mutableLoading.value = true
        loadJob = scope.launch {
            try {
                val list = fs.await().list(path)
                mutablePath.value = path
                mutableEntries.value = list
                mutableError.value = null
                if (!keepSelection) selection.value = emptySet() else selection.value = selection.value intersect list.map { it.path }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (mutablePath.value == null) mutablePath.value = path
                mutableError.value = failureMessage(e)
            } finally {
                mutableLoading.value = false
            }
        }
    }

    fun mkdir(name: String) = operate { fs -> fs.mkdir(childPath(currentPath(), name)) }

    fun rename(entry: RemoteFile, newName: String) = operate { fs -> fs.rename(entry.path, childPath(parentPath(entry.path), newName)) }

    fun delete(entries: List<RemoteFile>) = operate { fs -> entries.forEach { fs.delete(it.path, recursive = true) } }

    fun chmod(entry: RemoteFile, mode: Int) = operate { fs -> fs.chmod(entry.path, mode) }

    fun notify(message: String) {
        mutableMessage.value = message
    }

    fun dismissMessage() {
        mutableMessage.value = null
    }

    /** The file system, once connected; for transfers and the editor. */
    suspend fun fileSystem(): FileSystem = fs.await()

    private fun currentPath() = path.value ?: throw IllegalStateException("La cartella non è ancora aperta")

    private fun operate(block: suspend (FileSystem) -> Unit) {
        scope.launch {
            try {
                block(fs.await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutableMessage.value = failureMessage(e)
            }
            refresh()
        }
    }

    suspend fun close() {
        loadJob?.cancel()
        if (fs.isCompleted && !fs.isCancelled) runCatching { onClose(fs.await()) }
        else fs.cancel()
    }
}

/** Opening a file as text: refused above [maxBytes] or when it looks binary. */
class RemoteEditor internal constructor(
    val endpoint: Endpoint,
    val path: String,
    private val fs: FileSystem,
    private val maxBytes: Long = 2 * 1024 * 1024,
) {
    /** What the file was when loaded or last saved; a change since then is someone else's. */
    private var known: RemoteFile? = null

    sealed interface SaveResult {
        data object Saved : SaveResult
        /** Changed on the server since it was opened; save again with force to replace it. */
        data class ChangedOnServer(val current: RemoteFile) : SaveResult
    }

    suspend fun load(): String {
        val bytes = loadBytes()
        if (bytes.take(8192).any { it == 0.toByte() }) throw IllegalStateException("${fileName(path)} sembra un file binario")
        return bytes.decodeToString()
    }

    /** The file as it is, for an external editor, which can open anything. */
    internal suspend fun loadBytes(): ByteArray {
        val file = fs.stat(path) ?: throw IllegalStateException("${fileName(path)} non esiste più")
        if (file.size > maxBytes) throw IllegalStateException("${fileName(path)} è troppo grande per l'editor (${file.size / 1024} KB)")
        val bytes = readAll(file.size)
        known = file
        return bytes
    }

    /**
     * Writes to a temporary file next to it, then renames it over the original, so a
     * dropped connection never leaves half a file. Permissions are kept.
     */
    suspend fun save(text: String, force: Boolean = false): SaveResult = save(text.encodeToByteArray(), force)

    internal suspend fun save(bytes: ByteArray, force: Boolean = false): SaveResult {
        val current = fs.stat(path)
        val before = known
        if (!force && current != null && before != null &&
            (current.modifiedEpochSeconds != before.modifiedEpochSeconds || current.size != before.size)
        ) {
            return SaveResult.ChangedOnServer(current)
        }
        val temp = childPath(parentPath(path), ".${fileName(path)}.eltermino-${newId().take(8)}")
        try {
            fs.openWrite(temp).apply {
                write(bytes)
                close()
            }
            (current ?: before)?.permissions?.takeIf { it != 0 }?.let { fs.chmod(temp, it) }
            fs.rename(temp, path, overwrite = true)
        } catch (e: Throwable) {
            runCatching { fs.delete(temp, recursive = false) }
            throw e
        }
        known = fs.stat(path)
        return SaveResult.Saved
    }

    private suspend fun readAll(size: Long): ByteArray {
        val source = fs.openRead(path)
        try {
            var result = ByteArray(size.coerceIn(0, maxBytes).toInt())
            var length = 0
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                if (length + n > result.size) result = result.copyOf(maxOf(result.size * 2, length + n))
                buffer.copyInto(result, length, 0, n)
                length += n
            }
            return result.copyOf(length)
        } finally {
            source.close()
        }
    }
}
