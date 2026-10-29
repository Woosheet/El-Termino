package dev.eltermino.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.identitiesUsing
import dev.eltermino.core.data.SshKeyEntry
import dev.eltermino.core.ssh.InvalidKeyException
import dev.eltermino.ui.components.Badge
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

private class KeyDraft(val label: String, val bytes: ByteArray)

@Composable
internal fun KeysScreen(state: AppState, modifier: Modifier = Modifier) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var draft by remember { mutableStateOf<KeyDraft?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var certificateFor by remember { mutableStateOf<SshKeyEntry?>(null) }
    var deleting by remember { mutableStateOf<SshKeyEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Chiavi", Modifier.weight(1f))
            Button("Importa da file", kind = ButtonKind.Secondary, onClick = {
                scope.launch {
                    state.services.platform.pickFile("Scegli una chiave privata")?.let { draft = KeyDraft(it.name, it.bytes) }
                }
            })
            Gap(width = 8)
            Button("Incolla", kind = ButtonKind.Secondary, onClick = { pasting = true })
            Gap(width = 8)
            Button("Genera Ed25519", onClick = { generating = true })
        }
        Hint("OpenSSH, PEM o PuTTY (.ppk). Le chiavi private restano cifrate nel vault.")
        message?.let { Text(it, style = Carta.type.small, color = Carta.colors.accent, modifier = Modifier.padding(bottom = 8.dp)) }
        if (workspace.keys.isEmpty()) EmptyState("Nessuna chiave", "Importa la tua chiave o generane una nuova.")
        LazyColumn {
            items(workspace.keys.sortedBy { it.label.lowercase() }, key = { it.id }) { key ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(key.label)
                            Gap(width = 6)
                            Badge(key.type)
                            if (key.encrypted) { Gap(width = 4); Badge("passphrase") }
                            if (key.certificate != null) { Gap(width = 4); Badge("certificato") }
                        }
                        Text(
                            "${key.fingerprintSha256} · ${workspace.identitiesUsing(key.id)} identità",
                            style = Carta.type.label, color = Carta.colors.textDim, fontFamily = Carta.monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button("Copia pubblica", kind = ButtonKind.Ghost, onClick = {
                        clipboard.setText(AnnotatedString(key.publicKey))
                        message = "Chiave pubblica di ${key.label} copiata"
                    })
                    Button("Certificato", kind = ButtonKind.Ghost, onClick = { certificateFor = key })
                    Button("Elimina", kind = ButtonKind.Ghost, onClick = { deleting = key })
                }
                Divider()
            }
        }
    }

    draft?.let { pending ->
        ImportKeyDialog(state, pending) { result ->
            draft = null
            pending.bytes.fill(0)
            if (result != null) message = "Chiave ${result.label} importata"
        }
    }
    if (pasting) {
        PasteKeyDialog(onDismiss = { pasting = false }) { label, text ->
            pasting = false
            draft = KeyDraft(label, text.encodeToByteArray())
        }
    }
    if (generating) {
        NameDialog("Nuova chiave Ed25519", "Nome della chiave", "laptop-2026", onDismiss = { generating = false }) { name ->
            generating = false
            scope.launch {
                val key = state.actions.generateKey(name)
                clipboard.setText(AnnotatedString(key.publicKey))
                message = "Chiave $name creata. La pubblica è negli appunti: aggiungila a authorized_keys sui server."
            }
        }
    }
    certificateFor?.let { key ->
        CertificateDialog(key, onDismiss = { certificateFor = null }) { certificate ->
            certificateFor = null
            scope.launch { state.actions.updateKey(key.copy(certificate = certificate)) }
        }
    }
    deleting?.let { key ->
        Dialog(
            onDismiss = { deleting = null },
            title = "Eliminare ${key.label}?",
            actions = {
                Button("Annulla", onClick = { deleting = null }, kind = ButtonKind.Ghost)
                Button("Elimina", kind = ButtonKind.Danger, onClick = {
                    deleting = null
                    scope.launch { state.actions.deleteKey(key.id) }
                })
            },
        ) {
            Text("La chiave privata viene cancellata dal vault. Le identità che la usano restano senza chiave.")
        }
    }
}

@Composable
private fun ImportKeyDialog(state: AppState, draft: KeyDraft, onDone: (SshKeyEntry?) -> Unit) {
    val scope = rememberCoroutineScope()
    val encrypted = remember(draft) { runCatching { state.services.keyTool.isEncrypted(draft.bytes) }.getOrDefault(false) }
    var label by remember { mutableStateOf(draft.label.removeSuffix(".pem").removeSuffix(".ppk")) }
    var passphrase by remember { mutableStateOf("") }
    var savePassphrase by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismiss = { onDone(null) },
        title = "Importa chiave",
        actions = {
            Button("Annulla", onClick = { onDone(null) }, kind = ButtonKind.Ghost)
            Button("Importa", onClick = {
                scope.launch {
                    try {
                        val entry = state.actions.importKey(
                            label = label.trim().ifBlank { "chiave" },
                            privateKey = draft.bytes,
                            passphrase = if (encrypted) passphrase.encodeToByteArray() else null,
                            savePassphrase = savePassphrase,
                        )
                        onDone(entry)
                    } catch (e: InvalidKeyException) {
                        error = if (encrypted) "Passphrase sbagliata, oppure il file non è una chiave" else "Il file non è una chiave supportata"
                    }
                }
            })
        },
    ) {
        Field("Nome", label, { label = it })
        if (encrypted) {
            Field("Passphrase", passphrase, { passphrase = it; error = null }, secret = true, error = error)
            Checkbox(savePassphrase, { savePassphrase = it }, "Salva la passphrase nel vault")
        } else {
            error?.let { Text(it, color = Carta.colors.danger, style = Carta.type.small) }
        }
    }
}

@Composable
private fun PasteKeyDialog(onDismiss: () -> Unit, onPasted: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    Dialog(
        onDismiss = onDismiss,
        title = "Incolla una chiave privata",
        width = 540.dp,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Continua", onClick = { onPasted(label.ifBlank { "chiave" }, text.trim() + "\n") }, enabled = text.isNotBlank())
        },
    ) {
        Field("Nome", label, { label = it })
        Field("Chiave privata", text, { text = it }, singleLine = false, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----")
    }
}

@Composable
private fun CertificateDialog(key: SshKeyEntry, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(key.certificate.orEmpty()) }
    Dialog(
        onDismiss = onDismiss,
        title = "Certificato di ${key.label}",
        width = 540.dp,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Salva", onClick = { onSave(text.trim().ifBlank { null }) })
        },
    ) {
        Text("Incolla il contenuto del file -cert.pub firmato dalla tua CA. Lascia vuoto per toglierlo.", style = Carta.type.small)
        Gap(height = 8)
        Field("Certificato", text, { text = it }, singleLine = false, placeholder = "ssh-ed25519-cert-v01@openssh.com AAAA…")
        SelectionContainer { Text(key.publicKey, style = Carta.type.caption, fontFamily = Carta.monospace, color = Carta.colors.textDim) }
    }
}

@Composable
internal fun NameDialog(title: String, label: String, placeholder: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Dialog(
        onDismiss = onDismiss,
        title = title,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Crea", onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank())
        },
    ) {
        Field(label, name, { name = it }, placeholder = placeholder)
    }
}
