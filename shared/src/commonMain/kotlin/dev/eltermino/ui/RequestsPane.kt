package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.AttachedFile
import dev.eltermino.app.HttpOutcome
import dev.eltermino.app.HttpRequestException
import dev.eltermino.app.LinkState
import dev.eltermino.app.RequestsTab
import dev.eltermino.app.curlCommand
import dev.eltermino.app.describe
import dev.eltermino.app.formatBytes
import dev.eltermino.app.formatFileTime
import dev.eltermino.app.prepare
import dev.eltermino.core.data.HttpAuth
import dev.eltermino.core.data.HttpCheck
import dev.eltermino.core.data.HttpCollection
import dev.eltermino.core.data.HttpHeader
import dev.eltermino.core.data.HttpPart
import dev.eltermino.core.data.HttpRequest
import dev.eltermino.core.data.newId
import dev.eltermino.ui.components.HoppingSombrero
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Menu
import dev.eltermino.ui.components.MenuDivider
import dev.eltermino.ui.components.MenuItem
import dev.eltermino.ui.components.Picker
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.RadioOption
import dev.eltermino.ui.components.SearchField
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

internal fun newRequest() = HttpRequest(newId(), "Nuova richiesta", url = "http://localhost:8080/")

/** Dialogs the Requests tab can open, one at a time. */
internal sealed interface RequestsDialog {
    data object NewCollection : RequestsDialog
    data object Import : RequestsDialog
    class Rename(val collection: HttpCollection) : RequestsDialog
    class DeleteCollection(val collection: HttpCollection) : RequestsDialog
    class DeleteRequest(val collection: HttpCollection, val request: HttpRequest) : RequestsDialog
    class Variables(val collection: HttpCollection) : RequestsDialog
    class Run(val collection: HttpCollection) : RequestsDialog
}

@Composable
internal fun RequestsPane(state: AppState, tab: RequestsTab, focused: Boolean) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val selectedId by tab.selected.collectAsState()
    val located = workspace.httpCollections.firstNotNullOfOrNull { c -> c.requests.firstOrNull { it.id == selectedId }?.let { c to it } }
    var dialog by remember { mutableStateOf<RequestsDialog?>(null) }
    val scope = rememberCoroutineScope()

    fun newRequest(collection: HttpCollection) {
        val request = newRequest()
        scope.launch { state.actions.saveRequest(collection.id, request) }
        tab.selected.value = request.id
    }

    Column(Modifier.fillMaxSize()) {
        LinkStrip(tab)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sidebar: @Composable (Modifier) -> Unit = { modifier ->
                CollectionList(state, tab, workspace.httpCollections, selectedId, modifier, onDialog = { dialog = it }, onNewRequest = ::newRequest)
            }
            val compact = maxWidth < 760.dp
            val main: @Composable (Modifier) -> Unit = { modifier ->
                if (located == null) {
                    EmptyState(
                        if (workspace.httpCollections.isEmpty()) "Nessuna richiesta" else "Scegli una richiesta",
                        "Le richieste partono da ${tab.label} dentro la connessione SSH: localhost è il server stesso, e niente va esposto in rete.",
                        modifier,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button("Nuova collezione", onClick = { dialog = RequestsDialog.NewCollection })
                            Button("Importa…", onClick = { dialog = RequestsDialog.Import }, kind = ButtonKind.Secondary)
                        }
                    }
                } else {
                    val (collection, request) = located
                    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                        RequestEditor(state, tab, collection, request, compact, onDelete = { dialog = RequestsDialog.DeleteRequest(collection, request) })
                        Divider(Modifier.padding(vertical = 16.dp))
                        ResponseView(tab, request, compact)
                    }
                }
            }
            if (!compact) {
                Row(Modifier.fillMaxSize()) {
                    sidebar(Modifier.width(250.dp).fillMaxHeight())
                    Divider(vertical = true)
                    main(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    sidebar(Modifier.fillMaxWidth().height(180.dp))
                    Divider()
                    main(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }

    dialog?.let { current -> RequestsDialogs(state, tab, current) { dialog = null } }
}

/** A line over the tab while the host's link isn't up. */
@Composable
private fun LinkStrip(tab: RequestsTab) {
    val lease by tab.lease.collectAsState()
    val link = lease?.link
    val linkState by remember(link) { link?.state ?: flowOf(null) }.collectAsState(null)
    val text = when (val s = linkState) {
        is LinkState.Up -> return
        null, LinkState.Connecting -> "Connessione a ${tab.label}…"
        is LinkState.Retrying -> "Riconnessione a ${tab.label}: ${s.message}"
        is LinkState.Down -> "Non connesso a ${tab.label}: ${s.message}. Chiudi la scheda e riaprila per riprovare."
    }
    Row(Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (linkState !is LinkState.Down) HoppingSombrero(height = 20.dp)
        Text(text, style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun CollectionList(
    state: AppState,
    tab: RequestsTab,
    collections: List<HttpCollection>,
    selectedId: String?,
    modifier: Modifier,
    onDialog: (RequestsDialog) -> Unit,
    onNewRequest: (HttpCollection) -> Unit,
) {
    val sending by tab.sending.collectAsState()
    var addMenu by remember { mutableStateOf(false) }
    Column(modifier.background(Carta.colors.chrome)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Collezioni", style = Carta.type.label, color = Carta.colors.textDim, modifier = Modifier.weight(1f))
            Box {
                IconButton(Icons.Default.Add, "Nuova collezione o import", onClick = { addMenu = true })
                Menu(expanded = addMenu, onDismiss = { addMenu = false }, alignEnd = true) {
                    MenuItem("Nuova collezione", onClick = { addMenu = false; onDialog(RequestsDialog.NewCollection) })
                    MenuItem("Importa da Postman o curl…", icon = CartaIcons.Import, onClick = { addMenu = false; onDialog(RequestsDialog.Import) })
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
            collections.sortedBy { it.name.lowercase() }.forEach { collection ->
                CollectionRow(state, collection, onDialog, onNewRequest)
                collection.requests.forEach { request ->
                    val selected = request.id == selectedId
                    Pressable(
                        onClick = { tab.selected.value = request.id },
                        modifier = Modifier.fillMaxWidth(),
                        background = if (selected) Carta.colors.hover else androidx.compose.ui.graphics.Color.Transparent,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(request.method, style = Carta.type.caption, color = methodColor(request.method), fontFamily = Carta.monospace, modifier = Modifier.width(52.dp))
                            Text(request.name, style = Carta.type.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (request.id in sending) HoppingSombrero(height = 18.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(state: AppState, collection: HttpCollection, onDialog: (RequestsDialog) -> Unit, onNewRequest: (HttpCollection) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(collection.name, style = Carta.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box {
            IconButton(Icons.Default.MoreVert, "Azioni per ${collection.name}", onClick = { menu = true }, size = 28.dp, iconSize = 16.dp)
            Menu(expanded = menu, onDismiss = { menu = false }, alignEnd = true) {
                MenuItem("Nuova richiesta", onClick = { menu = false; onNewRequest(collection) })
                MenuItem("Variabili…", onClick = { menu = false; onDialog(RequestsDialog.Variables(collection)) })
                MenuItem("Esegui la collezione…", icon = CartaIcons.Play, onClick = { menu = false; onDialog(RequestsDialog.Run(collection)) })
                MenuDivider()
                MenuItem("Rinomina…", onClick = { menu = false; onDialog(RequestsDialog.Rename(collection)) })
                MenuItem("Elimina…", danger = true, onClick = { menu = false; onDialog(RequestsDialog.DeleteCollection(collection)) })
            }
        }
    }
    if (collection.requests.isEmpty()) {
        Text("Nessuna richiesta", style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
    }
}

@Composable
private fun methodColor(method: String) = when (method) {
    "GET" -> Carta.colors.online
    "DELETE" -> Carta.colors.danger
    else -> Carta.colors.accent
}

@Composable
private fun RequestEditor(state: AppState, tab: RequestsTab, collection: HttpCollection, request: HttpRequest, compact: Boolean, onDelete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val sending by tab.sending.collectAsState()
    var draft by remember(request.id) { mutableStateOf(request) }
    var section by remember { mutableStateOf(0) }
    var notice by remember(request.id) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    // Saved a moment after the last change, so typing doesn't rewrite the workspace on every key.
    LaunchedEffect(draft) {
        if (draft != request) {
            delay(600)
            state.actions.saveRequest(collection.id, draft)
        }
    }

    fun send() {
        scope.launch { state.actions.saveRequest(collection.id, draft) }
        state.requests.send(tab, collection, draft)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(null, draft.name, { draft = draft.copy(name = it) }, Modifier.weight(1f), placeholder = "Nome della richiesta")
        Box(Modifier.padding(start = 4.dp, bottom = 12.dp)) {
            IconButton(Icons.Default.MoreVert, "Azioni per la richiesta", onClick = { menu = true })
            Menu(expanded = menu, onDismiss = { menu = false }, alignEnd = true) {
                MenuItem("Copia come curl", onClick = {
                    menu = false
                    scope.launch {
                        notice = try {
                            clipboard.setText(AnnotatedString(curlCommand(prepare(draft, collection, tab.hostId) { state.services.vault.get(it) })))
                            "Comando curl copiato"
                        } catch (e: HttpRequestException) {
                            e.message
                        }
                    }
                })
                MenuItem("Duplica", onClick = {
                    menu = false
                    val copy = draft.copy(id = newId(), name = "${draft.name} (copia)", auth = draft.auth.copy(secretId = null))
                    scope.launch { state.actions.saveRequest(collection.id, copy) }
                    tab.selected.value = copy.id
                })
                MenuItem("Elimina…", danger = true, onClick = { menu = false; onDelete() })
            }
        }
    }
    val busy = request.id in sending
    val method: @Composable (Modifier) -> Unit = { Picker("Metodo", METHODS, draft.method, { m -> m }, { m -> draft = draft.copy(method = m) }, it) }
    val url: @Composable (Modifier) -> Unit = {
        Field("Indirizzo, come lo vede ${tab.label}", draft.url, { u -> draft = draft.copy(url = u) }, it, monospace = true, placeholder = "http://localhost:8080/health")
    }
    val sendButton: @Composable (Modifier) -> Unit = { Button(if (busy) "Invio…" else "Invia", onClick = ::send, enabled = !busy, icon = CartaIcons.Request, modifier = it) }
    if (compact) {
        url(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.Bottom) {
            method(Modifier.width(140.dp).padding(end = 8.dp))
            Box(Modifier.weight(1f))
            sendButton(Modifier.padding(bottom = 10.dp))
        }
    } else {
        Row(verticalAlignment = Alignment.Bottom) {
            method(Modifier.width(120.dp).padding(end = 8.dp))
            url(Modifier.weight(1f))
            sendButton(Modifier.padding(start = 8.dp, bottom = 12.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(draft.viaCurl, { draft = draft.copy(viaCurl = it) }, "Esegui con curl sul server")
        if (!compact) Text(
            if (draft.viaCurl) "Usa DNS, proxy e certificati della macchina" else "Passa dal tunnel SSH, senza installare niente sul server",
            style = Carta.type.small,
            color = Carta.colors.textDim,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    notice?.let { Text(it, style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(top = 4.dp)) }

    val enabledHeaders = draft.headers.count { it.enabled }
    SectionTabs(
        listOf(
            if (enabledHeaders > 0) "Header ($enabledHeaders)" else "Header",
            "Corpo",
            "Autenticazione",
            if (draft.checks.isNotEmpty()) "Verifiche (${draft.checks.size})" else "Verifiche",
        ),
        section,
    ) { section = it }
    when (section) {
        0 -> HeadersEditor(draft.headers) { draft = draft.copy(headers = it) }
        1 -> BodyEditor(draft, pickFile = { state.services.platform.pickAttachment("File da mandare nel form") }) { draft = it }
        2 -> AuthEditor(draft, onChange = { draft = it }) { secret ->
            scope.launch {
                draft = state.actions.saveRequest(collection.id, draft, secret)
                notice = if (secret.isEmpty()) "Segreto tolto dal vault" else "Salvato nel vault"
            }
        }
        else -> ChecksEditor(draft.checks) { draft = draft.copy(checks = it) }
    }
}

@Composable
private fun SectionTabs(titles: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    // Scrolls sideways when a phone is too narrow for all of them.
    Row(modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 12.dp)) {
        titles.forEachIndexed { index, title ->
            val active = index == selected
            Pressable(onClick = { onSelect(index) }, shape = RoundedCornerShape(6.dp)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = if (active) Carta.type.bodyStrong else Carta.type.body, color = if (active) Carta.colors.accent else Carta.colors.textDim)
                    Box(Modifier.padding(top = 4.dp).width(24.dp).height(2.dp).background(if (active) Carta.colors.accent else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun HeadersEditor(headers: List<HttpHeader>, onChange: (List<HttpHeader>) -> Unit) {
    fun update(index: Int, header: HttpHeader) = onChange(headers.mapIndexed { i, h -> if (i == index) header else h })
    headers.forEachIndexed { index, header ->
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(header.enabled, { update(index, header.copy(enabled = it)) }, "", Modifier.padding(top = 4.dp))
            Field(null, header.name, { update(index, header.copy(name = it)) }, Modifier.weight(1f).padding(end = 8.dp), placeholder = "Nome", monospace = true)
            Field(null, header.value, { update(index, header.copy(value = it)) }, Modifier.weight(1.6f), placeholder = "Valore, anche con {{variabili}}", monospace = true)
            IconButton(Icons.Default.Close, "Togli ${header.name}", onClick = { onChange(headers.filterIndexed { i, _ -> i != index }) }, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
    }
    Button("Aggiungi header", onClick = { onChange(headers + HttpHeader("", "")) }, kind = ButtonKind.Ghost, icon = Icons.Default.Add)
}

@Composable
private fun BodyEditor(draft: HttpRequest, pickFile: suspend () -> AttachedFile?, onChange: (HttpRequest) -> Unit) {
    val types = listOf("none" to "Nessuno", "json" to "JSON", "form" to "Form", "multipart" to "Multipart", "text" to "Testo")
    Row {
        types.forEach { (type, label) ->
            RadioOption(draft.bodyType == type, { onChange(draft.copy(bodyType = type)) }, label, Modifier.padding(end = 12.dp))
        }
    }
    if (draft.bodyType == "multipart") {
        PartsEditor(draft.parts, pickFile) { onChange(draft.copy(parts = it)) }
    } else if (draft.bodyType != "none") {
        Field(
            null,
            draft.body,
            { onChange(draft.copy(body = it)) },
            singleLine = false,
            monospace = true,
            placeholder = when (draft.bodyType) {
                "json" -> "{ \"nome\": \"{{nome}}\" }"
                "form" -> "nome=valore, uno per riga"
                else -> null
            },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Fields of a multipart form: text, or a file of this device read again at every send. */
@Composable
private fun PartsEditor(parts: List<HttpPart>, pickFile: suspend () -> AttachedFile?, onChange: (List<HttpPart>) -> Unit) {
    val scope = rememberCoroutineScope()
    fun update(index: Int, part: HttpPart) = onChange(parts.mapIndexed { i, p -> if (i == index) part else p })
    fun choose(index: Int?) {
        scope.launch {
            val file = pickFile() ?: return@launch
            if (index == null) onChange(parts + HttpPart(file.name.substringBeforeLast('.').ifEmpty { "file" }, file = file.reference, fileName = file.name))
            else update(index, parts[index].copy(file = file.reference, fileName = file.name))
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        parts.forEachIndexed { index, part ->
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(part.enabled, { update(index, part.copy(enabled = it)) }, "", Modifier.padding(top = 4.dp))
                Field(null, part.name, { update(index, part.copy(name = it)) }, Modifier.weight(1f).padding(end = 8.dp), placeholder = "Nome", monospace = true)
                if (part.file == null) {
                    Field(null, part.value, { update(index, part.copy(value = it)) }, Modifier.weight(1.6f), placeholder = "Valore", monospace = true)
                } else {
                    Pressable(onClick = { choose(index) }, modifier = Modifier.weight(1.6f).padding(bottom = 12.dp), border = Carta.colors.line) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(CartaIcons.Document, contentDescription = null, tint = Carta.colors.textDim, size = 16.dp)
                            Text(part.fileName ?: part.file.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                IconButton(Icons.Default.Close, "Togli ${part.name}", onClick = { onChange(parts.filterIndexed { i, _ -> i != index }) }, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
        }
        Row {
            Button("Aggiungi campo", onClick = { onChange(parts + HttpPart("")) }, kind = ButtonKind.Ghost, icon = Icons.Default.Add)
            Button("Aggiungi file…", onClick = { choose(null) }, kind = ButtonKind.Ghost, icon = CartaIcons.Upload)
        }
        Text("I file si rileggono a ogni invio: se lo sposti o lo cancelli, sceglilo di nuovo.", style = Carta.type.small, color = Carta.colors.textDim)
    }
}

@Composable
private fun AuthEditor(draft: HttpRequest, onChange: (HttpRequest) -> Unit, onSecret: (String) -> Unit) {
    val types = listOf("none" to "Nessuna", "bearer" to "Bearer token", "basic" to "Basic (utente e password)", "apikey" to "API key in un header")
    val auth = draft.auth
    fun set(value: HttpAuth) = onChange(draft.copy(auth = value))
    Picker("Tipo", types, types.first { it.first == auth.type }, { it.second }, { set(auth.copy(type = it.first)) }, Modifier.width(280.dp))
    if (auth.type == "none") return
    if (auth.type == "basic") Field("Utente", auth.username, { set(auth.copy(username = it)) }, Modifier.width(280.dp))
    if (auth.type == "apikey") Field("Header", auth.headerName, { set(auth.copy(headerName = it)) }, Modifier.width(280.dp), monospace = true)
    var secret by remember(draft.id) { mutableStateOf("") }
    val label = when (auth.type) {
        "bearer" -> "Token"
        "basic" -> "Password"
        else -> "Chiave"
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Field(label, secret, { secret = it }, Modifier.width(280.dp), secret = true, placeholder = if (auth.secretId != null) "Salvato nel vault" else null)
        Button("Salva nel vault", onClick = { onSecret(secret); secret = "" }, enabled = secret.isNotEmpty(), kind = ButtonKind.Secondary, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
        if (auth.secretId != null) {
            Button("Togli", onClick = { onSecret("") }, kind = ButtonKind.Ghost, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        }
    }
    Text("Resta cifrato nel vault, mai nel file delle impostazioni. Può contenere {{variabili}}.", style = Carta.type.small, color = Carta.colors.textDim)
}

@Composable
private fun ChecksEditor(checks: List<HttpCheck>, onChange: (List<HttpCheck>) -> Unit) {
    checks.forEachIndexed { index, check ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(check.describe(), modifier = Modifier.weight(1f))
            IconButton(Icons.Default.Close, "Togli la verifica", onClick = { onChange(checks.filterIndexed { i, _ -> i != index }) })
        }
    }
    if (checks.isEmpty()) {
        Text("Condizioni che la risposta deve rispettare. Servono per eseguire la collezione su un gruppo di host.", style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(bottom = 8.dp))
    }
    val kinds = listOf("status" to "Stato uguale a", "contains" to "Il corpo contiene", "json" to "Campo JSON uguale a", "time" to "Tempo sotto (ms)")
    var kind by remember { mutableStateOf(kinds.first()) }
    var path by remember { mutableStateOf("") }
    var expected by remember { mutableStateOf("") }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Picker("Nuova verifica", kinds, kind, { it.second }, { kind = it }, Modifier.width(200.dp).padding(end = 8.dp))
        if (kind.first == "json") Field("Campo", path, { path = it }, Modifier.weight(1f).padding(end = 8.dp), placeholder = "data.items[0].id", monospace = true)
        Field("Valore", expected, { expected = it }, Modifier.weight(1f), placeholder = if (kind.first == "status") "200" else null, numeric = kind.first in setOf("status", "time"))
        Button(
            "Aggiungi",
            onClick = {
                onChange(checks + HttpCheck(kind.first, path.trim(), expected.trim()))
                path = ""
                expected = ""
            },
            enabled = expected.isNotBlank() && (kind.first != "json" || path.isNotBlank()),
            kind = ButtonKind.Secondary,
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
        )
    }
}

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

/** Bodies past this many characters are cut in the view; copying still gives all of it. */
private const val SHOWN_CHARS = 200_000

@Composable
private fun ResponseView(tab: RequestsTab, request: HttpRequest, compact: Boolean) {
    val history by tab.history.collectAsState()
    val sending by tab.sending.collectAsState()
    val outcomes = history[request.id].orEmpty()
    var shown by remember(request.id) { mutableStateOf(0) }
    LaunchedEffect(outcomes.size) { shown = 0 }
    val outcome = outcomes.getOrNull(shown)
    val colors = Carta.colors

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Risposta", style = Carta.type.heading, modifier = Modifier.weight(1f))
        if (outcomes.size > 1) {
            Picker(
                "Risposte precedenti",
                outcomes.indices.toList(),
                shown,
                { i -> outcomes[i].let { o -> formatFileTime(o.atEpochSeconds) + " · " + if (o is HttpOutcome.Answered) "${o.response.status}" else "errore" } },
                { shown = it },
                Modifier.width(220.dp),
            )
        }
    }
    if (request.id in sending) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HoppingSombrero(height = 32.dp)
        Text("In attesa di risposta…", style = Carta.type.small, color = colors.textDim, modifier = Modifier.padding(start = 10.dp))
    }
    when (outcome) {
        null -> Text("Invia la richiesta per vedere qui stato, tempi, header e corpo.", style = Carta.type.small, color = colors.textDim, modifier = Modifier.padding(top = 8.dp))
        is HttpOutcome.Failed -> Text(outcome.message, color = colors.danger, modifier = Modifier.padding(top = 8.dp))
        is HttpOutcome.Answered -> {
            val response = outcome.response
            val timings = response.timings
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (response.status) {
                    in 200..299 -> colors.online
                    in 300..399 -> colors.accent
                    else -> colors.danger
                }
                Text("${response.status} ${response.reason}".trim(), style = Carta.type.bodyStrong, color = statusColor)
                Text(
                    listOfNotNull(
                        "${timings.totalMs} ms",
                        formatBytes(response.body.size.toLong()),
                        timings.tunnelMs?.let { "connessione $it ms" },
                        timings.firstByteMs?.let { "primo byte $it ms" },
                    ).joinToString("   "),
                    style = Carta.type.small,
                    color = colors.textDim,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            outcome.checks.forEach { result ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (result.passed) "✓" else "✗", color = if (result.passed) colors.online else colors.danger, style = Carta.type.bodyStrong, modifier = Modifier.width(20.dp))
                    Text(result.check.describe(), style = Carta.type.small)
                    if (!result.passed) Text("  (${result.actual})", style = Carta.type.small, color = colors.textDim)
                }
            }
            ResponseBody(outcome, compact)
        }
    }
}

@Composable
private fun ResponseBody(outcome: HttpOutcome.Answered, compact: Boolean) {
    val response = outcome.response
    var part by remember(outcome) { mutableStateOf(0) }
    var raw by remember(outcome) { mutableStateOf(false) }
    var query by remember(outcome) { mutableStateOf("") }
    val pretty = remember(outcome) { runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(response.text)) }.getOrNull() }
    val text = when {
        part == 1 -> response.headers.joinToString("\n") { (name, value) -> "$name: $value" }
        pretty != null && !raw -> pretty
        else -> response.text
    }
    val colors = Carta.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTabs(listOf("Corpo", "Header (${response.headers.size})"), part) { part = it }
        if (part == 0 && pretty != null) Checkbox(raw, { raw = it }, "Grezzo", Modifier.padding(start = 8.dp))
        if (!compact) {
            Box(Modifier.weight(1f))
            SearchField(query, { query = it }, "Cerca nella risposta", Modifier.width(240.dp).padding(top = 12.dp))
        }
    }
    if (compact) SearchField(query, { query = it }, "Cerca nella risposta")
    val matches = if (query.isEmpty()) 0 else Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).count()
    if (query.isNotEmpty()) Text(if (matches == 1) "1 risultato" else "$matches risultati", style = Carta.type.small, color = colors.textDim, modifier = Modifier.padding(bottom = 6.dp))
    val shown = text.take(SHOWN_CHARS)
    val highlighted = remember(shown, query, colors) {
        buildAnnotatedString {
            append(shown)
            if (query.isNotEmpty()) {
                Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(shown).forEach { match ->
                    addStyle(SpanStyle(background = colors.accentBackground, color = colors.accent), match.range.first, match.range.last + 1)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().background(colors.chrome, RoundedCornerShape(6.dp)).padding(12.dp)) {
        if (text.isEmpty()) {
            Text("Vuoto", style = Carta.type.small, color = colors.textDim)
        } else {
            SelectionContainer {
                BasicText(highlighted, style = Carta.type.small.copy(color = colors.text, fontFamily = Carta.monospace))
            }
        }
    }
    if (text.length > SHOWN_CHARS) {
        Text("Mostrati i primi ${SHOWN_CHARS / 1000} mila caratteri di ${text.length}.", style = Carta.type.small, color = colors.textDim, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The icon in front of a Requests tab's title. */
@Composable
internal fun RequestsTabMark() {
    Icon(CartaIcons.Request, contentDescription = "Richieste", tint = Carta.colors.textDim, size = 14.dp)
}
