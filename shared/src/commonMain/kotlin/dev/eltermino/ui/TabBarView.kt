package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.Frame
import dev.eltermino.app.GroupColor
import dev.eltermino.app.SessionTab
import dev.eltermino.app.Side
import dev.eltermino.app.Tab
import dev.eltermino.app.TabGroup
import dev.eltermino.app.titles
import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Snippet
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Menu
import dev.eltermino.ui.components.MenuDivider
import dev.eltermino.ui.components.MenuItem
import dev.eltermino.ui.components.MenuLabel
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

/**
 * A tab being dragged by its chip. Dropped on the session area it joins the visible tab
 * on the nearest side; dropped on a group label it joins the group; on a tab, it moves.
 */
internal class ChipDrag {
    var frameId by mutableStateOf<String?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var content by mutableStateOf<Rect?>(null)
    val chips = mutableStateMapOf<String, Rect>()
    val groups = mutableStateMapOf<String, Rect>()

    val active: Boolean get() = frameId != null

    /** The side of the session area under the pointer, if it's there. */
    fun side(): Side? {
        val area = content ?: return null
        if (!active || !area.contains(pointer)) return null
        val x = (pointer.x - area.left) / area.width
        val y = (pointer.y - area.top) / area.height
        val horizontal = minOf(x, 1 - x)
        val vertical = minOf(y, 1 - y)
        return if (horizontal <= vertical) (if (x < 0.5f) Side.Left else Side.Right) else (if (y < 0.5f) Side.Top else Side.Bottom)
    }
}

internal val LocalChipDrag = staticCompositionLocalOf<ChipDrag?> { null }

/** Runs [onMenu] on a right click, the way a context menu opens on desktop. */
internal fun Modifier.onSecondaryClick(onMenu: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) onMenu()
        }
    }
}

@Composable
internal fun groupStrong(color: GroupColor): Color = Carta.colors.groupStrong[color.ordinal]

@Composable
internal fun groupSoft(color: GroupColor): Color = Carta.colors.groupSoft[color.ordinal]

private fun GroupColor.label() = when (this) {
    GroupColor.Blu -> "Blu"
    GroupColor.Verde -> "Verde"
    GroupColor.Viola -> "Viola"
    GroupColor.Corallo -> "Corallo"
    GroupColor.Ambra -> "Ambra"
    GroupColor.Rosa -> "Rosa"
    GroupColor.Grigio -> "Grigio"
}

/** A tab's title: its session's, or "web-01 + db-01" for a split. */
internal fun frameTitle(frame: Frame, titles: Map<String, String>): String =
    frame.sessionIds.mapNotNull { titles[it] }.joinToString(" + ")

private sealed interface BarDialog {
    class RenameGroup(val group: TabGroup) : BarDialog
    class NewGroup(val frameId: String) : BarDialog
    class SaveGroup(val group: TabGroup) : BarDialog
    class SnippetForGroup(val group: TabGroup) : BarDialog
}

@Composable
internal fun SessionTabs(state: AppState, onConnect: (Host) -> Unit, modifier: Modifier = Modifier) {
    val bar by state.sessions.bar.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    val broadcast by state.sessions.broadcast.collectAsState()
    val titles = remember(tabs) { tabs.titles() }
    val byId = tabs.associateBy { it.id }
    var dialog by remember { mutableStateOf<BarDialog?>(null) }
    var runSnippet by remember { mutableStateOf<Pair<Snippet, Set<String>>?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            var previousGroup: String? = null
            bar.frames.forEachIndexed { index, frame ->
                val group = bar.group(frame.groupId)
                if (group != null && group.id != previousGroup) {
                    GroupLabel(state, group, bar.framesIn(group.id).size, onDialog = { dialog = it })
                }
                previousGroup = frame.groupId
                if (group?.collapsed != true) {
                    FrameChip(
                        state = state,
                        frame = frame,
                        index = index,
                        sessions = frame.sessionIds.mapNotNull(byId::get),
                        title = frameTitle(frame, titles),
                        selected = frame.id == bar.selected,
                        group = group,
                        broadcasting = frame.sessionIds.any { it in broadcast },
                        onNewGroup = { dialog = BarDialog.NewGroup(frame.id) },
                    )
                }
            }
        }
        NewTabMenu(state, onConnect)
    }

    when (val open = dialog) {
        null -> Unit
        is BarDialog.RenameGroup -> TextDialog("Rinomina il gruppo", open.group.name, "Rinomina") { name ->
            dialog = null
            if (name != null) state.sessions.renameGroup(open.group.id, name)
        }
        is BarDialog.NewGroup -> TextDialog("Nuovo gruppo", "", "Crea") { name ->
            dialog = null
            if (name != null) state.sessions.createGroup(listOf(open.frameId), name)
        }
        is BarDialog.SaveGroup -> TextDialog("Salva come spazio di lavoro", open.group.name, "Salva") { name ->
            dialog = null
            val layout = name?.let { state.sessions.snapshot(it, open.group.id) }
            if (layout != null) state.scope.launch { state.actions.saveLayout(layout) }
        }
        is BarDialog.SnippetForGroup -> SnippetPickerDialog(state) { snippet ->
            dialog = null
            if (snippet != null) runSnippet = snippet to state.sessions.terminalsIn(open.group.id)
        }
    }
    runSnippet?.let { (snippet, targets) -> SnippetRunDialog(state, snippet, initialTabs = targets) { runSnippet = null } }
}

@Composable
private fun FrameChip(
    state: AppState,
    frame: Frame,
    index: Int,
    sessions: List<Tab>,
    title: String,
    selected: Boolean,
    group: TabGroup?,
    broadcasting: Boolean,
    onNewGroup: () -> Unit,
) {
    val colors = Carta.colors
    val drag = LocalChipDrag.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val shape = RoundedCornerShape(6.dp)
    val dragging = drag?.frameId == frame.id
    Box(Modifier.padding(end = 4.dp)) {
        Row(
            Modifier
                .onGloballyPositioned {
                    coordinates = it
                    drag?.chips?.set(frame.id, it.boundsInWindow())
                }
                .clip(shape)
                .background(
                    when {
                        dragging -> colors.accentBackground
                        selected -> colors.background
                        hovered -> colors.hover
                        else -> colors.chrome
                    },
                )
                .then(if (group != null) Modifier.border(1.dp, groupStrong(group.color).copy(alpha = if (selected) 0.9f else 0.35f), shape) else Modifier)
                .hoverable(interaction)
                .onSecondaryClick { menu = true }
                .then(
                    if (state.services.platform.isDesktop && drag != null) {
                        Modifier.pointerInput(frame.id) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    drag.frameId = frame.id
                                    drag.pointer = (coordinates?.positionInWindow() ?: Offset.Zero) + offset
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    drag.pointer += delta
                                },
                                onDragEnd = { drop(state, drag, frame) },
                                onDragCancel = { drag.frameId = null },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(interactionSource = interaction, indication = null, onClick = { state.sessions.selectFrame(frame.id) }, onLongClick = { menu = true })
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sessions.size > 1) {
                Icon(CartaIcons.SplitColumns, contentDescription = "Scheda divisa", tint = colors.textDim, size = 14.dp)
            } else {
                sessions.firstOrNull()?.let { TabMark(it) }
            }
            Text(
                title,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = 220.dp),
                style = Carta.type.small,
                color = if (selected) colors.text else colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (broadcasting) Icon(CartaIcons.Broadcast, "Broadcast", tint = colors.accent, size = 14.dp, modifier = Modifier.padding(start = 6.dp))
            IconButton(Icons.Default.Close, "Chiudi $title", onClick = { state.sessions.closeFrame(frame.id) }, size = 28.dp, iconSize = 14.dp)
        }
        Menu(expanded = menu, onDismiss = { menu = false }) {
            FrameMenu(state, frame, sessions, onNewGroup = onNewGroup, close = { menu = false })
        }
    }
}

/** What a dragged chip does where it's let go. */
private fun drop(state: AppState, drag: ChipDrag, frame: Frame) {
    val side = drag.side()
    val pointer = drag.pointer
    drag.frameId = null
    val bar = state.sessions.bar.value
    val visible = bar.selected
    when {
        side != null && visible != null && visible != frame.id -> state.sessions.merge(frame.id, visible, side)
        else -> {
            drag.groups.entries.firstOrNull { it.value.contains(pointer) }?.let { (groupId, _) ->
                state.sessions.addToGroup(frame.id, groupId)
                return
            }
            val over = drag.chips.entries.firstOrNull { it.key != frame.id && it.value.contains(pointer) } ?: return
            val target = bar.frames.indexOfFirst { it.id == over.key }
            val after = pointer.x > over.value.center.x
            val current = bar.frames.indexOfFirst { it.id == frame.id }
            val index = (if (after) target + 1 else target).let { if (current < it) it - 1 else it }
            state.sessions.moveFrame(frame.id, index)
        }
    }
}

@Composable
private fun FrameMenu(state: AppState, frame: Frame, sessions: List<Tab>, onNewGroup: () -> Unit, close: () -> Unit) {
    val bar by state.sessions.bar.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    val titles = remember(tabs) { tabs.titles() }
    if (frame.groupId == null) {
        MenuItem("Nuovo gruppo con questa scheda…", onClick = { close(); onNewGroup() })
    } else {
        MenuItem("Togli dal gruppo", onClick = { close(); state.sessions.removeFromGroup(frame.id) })
    }
    bar.groups.filter { it.id != frame.groupId }.forEach { group ->
        MenuItem("Aggiungi a ${group.name}", onClick = { close(); state.sessions.addToGroup(frame.id, group.id) })
    }
    MenuDivider()
    val others = bar.frames.filter { it.id != frame.id }
    if (others.isNotEmpty() && frame.sessionIds.size < 4) {
        MenuLabel("Affianca a")
        others.filter { it.sessionIds.size + frame.sessionIds.size <= 4 }.take(6).forEach { other ->
            MenuItem(frameTitle(other, titles), icon = CartaIcons.SplitColumns, onClick = {
                close()
                state.sessions.merge(frame.id, other.id, Side.Right)
            })
        }
    }
    if (sessions.size > 1) MenuItem("Separa i riquadri", icon = CartaIcons.SplitSingle, onClick = { close(); state.sessions.separate(frame.id) })
    (sessions.singleOrNull() as? SessionTab)?.let { terminal ->
        MenuItem("Duplica", icon = CartaIcons.Duplicate, onClick = { close(); state.sessions.duplicate(terminal) })
    }
    MenuDivider()
    MenuItem("Chiudi le altre schede", onClick = {
        close()
        others.forEach { state.sessions.closeFrame(it.id) }
    })
    MenuItem("Chiudi la scheda", danger = true, onClick = { close(); state.sessions.closeFrame(frame.id) })
}

/** The coloured label in front of a group's tabs; it shows how many when collapsed. */
@Composable
private fun GroupLabel(state: AppState, group: TabGroup, count: Int, onDialog: (BarDialog) -> Unit) {
    val drag = LocalChipDrag.current
    var menu by remember { mutableStateOf(false) }
    val strong = groupStrong(group.color)
    val target = drag?.active == true && drag.groups[group.id]?.contains(drag.pointer) == true
    Box(Modifier.padding(end = 4.dp)) {
        Pressable(
            onClick = { menu = true },
            modifier = Modifier
                .onGloballyPositioned { drag?.groups?.set(group.id, it.boundsInWindow()) }
                .then(if (target) Modifier.border(2.dp, strong, RoundedCornerShape(6.dp)) else Modifier),
            shape = RoundedCornerShape(6.dp),
            background = groupSoft(group.color),
            hover = groupSoft(group.color).copy(alpha = 0.7f),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, style = Carta.type.small, color = strong, maxLines = 1)
                if (group.collapsed) {
                    Text(
                        "$count",
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(strong).padding(horizontal = 5.dp),
                        style = Carta.type.caption,
                        color = Carta.colors.background,
                    )
                }
            }
        }
        Menu(expanded = menu, onDismiss = { menu = false }) {
            GroupMenu(state, group, onDialog, close = { menu = false })
        }
    }
}

@Composable
private fun GroupMenu(state: AppState, group: TabGroup, onDialog: (BarDialog) -> Unit, close: () -> Unit) {
    val bar by state.sessions.bar.collectAsState()
    val terminals = state.sessions.terminalsIn(group.id)
    MenuItem(if (group.collapsed) "Espandi" else "Comprimi", onClick = { close(); state.sessions.toggleGroup(group.id) })
    MenuItem("Rinomina…", onClick = { close(); onDialog(BarDialog.RenameGroup(group)) })
    MenuLabel("Colore")
    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupColor.entries.forEach { color ->
            Pressable(
                onClick = { close(); state.sessions.recolorGroup(group.id, color) },
                modifier = Modifier.size(22.dp),
                shape = CircleShape,
                background = groupStrong(color),
                hover = groupStrong(color).copy(alpha = 0.8f),
            ) {
                if (color == group.color) Box(Modifier.size(8.dp).clip(CircleShape).background(Carta.colors.background))
            }
        }
    }
    MenuDivider()
    if (terminals.size >= 2) {
        MenuItem("Broadcast a tutto il gruppo", icon = CartaIcons.Broadcast, onClick = { close(); state.sessions.setBroadcast(terminals) })
    }
    if (terminals.isNotEmpty()) {
        MenuItem("Esegui uno snippet sul gruppo…", icon = CartaIcons.Snippet, onClick = { close(); onDialog(BarDialog.SnippetForGroup(group)) })
    }
    if (bar.framesIn(group.id).size >= 2) {
        MenuItem("Apri in griglia", icon = CartaIcons.SplitGrid, onClick = { close(); state.sessions.gridFromGroup(group.id) })
    }
    MenuItem("Salva come spazio di lavoro…", icon = CartaIcons.Layers, onClick = { close(); onDialog(BarDialog.SaveGroup(group)) })
    MenuDivider()
    MenuItem("Separa il gruppo", onClick = { close(); state.sessions.ungroup(group.id) })
    MenuItem("Chiudi il gruppo", danger = true, onClick = { close(); state.sessions.closeGroup(group.id) })
}

/** "+" after the tabs: another shell like the focused one, files, or a recent host. */
@Composable
private fun NewTabMenu(state: AppState, onConnect: (Host) -> Unit) {
    // In the app state, so ⌘T opens it too.
    var open by state.newTabMenu.collectAsMutableState()
    val workspace by state.services.workspace.workspace.collectAsState()
    val tabs by state.sessions.tabs.collectAsState()
    val selectedId by state.sessions.selectedId.collectAsState()
    val selected = tabs.firstOrNull { it.id == selectedId }
    val focused = selected as? SessionTab
    Box {
        IconButton(Icons.Default.Add, "Nuova sessione", onClick = { open = true }, size = 28.dp, iconSize = 16.dp)
        Menu(expanded = open, onDismiss = { open = false }) {
            val focusedHost = selected?.hostId?.let(workspace::host)
            if (focused != null) {
                MenuItem("Duplica ${focused.label}", icon = CartaIcons.Duplicate, onClick = {
                    open = false
                    state.sessions.duplicate(focused)
                })
            }
            if (focusedHost != null) {
                MenuItem("File di ${focusedHost.label}", icon = CartaIcons.Folder, onClick = {
                    open = false
                    state.sessions.openFiles(focusedHost)
                })
            }
            if (state.services.localFiles != null) {
                MenuItem(if (state.services.platform.isDesktop) "File di questo computer" else "File dell'app", icon = CartaIcons.Folder, onClick = {
                    open = false
                    state.sessions.openFiles(null)
                })
            }
            MenuDivider()
            val recent = recentHosts(workspace.hosts)
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
            if (tabs.size >= 2) {
                MenuDivider()
                MenuItem("Raggruppa le schede per host", onClick = {
                    open = false
                    state.sessions.groupByHost()
                })
            }
        }
    }
}

/** While a tab is dragged over the session area: where it would land. */
@Composable
internal fun DropOverlay(drag: ChipDrag?, modifier: Modifier = Modifier) {
    val side = drag?.side() ?: return
    val colors = Carta.colors
    Box(modifier) {
        val zone = when (side) {
            Side.Left -> Modifier.align(Alignment.CenterStart).fillMaxWidth(0.5f).fillMaxHeight()
            Side.Right -> Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight()
            Side.Top -> Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.5f)
            Side.Bottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
        }
        Box(zone.background(colors.accent.copy(alpha = 0.12f)).border(2.dp, colors.accent), contentAlignment = Alignment.Center) {
            Text(
                when (side) {
                    Side.Left -> "Affianca a sinistra"
                    Side.Right -> "Affianca a destra"
                    Side.Top -> "Metti sopra"
                    Side.Bottom -> "Metti sotto"
                },
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.accent).padding(horizontal = 10.dp, vertical = 4.dp),
                style = Carta.type.small,
                color = colors.background,
            )
        }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, confirm: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val submit = { if (text.isNotBlank()) onDone(text.trim()) }
    Dialog(
        onDismiss = { onDone(null) },
        title = title,
        actions = {
            Button("Annulla", onClick = { onDone(null) }, kind = ButtonKind.Ghost)
            Button(confirm, onClick = submit, enabled = text.isNotBlank())
        },
    ) {
        Field("Nome", text, { text = it }, fieldModifier = Modifier.onEnter(submit))
    }
}

/** Picks which snippet to run, for actions that already know where. */
@Composable
internal fun SnippetPickerDialog(state: AppState, onPick: (Snippet?) -> Unit) {
    val workspace by state.services.workspace.workspace.collectAsState()
    Dialog(
        onDismiss = { onPick(null) },
        title = "Quale snippet?",
        icon = CartaIcons.Snippet,
        actions = { Button("Annulla", onClick = { onPick(null) }, kind = ButtonKind.Ghost) },
    ) {
        if (workspace.snippets.isEmpty()) Hint("Non hai ancora salvato snippet: li trovi in Strumenti.")
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            workspace.snippets.sortedBy { it.name.lowercase() }.forEach { snippet ->
                Pressable(onClick = { onPick(snippet) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(snippet.name, style = Carta.type.bodyStrong)
                        Text(snippet.command, fontFamily = Carta.monospace, style = Carta.type.small, color = Carta.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The hosts under "Apri una sessione su": the most recently connected first, then favourites. */
internal fun recentHosts(hosts: List<Host>, limit: Int = 8): List<Host> =
    // 0L, not 0: the selector returns Comparable<*>, so a bare 0 boxes as an Integer, and
    // comparing it with a host's Long timestamp throws ClassCastException.
    hosts.sortedWith(compareByDescending<Host> { it.lastConnectedEpochSeconds ?: 0L }.thenByDescending { it.favorite }).take(limit)
