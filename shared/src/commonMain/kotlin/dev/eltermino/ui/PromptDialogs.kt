package dev.eltermino.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import dev.eltermino.app.PendingPrompt
import dev.eltermino.app.Prompter
import dev.eltermino.app.SecretAnswer
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.DialogTone
import dev.eltermino.ui.components.Field
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta

/** Shows whatever the connection flow is currently asking. */
@Composable
internal fun PromptDialogs(prompter: Prompter) {
    val prompt by prompter.prompt.collectAsState()
    when (val p = prompt) {
        null -> Unit
        // key() gives every prompt fresh field state.
        is PendingPrompt.HostKey -> key(p) { HostKeyDialog(p) }
        is PendingPrompt.Secret -> key(p) { SecretDialog(p) }
        is PendingPrompt.Text -> key(p) { TextDialog(p) }
        is PendingPrompt.Interactive -> key(p) { InteractiveDialog(p) }
    }
}

@Composable
private fun HostKeyDialog(prompt: PendingPrompt.HostKey) {
    val changed = prompt.previous != null
    Dialog(
        onDismiss = prompt::cancel,
        title = if (changed) "La chiave dell'host è cambiata" else "Nuovo host",
        icon = if (changed) Icons.Default.Warning else null,
        tone = if (changed) DialogTone.Danger else DialogTone.Normal,
        actions = {
            Button("Annulla", onClick = prompt::cancel, kind = ButtonKind.Ghost)
            Button(
                if (changed) "Sostituisci la chiave" else "Fidati e connetti",
                onClick = { prompt.answer(true) },
                kind = if (changed) ButtonKind.Danger else ButtonKind.Primary,
            )
        },
    ) {
        if (changed) {
            Text(
                "${prompt.host}:${prompt.port} presenta una chiave diversa da quella salvata. " +
                    "Può essere una reinstallazione del server, oppure qualcuno che intercetta la connessione. " +
                    "Continua solo se sai perché è cambiata.",
                color = Carta.colors.danger,
            )
        } else {
            Text("È la prima volta che ti colleghi a ${prompt.host}:${prompt.port}. Controlla che l'impronta sia quella del server.")
        }
        Gap(height = 14)
        Text(prompt.fingerprint.keyType, style = Carta.type.label, color = Carta.colors.textDim)
        SelectionContainer { Text(prompt.fingerprint.sha256, fontFamily = Carta.monospace, style = Carta.type.small) }
        prompt.previous?.let {
            Gap(height = 10)
            Text("Impronta salvata in precedenza", style = Carta.type.label, color = Carta.colors.textDim)
            SelectionContainer { Text(it.fingerprintSha256, fontFamily = Carta.monospace, style = Carta.type.small) }
        }
    }
}

@Composable
private fun SecretDialog(prompt: PendingPrompt.Secret) {
    var value by remember { mutableStateOf("") }
    var save by remember { mutableStateOf(false) }
    val submit = { prompt.answer(SecretAnswer(value.encodeToByteArray(), save)) }
    Dialog(
        onDismiss = prompt::cancel,
        title = prompt.title,
        actions = {
            Button("Annulla", onClick = prompt::cancel, kind = ButtonKind.Ghost)
            Button("Connetti", onClick = submit)
        },
    ) {
        Field(prompt.label, value, { value = it }, secret = true, fieldModifier = Modifier.onEnter(submit))
        if (prompt.allowSave) Checkbox(save, { save = it }, "Salva nel vault")
    }
}

@Composable
private fun TextDialog(prompt: PendingPrompt.Text) {
    var value by remember { mutableStateOf("") }
    val submit = { if (value.isNotBlank()) prompt.answer(value.trim()) }
    Dialog(
        onDismiss = prompt::cancel,
        title = prompt.title,
        actions = {
            Button("Annulla", onClick = prompt::cancel, kind = ButtonKind.Ghost)
            Button("Continua", onClick = submit, enabled = value.isNotBlank())
        },
    ) {
        Field(prompt.label, value, { value = it }, fieldModifier = Modifier.onEnter(submit))
    }
}

@Composable
private fun InteractiveDialog(prompt: PendingPrompt.Interactive) {
    val answers = remember { mutableStateListOf(*Array(prompt.prompts.size) { "" }) }
    val submit = { prompt.answer(answers.toList()) }
    Dialog(
        onDismiss = prompt::cancel,
        title = prompt.name.ifBlank { "Verifica per ${prompt.hostLabel}" },
        actions = {
            Button("Annulla", onClick = prompt::cancel, kind = ButtonKind.Ghost)
            Button("Invia", onClick = submit)
        },
    ) {
        if (prompt.instruction.isNotBlank()) Text(prompt.instruction, modifier = Modifier.padding(bottom = 8.dp))
        prompt.prompts.forEachIndexed { index, question ->
            Field(
                label = question.text.trim().removeSuffix(":"),
                value = answers[index],
                onValueChange = { answers[index] = it },
                secret = !question.echo,
                fieldModifier = if (index == prompt.prompts.lastIndex) Modifier.onEnter(submit) else Modifier,
            )
        }
    }
}
