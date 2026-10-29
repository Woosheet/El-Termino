package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import dev.eltermino.app.CollectionRun
import dev.eltermino.app.HttpOutcome
import dev.eltermino.app.HttpRequestException
import dev.eltermino.app.RequestsTab
import dev.eltermino.app.RunCell
import dev.eltermino.app.describe
import dev.eltermino.app.parseCurl
import dev.eltermino.app.parsePostman
import dev.eltermino.app.variables
import dev.eltermino.core.data.Host
import dev.eltermino.core.data.HttpCollection
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.data.groupPath
import dev.eltermino.core.data.newId
import dev.eltermino.ui.components.HoppingSombrero
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.DialogTone
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Picker
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.RadioOption
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

@Composable
internal fun RequestsDialogs(state: AppState, tab: RequestsTab, dialog: RequestsDialog, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    when (dialog) {
        RequestsDialog.NewCollection -> CollectionNameDialog("Nuova collezione", "", "Crea", onDismiss) { name ->
            // With its first request in the same write, so the two can't race.
            val request = newRequest()
            scope.launch { state.actions.saveCollection(HttpCollection(newId(), name, listOf(request))) }
            tab.selected.value = request.id
        }
        is RequestsDialog.Rename -> CollectionNameDialog("Rinomina la collezione", dialog.collection.name, "Rinomina", onDismiss) { name ->
            scope.launch { state.actions.saveCollection(dialog.collection.copy(name = name)) }
        }
        is RequestsDialog.DeleteCollection -> ConfirmDelete(
            "Eliminare ${dialog.collection.name}?",
            "Le sue ${dialog.collection.requests.size} richieste e i loro segreti nel vault spariscono.",
            onDismiss,
        ) {
            if (dialog.collection.requests.any { it.id == tab.selected.value }) tab.selected.value = null
            scope.launch { state.actions.deleteCollection(dialog.collection.id) }
        }
        is RequestsDialog.DeleteRequest -> ConfirmDelete("Eliminare ${dialog.request.name}?", "${dialog.request.method} ${dialog.request.url}", onDismiss) {
            tab.selected.value = null
            scope.launch { state.actions.deleteRequest(dialog.collection.id, dialog.request.id) }
        }
        is RequestsDialog.Variables -> VariablesDialog(state, tab, dialog.collection, onDismiss)
        is RequestsDialog.Run -> RunDialog(state, tab, dialog.collection, onDismiss)
        RequestsDialog.Import -> ImportDialog(state, tab, onDismiss)
    }
}

@Composable
private fun CollectionNameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    Dialog(
        onDismiss = onDismiss,
        title = title,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button(confirm, enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()); onDismiss() })
        },
    ) {
        Field("Nome", name, { name = it }, placeholder = "Per esempio: API ordini")
    }
}

@Composable
private fun ConfirmDelete(title: String, detail: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(
        onDismiss = onDismiss,
        title = title,
        tone = DialogTone.Danger,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Elimina", kind = ButtonKind.Danger, onClick = { onConfirm(); onDismiss() })
        },
    ) {
        Text(detail, style = Carta.type.small, color = Carta.colors.textDim)
    }
}

/** The collection's defaults next to one host's own values; the host's value wins when it's set. */
@Composable
private fun VariablesDialog(state: AppState, tab: RequestsTab, collection: HttpCollection, onDismiss: () -> Unit) {
    val workspace = state.services.workspace.workspace.value
    val scope = rememberCoroutineScope()
    val used = collection.requests.flatMap { it.variables() }
    var names by remember { mutableStateOf((used + collection.variables.keys + collection.hostVariables.values.flatMap { it.keys }).distinct().sorted()) }
    val defaults = remember { mutableStateMapOf<String, String>().apply { putAll(collection.variables) } }
    val perHost = remember { mutableStateMapOf<String, Map<String, String>>().apply { putAll(collection.hostVariables) } }
    var host by remember { mutableStateOf(workspace.host(tab.hostId) ?: workspace.hosts.firstOrNull()) }
    var newName by remember { mutableStateOf("") }

    Dialog(
        onDismiss = onDismiss,
        title = "Variabili di ${collection.name}",
        width = 640.dp,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Salva", onClick = {
                val cleaned = perHost.mapValues { (_, values) -> values.filterValues { it.isNotEmpty() } }.filterValues { it.isNotEmpty() }
                scope.launch { state.actions.saveCollection(collection.copy(variables = defaults.filterValues { it.isNotEmpty() }, hostVariables = cleaned)) }
                onDismiss()
            })
        },
    ) {
        Text("Scrivi {{nome}} nell'indirizzo, negli header, nel corpo o nel token. Il valore dell'host vince su quello predefinito.", style = Carta.type.small, color = Carta.colors.textDim)
        if (workspace.hosts.isNotEmpty()) {
            Picker("Valori per l'host", workspace.hosts, host ?: workspace.hosts.first(), { it.label }, { host = it }, Modifier.width(280.dp).padding(top = 12.dp))
        }
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text("Nome", style = Carta.type.label, color = Carta.colors.textDim, modifier = Modifier.width(140.dp))
                Text("Predefinito", style = Carta.type.label, color = Carta.colors.textDim, modifier = Modifier.weight(1f))
                Text("Su ${host?.label ?: "—"}", style = Carta.type.label, color = Carta.colors.textDim, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            names.forEach { name ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(name, fontFamily = Carta.monospace, modifier = Modifier.width(140.dp).padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Field(null, defaults[name].orEmpty(), { defaults[name] = it }, Modifier.weight(1f), monospace = true)
                    val current = host
                    if (current != null) {
                        val values = perHost[current.id].orEmpty()
                        Field(null, values[name].orEmpty(), { perHost[current.id] = values + (name to it) }, Modifier.weight(1f).padding(start = 8.dp), monospace = true, placeholder = defaults[name])
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Field("Nuova variabile", newName, { newName = it.filter { c -> c.isLetterOrDigit() || c in "_-." } }, Modifier.width(200.dp), monospace = true)
            Button("Aggiungi", enabled = newName.isNotBlank() && newName !in names, kind = ButtonKind.Secondary, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp), onClick = {
                names = (names + newName).sorted()
                newName = ""
            })
        }
    }
}

/** Hosts in [groupId] and in the groups under it. */
private fun Workspace.hostsUnder(groupId: String): List<Host> {
    val ids = mutableSetOf(groupId)
    while (true) {
        val more = groups.filter { it.parentId in ids && it.id !in ids }.map { it.id }
        if (more.isEmpty()) break
        ids += more
    }
    return hosts.filter { it.groupId in ids }.sortedBy { it.label.lowercase() }
}

/** Picks where to run [collection], then shows the host × request table as answers arrive. */
@Composable
private fun RunDialog(state: AppState, tab: RequestsTab, collection: HttpCollection, onDismiss: () -> Unit) {
    val workspace = state.services.workspace.workspace.value
    val here = workspace.host(tab.hostId)
    val targets = buildList {
        here?.let { add(it.label to listOf(it)) }
        workspace.groups.sortedBy { workspace.groupPathLabel(it.id).lowercase() }.forEach { group ->
            val hosts = workspace.hostsUnder(group.id)
            if (hosts.isNotEmpty()) add("Gruppo ${workspace.groupPathLabel(group.id)} (${hosts.size} host)" to hosts)
        }
    }
    var target by remember { mutableStateOf(targets.firstOrNull()) }
    var run by remember { mutableStateOf<CollectionRun?>(null) }
    DisposableEffect(Unit) { onDispose { run?.cancel() } }

    val current = run
    if (current == null) {
        Dialog(
            onDismiss = onDismiss,
            title = "Esegui ${collection.name}",
            actions = {
                Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
                Button("Esegui", enabled = target != null && collection.requests.isNotEmpty(), onClick = { target?.let { run = state.requests.run(collection, it.second) } })
            },
        ) {
            Text(
                if (collection.requests.isEmpty()) "La collezione non ha richieste." else "Le ${collection.requests.size} richieste partono in ordine su ogni host, gli host tutti insieme.",
                style = Carta.type.small,
                color = Carta.colors.textDim,
            )
            Column(Modifier.padding(top = 8.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                targets.forEach { option -> RadioOption(target == option, { target = option }, option.first) }
            }
        }
    } else {
        RunResults(current, onDismiss)
    }
}

private fun Workspace.groupPathLabel(groupId: String) = groupPath(groupId).joinToString(" / ")

@Composable
private fun RunResults(run: CollectionRun, onDismiss: () -> Unit) {
    val cells by run.cells.collectAsState()
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    val done = cells.values.count { it is RunCell.Done }
    val passed = cells.values.count { (it as? RunCell.Done)?.outcome.let { o -> o is HttpOutcome.Answered && o.passed } }
    val requests = run.collection.requests
    val colors = Carta.colors

    Dialog(
        onDismiss = onDismiss,
        title = "Esito di ${run.collection.name}",
        width = 900.dp,
        actions = { Button("Chiudi", onClick = onDismiss) },
    ) {
        Text(
            if (done < cells.size) "In corso: $done di ${cells.size}" else "$passed di ${cells.size} superate",
            style = Carta.type.small,
            color = colors.textDim,
        )
        Column(Modifier.padding(top = 12.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Row {
                Text("Host", style = Carta.type.label, color = colors.textDim, modifier = Modifier.width(160.dp))
                requests.forEach { Text(it.name, style = Carta.type.label, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp).padding(end = 8.dp)) }
            }
            run.hosts.forEach { host ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(host.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(160.dp))
                    requests.forEach { request ->
                        val key = host.id to request.id
                        Box(Modifier.width(140.dp).padding(end = 8.dp)) { RunCellView(cells[key] ?: RunCell.Waiting, selected = detail == key) { detail = key } }
                    }
                }
            }
        }
        detail?.let { key ->
            val host = run.hosts.firstOrNull { it.id == key.first }
            val request = requests.firstOrNull { it.id == key.second }
            val outcome = (cells[key] as? RunCell.Done)?.outcome
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(colors.chrome, RoundedCornerShape(6.dp)).padding(12.dp)) {
                Text("${request?.name} su ${host?.label}", style = Carta.type.bodyStrong)
                when (outcome) {
                    null -> Text("Non ancora arrivata", style = Carta.type.small, color = colors.textDim)
                    is HttpOutcome.Failed -> Text(outcome.message, style = Carta.type.small, color = colors.danger)
                    is HttpOutcome.Answered -> {
                        Text("${outcome.response.status} ${outcome.response.reason} in ${outcome.response.timings.totalMs} ms", style = Carta.type.small)
                        if (outcome.checks.isEmpty()) Text("Nessuna verifica: conta solo che risponda.", style = Carta.type.small, color = colors.textDim)
                        outcome.checks.forEach { result ->
                            Text(
                                (if (result.passed) "✓ " else "✗ ") + result.check.describe() + if (result.passed) "" else " (${result.actual})",
                                style = Carta.type.small,
                                color = if (result.passed) colors.text else colors.danger,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunCellView(cell: RunCell, selected: Boolean, onClick: () -> Unit) {
    val colors = Carta.colors
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth(), border = if (selected) colors.accent else colors.line, shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (cell) {
                RunCell.Waiting -> Text("In attesa", style = Carta.type.small, color = colors.textDim)
                RunCell.Running -> {
                    HoppingSombrero(height = 18.dp)
                    Text("In corso", style = Carta.type.small, color = colors.textDim, modifier = Modifier.padding(start = 6.dp))
                }
                is RunCell.Done -> when (val outcome = cell.outcome) {
                    is HttpOutcome.Failed -> Text("✗ errore", style = Carta.type.small, color = colors.danger)
                    is HttpOutcome.Answered -> Text(
                        (if (outcome.passed) "✓ " else "✗ ") + "${outcome.response.status} · ${outcome.response.timings.totalMs} ms",
                        style = Carta.type.small,
                        color = if (outcome.passed) colors.online else colors.danger,
                    )
                }
            }
        }
    }
}

/** A Postman v2.1 collection (pasted or from a file) or a `curl` command. */
@Composable
private fun ImportDialog(state: AppState, tab: RequestsTab, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val collections = state.services.workspace.workspace.value.httpCollections
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val isCurl = text.trimStart().startsWith("curl")
    val newOne = HttpCollection("", "Una nuova collezione")
    var into by remember { mutableStateOf(collections.firstOrNull() ?: newOne) }

    fun import() {
        scope.launch {
            try {
                if (isCurl) {
                    val imported = parseCurl(text)
                    val target = if (into.id.isEmpty()) HttpCollection(newId(), "Importate da curl").also { state.actions.saveCollection(it) } else into
                    state.actions.saveRequest(target.id, imported.request, imported.secret)
                    tab.selected.value = imported.request.id
                } else {
                    val collection = state.actions.importCollection(parsePostman(text))
                    tab.selected.value = collection.requests.firstOrNull()?.id
                }
                onDismiss()
            } catch (e: HttpRequestException) {
                error = e.message
            }
        }
    }

    Dialog(
        onDismiss = onDismiss,
        title = "Importa richieste",
        width = 620.dp,
        actions = {
            Button("Scegli un file…", kind = ButtonKind.Ghost, onClick = {
                scope.launch {
                    state.services.platform.pickFile("Collezione di Postman (JSON)")?.let {
                        text = it.bytes.decodeToString()
                        error = null
                    }
                }
            })
            Box(Modifier.weight(1f))
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Importa", enabled = text.isNotBlank(), onClick = ::import)
        },
    ) {
        Text(
            "Incolla una collezione di Postman (esportata in formato v2.1) oppure un comando curl. Token e password finiscono nel vault.",
            style = Carta.type.small,
            color = Carta.colors.textDim,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Field(null, text, { text = it; error = null }, singleLine = false, monospace = true, placeholder = "curl -H 'Accept: application/json' http://localhost:8080/health", error = error, fieldModifier = Modifier.heightIn(max = 280.dp))
        if (isCurl) {
            Picker("Aggiungi a", collections + newOne, into, { it.name }, { into = it }, Modifier.width(280.dp))
        }
    }
}
