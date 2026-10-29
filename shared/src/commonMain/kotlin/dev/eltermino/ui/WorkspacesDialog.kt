package dev.eltermino.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.core.data.SavedLayout
import dev.eltermino.core.data.Workspace
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

/** "3 schede · 2 gruppi · web-01, db-01 e altri 2" */
internal fun summary(layout: SavedLayout, workspace: Workspace): String {
    val hosts = layout.tabs.flatMap { it.panes }.mapNotNull { pane -> pane?.hostId?.let { workspace.host(it)?.label } ?: pane?.let { "questo computer" } }.distinct()
    val names = when {
        hosts.size <= 1 -> hosts.joinToString()
        hosts.size <= 3 -> hosts.dropLast(1).joinToString(", ") + " e " + hosts.last()
        else -> "${hosts.take(2).joinToString(", ")} e altri ${hosts.size - 2}"
    }
    return listOfNotNull(
        if (layout.tabs.size == 1) "1 scheda" else "${layout.tabs.size} schede",
        when (layout.groups.size) {
            0 -> null
            1 -> "1 gruppo"
            else -> "${layout.groups.size} gruppi"
        },
        names.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
}

/** Saves the open tabs under a name, and reopens saved ones or what was open last time. */
@Composable
internal fun WorkspacesDialog(state: AppState, onDismiss: () -> Unit) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val bar by state.sessions.bar.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val save = {
        state.sessions.snapshot(name.trim())?.let { layout -> scope.launch { state.actions.saveLayout(layout) } }
        name = ""
    }
    Dialog(
        onDismiss = onDismiss,
        title = "Spazi di lavoro",
        icon = CartaIcons.Layers,
        width = 560.dp,
        actions = { Button("Chiudi", onClick = onDismiss, kind = ButtonKind.Ghost) },
    ) {
        Text("Uno spazio di lavoro ricorda le schede, come sono divise e i loro gruppi. Riaprendolo, le sessioni si ricollegano da sole.")
        Gap(height = 12)
        if (bar.frames.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Field(
                    "Salva le schede aperte come", name, { name = it },
                    placeholder = "Deploy di venerdì",
                    modifier = Modifier.weight(1f),
                    fieldModifier = Modifier.onEnter { if (name.isNotBlank()) save() },
                )
                Button("Salva", onClick = save, enabled = name.isNotBlank(), modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            }
        }
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            workspace.lastSession?.let { last ->
                LayoutRow(
                    title = "Ultima sessione",
                    detail = summary(last, workspace),
                    onOpen = {
                        onDismiss()
                        state.sessions.restore(last)
                    },
                )
            }
            workspace.savedLayouts.sortedBy { it.name.lowercase() }.forEach { layout ->
                LayoutRow(
                    title = layout.name,
                    detail = summary(layout, workspace),
                    onOpen = {
                        onDismiss()
                        state.sessions.restore(layout)
                    },
                    onDelete = { scope.launch { state.actions.deleteLayout(layout.id) } },
                )
            }
            if (workspace.savedLayouts.isEmpty() && workspace.lastSession == null) {
                Hint("Non hai ancora salvato spazi di lavoro.")
            }
        }
    }
}

@Composable
private fun LayoutRow(title: String, detail: String, onOpen: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Carta.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = Carta.type.small, color = Carta.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onDelete != null) Button("Elimina", onClick = onDelete, kind = ButtonKind.Ghost)
        Button("Apri", onClick = onOpen, kind = ButtonKind.Secondary)
    }
    Divider()
}
