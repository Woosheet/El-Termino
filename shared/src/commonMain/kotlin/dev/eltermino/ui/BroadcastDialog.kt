package dev.eltermino.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.SessionTab
import dev.eltermino.app.SplitLayout
import dev.eltermino.app.titles
import dev.eltermino.core.data.groupPath
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta

/** Picks the sessions that receive each other's typing. */
@Composable
internal fun BroadcastDialog(state: AppState, onDismiss: () -> Unit) {
    val allTabs by state.sessions.tabs.collectAsState()
    val tabs = allTabs.filterIsInstance<SessionTab>()
    val current by state.sessions.broadcast.collectAsState()
    val panes by state.sessions.panes.collectAsState()
    val workspace by state.services.workspace.workspace.collectAsState()
    val titles = remember(tabs) { tabs.titles() }
    val visible = panes.tabIds.filterNotNull().filter { id -> tabs.any { it.id == id } }.toSet()
    var chosen by remember {
        mutableStateOf(
            when {
                current.isNotEmpty() -> current
                panes.layout != SplitLayout.Single && visible.size >= 2 -> visible
                else -> tabs.map { it.id }.toSet()
            },
        )
    }
    // Groups of the hosts that have a tab open, for picking "everything in Produzione".
    val groups = remember(tabs, workspace) {
        tabs.groupBy { workspace.groupPath(workspace.host(it.hostId)?.groupId).joinToString(" / ") }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, members) -> members.map { it.id }.toSet() }
    }

    Dialog(
        onDismiss = onDismiss,
        title = "Broadcast",
        icon = CartaIcons.Broadcast,
        actions = {
            if (current.isNotEmpty()) {
                Button("Interrompi", kind = ButtonKind.Ghost, onClick = {
                    state.sessions.setBroadcast(emptySet())
                    onDismiss()
                })
            } else {
                Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            }
            Button(if (current.isEmpty()) "Avvia" else "Aggiorna", enabled = chosen.size >= 2, onClick = {
                state.sessions.setBroadcast(chosen)
                onDismiss()
            })
        },
    ) {
        Text("Quello che scrivi in una di queste sessioni arriva anche a tutte le altre. Usalo con attenzione: anche Ctrl+C e Invio vanno ovunque.")
        if (tabs.size < 2) {
            Hint("Servono almeno due sessioni aperte.")
            return@Dialog
        }
        Gap(height = 10)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button("Tutte", onClick = { chosen = tabs.map { it.id }.toSet() }, kind = ButtonKind.Secondary)
            if (visible.size >= 2) Button("Visibili", onClick = { chosen = visible }, kind = ButtonKind.Secondary)
            groups.forEach { (name, ids) -> Button(name, onClick = { chosen = ids }, kind = ButtonKind.Secondary) }
            Button("Nessuna", onClick = { chosen = emptySet() }, kind = ButtonKind.Ghost)
        }
        Gap(height = 6)
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            tabs.forEach { tab ->
                val host = workspace.host(tab.hostId)
                Checkbox(
                    checked = tab.id in chosen,
                    onCheckedChange = { on -> chosen = if (on) chosen + tab.id else chosen - tab.id },
                    label = titles.getValue(tab.id) + (host?.let { " · ${it.address}" } ?: ""),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text("${chosen.size} sessioni scelte", style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(top = 6.dp))
    }
}
