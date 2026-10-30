package dev.eltermino.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import dev.eltermino.app.isLoopbackAddress
import dev.eltermino.app.isValidBindAddress
import dev.eltermino.app.ForwardState
import dev.eltermino.core.data.Host
import dev.eltermino.core.data.PortForwardRule
import dev.eltermino.core.data.newId
import dev.eltermino.ui.components.Badge
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.HoppingSombrero
import dev.eltermino.ui.components.IconButton
import dev.eltermino.ui.components.Picker
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlinx.coroutines.launch

private val KINDS = listOf("local", "remote", "dynamic")

private fun kindLabel(kind: String) = when (kind) {
    "remote" -> "Remota: dal server a questo dispositivo"
    "dynamic" -> "Dinamica: proxy SOCKS che esce dal server"
    else -> "Locale: da questo dispositivo al server"
}

/** One line a person reads as "what goes where". */
internal fun PortForwardRule.route(host: Host): String = when (kind) {
    "remote" -> "${host.label} $bindAddress:$bindPort → $targetHost:$targetPort qui"
    "dynamic" -> "SOCKS su $bindAddress:$bindPort, esce da ${host.label}"
    else -> "$bindAddress:$bindPort → $targetHost:$targetPort su ${host.label}"
}

/** The forwards saved on [hostId]: start, stop, add, change, remove. */
@Composable
internal fun PortForwardsDialog(state: AppState, hostId: String, onDismiss: () -> Unit) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val host = workspace.host(hostId) ?: return
    val states by state.forwards.states.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<PortForwardRule?>(null) }

    editing?.let { rule ->
        RuleEditor(host, rule, onDismiss = { editing = null }) { saved ->
            editing = null
            val updated = host.copy(forwards = if (host.forwards.any { it.id == saved.id }) host.forwards.map { if (it.id == saved.id) saved else it } else host.forwards + saved)
            scope.launch {
                state.actions.saveHost(updated)
                // A running rule picks up its new settings right away.
                if (state.forwards.isRunning(saved.id)) state.forwards.start(updated, saved)
            }
        }
        return
    }

    Dialog(
        onDismiss = onDismiss,
        title = "Port forwarding · ${host.label}",
        width = 560.dp,
        actions = {
            Button("Nuova regola", onClick = { editing = PortForwardRule(newId(), bindPort = 0) }, kind = ButtonKind.Ghost)
            Button("Chiudi", onClick = onDismiss)
        },
    ) {
        if (host.forwards.isEmpty()) {
            Text(
                "Nessuna regola. Con una regola locale raggiungi da qui un servizio che ascolta solo sul server, " +
                    "per esempio un database su localhost:5432.",
                color = Carta.colors.textDim,
            )
        }
        host.forwards.forEachIndexed { index, rule ->
            if (index > 0) Divider()
            RuleRow(
                rule = rule,
                host = host,
                forwardState = states[rule.id],
                onStart = { state.forwards.start(host, rule) },
                onStop = { state.forwards.stop(rule.id) },
                onEdit = { editing = rule },
                onDelete = {
                    state.forwards.stop(rule.id)
                    scope.launch { state.actions.saveHost(host.copy(forwards = host.forwards - rule)) }
                },
            )
        }
        Text(
            "Una regola attiva tiene aperta la connessione all'host finché non la fermi.",
            style = Carta.type.small,
            color = Carta.colors.textDim,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun RuleRow(
    rule: PortForwardRule,
    host: Host,
    forwardState: ForwardState?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Carta.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.name.ifBlank { rule.route(host) }, style = Carta.type.bodyStrong, modifier = Modifier.weight(1f, fill = false))
                if (rule.autoStart) Row(Modifier.padding(start = 8.dp)) { Badge("automatica") }
            }
            if (rule.name.isNotBlank()) Text(rule.route(host), style = Carta.type.small, color = colors.textDim, fontFamily = Carta.monospace)
            when (forwardState) {
                null -> Text("Spenta", style = Carta.type.small, color = colors.textDim)
                ForwardState.Starting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    HoppingSombrero(height = 18.dp)
                    Text("In avvio…", style = Carta.type.small, color = colors.textDim, modifier = Modifier.padding(start = 6.dp))
                }
                is ForwardState.Active -> Text(
                    if (rule.kind == "remote") "Attiva: ${host.label} ascolta sulla porta ${forwardState.port}" else "Attiva su ${rule.bindAddress}:${forwardState.port}",
                    style = Carta.type.small,
                    color = colors.online,
                )
                is ForwardState.Failed -> Text(forwardState.message, style = Carta.type.small, color = colors.danger)
            }
        }
        val running = forwardState == ForwardState.Starting || forwardState is ForwardState.Active
        Button(if (running) "Ferma" else "Avvia", onClick = if (running) onStop else onStart, kind = ButtonKind.Ghost, modifier = Modifier.padding(start = 8.dp))
        IconButton(Icons.Default.Edit, "Modifica ${rule.name.ifBlank { "regola" }}", onClick = onEdit)
        IconButton(Icons.Default.Delete, "Elimina ${rule.name.ifBlank { "regola" }}", onClick = onDelete)
    }
}

@Composable
private fun RuleEditor(host: Host, rule: PortForwardRule, onDismiss: () -> Unit, onSave: (PortForwardRule) -> Unit) {
    val isNew = host.forwards.none { it.id == rule.id }
    var name by remember { mutableStateOf(rule.name) }
    var kind by remember { mutableStateOf(rule.kind) }
    var bindPort by remember { mutableStateOf(if (rule.bindPort > 0) rule.bindPort.toString() else "") }
    var targetHost by remember { mutableStateOf(rule.targetHost) }
    var targetPort by remember { mutableStateOf(if (rule.targetPort > 0) rule.targetPort.toString() else "") }
    var autoStart by remember { mutableStateOf(rule.autoStart) }
    var bindAddress by remember { mutableStateOf(rule.bindAddress) }
    var tried by remember { mutableStateOf(false) }

    val bindError = portError(bindPort)
    val targetHostError = if (kind != "dynamic" && targetHost.isBlank()) "Scrivi un indirizzo, per esempio localhost" else null
    val targetPortError = if (kind != "dynamic") portError(targetPort) else null
    val addressError = if (isValidBindAddress(bindAddress.trim())) null else "Un indirizzo IP o un nome"
    val valid = bindError == null && targetHostError == null && targetPortError == null && addressError == null

    Dialog(
        onDismiss = onDismiss,
        title = if (isNew) "Nuova regola · ${host.label}" else "Modifica regola · ${host.label}",
        width = 520.dp,
        actions = {
            Button("Annulla", onClick = onDismiss, kind = ButtonKind.Ghost)
            Button("Salva", onClick = {
                tried = true
                if (valid) onSave(
                    rule.copy(
                        name = name.trim(),
                        kind = kind,
                        bindPort = bindPort.trim().toInt(),
                        targetHost = targetHost.trim(),
                        targetPort = if (kind == "dynamic") 0 else targetPort.trim().toInt(),
                        autoStart = autoStart,
                        bindAddress = bindAddress.trim(),
                    ),
                )
            })
        },
    ) {
        Field("Nome", name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Database")
        Picker("Tipo", KINDS, kind, ::kindLabel, { kind = it }, Modifier.fillMaxWidth())
        Field(
            "Indirizzo di ascolto",
            bindAddress,
            { bindAddress = it },
            Modifier.fillMaxWidth(),
            monospace = true,
            placeholder = "127.0.0.1",
            error = addressError.takeIf { tried },
        )
        if (addressError == null && !isLoopbackAddress(bindAddress.trim())) {
            Text("La porta diventa raggiungibile da altri computer della rete.", style = Carta.type.small, color = Carta.colors.danger)
            if (kind == "remote") Text("Sul server serve anche GatewayPorts, altrimenti resta su loopback.", style = Carta.type.small, color = Carta.colors.textDim)
        }
        Field(
            if (kind == "remote") "Porta su ${host.label}" else "Porta su questo dispositivo",
            bindPort,
            { bindPort = it },
            Modifier.fillMaxWidth(),
            numeric = true,
            placeholder = if (kind == "dynamic") "1080" else "5432",
            error = bindError.takeIf { tried },
        )
        if (kind != "dynamic") Row {
            Field(
                if (kind == "remote") "Destinazione, vista da questo dispositivo" else "Destinazione, vista da ${host.label}",
                targetHost,
                { targetHost = it },
                Modifier.weight(1f).padding(end = 8.dp),
                monospace = true,
                placeholder = "localhost",
                error = targetHostError.takeIf { tried },
            )
            Field("Porta", targetPort, { targetPort = it }, Modifier.width(110.dp), numeric = true, placeholder = "5432", error = targetPortError.takeIf { tried })
        }
        Checkbox(autoStart, { autoStart = it }, "Avvia quando mi collego a ${host.label}")
    }
}

private fun portError(text: String): String? {
    val port = text.trim().toIntOrNull()
    return if (port == null || port !in 1..65535) "Un numero da 1 a 65535" else null
}
