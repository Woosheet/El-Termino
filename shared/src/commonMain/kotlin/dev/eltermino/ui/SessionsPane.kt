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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.EditorTab
import dev.eltermino.app.Endpoint
import dev.eltermino.app.Location
import dev.eltermino.app.childPath
import kotlinx.coroutines.launch
import dev.eltermino.app.FilesTab
import dev.eltermino.app.SessionTab
import dev.eltermino.app.Tab
import dev.eltermino.app.SplitLayout
import dev.eltermino.app.TabStatus
import dev.eltermino.app.titles
import dev.eltermino.core.data.Host
import dev.eltermino.core.terminal.ExtraKeysColors
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

/** Split layout, broadcast and snippets, at the end of the tab bar. */
@Composable
internal fun SessionTools(state: AppState, wide: Boolean, onManageSnippets: () -> Unit) {
    var broadcastDialog by remember { mutableStateOf(false) }
    var workspacesDialog by remember { mutableStateOf(false) }
    val broadcast by state.sessions.broadcast.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        SnippetLauncher(state, onManageSnippets)
        if (broadcast.isNotEmpty()) {
            Button("Broadcast · ${broadcast.size}", onClick = { broadcastDialog = true }, icon = CartaIcons.Broadcast, modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            IconButton(CartaIcons.Broadcast, "Broadcast", onClick = { broadcastDialog = true })
        }
        IconButton(CartaIcons.Layers, "Spazi di lavoro", onClick = { workspacesDialog = true })
        if (wide) LayoutMenu(state)
    }
    if (broadcastDialog) BroadcastDialog(state) { broadcastDialog = false }
    if (workspacesDialog) WorkspacesDialog(state) { workspacesDialog = false }
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
    val scope = rememberCoroutineScope()
    val drag = remember { FileDrag() }
    val copier = remember { CopyFlow(state, scope) }
    CompositionLocalProvider(LocalFileDrag provides drag, LocalCopyFlow provides copier) {
        Column(modifier) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Carta.theme.terminal.background)) {
                SplitPanes(state, tabs.isEmpty(), layout, showExtraKeys)
            }
            TransfersBar(state)
        }
    }
    copier.pending?.let { pending -> ConflictDialog(pending) { policy -> copier.resolve(policy) } }
}

@Composable
private fun SplitPanes(state: AppState, empty: Boolean, layout: SplitLayout, showExtraKeys: Boolean) {
    val panes by state.sessions.panes.collectAsState()
    val drag = LocalChipDrag.current
    val workspace by state.services.workspace.workspace.collectAsState()
    Box(Modifier.fillMaxSize().onGloballyPositioned { drag?.content = it.boundsInWindow() }) {
        if (empty) {
            val last = workspace.lastSession
            EmptyState(
                "Nessuna sessione aperta",
                if (last != null) "Scegli un host, oppure riprendi da dove eri rimasto: ${summary(last, workspace)}." else "Scegli un host per aprire un terminale o i suoi file.",
                Modifier.align(Alignment.Center),
                action = last?.let { { Button("Riapri l'ultima sessione", onClick = { state.sessions.restore(it) }, icon = CartaIcons.Layers) } },
            )
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
        DropOverlay(drag, Modifier.matchParentSize())
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
private fun PaneHeader(state: AppState, index: Int, tab: Tab?, tabs: List<Tab>, broadcasting: Boolean) {
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
                    if (tab != null) TabMark(tab)
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
                if (tab != null) {
                    MenuItem("Sposta in una scheda nuova", icon = CartaIcons.SplitSingle, onClick = {
                        picking = false
                        state.sessions.detach(tab.id)
                    })
                    MenuDivider()
                }
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
            if (tab is SessionTab) IconButton(CartaIcons.Duplicate, "Duplica ${tab.label}", onClick = { state.sessions.duplicate(tab) }, size = 26.dp, iconSize = 15.dp)
            IconButton(Icons.Default.Close, "Chiudi ${tab.label}", onClick = { state.sessions.close(tab) }, size = 26.dp, iconSize = 14.dp)
        }
    }
    Divider()
}

@Composable
private fun EmptyPane(state: AppState, index: Int, tabs: List<Tab>, shown: List<String?>) {
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
private fun TabBody(state: AppState, tab: Tab, showExtraKeys: Boolean, focused: Boolean) {
    when (tab) {
        is SessionTab -> TerminalTabBody(state, tab, showExtraKeys, focused)
        is FilesTab -> FilesPane(state, tab, focused)
        is EditorTab -> EditorPane(state, tab, focused)
    }
}

/** The dot or icon in front of a tab's title. */
@Composable
internal fun TabMark(tab: Tab) {
    when (tab) {
        is SessionTab -> {
            val status by tab.status.collectAsState()
            StatusDot(statusColor(status))
        }
        is FilesTab -> Icon(CartaIcons.Folder, contentDescription = "File", tint = Carta.colors.textDim, size = 14.dp)
        is EditorTab -> {
            val dirty by tab.dirty.collectAsState()
            Icon(CartaIcons.Document, contentDescription = "Editor", tint = if (dirty) Carta.colors.accent else Carta.colors.textDim, size = 14.dp)
        }
    }
}

@Composable
private fun TerminalTabBody(state: AppState, tab: SessionTab, showExtraKeys: Boolean, focused: Boolean) {
    when (val status = tab.status.collectAsState().value) {
        TabStatus.Connecting -> Centered {
            Spinner()
            Gap(height = 12)
            Text("Connessione a ${tab.label}…", color = Carta.colors.textDim)
        }
        is TabStatus.Open -> UploadDropZone(state, tab, status.terminal) { Terminal(status.terminal, showExtraKeys, focused) }
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

/**
 * Files dropped on a terminal are uploaded to the shell's folder, read from the window
 * title the shell sets ("dev@web-01: /var/www"), or to the home folder when it doesn't say.
 */
@Composable
private fun UploadDropZone(state: AppState, tab: SessionTab, terminal: TerminalSession, content: @Composable () -> Unit) {
    val copier = LocalCopyFlow.current
    val scope = rememberCoroutineScope()
    val title by terminal.title.collectAsState()
    var hovering by remember { mutableStateOf(false) }
    val folder = shellFolder(title)
    Box(
        Modifier.fillMaxSize().onFilesDropped(onHover = { hovering = it }) { paths ->
            val local = state.services.localFiles ?: return@onFilesDropped
            scope.launch {
                val endpoint = Endpoint.Remote(tab.hostId)
                val home = runCatching { state.transfers.home(endpoint) }.getOrNull() ?: return@launch
                val dir = folder?.let { if (it == "~") home else if (it.startsWith("~/")) childPath(home, it.removePrefix("~/")) else it } ?: home
                val files = paths.mapNotNull { runCatching { local.stat(it) }.getOrNull() }
                copier?.copy(Endpoint.Local, files, Location(endpoint, dir), tab.label)
            }
        },
    ) {
        content()
        if (hovering) {
            Box(Modifier.matchParentSize().background(Carta.colors.accent.copy(alpha = 0.10f)).border(2.dp, Carta.colors.accent), contentAlignment = Alignment.Center) {
                Text(
                    "Rilascia per caricare in ${folder ?: "~"} su ${tab.label}",
                    modifier = Modifier.background(Carta.colors.accent, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = Carta.type.small,
                    color = Carta.colors.background,
                )
            }
        }
    }
}

/** "dev@web-01: /var/www" → "/var/www"; "~/app" stays relative to home. Null when the title doesn't tell. */
internal fun shellFolder(title: String?): String? {
    val match = Regex("""^[^@\s]+@[^:\s]+:\s*(.+)$""").find(title?.trim().orEmpty()) ?: return null
    return match.groupValues[1].trim().takeIf { it.startsWith("/") || it.startsWith("~") }
}

@Composable
internal fun statusColor(status: TabStatus) = when (status) {
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
            Modifier.weight(1f).fillMaxWidth().background(Carta.theme.terminal.background).padding(horizontal = 12.dp, vertical = 8.dp),
            theme = Carta.theme.terminal,
            fontFamily = Carta.monospace,
            focused = focused,
        )
        if (showExtraKeys) {
            val colors = Carta.colors
            TerminalExtraKeys(
                session,
                Modifier.fillMaxWidth(),
                colors = ExtraKeysColors(colors.chrome, colors.background, colors.line, colors.text, colors.accentBackground, colors.accent),
            )
        }
    }
}
