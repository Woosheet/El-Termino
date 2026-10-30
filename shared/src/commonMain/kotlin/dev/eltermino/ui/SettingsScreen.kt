package dev.eltermino.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppCommand
import dev.eltermino.app.AppState
import dev.eltermino.app.Shortcuts
import dev.eltermino.ui.components.AppIconPainter
import dev.eltermino.ui.components.AppIconStyle
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.AppTheme
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.SYSTEM_THEME
import dev.eltermino.ui.theme.Themes
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(state: AppState, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val scope = rememberCoroutineScope()
    val chosen = workspace.ui.theme
    fun update(transform: (dev.eltermino.core.data.UiSettings) -> dev.eltermino.core.data.UiSettings) {
        scope.launch { state.services.workspace.update { it.copy(ui = transform(it.ui)) } }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        ScreenTitle("Impostazioni")
        Hint("Valgono per questo dispositivo e si salvano da sole.")
        SettingsSection("Tema", "Colori dell'app e del terminale insieme. \"Segui il sistema\" usa Carta di giorno e Notte quando il sistema è in modalità scura.") {
            val ids = listOf(SYSTEM_THEME) + Themes.all.map { it.id }
            ids.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { id ->
                        val theme = Themes.all.firstOrNull { it.id == id }
                        ThemeCard(theme?.name ?: "Segui il sistema", selected = chosen == id, onClick = { update { it.copy(theme = id) } }, modifier = Modifier.weight(1f)) {
                            if (theme == null) SystemPreview() else ThemePreview(theme)
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        SettingsSection("Icona", "L'icona dell'app nel Dock, nella barra delle applicazioni e nella finestra.") {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIconStyle.entries.forEach { style ->
                    ThemeCard(style.label, selected = AppIconStyle.of(workspace.ui.appIcon) == style, onClick = { update { it.copy(appIcon = style.id) } }, modifier = Modifier.weight(1f)) {
                        Image(AppIconPainter(style), contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                }
                Box(Modifier.weight(1f))
            }
        }
        SettingsSection("Avvisi", "Un messaggio in basso a destra compare sempre. La notifica del sistema serve quando sei in un'altra finestra.") {
            Checkbox(
                workspace.ui.notifyTransfers,
                { on -> update { it.copy(notifyTransfers = on) } },
                "Notifica quando un trasferimento finisce o non riesce",
            )
        }
        if (state.services.recordings != null) RecordingSettings(
            workspace.ui.recordSessions,
            workspace.ui.recordingDays,
            onRecord = { on -> update { it.copy(recordSessions = on) } },
            onDays = { days -> update { it.copy(recordingDays = days) } },
        )
        if (state.services.platform.biometricAvailable) SettingsSection("Blocco", "Protegge lo schermo, non il vault: sessioni e trasferimenti continuano sotto il blocco.") {
            Checkbox(workspace.ui.biometricLock, { on -> update { it.copy(biometricLock = on) } }, "Chiedi impronta o password del dispositivo per aprire l'app")
            if (workspace.ui.biometricLock) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 15, 30).forEach { minutes ->
                    dev.eltermino.ui.components.Button(
                        "$minutes min",
                        onClick = { update { it.copy(lockAfterMinutes = minutes) } },
                        kind = if (workspace.ui.lockAfterMinutes == minutes) dev.eltermino.ui.components.ButtonKind.Primary else dev.eltermino.ui.components.ButtonKind.Ghost,
                    )
                }
            }
        }
        TrustedCasSection(state)
        BackupSection(state)
        UpdatesSection(state, workspace.ui.checkUpdates) { on -> update { it.copy(checkUpdates = on) } }
        if (state.services.platform.isDesktop) ShortcutsSection(state.services.platform.isMacOs)
        extra()
    }
}

/** The app's shortcuts, and on macOS the terminal's line editing, as a list to look up. */
@Composable
private fun ShortcutsSection(macOs: Boolean) {
    val shortcuts = Shortcuts.all(macOs).associate { (command, keys) -> command to keys.joinToString(" · ") { Shortcuts.label(it, macOs) } }
    val rows = buildList {
        add("Nuova scheda" to shortcuts.getValue(AppCommand.NewTab))
        add("Chiudi il riquadro o la scheda" to shortcuts.getValue(AppCommand.Close))
        add("Scheda successiva" to shortcuts.getValue(AppCommand.NextTab))
        add("Scheda precedente" to shortcuts.getValue(AppCommand.PreviousTab))
        add("Vai alla scheda 1–8, l'ultima con 9" to "${Shortcuts.tabLabel(1, macOs)} … ${Shortcuts.tabLabel(9, macOs)}")
        add("Nuovo host" to shortcuts.getValue(AppCommand.NewHost))
        add("Mostra o nascondi la barra laterale" to shortcuts.getValue(AppCommand.ToggleSidebar))
        add("Impostazioni" to shortcuts.getValue(AppCommand.Settings))
        if (macOs) {
            add("Terminale: cancella la riga" to "⌘⌫")
            add("Terminale: cancella fino alla fine della riga" to "⌘⌦")
            add("Terminale: inizio e fine della riga" to "⌘← · ⌘→")
            add("Terminale: parola precedente e successiva" to "⌥← · ⌥→")
            add("Terminale: cancella la parola" to "⌥⌫")
            add("Terminale: copia e incolla" to "⌘C · ⌘V")
        } else {
            add("Terminale: copia e incolla" to "Ctrl+Shift+C · Ctrl+Shift+V")
        }
    }
    SettingsSection(
        "Scorciatoie da tastiera",
        if (macOs) "Con ⌘, che la shell non riceve mai. Le trovi anche nei menu File, Vista e Finestra."
        else "Con Ctrl+Maiuscole, perché Ctrl e una lettera restano alla shell.",
    ) {
        rows.forEach { (what, keys) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(what, modifier = Modifier.weight(1f))
                Text(keys, style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

/** One area of the settings: its name and what it does on the left, its controls on the right. */
@Composable
internal fun SettingsSection(title: String, description: String, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 720.dp
        Column {
            Divider(Modifier.padding(vertical = 24.dp))
            if (wide) {
                Row {
                    Column(Modifier.width(240.dp).padding(end = 32.dp)) {
                        Text(title, style = Carta.type.heading)
                        Text(description, style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(top = 4.dp))
                    }
                    Column(Modifier.weight(1f)) { content() }
                }
            } else {
                Text(title, style = Carta.type.heading)
                Text(description, style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                content()
            }
        }
    }
}

@Composable
private fun ThemeCard(name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, preview: @Composable () -> Unit) {
    val colors = Carta.colors
    val shape = RoundedCornerShape(8.dp)
    Pressable(onClick = onClick, modifier = modifier, shape = shape, border = if (selected) null else colors.line) {
        Column(
            Modifier.fillMaxWidth().then(if (selected) Modifier.border(2.dp, colors.accent, shape) else Modifier).padding(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(6.dp))) { preview() }
            Text(name, style = Carta.type.small, color = if (selected) colors.accent else colors.text, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
    }
}

/** A tiny window in the theme's colours: sidebar, tab bar and a terminal with a prompt. */
@Composable
private fun ThemePreview(theme: AppTheme) {
    val c = theme.colors
    val t = theme.terminal
    Row(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.width(34.dp).fillMaxHeight().background(c.chrome).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { i -> Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(2.dp)).background(if (i == 1) c.accent else c.lineStrong)) }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(16.dp).background(c.chrome).padding(horizontal = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(width = 26.dp, height = 8.dp).clip(RoundedCornerShape(2.dp)).background(c.background))
                Box(Modifier.padding(start = 4.dp).size(6.dp).clip(CircleShape).background(c.online))
            }
            Column(Modifier.fillMaxSize().background(t.background).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Bar(t.ansi[2], 24)
                    Bar(t.ansi[4], 14)
                    Bar(t.foreground, 18)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Bar(t.ansi[4], 12)
                    Bar(t.ansi[6], 16)
                    Bar(t.ansi[3], 10)
                    Bar(t.ansi[1], 8)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Bar(t.ansi[2], 24)
                    Bar(t.cursor, 6)
                }
            }
        }
    }
}

@Composable
private fun Bar(color: androidx.compose.ui.graphics.Color, width: Int) {
    Box(Modifier.size(width = width.dp, height = 5.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

/** Carta and Notte side by side. */
@Composable
private fun SystemPreview() {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { ThemePreview(Themes.Carta) }
        Box(Modifier.weight(1f).fillMaxHeight()) { ThemePreview(Themes.Notte) }
    }
}
