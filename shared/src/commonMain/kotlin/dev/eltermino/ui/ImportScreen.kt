package dev.eltermino.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.eltermino.app.ImportService
import dev.eltermino.core.importer.ImportResult
import dev.eltermino.core.importer.ImportedHost
import dev.eltermino.ui.components.Badge
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

private class Preview(val source: String, val result: ImportResult)

@Composable
internal fun ImportScreen(state: AppState, modifier: Modifier = Modifier) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val service = remember { ImportService(state.services, state.actions) }
    val platform = state.services.platform
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Preview?>(null) }
    val selected = remember { mutableStateMapOf<Int, Boolean>() }
    var outcome by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun show(source: String, result: ImportResult) {
        outcome = null
        selected.clear()
        result.hosts.forEachIndexed { index, host -> selected[index] = !service.isDuplicate(workspace, host) }
        preview = Preview(source, result)
    }

    Column(modifier.padding(16.dp)) {
        ScreenTitle("Importa host")
        Hint(
            "Termius non ha un export ufficiale: se hai un CSV dei tuoi host (dal modello CSV di Termius o da un tool di export), sceglilo qui. " +
                "Password e chiavi finiscono nel vault, mai in chiaro.",
        )
        Row {
            if (platform.isDesktop) {
                Button("Leggi ~/.ssh/config", kind = ButtonKind.Secondary, onClick = {
                    val text = platform.readSshConfig()
                    if (text == null) outcome = "Non ho trovato ~/.ssh/config" else show("~/.ssh/config", service.parseSshConfig(text))
                })
                Gap(width = 8)
            }
            Button("File ssh config", kind = ButtonKind.Secondary, onClick = {
                scope.launch {
                    platform.pickFile("Scegli un file di configurazione SSH")?.let { show(it.name, service.parseSshConfig(it.bytes.decodeToString())) }
                }
            })
            Gap(width = 8)
            Button("CSV di Termius", kind = ButtonKind.Secondary, onClick = {
                scope.launch {
                    platform.pickFile("Scegli il CSV esportato da Termius")?.let { show(it.name, service.parseTermiusCsv(it.bytes.decodeToString())) }
                }
            })
        }
        outcome?.let { Text(it, color = Carta.colors.accent, modifier = Modifier.padding(vertical = 8.dp)) }

        preview?.let { current ->
            val hosts = current.result.hosts
            val count = selected.count { it.value }
            Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${hosts.size} host in ${current.source}", modifier = Modifier.weight(1f))
                Button(
                    "Importa $count host",
                    enabled = count > 0 && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val summary = service.apply(hosts.filterIndexed { index, _ -> selected[index] == true })
                            busy = false
                            preview = null
                            outcome = buildString {
                                append("Aggiunti ${summary.hostsAdded} host")
                                if (summary.keysImported > 0) append(", ${summary.keysImported} chiavi")
                                if (summary.duplicatesSkipped > 0) append(", ${summary.duplicatesSkipped} già presenti")
                                summary.notes.forEach { append("\n· ").append(it) }
                            }
                        }
                    },
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                items(hosts.indices.toList()) { index ->
                    ImportRow(hosts[index], service.isDuplicate(workspace, hosts[index]), selected[index] == true) { selected[index] = it }
                    Divider()
                }
                if (current.result.warnings.isNotEmpty()) {
                    item { SectionLabel("Avvisi") }
                    items(current.result.warnings) { warning ->
                        val where = listOfNotNull(warning.file, warning.line?.let { "riga $it" }).joinToString(", ")
                        Text(
                            (if (where.isEmpty()) "" else "$where: ") + warning.message,
                            style = Carta.type.label, color = Carta.colors.textDim, modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportRow(host: ImportedHost, duplicate: Boolean, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onChecked, host.label)
            if (duplicate) { Gap(width = 6); Badge("già presente") }
        }
        val details = listOfNotNull(
            (host.username?.let { "$it@" } ?: "") + host.address + (host.port?.takeIf { it != 22 }?.let { ":$it" } ?: ""),
            host.groupPath.takeIf { it.isNotEmpty() }?.joinToString(" / "),
            host.proxyJump.takeIf { it.isNotEmpty() }?.joinToString(" → ", prefix = "via ") { it.host },
            host.identityFiles.firstOrNull()?.let { "chiave $it" },
            if (host.password != null) "password" else null,
        )
        Text(
            details.joinToString(" · "),
            modifier = Modifier.padding(start = 30.dp, bottom = 4.dp),
            style = Carta.type.label, color = Carta.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
