package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.delay

/** The latest transfer notice in a corner; it goes away by itself after a few seconds. */
@Composable
internal fun NoticeToast(state: AppState, modifier: Modifier = Modifier) {
    val notice by state.notifier.notice.collectAsState()
    val current = notice ?: return
    LaunchedEffect(current.id) {
        delay(if (current.failed) 10_000 else 5_000)
        state.notifier.dismiss()
    }
    val colors = Carta.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .widthIn(max = 420.dp)
            .shadow(10.dp, shape)
            .background(colors.background, shape)
            .border(1.dp, if (current.failed) colors.danger else colors.line, shape)
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (current.failed) Icons.Default.Warning else CartaIcons.Transfer,
            contentDescription = null,
            tint = if (current.failed) colors.danger else colors.accent,
            size = 18.dp,
        )
        Column(Modifier.weight(1f, fill = false).padding(horizontal = 10.dp)) {
            Text(current.title, style = Carta.type.bodyStrong, color = if (current.failed) colors.danger else colors.text)
            Text(current.message, style = Carta.type.small, color = colors.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(Icons.Default.Close, "Chiudi l'avviso", onClick = { state.notifier.dismiss() }, size = 28.dp, iconSize = 14.dp)
    }
}

/** Asked when the window closes with transfers still going. */
@Composable
internal fun ExitDialog(state: AppState, onExit: () -> Unit) {
    val asking by state.exitRequested.collectAsState()
    if (!asking) return
    val summary by state.transfers.summary.collectAsState()
    val count = summary.active + summary.waiting
    Dialog(
        onDismiss = { state.cancelExit() },
        title = if (count == 1) "C'è un trasferimento in corso" else "Ci sono $count trasferimenti in corso",
        icon = Icons.Default.Warning,
        actions = {
            Button("Resta", onClick = { state.cancelExit() }, kind = ButtonKind.Ghost)
            Button("Chiudi comunque", kind = ButtonKind.Danger, onClick = onExit)
        },
    ) {
        Text("Chiudendo ora si fermano a metà. I file già copiati restano; per finirli, ricopiali e scegli Riprendi.")
    }
}
