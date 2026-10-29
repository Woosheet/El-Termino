package dev.eltermino.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.SnippetTarget
import dev.eltermino.app.fillSnippet
import dev.eltermino.app.snippetVariables
import dev.eltermino.app.titles
import dev.eltermino.core.data.Snippet
import dev.eltermino.core.data.newId
import dev.eltermino.ui.components.Badge
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Menu
import dev.eltermino.ui.components.MenuDivider
import dev.eltermino.ui.components.MenuItem
import dev.eltermino.ui.components.MenuLabel
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.RadioOption
import dev.eltermino.ui.components.SearchField
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

@Composable
internal fun SnippetsScreen(state: AppState, modifier: Modifier = Modifier) {
    val workspace by state.services.workspace.workspace.collectAsState()
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var running by remember { mutableStateOf<Snippet?>(null) }
    var deleting by remember { mutableStateOf<Snippet?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Snippet", Modifier.weight(1f))
            Button("Nuovo snippet", onClick = { editing = Snippet(newId(), "", "") })
        }
        Hint("Comandi salvati da lanciare nel terminale, anche su più host insieme. Scrivi {{nome}} dove il comando cambia: te lo chiedo ogni volta.")
        if (workspace.snippets.isEmpty()) {
            EmptyState("Nessuno snippet", "Per esempio: sudo systemctl restart {{servizio}}")
        }
        LazyColumn {
            items(workspace.snippets.sortedBy { it.name.lowercase() }, key = { it.id }) { snippet ->
                SnippetRow(
                    snippet = snippet,
                    onRun = { running = snippet },
                    onEdit = { editing = snippet },
                    onDuplicate = { scope.launch { state.actions.saveSnippet(snippet.copy(id = newId(), name = "${snippet.name} (copia)")) } },
                    onDelete = { deleting = snippet },
                )
                Divider()
            }
        }
    }

    editing?.let { snippet -> SnippetEditorDialog(state, snippet) { editing = null } }
    running?.let { snippet -> SnippetRunDialog(state, snippet) { running = null } }
    deleting?.let { snippet ->
        Dialog(
            onDismiss = { deleting = null },
            title = "Eliminare ${snippet.name}?",
            actions = {
                Button("Annulla", onClick = { deleting = null }, kind = ButtonKind.Ghost)
                Button("Elimina", kind = ButtonKind.Danger, onClick = {
                    deleting = null
                    scope.launch { state.actions.deleteSnippet(snippet.id) }
                })
            },
        ) {
            Text(snippet.command, fontFamily = Carta.monospace, style = Carta.type.small)
        }
    }
}

@Composable
private fun SnippetRow(snippet: Snippet, onRun: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Pressable(onClick = onRun, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(snippet.name, style = Carta.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!snippet.run) Box(Modifier.padding(start = 8.dp)) { Badge("senza Invio") }
                }
                Text(snippet.command, fontFamily = Carta.monospace, style = Carta.type.small, color = Carta.colors.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(Icons.Default.MoreVert, "Azioni per ${snippet.name}", onClick = { menu = true })
                Menu(expanded = menu, onDismiss = { menu = false }, alignEnd = true) {
                    MenuItem("Esegui…", onClick = { menu = false; onRun() })
                    MenuItem("Modifica", onClick = { menu = false; onEdit() })
                    MenuItem("Duplica", onClick = { menu = false; onDuplicate() })
                    MenuItem("Elimina", onClick = { menu = false; onDelete() }, danger = true)
                }
            }
        }
    }
}

@Composable
internal fun SnippetEditorDialog(state: AppState, initial: Snippet, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var command by remember { mutableStateOf(initial.command) }
    var run by remember { mutableStateOf(initial.run) }
    var submitted by remember { mutableStateOf(false) }
    val variables = snippetVariables(command)
    val isNew = state.services.workspace.workspace.value.snippet(initial.id) == null

    Dialog(
        onDismiss = onDismiss,
        title = if (isNew) "Nuovo snippet" else "Modifica snippet",
        width = 560.dp,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Salva", onClick = {
                submitted = true
                if (command.isBlank()) return@Button
                scope.launch {
                    state.actions.saveSnippet(initial.copy(name = name.trim().ifBlank { command.lineSequence().first().take(40) }, command = command.trimEnd(), run = run))
                    onDismiss()
                }
            })
        },
    ) {
        Field("Nome", name, { name = it }, placeholder = "Riavvia nginx")
        Field(
            "Comando", command, { command = it },
            singleLine = false,
            monospace = true,
            placeholder = "sudo systemctl restart {{servizio}}",
            error = if (submitted && command.isBlank()) "Serve un comando" else null,
        )
        if (variables.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Text("Variabili:", style = Carta.type.small, color = Carta.colors.textDim)
                variables.forEach { Box(Modifier.padding(start = 6.dp)) { Badge(it) } }
            }
        }
        Checkbox(run, { run = it }, "Premi Invio dopo aver inserito il comando")
    }
}

private enum class RunWhere { Focused, Tabs, Hosts }

/** Asks the variables and where to run, then runs. */
@Composable
internal fun SnippetRunDialog(state: AppState, snippet: Snippet, onDismiss: () -> Unit) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    val selectedId by state.sessions.selectedId.collectAsState()
    val broadcast by state.sessions.broadcast.collectAsState()
    val titles = remember(tabs) { tabs.titles() }
    val focused = tabs.firstOrNull { it.id == selectedId }
    val variables = remember(snippet) { snippetVariables(snippet.command) }
    val values = remember { mutableStateMapOf<String, String>() }
    var where by remember { mutableStateOf(if (focused != null) RunWhere.Focused else RunWhere.Hosts) }
    var chosenTabs by remember { mutableStateOf(tabs.map { it.id }.toSet()) }
    var chosenHosts by remember { mutableStateOf(emptySet<String>()) }
    var hostQuery by remember { mutableStateOf("") }
    val command = fillSnippet(snippet.command, values)
    val complete = variables.all { !values[it].isNullOrEmpty() }
    val ready = complete && when (where) {
        RunWhere.Focused -> focused != null
        RunWhere.Tabs -> chosenTabs.isNotEmpty()
        RunWhere.Hosts -> chosenHosts.isNotEmpty()
    }
    val run = {
        if (ready) {
            val target = when (where) {
                RunWhere.Focused -> SnippetTarget.Focused
                RunWhere.Tabs -> SnippetTarget.Tabs(chosenTabs)
                RunWhere.Hosts -> SnippetTarget.Hosts(workspace.hosts.map { it.id }.filter { it in chosenHosts })
            }
            state.sessions.runSnippet(command, snippet.run, target)
            onDismiss()
        }
    }

    Dialog(
        onDismiss = onDismiss,
        title = snippet.name,
        icon = CartaIcons.Snippet,
        width = 560.dp,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button(if (snippet.run) "Esegui" else "Inserisci", onClick = run, enabled = ready)
        },
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            variables.forEachIndexed { index, name ->
                Field(
                    name, values[name].orEmpty(), { values[name] = it },
                    monospace = true,
                    fieldModifier = if (index == variables.lastIndex) Modifier.onEnter(run) else Modifier,
                )
            }
            Text(command, fontFamily = Carta.monospace, style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(bottom = 8.dp))
            SectionLabel("Dove")
            if (focused != null) {
                RadioOption(
                    where == RunWhere.Focused, { where = RunWhere.Focused }, "Sessione attiva: ${titles[focused.id]}",
                    detail = if (focused.id in broadcast) "Con il broadcast attivo arriva a ${broadcast.size} sessioni" else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (tabs.size > 1) {
                RadioOption(where == RunWhere.Tabs, { where = RunWhere.Tabs }, "Sessioni aperte…", modifier = Modifier.fillMaxWidth())
                if (where == RunWhere.Tabs) {
                    Column(Modifier.padding(start = 26.dp)) {
                        tabs.forEach { tab ->
                            Checkbox(tab.id in chosenTabs, { on -> chosenTabs = if (on) chosenTabs + tab.id else chosenTabs - tab.id }, titles.getValue(tab.id))
                        }
                    }
                }
            }
            RadioOption(
                where == RunWhere.Hosts, { where = RunWhere.Hosts }, "Nuove sessioni su host…",
                detail = "Apre una sessione per host e lancia il comando appena è connessa",
                modifier = Modifier.fillMaxWidth(),
            )
            if (where == RunWhere.Hosts) {
                Column(Modifier.padding(start = 26.dp)) {
                    SearchField(hostQuery, { hostQuery = it }, "Cerca host")
                    val needle = hostQuery.trim().lowercase()
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        workspace.hosts
                            .filter { needle.isEmpty() || needle in it.label.lowercase() || needle in it.address.lowercase() }
                            .sortedBy { it.label.lowercase() }
                            .forEach { host ->
                                Checkbox(host.id in chosenHosts, { on -> chosenHosts = if (on) chosenHosts + host.id else chosenHosts - host.id }, "${host.label} · ${host.address}")
                            }
                    }
                }
            }
        }
    }
}

/** The snippet button in the tab bar: runs one in the focused session, or opens the list. */
@Composable
internal fun SnippetLauncher(state: AppState, onManage: () -> Unit) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val selectedId by state.sessions.selectedId.collectAsState()
    var open by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<Snippet?>(null) }
    Box {
        IconButton(CartaIcons.Snippet, "Snippet", onClick = { open = true })
        Menu(expanded = open, onDismiss = { open = false }, alignEnd = true) {
            if (workspace.snippets.isEmpty()) {
                MenuLabel("Nessuno snippet salvato")
            } else {
                MenuLabel(if (selectedId != null) "Esegui nella sessione attiva" else "Esegui")
                workspace.snippets.sortedBy { it.name.lowercase() }.take(12).forEach { snippet ->
                    MenuItem(snippet.name, detail = snippet.command.lineSequence().first(), onClick = {
                        open = false
                        // Nothing to ask: straight into the focused terminal, like typing it.
                        if (selectedId != null && snippetVariables(snippet.command).isEmpty()) {
                            state.sessions.runSnippet(snippet.command, snippet.run, SnippetTarget.Focused)
                        } else {
                            running = snippet
                        }
                    })
                }
            }
            MenuDivider()
            MenuItem("Gestisci snippet…", onClick = { open = false; onManage() })
        }
    }
    running?.let { snippet -> SnippetRunDialog(state, snippet) { running = null } }
}
