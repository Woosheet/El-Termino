package dev.eltermino.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.core.data.Host
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Fab
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Menu
import dev.eltermino.ui.components.MenuItem
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.CartaTheme

internal enum class Screen(val title: String) {
    Sessions("Sessioni"),
    Keys("Chiavi"),
    Identities("Identità"),
    Import("Importa"),
}

/** Host being edited: [host] null means a new one. */
private class Editing(val host: Host?)

@Composable
fun App(state: AppState) {
    CartaTheme {
        var screen by remember { mutableStateOf(Screen.Sessions) }
        var editing by remember { mutableStateOf<Editing?>(null) }
        // On a phone the host list is its own screen, in front of the sessions.
        var showHostsOnPhone by remember { mutableStateOf(true) }

        val connect: (Host) -> Unit = { host ->
            state.sessions.connect(host)
            screen = Screen.Sessions
            showHostsOnPhone = false
        }

        // Insets are zero on desktop; on Android they keep us clear of the system bars.
        BoxWithConstraints(Modifier.fillMaxSize().background(Carta.colors.background).safeDrawingPadding()) {
            if (maxWidth >= 720.dp) {
                DesktopLayout(state, screen, { screen = it }, connect, onEdit = { editing = Editing(it) })
            } else {
                PhoneLayout(
                    state = state,
                    screen = screen,
                    showHosts = showHostsOnPhone,
                    onScreen = { screen = it; showHostsOnPhone = false },
                    onBackToHosts = { showHostsOnPhone = true; screen = Screen.Sessions },
                    onConnect = connect,
                    onEdit = { editing = Editing(it) },
                )
            }
        }

        editing?.let { HostEditorDialog(state, it.host) { editing = null } }
        PromptDialogs(state.sessions.prompter)
    }
}

@Composable
private fun DesktopLayout(
    state: AppState,
    screen: Screen,
    onScreen: (Screen) -> Unit,
    onConnect: (Host) -> Unit,
    onEdit: (Host?) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(260.dp).fillMaxHeight().background(Carta.colors.chrome).padding(10.dp)) {
                Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Host", style = Carta.type.title, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    IconButton(Icons.Default.Add, "Nuovo host", onClick = { onEdit(null) }, tint = Carta.colors.accent)
                }
                HostList(state, onConnect, onEdit, Modifier.weight(1f))
                SectionLabel("Strumenti")
                ToolRow(Icons.Default.Lock, "Chiavi", screen == Screen.Keys) { onScreen(Screen.Keys) }
                ToolRow(Icons.Default.Person, "Identità", screen == Screen.Identities) { onScreen(Screen.Identities) }
                ToolRow(Icons.Default.Share, "Importa", screen == Screen.Import) { onScreen(Screen.Import) }
            }
            Divider(vertical = true)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (screen != Screen.Sessions) {
                        IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Torna alle sessioni", onClick = { onScreen(Screen.Sessions) })
                    }
                    SessionTabs(state, Modifier.weight(1f))
                }
                Divider()
                // Tablets get the wide layout but still need Esc, Ctrl and the arrows on screen.
                ScreenBody(state, screen, showExtraKeys = !state.services.platform.isDesktop, Modifier.weight(1f).fillMaxWidth())
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
    onEdit: (Host?) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (showHosts) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Host", style = Carta.type.title, modifier = Modifier.weight(1f).padding(start = 4.dp))
                        val tabs by state.sessions.tabs.collectAsState()
                        if (tabs.isNotEmpty()) {
                            Button(sessions(tabs.size), onClick = { onScreen(Screen.Sessions) }, kind = ButtonKind.Ghost)
                        }
                        ToolsMenu(onScreen)
                    }
                    HostList(state, onConnect, onEdit, Modifier.weight(1f))
                }
                Fab(Icons.Default.Add, "Nuovo host", onClick = { onEdit(null) }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Host", onClick = onBackToHosts)
                if (screen == Screen.Sessions) SessionTabs(state, Modifier.weight(1f))
                else Text(screen.title, style = Carta.type.heading, modifier = Modifier.weight(1f))
            }
            Divider()
            ScreenBody(state, screen, showExtraKeys = true, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun ScreenBody(state: AppState, screen: Screen, showExtraKeys: Boolean, modifier: Modifier) {
    when (screen) {
        Screen.Sessions -> SessionContent(state, showExtraKeys, modifier)
        Screen.Keys -> KeysScreen(state, modifier)
        Screen.Identities -> IdentitiesScreen(state, modifier)
        Screen.Import -> ImportScreen(state, modifier)
    }
}

@Composable
private fun ToolsMenu(onScreen: (Screen) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(Icons.Default.MoreVert, "Strumenti", onClick = { open = true })
        Menu(expanded = open, onDismiss = { open = false }, alignEnd = true) {
            listOf(Screen.Keys, Screen.Identities, Screen.Import).forEach { target ->
                MenuItem(target.title, onClick = { open = false; onScreen(target) })
            }
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Carta.colors
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        background = if (selected) colors.accentBackground else colors.chrome,
        hover = if (selected) colors.accentBackground else colors.hover,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.accent else colors.textDim, size = 18.dp, modifier = Modifier.padding(end = 10.dp))
            Text(label, color = if (selected) colors.accent else colors.text)
        }
    }
}

@Composable
private fun StatusBar(state: AppState) {
    val connected by state.sessions.connectedHostIds.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    Row(
        Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            when (connected.size) {
                0 -> "Nessuna connessione attiva"
                1 -> "1 host connesso · ${sessions(tabs.size)}"
                else -> "${connected.size} host connessi · ${sessions(tabs.size)}"
            },
            style = Carta.type.label, color = Carta.colors.textDim,
        )
        Text("Segreti nel vault cifrato", style = Carta.type.label, color = Carta.colors.textDim)
    }
}

private fun sessions(count: Int) = if (count == 1) "1 sessione" else "$count sessioni"
