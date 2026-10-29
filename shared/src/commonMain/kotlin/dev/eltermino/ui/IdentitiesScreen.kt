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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.describe
import dev.eltermino.app.hostsUsing
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.newId
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Picker
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

@Composable
internal fun IdentitiesScreen(state: AppState, modifier: Modifier = Modifier) {
    val workspace by state.services.workspace.workspace.collectAsState()
    var editing by remember { mutableStateOf<Pair<Identity, Boolean>?>(null) }
    var deleting by remember { mutableStateOf<Identity?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Identità", Modifier.weight(1f))
            Button("Nuova identità", onClick = { editing = Identity(newId(), "", "") to true })
        }
        Hint("Un'identità è un utente con password o chiave, da usare su più host.")
        if (workspace.identities.isEmpty()) {
            EmptyState("Nessuna identità", "Creane una, oppure salva la password al primo login.")
        }
        LazyColumn {
            items(workspace.identities.sortedBy { it.label.lowercase() }, key = { it.id }) { identity ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(identity.label.ifBlank { identity.username })
                        Text(
                            identity.describe(workspace) + " · usata da ${workspace.hostsUsing(identity.id)} host",
                            style = Carta.type.label, color = Carta.colors.textDim,
                        )
                    }
                    Button("Modifica", onClick = { editing = identity to false }, kind = ButtonKind.Ghost)
                    Button("Elimina", onClick = { deleting = identity }, kind = ButtonKind.Ghost)
                }
                Divider()
            }
        }
    }

    editing?.let { (identity, isNew) ->
        IdentityEditorDialog(state, identity, isNew) { editing = null }
    }
    deleting?.let { identity ->
        Dialog(
            onDismiss = { deleting = null },
            title = "Eliminare ${identity.label.ifBlank { identity.username }}?",
            actions = {
                Button("Annulla", onClick = { deleting = null }, kind = ButtonKind.Ghost)
                Button("Elimina", kind = ButtonKind.Danger, onClick = {
                    deleting = null
                    scope.launch { state.actions.deleteIdentity(identity.id) }
                })
            },
        ) {
            Text("La password salvata viene cancellata dal vault. Gli host che la usano tengono il nome utente.")
        }
    }
}

/** [onDismiss] gets the saved identity, or null when cancelled. */
@Composable
internal fun IdentityEditorDialog(state: AppState, initial: Identity, isNew: Boolean, onDismiss: (Identity?) -> Unit) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(initial.label) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var removePassword by remember { mutableStateOf(false) }
    var keyId by remember { mutableStateOf(initial.keyId) }
    var submitted by remember { mutableStateOf(false) }

    Dialog(
        onDismiss = { onDismiss(null) },
        title = if (isNew) "Nuova identità" else "Modifica identità",
        actions = {
            Button("Annulla", onClick = { onDismiss(null) }, kind = ButtonKind.Ghost)
            Button("Salva", onClick = {
                submitted = true
                if (username.isBlank()) return@Button
                scope.launch {
                    val newPassword = when {
                        removePassword -> ByteArray(0)
                        password.isNotEmpty() -> password.encodeToByteArray()
                        else -> null
                    }
                    val saved = state.actions.saveIdentity(
                        initial.copy(label = label.trim().ifBlank { username }, username = username, keyId = keyId),
                        newPassword,
                    )
                    onDismiss(saved)
                }
            })
        },
    ) {
        Field("Nome", label, { label = it }, placeholder = "deploy (produzione)")
        Field("Nome utente", username, { username = it.trim() }, error = if (submitted && username.isBlank()) "Serve un nome utente" else null)
        Field(
            "Password", password, { password = it }, secret = true,
            placeholder = if (initial.passwordSecretId != null) "Salvata: lascia vuoto per non cambiarla" else "Facoltativa",
        )
        if (initial.passwordSecretId != null) Checkbox(removePassword, { removePassword = it }, "Cancella la password salvata")
        Picker(
            label = "Chiave",
            options = listOf<String?>(null) + workspace.keys.map { it.id },
            selected = keyId,
            display = { id -> id?.let { workspace.key(it) }?.let { "${it.label} (${it.type})" } ?: "Nessuna chiave" },
            onSelect = { keyId = it },
        )
        if (workspace.keys.isEmpty()) {
            Text("Le chiavi si aggiungono dalla sezione Chiavi.", style = Carta.type.label, color = Carta.colors.textDim)
        }
    }
}
