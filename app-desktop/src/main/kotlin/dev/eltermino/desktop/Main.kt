package dev.eltermino.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.eltermino.app.AppState
import dev.eltermino.app.createAppServices
import dev.eltermino.core.vault.KeychainMasterKeyProvider
import dev.eltermino.ui.App
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
            onCloseRequest = ::exitApplication,
            title = "El Termino",
            state = rememberWindowState(width = 1200.dp, height = 760.dp),
        ) {
            frame = window
            App(state)
        }
    }
}
