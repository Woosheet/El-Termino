package dev.eltermino.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.eltermino.app.AppCommand
import dev.eltermino.app.AppState
import dev.eltermino.app.Shortcuts
import dev.eltermino.app.createAppServices
import dev.eltermino.core.ssh.agent.openSystemAgent
import dev.eltermino.core.vault.EncryptedFileVault
import dev.eltermino.core.vault.KeychainMasterKeyProvider
import dev.eltermino.core.vault.MasterKeyProvider
import dev.eltermino.core.vault.MasterKeySource
import dev.eltermino.core.vault.PasswordMasterKeyProvider
import dev.eltermino.core.vault.VaultException
import dev.eltermino.core.vault.chooseMasterKeySource
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import javax.swing.JPasswordField
import javax.swing.JOptionPane
import java.io.File
import dev.eltermino.ui.App
import dev.eltermino.ui.components.AppIconPainter
import dev.eltermino.ui.components.AppIconStyle
import java.awt.Frame
import java.awt.Taskbar

/** The system keychain where there is one, otherwise a password asked here, before any window. */
private fun chooseMasterKey(dataDir: File): MasterKeyProvider {
    val keychain = KeychainMasterKeyProvider()
    val vault = File(dataDir, "vault.bin")
    val salt = File(dataDir, "vault.salt")
    if (chooseMasterKeySource(keychain.isAvailable(), vault.exists(), salt.exists()) == MasterKeySource.Keychain) return keychain
    repeat(MASTER_PASSWORD_TRIES) { attempt ->
        val provider = PasswordMasterKeyProvider(salt, ::askMasterPassword)
        try {
            if (vault.exists()) runBlocking { EncryptedFileVault(vault, provider).snapshot() }
            return provider
        } catch (e: VaultException) {
            if (attempt == MASTER_PASSWORD_TRIES - 1 || !salt.exists()) {
                JOptionPane.showMessageDialog(null, "Non riesco ad aprire il vault: password non valida.", "El Termino", JOptionPane.ERROR_MESSAGE)
                exitProcess(1)
            }
        }
    }
    exitProcess(1)
}

private const val MASTER_PASSWORD_TRIES = 3

private fun askMasterPassword(firstTime: Boolean): CharArray? {
    val title = "El Termino"
    fun ask(label: String): CharArray? {
        val field = JPasswordField(24)
        val pane = arrayOf<Any>(label, field)
        val answer = JOptionPane.showConfirmDialog(null, pane, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        return field.password.takeIf { answer == JOptionPane.OK_OPTION }
    }
    if (!firstTime) return ask("Master password")
    while (true) {
        val password = ask("Questo sistema non ha un portachiavi. Scegli una master password: se la perdi, i segreti salvati non si recuperano.") ?: return null
        val again = ask("Ripeti la master password") ?: return null
        if (password.isNotEmpty() && password.contentEquals(again)) return password
        JOptionPane.showMessageDialog(null, "Le due password non coincidono.", title, JOptionPane.WARNING_MESSAGE)
    }
}

fun main() {
    if (isMac) {
        // The menu bar goes at the top of the screen, as in every Mac app, under the app's name.
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "El Termino")
    }
    var frame: Frame? = null
    val services = createAppServices(
        dataDir = DesktopPlatform.dataDir(),
        masterKey = chooseMasterKey(DesktopPlatform.dataDir()),
        platform = DesktopPlatform { frame },
        localFilesRoot = java.io.File(System.getProperty("user.home")),
        sshAgent = ::openSystemAgent,
    )
    val state = AppState(services)
    if (isMac && java.awt.Desktop.isDesktopSupported()) {
        // "Impostazioni…" with ⌘, in the app menu.
        runCatching { java.awt.Desktop.getDesktop().setPreferencesHandler { state.run(AppCommand.Settings) } }
    }

    application {
        val workspace by state.services.workspace.workspace.collectAsState()
        val icon = remember(workspace.ui.appIcon) { AppIconPainter(AppIconStyle.of(workspace.ui.appIcon)) }
        // The window takes [icon]; the macOS Dock needs it set on the taskbar too.
        LaunchedEffect(icon) {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().iconImage = icon.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(512f, 512f))
            }
        }
        Window(
            icon = icon,
            // With transfers running the app asks first; see ExitDialog.
            onCloseRequest = { if (!state.requestExit()) exitApplication() },
            title = "El Termino",
            state = rememberWindowState(width = 1200.dp, height = 760.dp),
            // Ahead of the focused view, so the terminal never sees the app's shortcuts.
            onPreviewKeyEvent = { event ->
                val command = if (event.type != KeyEventType.KeyDown || state.lock.locked.value) null else Shortcuts.commandFor(
                    event.key, event.isMetaPressed, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, isMac,
                )
                command?.let(state::run)
                command != null
            },
        ) {
            frame = window
            if (isMac) AppMenuBar(state)
            // On macOS the app draws under a transparent title bar, so the theme's colours reach the top.
            val titleBar = remember { if (isMac) drawUnderTitleBar(window) else 0.dp }
            App(state, onExit = ::exitApplication, titleBarHeight = titleBar)
        }
    }
}

private val isMac = "mac" in System.getProperty("os.name").lowercase()

/** The macOS menu bar: the shortcuts' commands where Mac users look for them, with their keys. */
@Composable
private fun FrameWindowScope.AppMenuBar(state: AppState) {
    MenuBar {
        Menu("File") {
            CommandItem(state, "Nuova scheda", AppCommand.NewTab)
            CommandItem(state, "Nuovo host…", AppCommand.NewHost)
            Separator()
            CommandItem(state, "Chiudi", AppCommand.Close)
        }
        Menu("Vista") {
            CommandItem(state, "Mostra o nascondi la barra laterale", AppCommand.ToggleSidebar)
        }
        Menu("Finestra") {
            CommandItem(state, "Scheda successiva", AppCommand.NextTab)
            CommandItem(state, "Scheda precedente", AppCommand.PreviousTab)
            Separator()
            listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).forEachIndexed { i, key ->
                val last = i == 8
                Item(if (last) "Ultima scheda" else "Scheda ${i + 1}", shortcut = KeyShortcut(key, meta = true), onClick = { state.run(AppCommand.GoToTab(if (last) -1 else i)) })
            }
        }
    }
}

/** Makes the title bar transparent with the content under it, and says how tall it is. */
private fun drawUnderTitleBar(window: ComposeWindow): Dp {
    // Measured before the content moves under it; 28 is macOS's height for a plain title bar.
    val height = window.insets.top.takeIf { it > 0 } ?: 28
    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    return height.dp
}

/** A menu item for [command], showing the first of its macOS shortcuts. */
@Composable
private fun MenuScope.CommandItem(state: AppState, text: String, command: AppCommand) {
    val keys = Shortcuts.all(macOs = true).first { it.first == command }.second.first()
    Item(text, shortcut = KeyShortcut(keys.key, ctrl = keys.ctrl, meta = keys.meta, alt = keys.alt, shift = keys.shift), onClick = { state.run(command) })
}
