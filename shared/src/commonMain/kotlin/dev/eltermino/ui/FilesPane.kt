package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.FileBrowser
import dev.eltermino.app.ConflictPolicy
import dev.eltermino.app.Endpoint
import dev.eltermino.app.FileSort
import dev.eltermino.app.FileSortColumn
import dev.eltermino.app.FilesTab
import dev.eltermino.app.Location
import dev.eltermino.app.childPath
import dev.eltermino.app.failureMessage
import dev.eltermino.app.formatBytes
import dev.eltermino.app.formatFileTime
import dev.eltermino.app.formatPermissions
import dev.eltermino.app.titles
import dev.eltermino.core.ssh.RemoteFile
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.DialogTone
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Menu
import dev.eltermino.ui.components.MenuDivider
import dev.eltermino.ui.components.MenuItem
import dev.eltermino.ui.components.MenuLabel
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.HoppingSombrero
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Files on their way to another panel, while the user still holds the mouse button. */
internal class FileDrag {
    var source by mutableStateOf<FilesTab?>(null)
    var items by mutableStateOf<List<RemoteFile>>(emptyList())
    var pointer by mutableStateOf(Offset.Zero)

    /** Where each file panel is on screen, to know which one is under the pointer. */
    val zones = mutableStateMapOf<String, Rect>()

    val active: Boolean get() = source != null

    fun over(): String? = if (!active) null else zones.entries.firstOrNull { it.key != source?.id && it.value.contains(pointer) }?.key
}

/** A copy waiting for the user to say what to do with names already at the destination. */
internal class PendingCopy(
    val source: Endpoint,
    val items: List<RemoteFile>,
    val target: Location,
    val targetLabel: String,
    val existing: List<String>,
)

/** Starts copies, asking first when the destination already has some of the names. */
internal class CopyFlow(private val state: AppState, private val scope: CoroutineScope) {
    var pending by mutableStateOf<PendingCopy?>(null)
    var error by mutableStateOf<String?>(null)

    fun copy(source: Endpoint, items: List<RemoteFile>, target: Location, targetLabel: String) {
        if (items.isEmpty()) return
        scope.launch {
            val existing = try {
                state.transfers.existing(target, items.map { it.name })
            } catch (e: Exception) {
                error = failureMessage(e)
                return@launch
            }
            if (existing.isEmpty()) state.transfers.enqueue(source, items, target, ConflictPolicy.Overwrite)
            else pending = PendingCopy(source, items, target, targetLabel, existing)
        }
    }

    fun resolve(policy: ConflictPolicy?) {
        val copy = pending ?: return
        pending = null
        if (policy != null) state.transfers.enqueue(copy.source, copy.items, copy.target, policy)
    }
}

internal val LocalFileDrag = staticCompositionLocalOf<FileDrag?> { null }
internal val LocalCopyFlow = staticCompositionLocalOf<CopyFlow?> { null }

internal expect fun Modifier.onFilesDropped(onHover: (Boolean) -> Unit, onDrop: (List<String>) -> Unit): Modifier

/** Starts a drag towards the system's file manager; [items] are what's dragged, downloaded on drop. */
internal expect fun Modifier.dragToSystem(browser: FileBrowser, items: () -> List<RemoteFile>): Modifier

private sealed interface FileDialogState {
    data object NewFolder : FileDialogState
    class Rename(val entry: RemoteFile) : FileDialogState
    class Delete(val entries: List<RemoteFile>) : FileDialogState
    class Permissions(val entry: RemoteFile) : FileDialogState
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FilesPane(state: AppState, tab: FilesTab, focused: Boolean) {
    val browser = tab.browser
    val path by browser.path.collectAsState()
    val entries by browser.entries.collectAsState()
    val loading by browser.loading.collectAsState()
    val error by browser.error.collectAsState()
    val message by browser.message.collectAsState()
    val hidden by browser.showHidden.collectAsState()
    val sort by browser.sort.collectAsState()
    val selection by browser.selection.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    val visible = remember(entries, hidden, sort) { browser.visible(entries, hidden, sort) }
    val drag = LocalFileDrag.current
    val copier = LocalCopyFlow.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalWindowInfo.current
    val colors = Carta.colors
    var dialog by remember { mutableStateOf<FileDialogState?>(null) }
    var anchor by remember { mutableStateOf(-1) }
    var externalHover by remember { mutableStateOf(false) }

    val here = path?.let { Location(browser.endpoint, it) }
    val title = tabs.titles()[tab.id] ?: tab.label
    val targets = tabs.filterIsInstance<FilesTab>().filter { it.id != tab.id }
    val selected = visible.filter { it.path in selection }

    fun copyTo(target: FilesTab, items: List<RemoteFile>) {
        val dir = target.browser.path.value ?: return
        copier?.copy(browser.endpoint, items, Location(target.browser.endpoint, dir), tabs.titles()[target.id] ?: target.label)
    }

    fun upload(paths: List<String>) {
        val destination = here ?: return
        val local = state.services.localFiles ?: return
        scope.launch {
            val files = paths.mapNotNull { runCatching { local.stat(it) }.getOrNull() }
            copier?.copy(Endpoint.Local, files, destination, title)
        }
    }

    fun openEntry(entry: RemoteFile) {
        scope.launch {
            if (!browser.enter(entry)) state.sessions.openEditor(browser.endpoint, entry.path)
        }
    }

    val dropTarget = drag?.over() == tab.id
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .onGloballyPositioned { drag?.zones?.set(tab.id, it.boundsInWindow()) }
            .then(if (dropTarget || externalHover) Modifier.border(2.dp, colors.accent) else Modifier)
            .onFilesDropped(onHover = { externalHover = it }, onDrop = ::upload),
    ) {
        FilesToolbar(state, tab, path, hidden, onNewFolder = { dialog = FileDialogState.NewFolder }, onUpload = {
            scope.launch {
                val picked = state.services.platform.pickLocalFiles("Scegli i file da caricare")
                if (picked.isNotEmpty()) upload(picked)
            }
        })
        Divider()
        error?.let { Banner(it, action = "Riprova") { browser.refresh() ?: path?.let(browser::open) } }
        message?.let { Banner(it, action = "Chiudi") { browser.dismissMessage() } }
        copier?.error?.let { Banner(it, action = "Chiudi") { copier.error = null } }
        if (dropTarget || externalHover) {
            Text(
                "Rilascia per copiare in ${path.orEmpty()}",
                modifier = Modifier.fillMaxWidth().background(colors.accentBackground).padding(horizontal = 12.dp, vertical = 6.dp),
                style = Carta.type.small,
                color = colors.accent,
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val showDate = maxWidth >= 460.dp
            val showPermissions = maxWidth >= 620.dp
            Column(Modifier.fillMaxSize()) {
                HeaderRow(sort, showDate, showPermissions) { column ->
                    browser.sort.value = if (sort.column == column) sort.copy(descending = !sort.descending) else FileSort(column)
                }
                Divider()
                when {
                    path == null && loading -> Centered {
                        HoppingSombrero()
                        Gap(height = 12)
                        Text("Apertura di ${tab.label}…", color = colors.textDim)
                    }
                    visible.isEmpty() && !loading -> EmptyState(
                        "Cartella vuota",
                        if (hidden || entries.isEmpty()) "Trascina qui dei file per copiarli." else "Ci sono solo file nascosti.",
                        Modifier.fillMaxWidth(),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(visible, key = { _, entry -> entry.path }) { index, entry ->
                            FileRow(
                                entry = entry,
                                selected = entry.path in selection,
                                showDate = showDate,
                                showPermissions = showPermissions,
                                onClick = {
                                    val modifiers = keyboard.keyboardModifiers
                                    browser.selection.value = when {
                                        modifiers.isShiftPressed && anchor in visible.indices ->
                                            visible.subList(minOf(anchor, index), maxOf(anchor, index) + 1).map { it.path }.toSet()
                                        modifiers.isCtrlPressed || modifiers.isMetaPressed ->
                                            if (entry.path in selection) selection - entry.path else selection + entry.path
                                        else -> setOf(entry.path)
                                    }
                                    if (!modifiers.isShiftPressed) anchor = index
                                },
                                onOpen = { openEntry(entry) },
                                dragOut = { if (entry.path in selection) selected else listOf(entry) },
                                browser = browser,
                                onDragStart = { position ->
                                    if (drag == null) return@FileRow
                                    val items = if (entry.path in selection) selected else listOf(entry)
                                    if (entry.path !in selection) browser.selection.value = setOf(entry.path)
                                    drag.source = tab
                                    drag.items = items
                                    drag.pointer = position
                                },
                                onDrag = { delta -> drag?.let { it.pointer += delta } },
                                onDragEnd = {
                                    val target = drag?.over()?.let { id -> targets.firstOrNull { it.id == id } }
                                    val items = drag?.items.orEmpty()
                                    drag?.source = null
                                    if (target != null) copyTo(target, items)
                                },
                                menu = { close ->
                                    if (entry.isDirectory || entry.type == RemoteFile.Type.Symlink) {
                                        MenuItem("Apri", onClick = { close(); openEntry(entry) })
                                    } else {
                                        MenuItem("Modifica", onClick = { close(); state.sessions.openEditor(browser.endpoint, entry.path) })
                                        if (state.services.platform.isDesktop) {
                                            MenuItem("Apri con editor esterno", onClick = { close(); state.sessions.openExternalEditor(browser.endpoint, entry.path) })
                                        }
                                    }
                                    CopyTargets(state, targets) { target -> close(); copyTo(target, listOf(entry)) }
                                    MenuDivider()
                                    MenuItem("Rinomina…", onClick = { close(); dialog = FileDialogState.Rename(entry) })
                                    MenuItem("Permessi…", onClick = { close(); dialog = FileDialogState.Permissions(entry) })
                                    MenuItem("Elimina…", danger = true, onClick = { close(); dialog = FileDialogState.Delete(listOf(entry)) })
                                },
                            )
                        }
                    }
                }
            }
        }
        Divider()
        Footer(
            count = visible.size,
            selected = selected,
            targets = targets,
            state = state,
            onCopy = { target -> copyTo(target, selected) },
            onDelete = { dialog = FileDialogState.Delete(selected) },
        )
    }

    when (val open = dialog) {
        null -> Unit
        FileDialogState.NewFolder -> NameDialog("Nuova cartella", "", "Crea") { name ->
            dialog = null
            if (name != null) browser.mkdir(name)
        }
        is FileDialogState.Rename -> NameDialog("Rinomina ${open.entry.name}", open.entry.name, "Rinomina") { name ->
            dialog = null
            if (name != null && name != open.entry.name) browser.rename(open.entry, name)
        }
        is FileDialogState.Delete -> Dialog(
            onDismiss = { dialog = null },
            title = if (open.entries.size == 1) "Eliminare ${open.entries.single().name}?" else "Eliminare ${open.entries.size} elementi?",
            tone = DialogTone.Danger,
            actions = {
                Button("Annulla", onClick = { dialog = null }, kind = ButtonKind.Ghost)
                Button("Elimina", kind = ButtonKind.Danger, onClick = {
                    dialog = null
                    browser.delete(open.entries)
                })
            },
        ) {
            val folders = open.entries.count { it.isDirectory }
            Text(if (folders > 0) "Le cartelle vengono eliminate con tutto il contenuto. Non si può annullare." else "Non si può annullare.")
        }
        is FileDialogState.Permissions -> PermissionsDialog(open.entry) { mode ->
            dialog = null
            if (mode != null) browser.chmod(open.entry, mode)
        }
    }

    LaunchedEffect(focused, path) {
        // A panel that just got the keyboard shows fresh contents, as Finder does.
        if (focused && path != null && !loading) browser.refresh()
    }
}

@Composable
private fun FilesToolbar(state: AppState, tab: FilesTab, path: String?, hidden: Boolean, onNewFolder: () -> Unit, onUpload: () -> Unit) {
    val browser = tab.browser
    val colors = Carta.colors
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val workspace by state.services.workspace.workspace.collectAsState()
    val bookmarks = when (val endpoint = browser.endpoint) {
        Endpoint.Local -> workspace.ui.localBookmarks
        is Endpoint.Remote -> workspace.host(endpoint.hostId)?.bookmarks.orEmpty()
    }
    var bookmarkMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(colors.chrome).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Icons.AutoMirrored.Filled.ArrowBack, "Cartella superiore", onClick = { browser.up() }, size = 28.dp, iconSize = 16.dp)
        IconButton(Icons.Default.Refresh, "Aggiorna", onClick = { browser.refresh() }, size = 28.dp, iconSize = 16.dp)
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (editing) {
                var text by remember(path) { mutableStateOf(path.orEmpty()) }
                Field(
                    label = null, value = text, onValueChange = { text = it }, monospace = true,
                    fieldModifier = Modifier.onEnter {
                        editing = false
                        if (text.isNotBlank()) browser.open(text.trim())
                    },
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                Pressable(onClick = { editing = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        path ?: "…",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        fontFamily = Carta.monospace,
                        style = Carta.type.small,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Pressable(onClick = { browser.showHidden.value = !hidden }, shape = RoundedCornerShape(6.dp), background = if (hidden) colors.accentBackground else colors.chrome) {
            Text(".*", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontFamily = Carta.monospace, style = Carta.type.small, color = if (hidden) colors.accent else colors.textDim)
        }
        Box {
            val saved = path != null && path in bookmarks
            IconButton(CartaIcons.Star, "Segnalibri", onClick = { bookmarkMenu = true }, tint = if (saved) colors.accent else colors.textDim, size = 28.dp, iconSize = 16.dp)
            Menu(expanded = bookmarkMenu, onDismiss = { bookmarkMenu = false }, alignEnd = true) {
                if (path != null) {
                    MenuItem(if (saved) "Togli questa cartella" else "Aggiungi questa cartella", onClick = {
                        bookmarkMenu = false
                        scope.launch { state.actions.toggleBookmark(browser.endpoint, path) }
                    })
                }
                if (bookmarks.isNotEmpty()) {
                    MenuDivider()
                    bookmarks.forEach { saved ->
                        MenuItem(saved, selected = saved == path, onClick = {
                            bookmarkMenu = false
                            browser.open(saved)
                        })
                    }
                }
            }
        }
        IconButton(Icons.Default.Add, "Nuova cartella", onClick = onNewFolder, size = 28.dp, iconSize = 16.dp)
        if (browser.endpoint is Endpoint.Remote && state.services.platform.isDesktop) {
            IconButton(CartaIcons.Upload, "Carica file", onClick = onUpload, size = 28.dp, iconSize = 16.dp)
        }
    }
}

@Composable
private fun HeaderRow(sort: FileSort, showDate: Boolean, showPermissions: Boolean, onSort: (FileSortColumn) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell("Nome", FileSortColumn.Name, sort, Modifier.weight(1f).padding(start = 22.dp), onSort)
        HeaderCell("Dimensione", FileSortColumn.Size, sort, Modifier.width(92.dp), onSort, end = true)
        if (showDate) HeaderCell("Modificato", FileSortColumn.Modified, sort, Modifier.width(124.dp), onSort, end = true)
        if (showPermissions) Text("Permessi", style = Carta.type.label, color = Carta.colors.textDim, modifier = Modifier.width(96.dp).padding(start = 16.dp))
    }
}

@Composable
private fun HeaderCell(text: String, column: FileSortColumn, sort: FileSort, modifier: Modifier, onSort: (FileSortColumn) -> Unit, end: Boolean = false) {
    val active = sort.column == column
    Pressable(onClick = { onSort(column) }, modifier = modifier, shape = RoundedCornerShape(4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            horizontalArrangement = if (end) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = Carta.type.label, color = if (active) Carta.colors.text else Carta.colors.textDim)
            if (active) {
                Icon(if (sort.descending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Carta.colors.textDim, size = 14.dp)
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: RemoteFile,
    selected: Boolean,
    showDate: Boolean,
    showPermissions: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    dragOut: () -> List<RemoteFile>,
    browser: FileBrowser,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    menu: @Composable ((close: () -> Unit) -> Unit),
) {
    val colors = Carta.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates = it }
            .background(
                when {
                    selected -> colors.accentBackground
                    hovered -> colors.hover
                    else -> colors.background
                },
            )
            .hoverable(interaction)
            .onSecondaryClick { open = true }
            .pointerInput(entry.path) {
                detectDragGestures(
                    onDragStart = { offset -> onDragStart((coordinates?.positionInWindow() ?: Offset.Zero) + offset) },
                    onDrag = { change, delta ->
                        change.consume()
                        onDrag(delta)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            }
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onDoubleClick = onOpen)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (entry.type) {
                    RemoteFile.Type.Directory -> CartaIcons.Folder
                    else -> CartaIcons.Document
                },
                contentDescription = null,
                tint = if (entry.isDirectory) colors.accent else colors.textDim,
                size = 16.dp,
                // The row's own drag copies between panels; this one leaves the app.
                modifier = Modifier.dragToSystem(browser, dragOut),
            )
            Text(entry.name, modifier = Modifier.padding(start = 6.dp), style = Carta.type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.linkTarget?.let {
                Text("→ $it", modifier = Modifier.padding(start = 6.dp), style = Carta.type.caption, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            if (entry.isDirectory) "" else formatBytes(entry.size),
            modifier = Modifier.width(92.dp),
            style = Carta.type.small.tabular(),
            color = colors.textDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1,
        )
        if (showDate) {
            Text(
                formatFileTime(entry.modifiedEpochSeconds),
                modifier = Modifier.width(124.dp),
                style = Carta.type.small.tabular(),
                color = colors.textDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                maxLines = 1,
            )
        }
        if (showPermissions) {
            Text(
                if (entry.permissions == 0) "" else formatPermissions(entry.permissions),
                modifier = Modifier.width(96.dp).padding(start = 16.dp),
                style = Carta.type.small,
                fontFamily = Carta.monospace,
                color = colors.textDim,
                maxLines = 1,
            )
        }
        Box {
            IconButton(Icons.Default.MoreVert, "Azioni per ${entry.name}", onClick = { open = true }, size = 26.dp, iconSize = 15.dp)
            Menu(expanded = open, onDismiss = { open = false }, alignEnd = true) { menu { open = false } }
        }
    }
}

/** "Copia in" entries: every other open file panel, with the folder it shows. */
@Composable
private fun CopyTargets(state: AppState, targets: List<FilesTab>, onPick: (FilesTab) -> Unit) {
    val tabs by state.sessions.tabs.collectAsState()
    val titles = remember(tabs) { tabs.titles() }
    MenuLabel(if (targets.isEmpty()) "Apri un altro pannello file per copiare" else "Copia in")
    targets.forEach { target ->
        val dir by target.browser.path.collectAsState()
        MenuItem(titles[target.id] ?: target.label, detail = dir, icon = CartaIcons.Transfer, onClick = { onPick(target) })
    }
}

@Composable
private fun Footer(count: Int, selected: List<RemoteFile>, targets: List<FilesTab>, state: AppState, onCopy: (FilesTab) -> Unit, onDelete: () -> Unit) {
    var copyMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val size = selected.filterNot { it.isDirectory }.sumOf { it.size }
        Text(
            if (selected.isEmpty()) "$count elementi" else "${selected.size} di $count selezionati" + (if (size > 0) " · ${formatBytes(size)}" else ""),
            style = Carta.type.label,
            color = Carta.colors.textDim,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (selected.isNotEmpty()) {
            Box {
                Button("Copia in…", onClick = { copyMenu = true }, kind = ButtonKind.Ghost, icon = CartaIcons.Transfer)
                Menu(expanded = copyMenu, onDismiss = { copyMenu = false }, alignEnd = true) {
                    CopyTargets(state, targets) { target ->
                        copyMenu = false
                        onCopy(target)
                    }
                }
            }
            Button("Elimina", onClick = onDelete, kind = ButtonKind.Ghost)
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Carta.colors.dangerBackground).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Carta.type.small, color = Carta.colors.danger, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button(action, onClick = onAction, kind = ButtonKind.Ghost)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val invalid = name.isBlank() || '/' in name
    val submit = { if (!invalid) onDone(name.trim()) }
    Dialog(
        onDismiss = { onDone(null) },
        title = title,
        actions = {
            Button("Annulla", onClick = { onDone(null) }, kind = ButtonKind.Ghost)
            Button(confirm, onClick = submit, enabled = !invalid)
        },
    ) {
        Field("Nome", name, { name = it }, error = if ('/' in name) "Il nome non può contenere /" else null, fieldModifier = Modifier.onEnter(submit))
    }
}

@Composable
private fun PermissionsDialog(entry: RemoteFile, onDone: (Int?) -> Unit) {
    var mode by remember { mutableStateOf(entry.permissions and 0x1FF) }
    var octal by remember { mutableStateOf(mode.toString(8).padStart(3, '0')) }
    Dialog(
        onDismiss = { onDone(null) },
        title = "Permessi di ${entry.name}",
        actions = {
            Button("Annulla", onClick = { onDone(null) }, kind = ButtonKind.Ghost)
            Button("Applica", onClick = { onDone(mode) })
        },
    ) {
        listOf("Proprietario" to 6, "Gruppo" to 3, "Altri" to 0).forEach { (who, shift) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(who, modifier = Modifier.width(110.dp), style = Carta.type.small)
                listOf("lettura" to 4, "scrittura" to 2, "esecuzione" to 1).forEach { (label, bit) ->
                    val mask = bit shl shift
                    Checkbox(mode and mask != 0, { on ->
                        mode = if (on) mode or mask else mode and mask.inv()
                        octal = mode.toString(8).padStart(3, '0')
                    }, label)
                }
            }
        }
        Gap(height = 8)
        Field("Ottale", octal, { text ->
            octal = text.filter { it in '0'..'7' }.take(4)
            octal.toIntOrNull(8)?.let { mode = it and 0xFFF }
        }, monospace = true)
        Text(formatPermissions(mode), fontFamily = Carta.monospace, style = Carta.type.small, color = Carta.colors.textDim)
    }
}

/** Asks what to do when a copy would land on names the destination already has. */
@Composable
internal fun ConflictDialog(copy: PendingCopy, onChoice: (ConflictPolicy?) -> Unit) {
    val many = copy.existing.size > 1
    Dialog(
        onDismiss = { onChoice(null) },
        title = if (many) "${copy.existing.size} elementi esistono già" else "${copy.existing.single()} esiste già",
        width = 520.dp,
        actions = {
            Button("Annulla", onClick = { onChoice(null) }, kind = ButtonKind.Ghost)
            Button("Salta", onClick = { onChoice(ConflictPolicy.Skip) }, kind = ButtonKind.Secondary)
            Button("Rinomina", onClick = { onChoice(ConflictPolicy.Rename) }, kind = ButtonKind.Secondary)
            Button("Riprendi", onClick = { onChoice(ConflictPolicy.Resume) }, kind = ButtonKind.Secondary)
            Button("Sovrascrivi", onClick = { onChoice(ConflictPolicy.Overwrite) })
        },
    ) {
        Text("In ${copy.targetLabel}: ${childPath(copy.target.path, "")}")
        Gap(height = 6)
        copy.existing.take(5).forEach { Text("· $it", fontFamily = Carta.monospace, style = Carta.type.small) }
        if (copy.existing.size > 5) Text("e altri ${copy.existing.size - 5}", style = Carta.type.small, color = Carta.colors.textDim)
        Gap(height = 8)
        Text(
            "Riprendi continua i file rimasti a metà da dove si sono fermati; Rinomina li copia con un nome nuovo, come \"file (1).txt\".",
            style = Carta.type.small,
            color = Carta.colors.textDim,
        )
    }
}
