package dev.eltermino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.RecordingSummary
import dev.eltermino.app.ReplayTab
import dev.eltermino.app.formatBytes
import dev.eltermino.app.formatFileTime
import dev.eltermino.app.summary
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Picker
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The recordings on this device: play, export, delete. */
@Composable
internal fun RecordingsScreen(state: AppState, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val store = state.services.recordings
    var recordings by remember { mutableStateOf<List<RecordingSummary>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(version) {
        recordings = withContext(Dispatchers.Default) { store?.list()?.mapNotNull { it.summary() }.orEmpty() }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        ScreenTitle("Registrazioni")
        Hint(
            "Quello che compare nel terminale, con i suoi tempi, in formato asciicast. Avvia una registrazione dal menu di una scheda, " +
                "o registra sempre dalle Impostazioni. I file restano su questo dispositivo, non cifrati.",
        )
        notice?.let { Text(it, style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(top = 8.dp)) }
        val list = recordings
        when {
            store == null -> EmptyState("Registrazioni non disponibili", "Su questo dispositivo non c'è dove salvarle.")
            list == null -> Unit
            list.isEmpty() -> EmptyState("Nessuna registrazione", "Dal menu di una scheda del terminale scegli \"Avvia registrazione\".")
            else -> Column(Modifier.padding(top = 12.dp)) {
                list.forEachIndexed { index, recording ->
                    if (index > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(recording.title.ifBlank { recording.id }, style = Carta.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${formatFileTime(recording.startedEpochSeconds)} · ${formatClock(recording.durationSeconds)} · ${formatBytes(recording.sizeBytes)}",
                                style = Carta.type.small,
                                color = Carta.colors.textDim,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button("Riproduci", onClick = {
                                if (state.sessions.openReplay(recording) != null) onPlay() else notice = "Non riesco a leggere ${recording.id}"
                            }, kind = ButtonKind.Ghost)
                            Button("Esporta…", onClick = {
                                scope.launch {
                                    val bytes = withContext(Dispatchers.Default) { store.read(recording.id) }
                                    notice = when {
                                        bytes == null -> "Non riesco a leggere ${recording.id}"
                                        state.services.platform.saveFile(recording.id, bytes) -> "Esportata ${recording.id}"
                                        else -> null
                                    }
                                }
                            }, kind = ButtonKind.Ghost)
                            Button("Elimina", onClick = {
                                scope.launch {
                                    withContext(Dispatchers.Default) { store.delete(recording.id) }
                                    version++
                                }
                            }, kind = ButtonKind.Ghost)
                        }
                    }
                }
            }
        }
    }
}

/** Play, pause, speed and where it is, above the recording playing in a terminal. */
@Composable
internal fun ReplayPane(tab: ReplayTab, showExtraKeys: Boolean) {
    val playing by tab.playing.collectAsState()
    val finished by tab.finished.collectAsState()
    val speed by tab.speed.collectAsState()
    val position by tab.position.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                finished -> Button("Ricomincia", onClick = tab::restart, kind = ButtonKind.Secondary)
                playing -> Button("Pausa", onClick = { tab.playing.value = false }, kind = ButtonKind.Secondary)
                else -> Button("Riprendi", onClick = { tab.playing.value = true }, kind = ButtonKind.Secondary)
            }
            if (!finished) Button("Ricomincia", onClick = tab::restart, kind = ButtonKind.Ghost)
            Picker("Velocità", listOf(1f, 2f, 4f, 8f), speed, { "${it.toInt()}×" }, { tab.speed.value = it }, Modifier.width(90.dp))
            Text(
                "${formatClock(position)} di ${formatClock(tab.cast.durationSeconds)}",
                style = Carta.type.small,
                color = Carta.colors.textDim,
                modifier = Modifier.weight(1f),
            )
        }
        Divider()
        Terminal(tab.terminal, showExtraKeys = false, focused = false)
    }
}

/** "1:05", or "1:02:03" past an hour. */
private fun formatClock(seconds: Double): String {
    val total = seconds.toLong()
    val s = (total % 60).toString().padStart(2, '0')
    return if (total >= 3600) "${total / 3600}:${(total % 3600 / 60).toString().padStart(2, '0')}:$s" else "${total / 60}:$s"
}

/** Recording and how long to keep recordings, in Settings. */
@Composable
internal fun RecordingSettings(record: Boolean, days: Int, onRecord: (Boolean) -> Unit, onDays: (Int) -> Unit) {
    SettingsSection("Registrazioni", "Quello che compare nei terminali, salvato su questo dispositivo senza cifratura: evita di registrare dove leggi segreti.") {
        dev.eltermino.ui.components.Checkbox(record, onRecord, "Registra tutti i terminali da quando si aprono")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Elimina le registrazioni dopo", modifier = Modifier.padding(end = 8.dp))
            Picker("Conservazione", listOf(7, 30, 90, 365, 0), days, { if (it == 0) "mai" else "$it giorni" }, onDays, Modifier.width(140.dp))
        }
    }
}
