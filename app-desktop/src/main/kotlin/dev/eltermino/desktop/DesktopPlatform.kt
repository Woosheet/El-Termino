package dev.eltermino.desktop

import dev.eltermino.app.PickedFile
import com.github.javakeyring.Keyring
import dev.eltermino.app.LATEST_RELEASE_URL
import dev.eltermino.app.DesktopExternalFile
import dev.eltermino.app.ExternalFile
import dev.eltermino.core.data.ExternalApp
import dev.eltermino.app.Platform
import dev.eltermino.app.runsWhenOpened
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

    override val isMacOs = "mac" in System.getProperty("os.name").lowercase()

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

    override suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean = withContext(Dispatchers.Main) {
        val dialog = FileDialog(window(), "Esporta", FileDialog.SAVE).apply {
            directory = home.path
            file = suggestedName
            isVisible = true
        }
        val name = dialog.file ?: return@withContext false
        withContext(Dispatchers.IO) { File(dialog.directory, name).writeBytes(bytes) }
        true
    }

    override val isInForeground: Boolean get() = window()?.isActive == true

    // Put in the app by the build on macOS; see native/touchid.swift. Packaging drops its
    // executable bit, and an installed app shouldn't change, so it runs from a private copy.
    private val touchId: File? by lazy {
        System.getProperty("compose.application.resources.dir")?.let { File(it, "touchid") }?.takeIf { it.isFile }?.let { packaged ->
            runCatching {
                File.createTempFile("touchid", null).apply {
                    deleteOnExit()
                    packaged.copyTo(this, overwrite = true)
                    setExecutable(true, true)
                }
            }.getOrNull()
        }
    }

    override val biometricAvailable: Boolean by lazy {
        runTouchId("check")
    }

    override suspend fun authenticate(reason: String): Boolean = withContext(Dispatchers.IO) {
        runTouchId("unlock", reason)
    }

    private fun runTouchId(vararg arguments: String): Boolean {
        val program = touchId ?: return false
        return runCatching { ProcessBuilder(program.path, *arguments).start().waitFor() == 0 }.getOrDefault(false)
    }

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

    override suspend fun openExternally(name: String, bytes: ByteArray, app: ExternalApp?): ExternalFile = withContext(Dispatchers.IO) {
        // It comes from a server: opening must never mean running. An editor the user chose only opens it.
        check(!runsWhenOpened(name, isWindows) || (app != null && !app.isTerminal())) {
            "$name verrebbe eseguito invece di aperto: aprilo con un editor, usa \"Copia in…\" o l'editor integrato"
        }
        DesktopExternalFile(name, bytes).also { file ->
            try {
                if (app == null) java.awt.Desktop.getDesktop().open(File(file.path)) else launch(app, file.path)
            } catch (e: Exception) {
                file.close()
                throw e
            }
        }
    }

    /** Opens [path] with [app]: `open -a` for a macOS bundle, so it reuses a running copy; the program itself elsewhere. */
    private fun launch(app: ExternalApp, path: String) {
        if (isMacOs && app.path.endsWith(".app")) {
            val open = ProcessBuilder("open", "-a", app.path, path).redirectErrorStream(true).start()
            val finished = open.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished || open.exitValue() != 0) {
                val why = if (finished) open.inputStream.bufferedReader().readText().trim() else "non ha risposto"
                throw IllegalStateException("${app.name} non si è aperto: $why")
            }
        } else {
            ProcessBuilder(app.path, path).start()
        }
    }

    override fun externalApps(): List<ExternalApp> = when {
        isMacOs -> MAC_EDITORS.mapNotNull { name ->
            listOf("/Applications", "${home.path}/Applications", "/System/Applications")
                .map { File(it, "$name.app") }
                .firstOrNull { it.isDirectory }
                ?.let { ExternalApp(name, it.path) }
        }
        isWindows -> WINDOWS_EDITORS.mapNotNull { (name, path) ->
            val expanded = path.replace("%LOCALAPPDATA%", System.getenv("LOCALAPPDATA").orEmpty())
            File(expanded).takeIf { it.isFile }?.let { ExternalApp(name, it.path) }
        }
        else -> LINUX_EDITORS.mapNotNull { (command, name) ->
            System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, command) }
                .firstOrNull { it.canExecute() }
                ?.let { ExternalApp(name, it.path) }
        }
    }

    override suspend fun pickApp(): ExternalApp? = withContext(Dispatchers.Main) {
        // Applications are bundles on macOS: the picker has to treat them as files to choose one.
        if (isMacOs) System.setProperty("apple.awt.use-file-dialog-packages", "true")
        try {
            val dialog = FileDialog(window(), "Scegli l'app con cui aprire il file", FileDialog.LOAD).apply {
                directory = when {
                    isMacOs -> "/Applications"
                    isWindows -> System.getenv("ProgramFiles") ?: "C:\\Program Files"
                    else -> "/usr/bin"
                }
                if (isMacOs) setFilenameFilter { _, name -> name.endsWith(".app") }
                isVisible = true
            }
            val name = dialog.file ?: return@withContext null
            ExternalApp(name.removeSuffix(".app").removeSuffix(".exe"), File(dialog.directory, name).path)
        } finally {
            if (isMacOs) System.setProperty("apple.awt.use-file-dialog-packages", "false")
        }
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

    override fun readKnownHosts(): String? = File(sshDir, "known_hosts").takeIf { it.isFile }?.readText()

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

private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/** Editors offered in "Apri con" when installed, by bundle name in /Applications. */
private val MAC_EDITORS = listOf(
    "Visual Studio Code", "Cursor", "Zed", "Sublime Text", "BBEdit", "CotEditor", "Nova", "TextMate", "MacVim",
    "IntelliJ IDEA", "IntelliJ IDEA CE", "WebStorm", "PyCharm", "PyCharm CE", "Xcode", "TextEdit",
)

private val WINDOWS_EDITORS = listOf(
    "Visual Studio Code" to "%LOCALAPPDATA%\\Programs\\Microsoft VS Code\\Code.exe",
    "Notepad++" to "C:\\Program Files\\Notepad++\\notepad++.exe",
    "Sublime Text" to "C:\\Program Files\\Sublime Text\\sublime_text.exe",
    "Blocco note" to "C:\\Windows\\System32\\notepad.exe",
)

/** Commands looked up on the PATH, with the name the menu shows. */
private val LINUX_EDITORS = listOf(
    "code" to "Visual Studio Code", "zed" to "Zed", "subl" to "Sublime Text", "gnome-text-editor" to "Editor di testo",
    "gedit" to "gedit", "kate" to "Kate", "mousepad" to "Mousepad", "xed" to "Xed",
)

/** Apps that run a script they're given rather than show it. */
private fun ExternalApp.isTerminal(): Boolean =
    listOf("terminal", "iterm", "warp", "alacritty", "kitty", "ghostty", "wezterm", "powershell", "cmd").any { it in name.lowercase() }
