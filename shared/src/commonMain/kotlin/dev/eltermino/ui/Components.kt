package dev.eltermino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 6.dp, top = 16.dp, bottom = 6.dp),
        style = Carta.type.label,
        color = Carta.colors.textDim,
    )
}

@Composable
internal fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = Carta.type.title)
}

/** One line of muted explanation under a screen title. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(top = 4.dp, bottom = 16.dp), style = Carta.type.small, color = Carta.colors.textDim)
}

@Composable
internal fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = Carta.type.bodyStrong, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = Carta.type.small, color = Carta.colors.textDim, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
internal fun Gap(width: Int = 0, height: Int = 0) {
    Spacer(Modifier.width(width.dp).height(height.dp))
}

/** Runs [action] on Enter, for fields whose dialog should submit from the keyboard. */
internal fun Modifier.onEnter(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
    if (enter && event.type == KeyEventType.KeyDown) {
        action()
        true
    } else {
        false
    }
}

/** A [MutableStateFlow] read and written like Compose state: `var open by flow.collectAsMutableState()`. */
@Composable
internal fun <T> MutableStateFlow<T>.collectAsMutableState(): MutableState<T> {
    val current = collectAsState()
    val flow = this
    return remember(flow) {
        object : MutableState<T> {
            override var value: T
                get() = current.value
                set(value) {
                    flow.value = value
                }

            override fun component1(): T = value

            override fun component2(): (T) -> Unit = { flow.value = it }
        }
    }
}
