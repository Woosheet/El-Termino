package dev.eltermino.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps the workspace in [file] as JSON. Every write goes to a temporary file that is
 * then moved over the old one, so a crash never leaves a half-written workspace; the
 * previous version is kept next to it as `.bak`.
 */
class FileWorkspaceStore(private val file: File) : WorkspaceStore {
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())

    override val workspace: StateFlow<Workspace> = state.asStateFlow()

    override suspend fun update(transform: (Workspace) -> Workspace): Workspace = mutex.withLock {
        val updated = transform(state.value)
        if (updated != state.value) {
            withContext(Dispatchers.IO) { save(updated) }
            state.value = updated
        }
        updated
    }

    private fun load(): Workspace {
        if (!file.exists()) return Workspace()
        val text = file.readText()
        val loaded = json.decodeFromString(Workspace.serializer(), text)
        check(loaded.version <= Workspace.CURRENT_VERSION) {
            "${file.name} was written by a newer version of the app (format ${loaded.version})"
        }
        return loaded
    }

    private fun save(workspace: Workspace) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(Workspace.serializer(), workspace))
        if (file.exists()) {
            Files.copy(file.toPath(), File(file.parentFile, "${file.name}.bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
