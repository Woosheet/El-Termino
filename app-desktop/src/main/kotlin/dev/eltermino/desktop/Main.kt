package dev.eltermino.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.eltermino.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "El Termino",
        state = rememberWindowState(width = 1200.dp, height = 760.dp),
    ) {
        App()
    }
}
