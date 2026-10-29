package dev.eltermino.desktop

import dev.eltermino.app.PickedFile
import dev.eltermino.app.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.FileSystems

class DesktopPlatform(private val window: () -> Frame?) : Platform {
    private val home = File(System.getProperty("user.home"))
    private val sshDir = File(home, ".ssh")

    override val isDesktop = true

    override suspend fun pickFile(title: String): PickedFile? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(window(), title, FileDialog.LOAD).apply {
            // ~/.ssh is hidden in most file pickers, so start there.
            if (sshDir.isDirectory) directory = sshDir.path
            isVisible = true
        }
        val name = dialog.file ?: return@withContext null
        val file = File(dialog.directory, name)
        withContext(Dispatchers.IO) { PickedFile(file.name, file.readBytes()) }
    }

    override fun readSshConfig(): String? = File(sshDir, "config").takeIf { it.isFile }?.readText()

    override fun resolveSshInclude(pattern: String): List<Pair<String, String>> {
        val path = when {
            pattern.startsWith("~/") -> File(home, pattern.removePrefix("~/"))
            File(pattern).isAbsolute -> File(pattern)
            else -> File(sshDir, pattern)
        }
        val directory = path.parentFile ?: return emptyList()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:${path.name}")
        return directory.listFiles().orEmpty()
            .filter { it.isFile && matcher.matches(it.toPath().fileName) }
            .sortedBy { it.name }
            .map { it.path.replaceFirst(home.path, "~") to it.readText() }
    }

    override fun readLocalFile(path: String): ByteArray? {
        val file = if (path.startsWith("~/")) File(home, path.removePrefix("~/")) else File(path)
        return file.takeIf { it.isFile && it.canRead() }?.readBytes()
    }

    companion object {
        /** Where the workspace and vault live; `-Deltermino.dataDir` overrides it. */
        fun dataDir(): File {
            System.getProperty("eltermino.dataDir")?.let { return File(it) }
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            return when {
                "mac" in os -> File(home, "Library/Application Support/El Termino")
                "win" in os -> File(System.getenv("APPDATA") ?: home, "El Termino")
                else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "el-termino")
            }
        }
    }
}
