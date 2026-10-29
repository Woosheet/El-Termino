package dev.eltermino.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
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
import dev.eltermino.app.usernameFor
import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Workspace
import dev.eltermino.core.data.groupPath
import dev.eltermino.core.data.newId
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Menu
import dev.eltermino.ui.components.MenuItem
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.SearchField
import dev.eltermino.ui.components.StatusDot
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

/** Hosts grouped as in the sidebar mockup: favourites first, then each group. */
@Composable
internal fun HostList(
    state: AppState,
    onConnect: (Host) -> Unit,
    onEdit: (Host) -> Unit,
    modifier: Modifier = Modifier,
    onOpenFiles: (Host) -> Unit = {},
) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val connected by state.sessions.connectedHostIds.collectAsState()
    var query by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Host?>(null) }
    val scope = rememberCoroutineScope()

    val sections = remember(workspace, query) { sections(workspace, query) }

    LazyColumn(modifier) {
        item { SearchField(query, { query = it }, "Cerca host") }
        if (workspace.hosts.isEmpty()) {
            item { EmptyState("Aggiungi il primo host", "Oppure importa i tuoi host da Termius o da ~/.ssh/config.") }
        } else if (sections.isEmpty()) {
            item { Hint("Nessun host corrisponde a \"$query\"", Modifier.padding(horizontal = 6.dp)) }
        }
        sections.forEach { (title, hosts) ->
            item(key = "section-$title") { SectionLabel(title) }
            items(hosts, key = { "$title-${it.id}" }) { host ->
                HostRow(
                    host = host,
                    subtitle = subtitle(workspace, host),
                    online = host.id in connected,
                    onConnect = { onConnect(host) },
                    onOpenFiles = { onOpenFiles(host) },
                    onEdit = { onEdit(host) },
                    onDuplicate = { scope.launch { state.actions.saveHost(host.copy(id = newId(), label = "${host.label} (copia)", lastConnectedEpochSeconds = null)) } },
                    onToggleFavorite = { scope.launch { state.actions.saveHost(host.copy(favorite = !host.favorite)) } },
                    onDelete = { deleting = host },
                )
            }
        }
    }

    deleting?.let { host ->
        Dialog(
            onDismiss = { deleting = null },
            title = "Eliminare ${host.label}?",
            actions = {
                Button("Annulla", onClick = { deleting = null }, kind = ButtonKind.Ghost)
                Button("Elimina", kind = ButtonKind.Danger, onClick = {
                    deleting = null
                    scope.launch { state.actions.deleteHost(host.id) }
                })
            },
        ) {
            Text("L'host sparisce dall'elenco. Identità e chiavi restano.")
        }
    }
}

@Composable
private fun HostRow(
    host: Host,
    subtitle: String,
    online: Boolean,
    onConnect: () -> Unit,
    onOpenFiles: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Pressable(onClick = onConnect, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (online) Carta.colors.online else Carta.colors.offline)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(host.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (host.favorite) Icon(Icons.Default.Star, contentDescription = "Preferito", tint = Carta.colors.accent, size = 12.dp, modifier = Modifier.padding(start = 4.dp))
                }
                Text(subtitle, style = Carta.type.caption, color = Carta.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(Icons.Default.MoreVert, "Azioni per ${host.label}", onClick = { menu = true })
                Menu(expanded = menu, onDismiss = { menu = false }, alignEnd = true) {
                    MenuItem("Terminale", onClick = { menu = false; onConnect() })
                    MenuItem("File (SFTP)", onClick = { menu = false; onOpenFiles() })
                    MenuItem("Modifica", onClick = { menu = false; onEdit() })
                    MenuItem("Duplica", onClick = { menu = false; onDuplicate() })
                    MenuItem(if (host.favorite) "Togli dai preferiti" else "Aggiungi ai preferiti", onClick = { menu = false; onToggleFavorite() })
                    MenuItem("Elimina", onClick = { menu = false; onDelete() }, danger = true)
                }
            }
        }
    }
}

private fun subtitle(workspace: Workspace, host: Host): String {
    val user = workspace.usernameFor(host)?.let { "$it@" } ?: ""
    val port = if (host.port != 22) ":${host.port}" else ""
    val jump = workspace.host(host.jumpHostId)?.let { " · via ${it.label}" } ?: ""
    return "$user${host.address}$port$jump"
}

private fun sections(workspace: Workspace, query: String): List<Pair<String, List<Host>>> {
    val needle = query.trim().lowercase()
    val hosts = workspace.hosts
        .filter { host ->
            needle.isEmpty() ||
                host.label.lowercase().contains(needle) ||
                host.address.lowercase().contains(needle) ||
                host.tags.any { it.lowercase().contains(needle) } ||
                workspace.groupPath(host.groupId).any { it.lowercase().contains(needle) }
        }
        .sortedBy { it.label.lowercase() }

    val result = mutableListOf<Pair<String, List<Host>>>()
    hosts.filter { it.favorite }.takeIf { it.isNotEmpty() }?.let { result += "Preferiti" to it }
    hosts.groupBy { it.groupId }
        .map { (groupId, members) -> (workspace.groupPath(groupId).joinToString(" / ").ifEmpty { "Senza gruppo" }) to members }
        .sortedWith(compareBy({ it.first == "Senza gruppo" }, { it.first.lowercase() }))
        .forEach { result += it }
    return result
}
