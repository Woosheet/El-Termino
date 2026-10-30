package dev.eltermino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.DockerAction
import dev.eltermino.app.DockerContainer
import dev.eltermino.app.DockerTab
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.HoppingSombrero
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.StatusDot
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta

/** The containers on a host: state, usage, and what to do with each. */
@Composable
internal fun DockerPane(state: AppState, tab: DockerTab) {
    val containers by tab.containers.collectAsState()
    val error by tab.error.collectAsState()
    val actionError by tab.actionError.collectAsState()
    val busy by tab.busy.collectAsState()
    val colors = Carta.colors

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Docker su ${tab.label}", style = Carta.type.title)
        Text(
            listOfNotNull(
                containers?.let { list -> "${list.size} container, ${list.count { it.running }} attivi" },
                "aggiornato ogni 5 secondi",
            ).joinToString(" · "),
            style = Carta.type.small,
            color = colors.textDim,
        )
        listOfNotNull(error, actionError).forEach { Text(it, color = colors.danger, modifier = Modifier.padding(top = 10.dp)) }
        val list = containers
        when {
            list == null -> if (error == null) Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HoppingSombrero()
                Text("Lettura dei container di ${tab.label}…", color = colors.textDim, modifier = Modifier.padding(top = 12.dp))
            }
            list.isEmpty() -> Text("Nessun container su ${tab.label}.", color = colors.textDim, modifier = Modifier.padding(top = 16.dp))
            else -> BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                val compact = maxWidth < 700.dp
                Column {
                    list.forEachIndexed { index, container ->
                        if (index > 0) Divider()
                        ContainerRow(
                            container = container,
                            busy = container.id in busy,
                            compact = compact,
                            onAction = { state.sessions.dockerAction(tab, container, it) },
                            onLogs = { state.sessions.dockerLogs(tab, container) },
                            onShell = { state.sessions.dockerShell(tab, container) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerRow(
    container: DockerContainer,
    busy: Boolean,
    compact: Boolean,
    onAction: (DockerAction) -> Unit,
    onLogs: () -> Unit,
    onShell: () -> Unit,
) {
    val colors = Carta.colors
    val info: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (container.running) colors.online else colors.offline)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(container.name, style = Carta.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${container.image} · ${container.status}", style = Carta.type.small, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (container.ports.isNotBlank()) {
                    Text(container.ports, style = Carta.type.caption, color = colors.textDim, fontFamily = Carta.monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (container.cpu != null) Column(Modifier.width(if (compact) 120.dp else 170.dp).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                Text("CPU ${container.cpu}", style = Carta.type.small)
                container.memory?.let { Text(it, style = Carta.type.caption, color = colors.textDim, maxLines = 1) }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                HoppingSombrero(height = 20.dp)
            } else if (container.running) {
                Button("Ferma", onClick = { onAction(DockerAction.Stop) }, kind = ButtonKind.Ghost)
                Button("Riavvia", onClick = { onAction(DockerAction.Restart) }, kind = ButtonKind.Ghost)
            } else {
                Button("Avvia", onClick = { onAction(DockerAction.Start) }, kind = ButtonKind.Ghost)
            }
            Button("Log", onClick = onLogs, kind = ButtonKind.Ghost)
            if (container.running) Button("Shell", onClick = onShell, kind = ButtonKind.Ghost)
        }
    }
    if (compact) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            info(Modifier.fillMaxWidth())
            Row(Modifier.padding(start = 12.dp, top = 4.dp)) { actions() }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            info(Modifier.weight(1f))
            Row(Modifier.padding(start = 12.dp)) { actions() }
        }
    }
}

/** The icon in front of a Docker tab's title. */
@Composable
internal fun DockerTabMark() {
    Icon(CartaIcons.Container, contentDescription = "Docker", tint = Carta.colors.textDim, size = 14.dp)
}
