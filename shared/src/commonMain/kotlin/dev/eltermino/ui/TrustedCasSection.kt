package dev.eltermino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.core.data.TrustedCa
import dev.eltermino.core.ssh.InvalidKeyException
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.DialogTone
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

private const val NEW_CA = "new"

/** Authorities that vouch for hosts, like `@cert-authority` in `known_hosts`. */
@Composable
internal fun TrustedCasSection(state: AppState) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<TrustedCa?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    SettingsSection("CA degli host", "Gli host coperti da una CA si collegano senza conferma se il certificato è valido. Se non lo è, la connessione si blocca.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            workspace.trustedCas.forEach { ca ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ca.label)
                        Text("${ca.patterns.joinToString()} · ${ca.fingerprintSha256}", style = Carta.type.small, color = Carta.colors.textDim)
                    }
                    Button("Modifica", onClick = { editing = ca.id }, kind = ButtonKind.Ghost)
                    Button("Rimuovi", onClick = { removing = ca }, kind = ButtonKind.Ghost)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Aggiungi CA…", onClick = { editing = NEW_CA }, kind = ButtonKind.Secondary)
                state.services.platform.readKnownHosts()?.let { text ->
                    Button("Importa da known_hosts", onClick = {
                        scope.launch {
                            val added = state.actions.importTrustedCas(text)
                            message = if (added == 0) "Nessuna CA nuova in known_hosts." else "CA importate: $added."
                        }
                    }, kind = ButtonKind.Ghost)
                }
            }
            message?.let { Text(it, style = Carta.type.small, color = Carta.colors.textDim) }
        }
    }
    editing?.let { id ->
        CaDialog(state, workspace.trustedCas.firstOrNull { it.id == id }, onDismiss = { editing = null })
    }
    removing?.let { ca ->
        Dialog(
            onDismiss = { removing = null },
            title = "Rimuovere ${ca.label}?",
            tone = DialogTone.Danger,
            actions = {
                Button("Annulla", onClick = { removing = null }, kind = ButtonKind.Ghost)
                Button("Rimuovi", onClick = {
                    scope.launch { state.actions.removeTrustedCa(ca.id) }
                    removing = null
                }, kind = ButtonKind.Danger)
            },
        ) {
            Text("Gli host coperti tornano a chiedere conferma alla prima connessione.")
        }
    }
}

@Composable
private fun CaDialog(state: AppState, existing: TrustedCa?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var publicKey by remember { mutableStateOf(existing?.publicKey.orEmpty()) }
    var patterns by remember { mutableStateOf(existing?.patterns?.joinToString(", ").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = patterns.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    Dialog(
        onDismiss = onDismiss,
        title = if (existing == null) "Aggiungi CA" else "Modifica CA",
        width = 560.dp,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Salva", enabled = label.isNotBlank() && publicKey.isNotBlank() && parsed.isNotEmpty(), onClick = {
                scope.launch {
                    try {
                        state.actions.saveTrustedCa(existing?.id, label.trim(), publicKey, parsed)
                        onDismiss()
                    } catch (e: InvalidKeyException) {
                        error = "Non è una chiave pubblica valida"
                    }
                }
            })
        },
    ) {
        Field("Nome", label, { label = it })
        Gap(height = 8)
        Field("Chiave pubblica della CA", publicKey, { publicKey = it; error = null }, singleLine = false, monospace = true, placeholder = "ssh-ed25519 AAAA…", error = error)
        Gap(height = 8)
        Field("Host coperti", patterns, { patterns = it }, placeholder = "*.example.com, [10.0.0.1]:2222", monospace = true)
    }
}
