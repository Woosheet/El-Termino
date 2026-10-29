package dev.eltermino.desktop

import dev.eltermino.app.PickedFile
import com.github.javakeyring.Keyring
import dev.eltermino.app.LATEST_RELEASE_URL
import dev.eltermino.app.Platform
import dev.eltermino.app.ReleaseAsset
import dev.eltermino.app.TermiusScan
import dev.eltermino.core.importer.termius.TermiusLocal
import dev.eltermino.core.importer.termius.TermiusLocalReader
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

    override suspend fun pickLocalFiles(title: String): List<String> = withContext(Dispatchers.Main) {
        val dialog = FileDialog(window(), title, FileDialog.LOAD).apply {
            isMultipleMode = true
            directory = home.path
            isVisible = true
        }
        dialog.files.orEmpty().map { it.absolutePath.replace('\\', '/') }
    }

    override val isInForeground: Boolean get() = window()?.isActive == true

    // jpackage's launcher sets the version; a run from Gradle has none and doesn't look for updates.
    override val appVersion: String? = System.getProperty("jpackage.app-version")

    /** The .app this process runs from, if any: its runtime sits inside it. */
    private val bundle: File? = generateSequence(File(System.getProperty("java.home"))) { it.parentFile }
        .firstOrNull { it.name.endsWith(".app") }

    private val updater by lazy { bundle?.let { MacUpdater(it) } }

    override val updateAssetSuffix: String? =
        if (bundle == null) null else "-macos-${if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"}.dmg"

    /** -Deltermino.releasesUrl points the check elsewhere, a local file for instance. */
    override val releasesUrl: String = System.getProperty("eltermino.releasesUrl") ?: LATEST_RELEASE_URL

    override suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        connect(url, accept = "application/vnd.github+json").getInputStream().use { it.readBytes().decodeToString() }
    }

    override suspend fun installUpdate(asset: ReleaseAsset, version: String, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val updater = updater ?: throw UpdateException("Si aggiorna da sola solo l'app installata")
        updater.install(asset.url, version, asset.sizeBytes, progress)
    }

    override fun openUpdateOnExit() {
        updater?.relaunchAfterExit()
    }

    override fun openUrl(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    /** Notification Center on macOS, notify-send on Linux, the tray balloon on Windows. */
    override fun notify(title: String, message: String) {
        val os = System.getProperty("os.name").lowercase()
        runCatching {
            when {
                "mac" in os -> {
                    fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    ProcessBuilder("osascript", "-e", "display notification ${quoted(message)} with title ${quoted(title)}").start()
                }
                "win" in os -> trayIcon()?.displayMessage(title, message, java.awt.TrayIcon.MessageType.INFO)
                else -> ProcessBuilder("notify-send", "--app-name=El Termino", title, message).start()
            }
        }
    }

    private var tray: java.awt.TrayIcon? = null

    private fun trayIcon(): java.awt.TrayIcon? {
        if (!java.awt.SystemTray.isSupported()) return null
        return tray ?: java.awt.TrayIcon(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), "El Termino").also {
            it.isImageAutoSize = true
            java.awt.SystemTray.getSystemTray().add(it)
            tray = it
        }
    }

    override suspend fun readTermius(): TermiusScan = withContext(Dispatchers.IO) {
        val dirs = TermiusLocalReader.dataDirs()
        if (dirs.isEmpty()) return@withContext TermiusScan.NotFound
        val keys = termiusKeys()
        var last: TermiusScan = TermiusScan.NotFound
        for (dir in dirs) {
            when (val found = TermiusLocalReader.read(dir, keys)) {
                is TermiusLocal.Found -> return@withContext TermiusScan.Found(found.result, found.details)
                is TermiusLocal.Unreadable -> last = TermiusScan.Unreadable(found.reason, found.details)
                TermiusLocal.NotFound -> Unit
            }
        }
        last
    }

    /** Every "localKey" Termius left in the keychain: the website and App Store builds each keep one. */
    private fun termiusKeys(): List<ByteArray> {
        val keyring = runCatching { Keyring.create() }.getOrNull() ?: return emptyList()
        return keyring.use { ring ->
            listOf("Termius", "Termius (MAS)", "termius-app").mapNotNull { service ->
                runCatching { ring.getPassword(service, "localKey") }.getOrNull()?.let(::decodeKey)
            }
        }
    }

    private fun decodeKey(text: String): ByteArray? {
        val trimmed = text.trim()
        runCatching { java.util.Base64.getDecoder().decode(trimmed) }.getOrNull()?.takeIf { it.size == 32 }?.let { return it }
        if (trimmed.length == 64 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ByteArray(32) { trimmed.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        return trimmed.toByteArray().takeIf { it.size == 32 }
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
