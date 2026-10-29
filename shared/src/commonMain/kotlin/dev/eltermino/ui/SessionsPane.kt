package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.SessionTab
import dev.eltermino.app.TabStatus
import dev.eltermino.core.terminal.TerminalExtraKeys
import dev.eltermino.core.terminal.TerminalSession
import dev.eltermino.core.terminal.TerminalView
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.Spinner
import dev.eltermino.ui.components.StatusDot
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta

@Composable
internal fun SessionTabs(state: AppState, modifier: Modifier = Modifier) {
    val tabs by state.sessions.tabs.collectAsState()
    val selected by state.sessions.selectedId.collectAsState()
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEach { tab -> SessionTabChip(state, tab, tab.id == selected) }
    }
}

@Composable
private fun SessionTabChip(state: AppState, tab: SessionTab, selected: Boolean) {
    val status by tab.status.collectAsState()
    val colors = Carta.colors
    Pressable(
        onClick = { state.sessions.select(tab.id) },
        modifier = Modifier.padding(end = 4.dp),
        shape = RoundedCornerShape(6.dp),
        background = if (selected) colors.background else colors.chrome,
        hover = if (selected) colors.background else colors.hover,
    ) {
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when (status) {
                    is TabStatus.Open -> colors.online
                    TabStatus.Connecting -> colors.accent
                    else -> colors.offline
                },
            )
            Text(
                tab.label,
                modifier = Modifier.padding(start = 6.dp),
                style = Carta.type.small,
                color = if (selected) colors.text else colors.textDim,
            )
            IconButton(Icons.Default.Close, "Chiudi ${tab.label}", onClick = { state.sessions.close(tab) }, size = 28.dp, iconSize = 14.dp)
        }
    }
}

/** The selected tab's terminal, or what's happening with its connection. */
@Composable
internal fun SessionContent(state: AppState, showExtraKeys: Boolean, modifier: Modifier = Modifier) {
    val tabs by state.sessions.tabs.collectAsState()
    val selectedId by state.sessions.selectedId.collectAsState()
    val tab = tabs.firstOrNull { it.id == selectedId }
    Box(modifier.background(Carta.colors.terminalBackground)) {
        if (tab == null) {
            EmptyState("Nessuna sessione aperta", "Scegli un host per aprire un terminale.", Modifier.align(Alignment.Center))
        } else {
            TabBody(state, tab, showExtraKeys)
        }
    }
}

@Composable
private fun TabBody(state: AppState, tab: SessionTab, showExtraKeys: Boolean) {
    when (val status = tab.status.collectAsState().value) {
        TabStatus.Connecting -> Centered {
            Spinner()
            Gap(height = 12)
            Text("Connessione a ${tab.label}…", color = Carta.colors.textDim)
        }
        is TabStatus.Open -> Terminal(status.terminal, showExtraKeys)
        is TabStatus.Failed -> Centered {
            Text("Connessione non riuscita", style = Carta.type.bodyStrong)
            Gap(height = 4)
            Text(status.message, style = Carta.type.small, color = Carta.colors.textDim)
            Gap(height = 14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Chiudi", onClick = { state.sessions.close(tab) }, kind = ButtonKind.Ghost)
                Button("Riprova", onClick = { state.sessions.reconnect(tab) })
            }
        }
        is TabStatus.Closed -> Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Disconnesso: ${status.message}", style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.weight(1f))
                Button("Riconnetti", onClick = { state.sessions.reconnect(tab) })
            }
            Divider()
            status.terminal?.let { Box(Modifier.weight(1f)) { Terminal(it, showExtraKeys = false) } }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun Terminal(session: TerminalSession, showExtraKeys: Boolean) {
    Column(Modifier.fillMaxSize()) {
        TerminalView(session, Modifier.weight(1f).fillMaxWidth().padding(4.dp), fontFamily = Carta.monospace)
        if (showExtraKeys) TerminalExtraKeys(session, Modifier.fillMaxWidth())
    }
}
