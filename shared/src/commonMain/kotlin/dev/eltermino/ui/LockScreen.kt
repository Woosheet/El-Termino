package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppLock
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

/** Covers the app, and takes the keyboard away from the terminal behind it. */
@Composable
internal fun LockScreen(lock: AppLock) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    suspend fun unlock() {
        busy = true
        lock.unlock()
        busy = false
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        busy = true
        lock.unlockWhenBack()
        busy = false
    }
    Box(
        Modifier.fillMaxSize()
            .background(Carta.colors.background)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { true },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("El Termino è bloccato", style = Carta.type.heading)
            Button("Sblocca", enabled = !busy, onClick = { scope.launch { unlock() } })
        }
    }
}
