package dev.eltermino.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.eltermino.app.AppState
import dev.eltermino.app.createAppServices
import dev.eltermino.core.vault.KeychainMasterKeyProvider
import dev.eltermino.ui.App
import dev.eltermino.ui.components.AppIconPainter
import dev.eltermino.ui.components.AppIconStyle
import dev.eltermino.ui.currentTheme
import java.awt.Frame
import java.awt.Taskbar

fun main() {
    var frame: Frame? = null
    val services = createAppServices(
        dataDir = DesktopPlatform.dataDir(),
        masterKey = KeychainMasterKeyProvider(),
        platform = DesktopPlatform { frame },
        localFilesRoot = java.io.File(System.getProperty("user.home")),
    )
    val state = AppState(services)

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
        ) {
            frame = window
            val dark = currentTheme(state).dark
            // The macOS title bar follows the theme instead of the system.
            LaunchedEffect(dark) {
                window.rootPane.putClientProperty("apple.awt.windowAppearance", if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua")
            }
            App(state, onExit = ::exitApplication)
        }
    }
}
