package dev.eltermino.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppCommand
import dev.eltermino.app.AppState
import dev.eltermino.app.EditorTab
import dev.eltermino.core.data.Host
import dev.eltermino.ui.components.AppIconPainter
import dev.eltermino.ui.components.AppIconStyle
import dev.eltermino.ui.components.LocalAppIcon
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Fab
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Menu
import dev.eltermino.ui.components.MenuItem
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.components.VerticalResizeHandle
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.AppTheme
import dev.eltermino.ui.theme.CartaTheme
import dev.eltermino.ui.theme.Themes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class Screen(val title: String) {
    Sessions("Sessioni"),
    Keys("Chiavi"),
    Identities("Identità"),
    Snippets("Snippet"),
    Recordings("Registrazioni"),
    Import("Importa"),
    Settings("Impostazioni"),
}

private val SidebarMin = 200.dp
private val SidebarMax = 520.dp

/** Host being edited: [host] null means a new one. */
private class Editing(val host: Host?)

/**
 * Room for a title bar the window draws over the app, as on macOS: the bars at the top
 * reach under it in their own colour, and keep their controls below it.
 */
private val LocalTitleBar = staticCompositionLocalOf { 0.dp }

@Composable
fun App(state: AppState, onExit: () -> Unit = {}, titleBarHeight: Dp = 0.dp) {
    val workspace by state.services.workspace.workspace.collectAsState()
    CartaTheme(currentTheme(state)) {
        val chipDrag = remember { ChipDrag() }
        CompositionLocalProvider(
            LocalChipDrag provides chipDrag,
            LocalTitleBar provides titleBarHeight,
            LocalAppIcon provides AppIconStyle.of(workspace.ui.appIcon),
        ) { AppContent(state) }
        UpdateDialog(state, onExit)
        ExitDialog(state, onExit)
    }
}

/** The theme the user picked, or the one the system's light or dark mode calls for. */
@Composable
fun currentTheme(state: AppState): AppTheme {
    val workspace by state.services.workspace.workspace.collectAsState()
    return Themes.resolve(workspace.ui.theme, isSystemInDarkTheme())
}

@Composable
private fun AppContent(state: AppState) {
    run {
        var screen by remember { mutableStateOf(Screen.Sessions) }
        var editing by remember { mutableStateOf<Editing?>(null) }
        // The tutorial again, from the settings, without the Termius question.
        var tour by remember { mutableStateOf(false) }
        val showTour = { tour = true }
        // On a phone the host list is its own screen, in front of the sessions.
        var showHostsOnPhone by remember { mutableStateOf(true) }

        val connect: (Host) -> Unit = { host ->
            state.sessions.connect(host)
            screen = Screen.Sessions
            showHostsOnPhone = false
        }
        val openFiles: (Host) -> Unit = { host ->
            state.sessions.openFiles(host)
            screen = Screen.Sessions
            showHostsOnPhone = false
        }
        val openRequests: (Host) -> Unit = { host ->
            state.sessions.openRequests(host)
            screen = Screen.Sessions
            showHostsOnPhone = false
        }
        val openMonitor: (Host) -> Unit = { host ->
            state.sessions.openMonitor(host)
            screen = Screen.Sessions
            showHostsOnPhone = false
        }
        val openDocker: (Host) -> Unit = { host ->
            state.sessions.openDocker(host)
            screen = Screen.Sessions
            showHostsOnPhone = false
        }
        // An editor with changes waiting for ⌘W to be confirmed.
        var closingEditor by remember { mutableStateOf<EditorTab?>(null) }

        LaunchedEffect(state) {
            state.commands.collect { command ->
                val sessions = state.sessions
                fun toSessions() {
                    screen = Screen.Sessions
                    showHostsOnPhone = false
                }
                when (command) {
                    AppCommand.NewTab -> {
                        toSessions()
                        state.newTabMenu.value = true
                    }
                    AppCommand.Close -> when {
                        screen != Screen.Sessions -> screen = Screen.Sessions
                        else -> when (val tab = sessions.focusedTab()) {
                            null -> Unit
                            is EditorTab -> if (tab.dirty.value) closingEditor = tab else sessions.close(tab)
                            else -> sessions.close(tab)
                        }
                    }
                    AppCommand.NextTab -> { toSessions(); sessions.selectNeighbour(1) }
                    AppCommand.PreviousTab -> { toSessions(); sessions.selectNeighbour(-1) }
                    is AppCommand.GoToTab -> { toSessions(); sessions.selectFrameAt(command.index) }
                    AppCommand.NewHost -> editing = Editing(null)
                    AppCommand.ToggleSidebar -> state.services.workspace.update { it.copy(ui = it.ui.copy(sidebarOpen = !it.ui.sidebarOpen)) }
                    AppCommand.Settings -> {
                        screen = Screen.Settings
                        showHostsOnPhone = false
                    }
                }
            }
        }

        // Insets are zero on desktop; on Android they keep us clear of the system bars.
        BoxWithConstraints(Modifier.fillMaxSize().background(Carta.colors.background).safeDrawingPadding()) {
            if (maxWidth >= 720.dp) {
                DesktopLayout(state, screen, { screen = it }, connect, openFiles, openRequests, openMonitor, openDocker, onEdit = { editing = Editing(it) }, onTour = showTour)
            } else {
                PhoneLayout(
                    state = state,
                    screen = screen,
                    showHosts = showHostsOnPhone,
                    onScreen = { screen = it; showHostsOnPhone = false },
                    onBackToHosts = { showHostsOnPhone = true; screen = Screen.Sessions },
                    onConnect = connect,
                    onOpenFiles = openFiles,
                    onOpenRequests = openRequests,
                    onOpenMonitor = openMonitor,
                    onOpenDocker = openDocker,
                    onEdit = { editing = Editing(it) },
                    onTour = showTour,
                )
            }
            val titleBar = LocalTitleBar.current
            if (titleBar > 0.dp) WindowTitle(Modifier.align(Alignment.TopCenter).height(titleBar))
            NoticeToast(state, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 40.dp))
        }

        editing?.let { HostEditorDialog(state, it.host) { editing = null } }
        closingEditor?.let { tab ->
            Dialog(
                onDismiss = { closingEditor = null },
                title = "Chiudere ${tab.label} senza salvare?",
                icon = Icons.Default.Warning,
                actions = {
                    Button("Annulla", onClick = { closingEditor = null }, kind = ButtonKind.Ghost)
                    Button("Chiudi senza salvare", kind = ButtonKind.Danger, onClick = {
                        closingEditor = null
                        state.sessions.close(tab)
                    })
                },
            ) {
                Text("Le modifiche non salvate andranno perse.")
            }
        }
        val settings by state.services.workspace.workspace.collectAsState()
        if (!settings.ui.welcomeDone || tour) {
            WelcomeFlow(state, startAtTour = tour, onOpenImport = { screen = Screen.Import; showHostsOnPhone = false }) { tour = false }
        }
        PromptDialogs(state.sessions.prompter)
    }
}

@Composable
private fun DesktopLayout(
    state: AppState,
    screen: Screen,
    onScreen: (Screen) -> Unit,
    onConnect: (Host) -> Unit,
    onOpenFiles: (Host) -> Unit,
    onOpenRequests: (Host) -> Unit,
    onOpenMonitor: (Host) -> Unit,
    onOpenDocker: (Host) -> Unit,
    onEdit: (Host?) -> Unit,
    onTour: () -> Unit,
) {
    val store = state.services.workspace
    val scope = rememberCoroutineScope()
    var sidebarWidth by remember { mutableStateOf(store.workspace.value.ui.sidebarWidthDp.dp) }
    val workspace by store.workspace.collectAsState()
    val sidebarOpen = workspace.ui.sidebarOpen
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (sidebarOpen) Column(
                // 6 on top, like the tab bar beside it, so "Host" and its + line up with the tabs.
                Modifier.width(sidebarWidth).fillMaxHeight().background(Carta.colors.chrome)
                    .padding(top = LocalTitleBar.current).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            ) {
                Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Host", style = Carta.type.heading, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    IconButton(Icons.Default.Add, "Nuovo host", onClick = { onEdit(null) }, tint = Carta.colors.text)
                }
                HostList(state, onConnect, onEdit, Modifier.weight(1f), onOpenFiles = onOpenFiles, onOpenRequests = onOpenRequests, onOpenMonitor = onOpenMonitor, onOpenDocker = onOpenDocker)
                SectionLabel("Strumenti")
                ToolRow(CartaIcons.Key, "Chiavi", screen == Screen.Keys) { onScreen(Screen.Keys) }
                ToolRow(CartaIcons.User, "Identità", screen == Screen.Identities) { onScreen(Screen.Identities) }
                ToolRow(CartaIcons.Snippet, "Snippet", screen == Screen.Snippets) { onScreen(Screen.Snippets) }
                ToolRow(CartaIcons.Play, "Registrazioni", screen == Screen.Recordings) { onScreen(Screen.Recordings) }
                ToolRow(CartaIcons.Import, "Importa", screen == Screen.Import) { onScreen(Screen.Import) }
                ToolRow(CartaIcons.Settings, "Impostazioni", screen == Screen.Settings) { onScreen(Screen.Settings) }
            }
            if (sidebarOpen) VerticalResizeHandle(
                onDrag = { delta -> sidebarWidth = (sidebarWidth + delta).coerceIn(SidebarMin, SidebarMax) },
                onDragStopped = {
                    val width = sidebarWidth.value.roundToInt()
                    scope.launch { store.update { it.copy(ui = it.ui.copy(sidebarWidthDp = width)) } }
                },
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(top = LocalTitleBar.current).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        CartaIcons.Sidebar,
                        if (sidebarOpen) "Nascondi la barra laterale" else "Mostra la barra laterale",
                        onClick = { scope.launch { store.update { it.copy(ui = it.ui.copy(sidebarOpen = !sidebarOpen)) } } },
                    )
                    if (screen != Screen.Sessions) {
                        IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Torna alle sessioni", onClick = { onScreen(Screen.Sessions) })
                    }
                    SessionTabs(state, onConnect, Modifier.weight(1f))
                    if (screen == Screen.Sessions) SessionTools(state, wide = true, onManageSnippets = { onScreen(Screen.Snippets) })
                    // With the sidebar folded away its tools stay one click away.
                    if (!sidebarOpen) ToolsMenu(onScreen)
                }
                Divider()
                // Tablets get the wide layout but still need Esc, Ctrl and the arrows on screen.
                ScreenBody(state, screen, showExtraKeys = !state.services.platform.isDesktop, split = true, Modifier.weight(1f).fillMaxWidth(), onTour, onScreen)
            }
        }
        Divider()
        StatusBar(state)
    }
}

@Composable
private fun PhoneLayout(
    state: AppState,
    screen: Screen,
    showHosts: Boolean,
    onScreen: (Screen) -> Unit,
    onBackToHosts: () -> Unit,
    onConnect: (Host) -> Unit,
    onOpenFiles: (Host) -> Unit,
    onOpenRequests: (Host) -> Unit,
    onOpenMonitor: (Host) -> Unit,
    onOpenDocker: (Host) -> Unit,
    onEdit: (Host?) -> Unit,
    onTour: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (showHosts) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(top = LocalTitleBar.current).padding(12.dp)) {
                    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // With a title bar over the app the name is already up there.
                        if (LocalTitleBar.current > 0.dp) Box(Modifier.weight(1f)) else ProductMark(Modifier.weight(1f).padding(start = 4.dp))
                        val tabs by state.sessions.tabs.collectAsState()
                        if (tabs.isNotEmpty()) {
                            Button(sessions(tabs.size), onClick = { onScreen(Screen.Sessions) }, kind = ButtonKind.Ghost)
                        }
                        ToolsMenu(onScreen)
                    }
                    HostList(state, onConnect, onEdit, Modifier.weight(1f), onOpenFiles = onOpenFiles, onOpenRequests = onOpenRequests, onOpenMonitor = onOpenMonitor, onOpenDocker = onOpenDocker)
                }
                Fab(Icons.Default.Add, "Nuovo host", onClick = { onEdit(null) }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(top = LocalTitleBar.current).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Host", onClick = onBackToHosts)
                if (screen == Screen.Sessions) {
                    SessionTabs(state, onConnect, Modifier.weight(1f))
                    SessionTools(state, wide = false, onManageSnippets = { onScreen(Screen.Snippets) })
                } else {
                    Text(screen.title, style = Carta.type.heading, modifier = Modifier.weight(1f))
                }
            }
            Divider()
            ScreenBody(state, screen, showExtraKeys = true, split = false, Modifier.weight(1f).fillMaxWidth(), onTour, onScreen)
        }
    }
}

@Composable
private fun ScreenBody(state: AppState, screen: Screen, showExtraKeys: Boolean, split: Boolean, modifier: Modifier, onTour: () -> Unit, onScreen: (Screen) -> Unit) {
    when (screen) {
        Screen.Sessions -> SessionContent(state, showExtraKeys, split, modifier)
        Screen.Keys -> KeysScreen(state, modifier)
        Screen.Identities -> IdentitiesScreen(state, modifier)
        Screen.Snippets -> SnippetsScreen(state, modifier)
        Screen.Recordings -> RecordingsScreen(state, onPlay = { onScreen(Screen.Sessions) }, modifier)
        Screen.Settings -> SettingsScreen(state, modifier) {
            SettingsSection("Tutorial", "La guida del primo avvio a schede, file, trasferimenti, snippet e spazi di lavoro.") {
                Button("Rivedi il tutorial", onClick = onTour, kind = ButtonKind.Secondary)
            }
        }
        Screen.Import -> ImportScreen(state, modifier)
    }
}

@Composable
private fun ToolsMenu(onScreen: (Screen) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(Icons.Default.MoreVert, "Strumenti", onClick = { open = true })
        Menu(expanded = open, onDismiss = { open = false }, alignEnd = true) {
            listOf(Screen.Keys, Screen.Identities, Screen.Snippets, Screen.Recordings, Screen.Import, Screen.Settings).forEach { target ->
                MenuItem(target.title, onClick = { open = false; onScreen(target) })
            }
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Carta.colors
    // A neutral pill marks where you are; the accent stays on data and the primary action.
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        background = if (selected) colors.pressed else colors.chrome,
        hover = if (selected) colors.pressed else colors.hover,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.text else colors.textDim, size = 16.dp, modifier = Modifier.padding(end = 10.dp))
            Text(label, color = colors.text)
        }
    }
}

/** The title in the bar macOS draws over the app: the icon chosen in the settings and the name. */
@Composable
private fun WindowTitle(modifier: Modifier = Modifier) {
    val style = LocalAppIcon.current
    val icon = remember(style) { AppIconPainter(style) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text("El Termino", style = Carta.type.bodyStrong, color = Carta.colors.textDim, modifier = Modifier.padding(start = 6.dp))
    }
}

/** The product's mark and name: a shell prompt in the accent, then "El Termino". */
@Composable
internal fun ProductMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(CartaIcons.Snippet, contentDescription = null, tint = Carta.colors.accent, size = 20.dp)
        Text("El Termino", style = Carta.type.dialogTitle, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun StatusBar(state: AppState) {
    val connected by state.sessions.connectedHostIds.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    val broadcast by state.sessions.broadcast.collectAsState()
    val transfers by state.transfers.summary.collectAsState()
    Row(
        Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (connected.size) {
                0 -> "Nessuna connessione attiva"
                1 -> "1 host connesso · ${sessions(tabs.size)}"
                else -> "${connected.size} host connessi · ${sessions(tabs.size)}"
            },
            style = Carta.type.label, color = Carta.colors.textDim,
        )
        if (transfers.active > 0 || transfers.waiting > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CartaIcons.Transfer, contentDescription = null, tint = Carta.colors.textDim, size = 14.dp)
                Text(describe(transfers, 0), style = Carta.type.label, color = Carta.colors.textDim, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (broadcast.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CartaIcons.Broadcast, contentDescription = null, tint = Carta.colors.accent, size = 14.dp)
                Text("Broadcast su ${broadcast.size} sessioni", style = Carta.type.label, color = Carta.colors.accent, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Box(Modifier.weight(1f))
        UpdateBadge(state)
        Text("Segreti nel vault cifrato", style = Carta.type.label, color = Carta.colors.textDim)
    }
}

private fun sessions(count: Int) = if (count == 1) "1 sessione" else "$count sessioni"
