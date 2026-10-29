package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.Endpoint
import dev.eltermino.app.Transfer
import dev.eltermino.app.TransferState
import dev.eltermino.app.TransferSummary
import dev.eltermino.app.formatBytes
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Menu
import dev.eltermino.ui.components.MenuItem
import dev.eltermino.ui.components.MenuLabel
import dev.eltermino.ui.components.ProgressBar
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.tabular

/** The queue, under the sessions: one row per transfer, collapsible to a single line. */
@Composable
internal fun TransfersBar(state: AppState, modifier: Modifier = Modifier) {
    val transfers by state.transfers.transfers.collectAsState()
    val summary by state.transfers.summary.collectAsState()
    var expanded by remember { mutableStateOf(true) }
    if (transfers.isEmpty()) return
    val colors = Carta.colors
    Column(modifier.fillMaxWidth().background(colors.chrome)) {
        Divider()
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CartaIcons.Transfer, contentDescription = null, tint = colors.textDim, size = 16.dp)
            Text("Trasferimenti", style = Carta.type.bodyStrong, modifier = Modifier.padding(start = 8.dp))
            Text(describe(summary, transfers.size), style = Carta.type.small, color = colors.textDim, modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1)
            ParallelMenu(state)
            Button("Pulisci completati", onClick = { state.transfers.clearFinished() }, kind = ButtonKind.Ghost)
            IconButton(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                if (expanded) "Riduci" else "Espandi",
                onClick = { expanded = !expanded },
            )
        }
        if (expanded) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                items(transfers, key = { it.id }) { TransferRow(state, it) }
            }
        }
    }
}

/** "2 in corso · 1 in coda · 12,4 MB/s", or how many are done. */
internal fun describe(summary: TransferSummary, total: Int): String {
    val parts = listOfNotNull(
        "${summary.active} in corso".takeIf { summary.active > 0 },
        "${summary.waiting} in coda".takeIf { summary.waiting > 0 },
        "${summary.paused} in pausa".takeIf { summary.paused > 0 },
        "${summary.failed} non riusciti".takeIf { summary.failed > 1 },
        "1 non riuscito".takeIf { summary.failed == 1 },
        "${formatBytes(summary.bytesPerSecond)}/s".takeIf { summary.bytesPerSecond > 0 },
    )
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return if (total == 1) "1 trasferimento concluso" else "$total trasferimenti conclusi"
}

@Composable
private fun ParallelMenu(state: AppState) {
    val parallel by state.transfers.maxParallel.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        Button("Paralleli: $parallel", onClick = { open = true }, kind = ButtonKind.Ghost)
        Menu(expanded = open, onDismiss = { open = false }, alignEnd = true) {
            MenuLabel("Trasferimenti alla volta (${state.transfers.maxPerHost} per host)")
            (1..6).forEach { n ->
                MenuItem("$n", selected = n == parallel, onClick = {
                    open = false
                    state.transfers.maxParallel.value = n
                })
            }
        }
    }
}

@Composable
private fun TransferRow(state: AppState, transfer: Transfer) {
    val status by transfer.state.collectAsState()
    val progress by transfer.progress.collectAsState()
    val workspace by state.services.workspace.workspace.collectAsState()
    val colors = Carta.colors
    fun where(endpoint: Endpoint) = when (endpoint) {
        Endpoint.Local -> "questo computer"
        is Endpoint.Remote -> workspace.host(endpoint.hostId)?.label ?: "host eliminato"
    }
    val fraction = if (progress.bytesTotal > 0) progress.bytesDone.toFloat() / progress.bytesTotal else if (status is TransferState.Done) 1f else 0f
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (transfer.isDirectory) CartaIcons.Folder else CartaIcons.Document, contentDescription = null, tint = colors.textDim, size = 16.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(transfer.name, style = Carta.type.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(
                    "  ${where(transfer.source.endpoint)} → ${where(transfer.targetDir.endpoint)}:${transfer.targetDir.path}",
                    style = Carta.type.caption,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProgressBar(
                fraction,
                Modifier.padding(vertical = 3.dp),
                color = when (status) {
                    is TransferState.Failed -> colors.danger
                    TransferState.Done -> colors.online
                    is TransferState.Waiting, TransferState.Paused -> colors.offline
                    else -> colors.accent
                },
            )
            Text(detail(status, progress), style = Carta.type.caption.tabular(), color = if (status is TransferState.Failed) colors.danger else colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (status) {
            TransferState.Queued, TransferState.Running, is TransferState.Waiting ->
                IconButton(CartaIcons.Pause, "Metti in pausa", onClick = { state.transfers.pause(transfer) }, size = 28.dp, iconSize = 15.dp)
            TransferState.Paused -> IconButton(CartaIcons.Play, "Riprendi", onClick = { state.transfers.resume(transfer) }, size = 28.dp, iconSize = 15.dp)
            is TransferState.Failed -> IconButton(Icons.Default.Refresh, "Riprova", onClick = { state.transfers.resume(transfer) }, size = 28.dp, iconSize = 15.dp)
            else -> Unit
        }
        if (!status.isFinished) IconButton(Icons.Default.Close, "Annulla", onClick = { state.transfers.cancel(transfer) }, size = 28.dp, iconSize = 14.dp)
    }
}

private fun detail(status: TransferState, progress: dev.eltermino.app.TransferProgress): String {
    val amount = if (progress.bytesTotal > 0) "${formatBytes(progress.bytesDone)} di ${formatBytes(progress.bytesTotal)}" else ""
    val files = if (progress.filesTotal > 1) " · ${progress.filesDone} di ${progress.filesTotal} file" else ""
    val skipped = if (progress.skipped > 0) " · ${progress.skipped} collegamenti saltati" else ""
    return when (status) {
        TransferState.Queued -> "In coda"
        TransferState.Paused -> "In pausa · $amount$files"
        is TransferState.Waiting -> status.message
        TransferState.Done -> "Completato · ${formatBytes(progress.bytesTotal)}$files$skipped"
        is TransferState.Failed -> "Non riuscito: ${status.message}"
        TransferState.Cancelled -> "Annullato"
        TransferState.Running -> {
            val speed = progress.bytesPerSecond
            val eta = if (speed > 0) " · ${formatDuration((progress.bytesTotal - progress.bytesDone) / speed)}" else ""
            "$amount$files" + (if (speed > 0) " · ${formatBytes(speed)}/s" else "") + eta + (progress.currentFile?.takeIf { progress.filesTotal > 1 }?.let { " · $it" } ?: "")
        }
    }
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
    else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
}
