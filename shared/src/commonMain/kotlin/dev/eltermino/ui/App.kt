package dev.eltermino.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.core.data.Host
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
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
    Import("Importa"),
    Settings("Impostazioni"),
}

private val SidebarMin = 200.dp
private val SidebarMax = 520.dp

/** Host being edited: [host] null means a new one. */
private class Editing(val host: Host?)

@Composable
fun App(state: AppState, onExit: () -> Unit = {}) {
    CartaTheme(currentTheme(state)) {
        val chipDrag = remember { ChipDrag() }
        CompositionLocalProvider(LocalChipDrag provides chipDrag) { AppContent(state) }
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

        // Insets are zero on desktop; on Android they keep us clear of the system bars.
        BoxWithConstraints(Modifier.fillMaxSize().background(Carta.colors.background).safeDrawingPadding()) {
            if (maxWidth >= 720.dp) {
                DesktopLayout(state, screen, { screen = it }, connect, openFiles, onEdit = { editing = Editing(it) }, onTour = showTour)
            } else {
                PhoneLayout(
                    state = state,
                    screen = screen,
                    showHosts = showHostsOnPhone,
                    onScreen = { screen = it; showHostsOnPhone = false },
                    onBackToHosts = { showHostsOnPhone = true; screen = Screen.Sessions },
                    onConnect = connect,
                    onOpenFiles = openFiles,
                    onEdit = { editing = Editing(it) },
                    onTour = showTour,
                )
            }
            NoticeToast(state, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 40.dp))
        }

        editing?.let { HostEditorDialog(state, it.host) { editing = null } }
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
    onEdit: (Host?) -> Unit,
    onTour: () -> Unit,
) {
    val store = state.services.workspace
    val scope = rememberCoroutineScope()
    var sidebarWidth by remember { mutableStateOf(store.workspace.value.ui.sidebarWidthDp.dp) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(sidebarWidth).fillMaxHeight().background(Carta.colors.chrome).padding(horizontal = 12.dp, vertical = 12.dp)) {
                ProductMark(Modifier.padding(start = 4.dp, bottom = 12.dp))
                Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Host", style = Carta.type.heading, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    IconButton(Icons.Default.Add, "Nuovo host", onClick = { onEdit(null) }, tint = Carta.colors.text)
                }
                HostList(state, onConnect, onEdit, Modifier.weight(1f), onOpenFiles = onOpenFiles)
                SectionLabel("Strumenti")
                ToolRow(CartaIcons.Key, "Chiavi", screen == Screen.Keys) { onScreen(Screen.Keys) }
                ToolRow(CartaIcons.User, "Identità", screen == Screen.Identities) { onScreen(Screen.Identities) }
                ToolRow(CartaIcons.Snippet, "Snippet", screen == Screen.Snippets) { onScreen(Screen.Snippets) }
                ToolRow(CartaIcons.Import, "Importa", screen == Screen.Import) { onScreen(Screen.Import) }
                ToolRow(CartaIcons.Settings, "Impostazioni", screen == Screen.Settings) { onScreen(Screen.Settings) }
            }
            VerticalResizeHandle(
                onDrag = { delta -> sidebarWidth = (sidebarWidth + delta).coerceIn(SidebarMin, SidebarMax) },
                onDragStopped = {
                    val width = sidebarWidth.value.roundToInt()
                    scope.launch { store.update { it.copy(ui = it.ui.copy(sidebarWidthDp = width)) } }
                },
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (screen != Screen.Sessions) {
                        IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Torna alle sessioni", onClick = { onScreen(Screen.Sessions) })
                    }
                    SessionTabs(state, onConnect, Modifier.weight(1f))
                    if (screen == Screen.Sessions) SessionTools(state, wide = true, onManageSnippets = { onScreen(Screen.Snippets) })
                }
                Divider()
                // Tablets get the wide layout but still need Esc, Ctrl and the arrows on screen.
                ScreenBody(state, screen, showExtraKeys = !state.services.platform.isDesktop, split = true, Modifier.weight(1f).fillMaxWidth(), onTour)
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
    onEdit: (Host?) -> Unit,
    onTour: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (showHosts) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProductMark(Modifier.weight(1f).padding(start = 4.dp))
                        val tabs by state.sessions.tabs.collectAsState()
                        if (tabs.isNotEmpty()) {
                            Button(sessions(tabs.size), onClick = { onScreen(Screen.Sessions) }, kind = ButtonKind.Ghost)
                        }
                        ToolsMenu(onScreen)
                    }
                    HostList(state, onConnect, onEdit, Modifier.weight(1f), onOpenFiles = onOpenFiles)
                }
                Fab(Icons.Default.Add, "Nuovo host", onClick = { onEdit(null) }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Host", onClick = onBackToHosts)
                if (screen == Screen.Sessions) {
                    SessionTabs(state, onConnect, Modifier.weight(1f))
                    SessionTools(state, wide = false, onManageSnippets = { onScreen(Screen.Snippets) })
                } else {
                    Text(screen.title, style = Carta.type.heading, modifier = Modifier.weight(1f))
                }
            }
            Divider()
            ScreenBody(state, screen, showExtraKeys = true, split = false, Modifier.weight(1f).fillMaxWidth(), onTour)
        }
    }
}

@Composable
private fun ScreenBody(state: AppState, screen: Screen, showExtraKeys: Boolean, split: Boolean, modifier: Modifier, onTour: () -> Unit) {
    when (screen) {
        Screen.Sessions -> SessionContent(state, showExtraKeys, split, modifier)
        Screen.Keys -> KeysScreen(state, modifier)
        Screen.Identities -> IdentitiesScreen(state, modifier)
        Screen.Snippets -> SnippetsScreen(state, modifier)
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
            listOf(Screen.Keys, Screen.Identities, Screen.Snippets, Screen.Import, Screen.Settings).forEach { target ->
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
        Text("Segreti nel vault cifrato", style = Carta.type.label, color = Carta.colors.textDim, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

private fun sessions(count: Int) = if (count == 1) "1 sessione" else "$count sessioni"
