package dev.eltermino.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
            // On macOS the app draws under a transparent title bar, so the theme's colours reach the top.
            val titleBar = remember { if (isMac) drawUnderTitleBar(window) else 0.dp }
            App(state, onExit = ::exitApplication, titleBarHeight = titleBar)
        }
    }
}

private val isMac = "mac" in System.getProperty("os.name").lowercase()

/** Makes the title bar transparent with the content under it, and says how tall it is. */
private fun drawUnderTitleBar(window: ComposeWindow): Dp {
    // Measured before the content moves under it; 28 is macOS's height for a plain title bar.
    val height = window.insets.top.takeIf { it > 0 } ?: 28
    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    return height.dp
}
