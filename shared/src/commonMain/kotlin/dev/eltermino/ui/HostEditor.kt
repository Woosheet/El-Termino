package dev.eltermino.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.describe
import dev.eltermino.core.data.Host
import dev.eltermino.core.data.Identity
import dev.eltermino.core.data.groupPath
import dev.eltermino.core.data.jumpChain
import dev.eltermino.core.data.newId
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Picker
import kotlinx.coroutines.launch

private const val NEW_GROUP = "\u0000new"
private const val NEW_IDENTITY = "\u0000create"

/** Creates a host when [initial] is null, otherwise edits it. */
@Composable
internal fun HostEditorDialog(state: AppState, initial: Host?, onDismiss: () -> Unit) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val scope = rememberCoroutineScope()

    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var groupId by remember { mutableStateOf(initial?.groupId) }
    var newGroup by remember { mutableStateOf("") }
    var identityId by remember { mutableStateOf(initial?.identityId) }
    var jumpHostId by remember { mutableStateOf(initial?.jumpHostId) }
    var agentForwarding by remember { mutableStateOf(initial?.agentForwarding == true) }
    var tags by remember { mutableStateOf(initial?.tags?.joinToString(", ").orEmpty()) }
    var favorite by remember { mutableStateOf(initial?.favorite ?: false) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var editingIdentity by remember { mutableStateOf<Identity?>(null) }
    var submitted by remember { mutableStateOf(false) }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val id = initial?.id ?: remember { newId() }
    // A host can't jump through itself or through a host that already jumps through it.
    val jumpCandidates = workspace.hosts.filter { candidate ->
        candidate.id != id && workspace.jumpChain(candidate).none { it.id == id }
    }

    val save: () -> Unit = save@{
        submitted = true
        if (label.isBlank() || address.isBlank() || portNumber == null) return@save
        scope.launch {
            val resolvedGroup = if (groupId == NEW_GROUP) state.actions.ensureGroupPath(newGroup.split('/')) else groupId
            state.actions.saveHost(
                (initial ?: Host(id = id, label = "", address = "")).copy(
                    label = label.trim(),
                    address = address,
                    port = portNumber,
                    username = username.ifBlank { null },
                    groupId = resolvedGroup,
                    identityId = identityId,
                    jumpHostId = jumpHostId,
                    agentForwarding = agentForwarding,
                    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    favorite = favorite,
                    notes = notes,
                ),
            )
            onDismiss()
        }
    }

    Dialog(
        onDismiss = onDismiss,
        title = if (initial == null) "Nuovo host" else "Modifica ${initial.label}",
        width = 540.dp,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Salva", onClick = save)
        },
    ) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Field("Nome", label, { label = it }, placeholder = "web-01", error = if (submitted && label.isBlank()) "Serve un nome" else null)
            Row {
                Field(
                    "Indirizzo", address, { address = it.trim() },
                    modifier = Modifier.weight(1f), placeholder = "10.0.0.11 o web-01.example.com",
                    error = if (submitted && address.isBlank()) "Serve un indirizzo" else null,
                )
                Gap(width = 8)
                Field(
                    "Porta", port, { port = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.width(96.dp), numeric = true,
                    error = if (submitted && portNumber == null) "1–65535" else null,
                )
            }
            Picker(
                label = "Identità",
                options = listOf<String?>(null) + workspace.identities.map { it.id } + NEW_IDENTITY,
                selected = identityId,
                display = { option ->
                    when (option) {
                        null -> "Nessuna: usa il nome utente qui sotto"
                        NEW_IDENTITY -> "Nuova identità…"
                        else -> workspace.identity(option)?.let { "${it.label} (${it.describe(workspace)})" } ?: "?"
                    }
                },
                onSelect = { option ->
                    if (option == NEW_IDENTITY) editingIdentity = Identity(newId(), "", username) else identityId = option
                },
            )
            Field(
                "Nome utente", username, { username = it.trim() },
                placeholder = workspace.identity(identityId)?.let { "dall'identità: ${it.username}" } ?: "root",
            )
            Picker(
                label = "Gruppo",
                options = listOf<String?>(null) + workspace.groups.map { it.id }.sortedBy { workspace.groupPath(it).joinToString("/") } + NEW_GROUP,
                selected = groupId,
                display = { option ->
                    when (option) {
                        null -> "Nessun gruppo"
                        NEW_GROUP -> "Nuovo gruppo…"
                        else -> workspace.groupPath(option).joinToString(" / ")
                    }
                },
                onSelect = { groupId = it },
            )
            if (groupId == NEW_GROUP) {
                Field("Nome del gruppo", newGroup, { newGroup = it }, placeholder = "Produzione / Web")
            }
            Picker(
                label = "Jump host",
                options = listOf<String?>(null) + jumpCandidates.sortedBy { it.label.lowercase() }.map { it.id },
                selected = jumpHostId,
                display = { option -> option?.let { workspace.host(it)?.label } ?: "Nessuno: connessione diretta" },
                onSelect = { jumpHostId = it },
            )
            Field("Tag", tags, { tags = it }, placeholder = "nginx, docker")
            Field("Note", notes, { notes = it }, singleLine = false)
            Checkbox(favorite, { favorite = it }, "Preferito")
            if (state.services.connector.supportsAgent) {
                Checkbox(agentForwarding, { agentForwarding = it }, "Inoltra l'SSH agent a questo host")
                Hint("Chi è root sul server può usare le tue chiavi finché sei collegato.")
            }
        }
    }

    editingIdentity?.let { draft ->
        IdentityEditorDialog(
            state = state,
            initial = draft,
            isNew = true,
            onDismiss = { saved ->
                editingIdentity = null
                if (saved != null) identityId = saved.id
            },
        )
    }
}
