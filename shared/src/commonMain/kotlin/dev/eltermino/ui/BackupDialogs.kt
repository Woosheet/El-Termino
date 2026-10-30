package dev.eltermino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.BackupPreview
import dev.eltermino.app.PickedFile
import dev.eltermino.app.backupFileName
import dev.eltermino.app.backupPasswordProblem
import dev.eltermino.core.data.BackupContent
import dev.eltermino.core.vault.BackupTooNewException
import dev.eltermino.core.vault.VaultException
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.DialogTone
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Moving everything to another device by hand, since nothing syncs. */
@Composable
internal fun BackupSection(state: AppState) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    SettingsSection("Backup", "Host, identità, chiavi, snippet, richieste e segreti in un solo file cifrato, per spostarli su un altro dispositivo. Tema e impostazioni restano qui.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Crea backup…", onClick = { creating = true }, kind = ButtonKind.Secondary)
            Button("Ripristina backup…", onClick = {
                scope.launch { picked = state.services.platform.pickFile("Scegli il backup") }
            }, kind = ButtonKind.Secondary)
        }
        Button("Salva l'ultima copia di sicurezza…", onClick = {
            scope.launch {
                val copy = state.services.backup.lastSafetyCopy()
                if (copy == null) message = "Nessuna copia di sicurezza: se ne crea una a ogni ripristino."
                else if (state.services.platform.saveFile("el-termino-copia-di-sicurezza.etbackup", copy)) message = "Copia salvata: si apre con la password del backup ripristinato."
            }
        }, kind = ButtonKind.Ghost, modifier = Modifier.padding(top = 8.dp))
        message?.let { Text(it, style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(top = 8.dp)) }
    }
    if (creating) CreateBackupDialog(state, onDismiss = { creating = false }) { message = it }
    picked?.let { file -> RestoreBackupDialog(state, file, onDismiss = { picked = null }) { message = it } }
}

@Composable
private fun CreateBackupDialog(state: AppState, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = backupPasswordProblem(password, repeated)
    Dialog(
        onDismiss = onDismiss,
        title = "Crea backup",
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Crea", enabled = problem == null && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val bytes = withContext(Dispatchers.Default) { state.services.backup.export(password.toCharArray()) }
                        if (state.services.platform.saveFile(backupFileName(), bytes)) onDone("Backup salvato.")
                        onDismiss()
                    } catch (e: Exception) {
                        error = "Backup non riuscito: ${e.message}"
                        busy = false
                    }
                }
            })
        },
    ) {
        Text("Se perdi questa password il backup non si può più aprire: non c'è modo di recuperarla.")
        Gap(height = 12)
        Field("Password del backup", password, { password = it }, secret = true)
        Gap(height = 8)
        Field("Ripeti la password", repeated, { repeated = it }, secret = true, error = error ?: problem?.takeIf { repeated.isNotEmpty() })
    }
}

@Composable
private fun RestoreBackupDialog(state: AppState, file: PickedFile, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val backup = state.services.backup
    var password by remember { mutableStateOf("") }
    var content by remember { mutableStateOf<BackupContent?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun restore(replaceAll: Boolean) {
        val chosen = content ?: return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.Default) { backup.restore(chosen, replaceAll, password.toCharArray()) }
                onDone("Backup ripristinato.")
                onDismiss()
            } catch (e: Exception) {
                error = "Ripristino non riuscito, i dati sono rimasti com'erano: ${e.message}"
                busy = false
            }
        }
    }

    val summary = preview
    when {
        confirmReplace -> Dialog(
            onDismiss = { confirmReplace = false },
            title = "Sostituire tutto?",
            tone = DialogTone.Danger,
            actions = {
                Button("Annulla", enabled = !busy, onClick = { confirmReplace = false }, kind = ButtonKind.Ghost)
                Button("Sostituisci tutto", enabled = !busy, onClick = { restore(replaceAll = true) }, kind = ButtonKind.Danger)
            },
        ) {
            Text("Host, identità, chiavi e segreti di questo dispositivo verranno sostituiti da quelli del backup. Prima l'app ne salva una copia cifrata con la stessa password.")
            error?.let { Text(it, color = Carta.colors.danger) }
        }
        summary == null -> Dialog(
            onDismiss = onDismiss,
            title = "Ripristina backup",
            actions = {
                Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
                Button("Apri", enabled = password.isNotEmpty() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val read = withContext(Dispatchers.Default) { backup.read(file.bytes, password.toCharArray()) }
                            content = read
                            preview = backup.preview(read)
                        } catch (e: BackupTooNewException) {
                            error = "Questo backup viene da una versione più nuova dell'app: aggiorna l'app e riprova."
                        } catch (e: VaultException) {
                            error = "Password sbagliata, oppure il file è danneggiato."
                        }
                        busy = false
                    }
                })
            },
        ) {
            Text(file.name)
            Gap(height = 12)
            Field("Password del backup", password, { password = it }, secret = true, error = error)
        }
        else -> Dialog(
            onDismiss = onDismiss,
            title = "Ripristina backup",
            width = 520.dp,
            actions = {
                Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
                Button("Sostituisci tutto…", onClick = { confirmReplace = true }, kind = ButtonKind.Secondary)
                Button("Unisci", enabled = !busy, onClick = { restore(replaceAll = false) })
            },
        ) {
            Text("${summary.hosts} host, ${summary.identities} identità, ${summary.keys} chiavi, ${summary.snippets} snippet, ${summary.collections} collezioni, ${summary.layouts} spazi di lavoro.")
            Gap(height = 8)
            Text("Unisci: gli elementi già presenti prendono la versione del backup, gli altri restano.", style = Carta.type.small, color = Carta.colors.textDim)
            if (summary.cas + summary.httpCerts > 0) {
                Gap(height = 8)
                Text("Il backup fa fidare questo dispositivo di ${summary.cas} CA e ${summary.httpCerts} certificati HTTPS accettati a mano: ripristinalo solo se è tuo.", style = Carta.type.small, color = Carta.colors.danger)
            }
            if (summary.knownHostConflicts.isNotEmpty()) {
                Gap(height = 8)
                val hosts = summary.knownHostConflicts.joinToString { "${it.host}:${it.port}" }
                Text("Con Unisci restano le chiavi di questo dispositivo per: $hosts. Nel backup ne risulta una diversa: controlla se è cambiata davvero.", style = Carta.type.small, color = Carta.colors.danger)
            }
            error?.let { Text(it, color = Carta.colors.danger) }
        }
    }
}
