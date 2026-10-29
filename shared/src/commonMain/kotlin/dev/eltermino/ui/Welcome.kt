package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.ImportService
import dev.eltermino.app.TermiusScan
import dev.eltermino.core.importer.ImportResult
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.ProgressBar
import dev.eltermino.ui.components.Spinner
import dev.eltermino.ui.components.StatusDot
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.tabular
import kotlinx.coroutines.launch

/** What the Termius step found and did. */
private sealed interface TermiusStep {
    data object Asking : TermiusStep
    data object Looking : TermiusStep
    class Found(val result: ImportResult) : TermiusStep
    class Problem(val reason: String) : TermiusStep
    class Imported(val summary: String) : TermiusStep
}

private class TourPage(val title: String, val body: String, val fragment: @Composable () -> Unit)

/**
 * First run: asks whether Termius is in use here and imports from it, then walks through
 * what the app does, one feature a page, each with a piece of the real interface.
 * [startAtTour] skips the question, as when reopened from the settings.
 */
@Composable
internal fun WelcomeFlow(state: AppState, startAtTour: Boolean, onOpenImport: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val service = remember { ImportService(state.services, state.actions) }
    val platform = state.services.platform
    var page by remember { mutableIntStateOf(if (startAtTour) 1 else 0) }
    var termius by remember { mutableStateOf<TermiusStep>(TermiusStep.Asking) }
    val tour = remember { tourPages() }
    val last = tour.size

    fun finish() {
        scope.launch { state.services.workspace.update { it.copy(ui = it.ui.copy(welcomeDone = true)) } }
        onDone()
    }

    if (page == 0) {
        Dialog(
            onDismiss = ::finish,
            title = "Benvenuto in El Termino",
            icon = CartaIcons.Snippet,
            width = 620.dp,
            actions = {
                Button("Salta", onClick = ::finish, kind = ButtonKind.Ghost)
                when (val step = termius) {
                    TermiusStep.Asking -> {
                        Button("No, parto da zero", onClick = { page = 1 }, kind = ButtonKind.Secondary)
                        Button("Sì, uso Termius", onClick = {
                            if (!platform.isDesktop) {
                                termius = TermiusStep.Problem("Su Android i dati di Termius non sono leggibili. Esporta un CSV da Termius sul computer e importalo da Importa.")
                                return@Button
                            }
                            termius = TermiusStep.Looking
                            scope.launch {
                                termius = when (val scan = platform.readTermius()) {
                                    TermiusScan.NotFound -> TermiusStep.Problem("Non trovo Termius su questo computer. Se lo usi su un altro, esporta da lì un CSV e importalo da Importa.")
                                    is TermiusScan.Unreadable -> TermiusStep.Problem(scan.reason + ". Puoi sempre importare un CSV o ~/.ssh/config da Importa.")
                                    is TermiusScan.Found -> TermiusStep.Found(scan.result)
                                }
                            }
                        })
                    }
                    TermiusStep.Looking -> Unit
                    is TermiusStep.Found -> {
                        Button("Non ora", onClick = { page = 1 }, kind = ButtonKind.Secondary)
                        val fresh = step.result.hosts.filterNot { service.isDuplicate(state.services.workspace.workspace.value, it) }
                        Button(if (fresh.size == 1) "Importa 1 host" else "Importa ${fresh.size} host", enabled = fresh.isNotEmpty(), onClick = {
                            scope.launch {
                                val summary = service.apply(fresh)
                                termius = TermiusStep.Imported(
                                    buildString {
                                        append(if (summary.hostsAdded == 1) "1 host importato" else "${summary.hostsAdded} host importati")
                                        if (summary.hostsCompleted > 0) append(", credenziali aggiunte a ${summary.hostsCompleted} già presenti")
                                        if (summary.keysImported > 0) append(", ${summary.keysImported} chiavi nel vault")
                                        if (summary.duplicatesSkipped > 0) append(", ${summary.duplicatesSkipped} già presenti")
                                        append(".")
                                    },
                                )
                            }
                        })
                    }
                    is TermiusStep.Problem -> {
                        Button("Apri Importa", onClick = { finish(); onOpenImport() }, kind = ButtonKind.Secondary)
                        Button("Continua", onClick = { page = 1 })
                    }
                    is TermiusStep.Imported -> Button("Continua", onClick = { page = 1 })
                }
            },
        ) {
            Text("Un client SSH e SFTP per le tue macchine: terminali, file e trasferimenti, con le password e le chiavi in un vault cifrato su questo dispositivo.")
            Gap(height = 16)
            when (val step = termius) {
                TermiusStep.Asking -> {
                    Text("Usi Termius su questo computer?", style = Carta.type.heading)
                    Gap(height = 4)
                    Text(
                        "Se sì, leggo i suoi host, gruppi, identità e chiavi per portarli qui. Il sistema ti chiederà il permesso di aprire la chiave di Termius nel portachiavi.",
                        style = Carta.type.small,
                        color = Carta.colors.textDim,
                    )
                }
                TermiusStep.Looking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 18.dp)
                    Text("Cerco i dati di Termius…", modifier = Modifier.padding(start = 10.dp), color = Carta.colors.textDim)
                }
                is TermiusStep.Found -> {
                    val hosts = step.result.hosts
                    val groups = hosts.mapNotNull { it.groupPath.firstOrNull() }.distinct()
                    val keys = hosts.mapNotNull { it.key?.label }.distinct()
                    Text("Ho trovato i tuoi dati di Termius", style = Carta.type.heading)
                    Gap(height = 8)
                    FactRow(hosts.size, if (hosts.size == 1) "host" else "host")
                    FactRow(groups.size, if (groups.size == 1) "gruppo" else "gruppi")
                    FactRow(hosts.count { it.username != null }, "con utente")
                    FactRow(hosts.count { it.password != null }, "con password, che vanno nel vault")
                    FactRow(keys.size, if (keys.size == 1) "chiave" else "chiavi")
                    Gap(height = 8)
                    Text("Gli host già presenti qui vengono saltati. Puoi rivedere tutto in qualsiasi momento da Importa.", style = Carta.type.small, color = Carta.colors.textDim)
                }
                is TermiusStep.Problem -> Text(step.reason)
                is TermiusStep.Imported -> {
                    Text("Fatto", style = Carta.type.heading)
                    Gap(height = 4)
                    Text(step.summary)
                }
            }
        }
        return
    }

    val current = tour[page - 1]
    Dialog(
        onDismiss = ::finish,
        title = current.title,
        width = 620.dp,
        actions = {
            Text("$page di $last", style = Carta.type.small.tabular(), color = Carta.colors.textDim, modifier = Modifier.weight(1f).padding(top = 8.dp))
            if (page < last) Button("Salta il tutorial", onClick = ::finish, kind = ButtonKind.Ghost)
            if (page > 1) Button("Indietro", onClick = { page-- }, kind = ButtonKind.Secondary)
            if (page < last) Button("Avanti", onClick = { page++ }) else Button("Inizia", onClick = ::finish)
        },
    ) {
        Text(current.body)
        Gap(height = 16)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Carta.colors.chrome).border(1.dp, Carta.colors.line, RoundedCornerShape(8.dp)).padding(16.dp),
        ) { current.fragment() }
    }
}

@Composable
private fun FactRow(count: Int, label: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$count", style = Carta.type.bodyStrong.tabular(), modifier = Modifier.width(44.dp))
        Text(label, color = Carta.colors.textDim)
    }
}

private fun tourPages() = listOf(
    TourPage(
        "Host e identità",
        "Aggiungi un host con il + nella barra laterale e mettilo in un gruppo. Un'identità tiene utente, password o chiave, e la usi su quanti host vuoi.",
    ) {
        Column {
            Text("Produzione", style = Carta.type.label, color = Carta.colors.textDim)
            Gap(height = 6)
            FragmentHost("web-01", "deploy@10.0.0.12", online = true)
            FragmentHost("db-01", "deploy@10.0.0.20 · via bastion", online = false)
        }
    },
    TourPage(
        "Terminali che restano",
        "Un clic su un host apre il terminale. Un secondo terminale sulla stessa macchina si apre subito, senza un nuovo login; se la rete cade, si ricollega da solo e lo scrollback resta.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FragmentChip("web-01 · 1", Carta.colors.online, selected = true)
            FragmentChip("web-01 · 2", Carta.colors.online)
            FragmentChip("db-01", Carta.colors.accent)
        }
    },
    TourPage(
        "Schede unite e gruppi",
        "Trascina una scheda sull'area delle sessioni per affiancarla, a sinistra, a destra, sopra o sotto. Con il tasto destro su una scheda crei gruppi colorati, anche per host.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Web",
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(groupSoft(dev.eltermino.app.GroupColor.Blu)).padding(horizontal = 10.dp, vertical = 6.dp),
                style = Carta.type.small,
                color = groupStrong(dev.eltermino.app.GroupColor.Blu),
            )
            FragmentChip("web-01 + file di web-01", null, selected = true, split = true)
            FragmentChip("web-02", Carta.colors.online)
        }
    },
    TourPage(
        "File e trasferimenti",
        "Dal menu di un host apri i suoi file. Affianca due pannelli e trascina i file tra loro, o dal Finder. Una coda sola, con pausa, ripresa e la scelta su cosa fare se un file esiste già.",
    ) {
        Column {
            FragmentFile("backup.tar.gz", "85,8 MB")
            FragmentFile("app.yaml", "2 KB")
            Gap(height = 10)
            Text("backup.tar.gz → questo computer", style = Carta.type.small)
            ProgressBar(0.42f, Modifier.padding(vertical = 4.dp))
            Text("36,0 MB di 85,8 MB · 12,4 MB/s · 4 s", style = Carta.type.caption.tabular(), color = Carta.colors.textDim)
        }
    },
    TourPage(
        "Snippet e broadcast",
        "Salva i comandi che usi spesso, con {{variabili}} da compilare al momento, e lanciali su più host insieme. Il broadcast scrive in più terminali alla volta.",
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Carta.colors.accent).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CartaIcons.Broadcast, contentDescription = null, tint = Carta.colors.background, size = 16.dp)
                Text("Broadcast attivo: quello che scrivi arriva a 3 sessioni", style = Carta.type.small, color = Carta.colors.background, modifier = Modifier.padding(start = 8.dp))
            }
            Gap(height = 10)
            Text("Riavvia un servizio", style = Carta.type.bodyStrong)
            Text("sudo systemctl restart {{servizio}}", fontFamily = Carta.monospace, style = Carta.type.small, color = Carta.colors.textDim)
        }
    },
    TourPage(
        "Spazi di lavoro e temi",
        "Salva le schede aperte, divise e raggruppate, e riaprile con un clic: le sessioni si ricollegano da sole. In Impostazioni scegli il tema, chiaro, Notte o uno dei classici.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.eltermino.ui.theme.Themes.all.forEach { theme ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(width = 56.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)).background(theme.colors.background).border(1.dp, Carta.colors.line, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 24.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(theme.colors.accent))
                    }
                    Text(theme.name, style = Carta.type.caption, color = Carta.colors.textDim, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    },
)

@Composable
private fun FragmentHost(label: String, subtitle: String, online: Boolean) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (online) Carta.colors.online else Carta.colors.offline)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label)
            Text(subtitle, style = Carta.type.caption, color = Carta.colors.textDim)
        }
    }
}

@Composable
private fun FragmentChip(label: String, dot: androidx.compose.ui.graphics.Color?, selected: Boolean = false, split: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (selected) Carta.colors.background else Carta.colors.hover).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (split) Icon(CartaIcons.SplitColumns, contentDescription = null, tint = Carta.colors.textDim, size = 14.dp)
        if (dot != null) Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, style = Carta.type.small, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun FragmentFile(name: String, size: String) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CartaIcons.Document, contentDescription = null, tint = Carta.colors.textDim, size = 16.dp)
        Text(name, style = Carta.type.small, modifier = Modifier.weight(1f).padding(start = 6.dp))
        Text(size, style = Carta.type.small.tabular(), color = Carta.colors.textDim)
    }
}
