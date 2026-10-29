package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.SessionTab
import dev.eltermino.app.SplitLayout
import dev.eltermino.app.TabStatus
import dev.eltermino.app.titles
import dev.eltermino.core.data.Host
import dev.eltermino.core.terminal.TerminalExtraKeys
import dev.eltermino.core.terminal.TerminalSession
import dev.eltermino.core.terminal.TerminalView
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.HorizontalResizeHandle
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Menu
import dev.eltermino.ui.components.MenuDivider
import dev.eltermino.ui.components.MenuItem
import dev.eltermino.ui.components.MenuLabel
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.Spinner
import dev.eltermino.ui.components.StatusDot
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.components.VerticalResizeHandle
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun SessionTabs(state: AppState, onConnect: (Host) -> Unit, modifier: Modifier = Modifier) {
    val tabs by state.sessions.tabs.collectAsState()
    val panes by state.sessions.panes.collectAsState()
    val broadcast by state.sessions.broadcast.collectAsState()
    val titles = remember(tabs) { tabs.titles() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                SessionTabChip(
                    state = state,
                    tab = tab,
                    title = titles.getValue(tab.id),
                    focused = tab.id == panes.focusedTabId,
                    visible = panes.layout != SplitLayout.Single && tab.id in panes.tabIds,
                    broadcasting = tab.id in broadcast,
                )
            }
        }
        NewTabMenu(state, onConnect)
    }
}

@Composable
private fun SessionTabChip(state: AppState, tab: SessionTab, title: String, focused: Boolean, visible: Boolean, broadcasting: Boolean) {
    val status by tab.status.collectAsState()
    val colors = Carta.colors
    Pressable(
        onClick = { state.sessions.select(tab.id) },
        modifier = Modifier.padding(end = 4.dp),
        shape = RoundedCornerShape(6.dp),
        background = when {
            focused -> colors.background
            visible -> colors.background.copy(alpha = 0.55f)
            else -> colors.chrome
        },
        hover = if (focused) colors.background else colors.hover,
    ) {
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(statusColor(status))
            Text(
                title,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = 180.dp),
                style = Carta.type.small,
                color = if (focused) colors.text else colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (broadcasting) Icon(CartaIcons.Broadcast, "Broadcast", tint = colors.accent, size = 14.dp, modifier = Modifier.padding(start = 6.dp))
            IconButton(Icons.Default.Close, "Chiudi $title", onClick = { state.sessions.close(tab) }, size = 28.dp, iconSize = 14.dp)
        }
    }
}

/** "+" after the tabs: another shell like the focused one, or a quick pick of recent hosts. */
@Composable
private fun NewTabMenu(state: AppState, onConnect: (Host) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val workspace by state.services.workspace.workspace.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    val selectedId by state.sessions.selectedId.collectAsState()
    val focused = tabs.firstOrNull { it.id == selectedId }
    Box {
        IconButton(Icons.Default.Add, "Nuova sessione", onClick = { open = true }, size = 28.dp, iconSize = 16.dp)
        Menu(expanded = open, onDismiss = { open = false }) {
            if (focused != null) {
                MenuItem("Duplica ${focused.label}", icon = CartaIcons.Duplicate, onClick = {
                    open = false
                    state.sessions.duplicate(focused)
                })
                MenuDivider()
            }
            val recent = workspace.hosts
                .sortedWith(compareByDescending<Host> { it.lastConnectedEpochSeconds ?: 0 }.thenByDescending { it.favorite })
                .take(8)
            if (recent.isEmpty()) {
                MenuLabel("Nessun host: aggiungine uno dalla barra laterale")
            } else {
                MenuLabel("Apri una sessione su")
                recent.forEach { host ->
                    MenuItem(host.label, detail = host.address, onClick = {
                        open = false
                        onConnect(host)
                    })
                }
            }
        }
    }
}

/** Split layout, broadcast and snippets, at the end of the tab bar. */
@Composable
internal fun SessionTools(state: AppState, wide: Boolean, onManageSnippets: () -> Unit) {
    var broadcastDialog by remember { mutableStateOf(false) }
    val broadcast by state.sessions.broadcast.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        SnippetLauncher(state, onManageSnippets)
        if (broadcast.isNotEmpty()) {
            Button("Broadcast · ${broadcast.size}", onClick = { broadcastDialog = true }, icon = CartaIcons.Broadcast, modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            IconButton(CartaIcons.Broadcast, "Broadcast", onClick = { broadcastDialog = true })
        }
        if (wide) LayoutMenu(state)
    }
    if (broadcastDialog) BroadcastDialog(state) { broadcastDialog = false }
}

@Composable
private fun LayoutMenu(state: AppState) {
    var open by remember { mutableStateOf(false) }
    val panes by state.sessions.panes.collectAsState()
    Box {
        IconButton(panes.layout.icon(), "Disposizione dei riquadri", onClick = { open = true })
        Menu(expanded = open, onDismiss = { open = false }, alignEnd = true) {
            SplitLayout.entries.forEach { layout ->
                MenuItem(layout.title(), icon = layout.icon(), selected = layout == panes.layout, onClick = {
                    open = false
                    state.sessions.setLayout(layout)
                })
            }
        }
    }
}

private fun SplitLayout.icon(): ImageVector = when (this) {
    SplitLayout.Single -> CartaIcons.SplitSingle
    SplitLayout.Columns -> CartaIcons.SplitColumns
    SplitLayout.Rows -> CartaIcons.SplitRows
    SplitLayout.Grid -> CartaIcons.SplitGrid
}

private fun SplitLayout.title(): String = when (this) {
    SplitLayout.Single -> "Un riquadro"
    SplitLayout.Columns -> "Due affiancati"
    SplitLayout.Rows -> "Due sovrapposti"
    SplitLayout.Grid -> "Quattro in griglia"
}

/**
 * The session area. On a wide screen it follows the split layout; on a phone it shows
 * the focused tab only.
 */
@Composable
internal fun SessionContent(state: AppState, showExtraKeys: Boolean, split: Boolean, modifier: Modifier = Modifier) {
    val panes by state.sessions.panes.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    val layout = if (split) panes.layout else SplitLayout.Single
    Box(modifier.background(Carta.colors.terminalBackground)) {
        if (tabs.isEmpty()) {
            EmptyState("Nessuna sessione aperta", "Scegli un host per aprire un terminale.", Modifier.align(Alignment.Center))
            return@Box
        }
        var columnRatio by remember { mutableFloatStateOf(0.5f) }
        var rowRatio by remember { mutableFloatStateOf(0.5f) }
        val pane: @Composable (Int, Modifier) -> Unit = { index, paneModifier ->
            val paneIndex = if (layout == SplitLayout.Single) panes.focused else index
            Pane(state, paneIndex, framed = layout != SplitLayout.Single, showExtraKeys = showExtraKeys, modifier = paneModifier)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth
            val height = maxHeight
            fun Float.dragged(delta: Dp, total: Dp) = (this + delta / total).coerceIn(0.2f, 0.8f)
            when (layout) {
                SplitLayout.Single -> pane(0, Modifier.fillMaxSize())
                SplitLayout.Columns -> Row(Modifier.fillMaxSize()) {
                    pane(0, Modifier.weight(columnRatio).fillMaxHeight())
                    VerticalResizeHandle(onDrag = { columnRatio = columnRatio.dragged(it, width) })
                    pane(1, Modifier.weight(1 - columnRatio).fillMaxHeight())
                }
                SplitLayout.Rows -> Column(Modifier.fillMaxSize()) {
                    pane(0, Modifier.weight(rowRatio).fillMaxWidth())
                    HorizontalResizeHandle(onDrag = { rowRatio = rowRatio.dragged(it, height) })
                    pane(1, Modifier.weight(1 - rowRatio).fillMaxWidth())
                }
                SplitLayout.Grid -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(rowRatio).fillMaxWidth()) {
                        pane(0, Modifier.weight(columnRatio).fillMaxHeight())
                        VerticalResizeHandle(onDrag = { columnRatio = columnRatio.dragged(it, width) })
                        pane(1, Modifier.weight(1 - columnRatio).fillMaxHeight())
                    }
                    HorizontalResizeHandle(onDrag = { rowRatio = rowRatio.dragged(it, height) })
                    Row(Modifier.weight(1 - rowRatio).fillMaxWidth()) {
                        pane(2, Modifier.weight(columnRatio).fillMaxHeight())
                        VerticalResizeHandle(onDrag = { columnRatio = columnRatio.dragged(it, width) })
                        pane(3, Modifier.weight(1 - columnRatio).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** One pane: a header when the view is split, then the tab it shows or a way to pick one. */
@Composable
private fun Pane(state: AppState, index: Int, framed: Boolean, showExtraKeys: Boolean, modifier: Modifier) {
    val panes by state.sessions.panes.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    val broadcast by state.sessions.broadcast.collectAsState()
    val tabId = panes.tabIds.getOrNull(index)
    val tab = tabs.firstOrNull { it.id == tabId }
    val focused = index == panes.focused
    val colors = Carta.colors
    // The border only says where the keyboard is; broadcast shows in the pane header.
    val border = if (framed && focused) Modifier.border(2.dp, colors.accent) else Modifier
    Column(
        modifier
            .then(border)
            // Watches presses without taking them, so the terminal below still gets the click.
            .pointerInput(index) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) state.sessions.focusPane(index)
                    }
                }
            },
    ) {
        if (framed) PaneHeader(state, index, tab, tabs, broadcasting = tab != null && tab.id in broadcast)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == null) {
                EmptyPane(state, index, tabs, panes.tabIds)
            } else {
                key(tab.id) {
                    if (focused && tab.id in broadcast) {
                        Column(Modifier.fillMaxSize()) {
                            BroadcastBand(state, broadcast.size)
                            Box(Modifier.weight(1f)) { TabBody(state, tab, showExtraKeys && focused, focused) }
                        }
                    } else {
                        TabBody(state, tab, showExtraKeys && focused, focused)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneHeader(state: AppState, index: Int, tab: SessionTab?, tabs: List<SessionTab>, broadcasting: Boolean) {
    val colors = Carta.colors
    var picking by remember { mutableStateOf(false) }
    val titles = remember(tabs) { tabs.titles() }
    Row(
        Modifier.fillMaxWidth().background(if (broadcasting) colors.accentBackground else colors.chrome).padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Pressable(onClick = { picking = true }, shape = RoundedCornerShape(6.dp)) {
                Row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tab != null) {
                        val status by tab.status.collectAsState()
                        StatusDot(statusColor(status))
                    }
                    Text(
                        tab?.let { titles[it.id] } ?: "Riquadro libero",
                        modifier = Modifier.padding(start = 6.dp),
                        style = Carta.type.small,
                        color = if (broadcasting) colors.accent else colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Scegli la sessione", tint = colors.textDim, size = 18.dp)
                }
            }
            Menu(expanded = picking, onDismiss = { picking = false }) {
                MenuLabel("Mostra in questo riquadro")
                tabs.forEach { other ->
                    MenuItem(titles.getValue(other.id), selected = other.id == tab?.id, onClick = {
                        picking = false
                        state.sessions.showInPane(index, other.id)
                    })
                }
            }
        }
        if (broadcasting) Text("BROADCAST", style = Carta.type.caption, color = colors.accent, modifier = Modifier.padding(end = 6.dp))
        if (tab != null) {
            IconButton(CartaIcons.Duplicate, "Duplica ${tab.label}", onClick = { state.sessions.duplicate(tab) }, size = 26.dp, iconSize = 15.dp)
            IconButton(Icons.Default.Close, "Chiudi ${tab.label}", onClick = { state.sessions.close(tab) }, size = 26.dp, iconSize = 14.dp)
        }
    }
    Divider()
}

@Composable
private fun EmptyPane(state: AppState, index: Int, tabs: List<SessionTab>, shown: List<String?>) {
    val hidden = tabs.filter { it.id !in shown }
    val titles = remember(tabs) { tabs.titles() }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Riquadro libero", style = Carta.type.bodyStrong)
        Gap(height = 4)
        Text(
            if (hidden.isEmpty()) "Apri un host dalla barra laterale: la sessione compare qui." else "Scegli una sessione da mostrare, oppure apri un host.",
            style = Carta.type.small,
            color = Carta.colors.textDim,
        )
        Gap(height = 12)
        hidden.take(6).forEach { tab ->
            Button(titles.getValue(tab.id), onClick = { state.sessions.showInPane(index, tab.id) }, kind = ButtonKind.Secondary, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/** Shown above the terminal you're typing in while broadcast is on. */
@Composable
private fun BroadcastBand(state: AppState, members: Int) {
    val colors = Carta.colors
    Row(
        Modifier.fillMaxWidth().background(colors.accent).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CartaIcons.Broadcast, contentDescription = null, tint = colors.background, size = 16.dp)
        Text(
            "Broadcast attivo: quello che scrivi arriva a ${members} sessioni",
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = Carta.type.small,
            color = colors.background,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Pressable(onClick = { state.sessions.setBroadcast(emptySet()) }, hover = colors.accentHover, pressed = colors.accentPressed, shape = RoundedCornerShape(6.dp)) {
            Text("Interrompi", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = Carta.type.small, color = colors.background)
        }
    }
}

@Composable
private fun TabBody(state: AppState, tab: SessionTab, showExtraKeys: Boolean, focused: Boolean) {
    when (val status = tab.status.collectAsState().value) {
        TabStatus.Connecting -> Centered {
            Spinner()
            Gap(height = 12)
            Text("Connessione a ${tab.label}…", color = Carta.colors.textDim)
        }
        is TabStatus.Open -> Terminal(status.terminal, showExtraKeys, focused)
        is TabStatus.Reconnecting -> Column(Modifier.fillMaxSize()) {
            ReconnectingBar(state, tab, status)
            Divider()
            Box(Modifier.weight(1f)) { Terminal(status.terminal, showExtraKeys = false, focused = focused) }
        }
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
            status.terminal?.let { Box(Modifier.weight(1f)) { Terminal(it, showExtraKeys = false, focused = focused) } }
        }
    }
}

@Composable
private fun ReconnectingBar(state: AppState, tab: SessionTab, status: TabStatus.Reconnecting) {
    val colors = Carta.colors
    // Counts down to the next attempt; the status itself only changes per attempt.
    var secondsLeft by remember(status) { mutableIntStateOf(0) }
    LaunchedEffect(status) {
        val retryAt = status.retryAt ?: return@LaunchedEffect
        while (true) {
            secondsLeft = (-retryAt.elapsedNow().inWholeMilliseconds + 999).coerceAtLeast(0).toInt() / 1000
            delay(250.milliseconds)
        }
    }
    val text = when {
        status.attempt == 0 -> "Riconnessione a ${tab.label}…"
        status.retryAt == null -> "Riconnessione, tentativo ${status.attempt} di ${status.attempts}…"
        else -> "Connessione persa. Nuovo tentativo tra $secondsLeft s (${status.attempt} di ${status.attempts})"
    }
    Row(
        Modifier.fillMaxWidth().background(colors.chrome).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spinner(size = 16.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(text, style = Carta.type.small)
            status.message?.let { Text(it, style = Carta.type.caption, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (status.retryAt != null) Button("Riprova ora", onClick = { state.sessions.retryNow(tab) }, kind = ButtonKind.Ghost)
        Button("Annulla", onClick = { state.sessions.stopReconnecting(tab) }, kind = ButtonKind.Ghost)
    }
}

@Composable
private fun statusColor(status: TabStatus) = when (status) {
    is TabStatus.Open -> Carta.colors.online
    TabStatus.Connecting, is TabStatus.Reconnecting -> Carta.colors.accent
    else -> Carta.colors.offline
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
private fun Terminal(session: TerminalSession, showExtraKeys: Boolean, focused: Boolean) {
    Column(Modifier.fillMaxSize()) {
        TerminalView(
            session,
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            fontFamily = Carta.monospace,
            focused = focused,
        )
        if (showExtraKeys) TerminalExtraKeys(session, Modifier.fillMaxWidth())
    }
}
