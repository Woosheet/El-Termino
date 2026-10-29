package dev.eltermino.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.eltermino.app.AppState
import dev.eltermino.app.createAppServices
import dev.eltermino.core.vault.KeychainMasterKeyProvider
import dev.eltermino.ui.App
import dev.eltermino.ui.currentTheme
import java.awt.Frame

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
        Window(
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
