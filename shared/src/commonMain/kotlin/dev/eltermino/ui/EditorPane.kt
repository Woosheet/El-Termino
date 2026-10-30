package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import dev.eltermino.app.AppState
import dev.eltermino.app.EditorTab
import dev.eltermino.app.RemoteEditor
import dev.eltermino.app.failureMessage
import dev.eltermino.app.formatFileTime
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.DialogTone
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.HoppingSombrero
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.CartaColors
import kotlinx.coroutines.launch

@Composable
internal fun EditorPane(state: AppState, tab: EditorTab, focused: Boolean) {
    val loaded by tab.loaded.collectAsState()
    val error by tab.error.collectAsState()
    val editor by tab.editor.collectAsState()
    val text = loaded
    val ready = editor
    when {
        error != null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Impossibile aprire ${tab.label}", style = Carta.type.bodyStrong)
            Gap(height = 4)
            Text(error.orEmpty(), style = Carta.type.small, color = Carta.colors.textDim, textAlign = TextAlign.Center)
            Gap(height = 14)
            Button("Chiudi", onClick = { state.sessions.close(tab) }, kind = ButtonKind.Secondary)
        }
        text == null || ready == null -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            HoppingSombrero()
            Gap(height = 12)
            Text("Apertura di ${tab.label}…", color = Carta.colors.textDim)
        }
        tab.external -> ExternalEditorBody(state, tab)
        else -> EditorBody(tab, ready, text, focused)
    }
}

@Composable
private fun ExternalEditorBody(state: AppState, tab: EditorTab) {
    val uploads by tab.uploads.collectAsState()
    val uploadError by tab.uploadError.collectAsState()
    val conflict by tab.conflict.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${tab.label} è aperto nell'editor esterno", style = Carta.type.bodyStrong)
        Gap(height = 4)
        Text(
            uploadError ?: if (uploads == 0) "Ogni salvataggio viene caricato sul server." else "Salvataggi caricati: $uploads",
            style = Carta.type.small,
            color = if (uploadError != null) Carta.colors.danger else Carta.colors.textDim,
            textAlign = TextAlign.Center,
        )
        Gap(height = 14)
        Button("Chiudi", onClick = { state.sessions.close(tab) }, kind = ButtonKind.Secondary)
    }
    conflict?.let { answer ->
        Dialog(
            onDismiss = { answer.complete(false) },
            title = "${tab.label} è cambiato sul server",
            actions = {
                Button("Non caricare", onClick = { answer.complete(false) }, kind = ButtonKind.Secondary)
                Button("Sostituisci", onClick = { answer.complete(true) })
            },
        ) {
            Text("Qualcuno l'ha modificato dopo che l'hai aperto. Sostituendolo perdi quelle modifiche.")
        }
    }
}

@Composable
private fun EditorBody(tab: EditorTab, editor: RemoteEditor, initial: String, focused: Boolean) {
    val colors = Carta.colors
    val scope = rememberCoroutineScope()
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial)) }
    var saved by remember(initial) { mutableStateOf(initial) }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<RemoteEditor.SaveResult.ChangedOnServer?>(null) }
    val language = remember(tab.path) { languageFor(tab.path) }
    val highlight = remember(language, colors) { SyntaxHighlight(language, colors) }
    val focus = remember { FocusRequester() }
    val dirty = value.text != saved

    LaunchedEffect(dirty) { tab.dirty.value = dirty }
    LaunchedEffect(focused) { if (focused) runCatching { focus.requestFocus() } }

    fun save(force: Boolean) {
        if (saving) return
        saving = true
        val text = value.text
        scope.launch {
            try {
                when (val result = editor.save(text, force)) {
                    RemoteEditor.SaveResult.Saved -> {
                        saved = text
                        status = "Salvato alle ${formatFileTime(nowSeconds())}"
                    }
                    is RemoteEditor.SaveResult.ChangedOnServer -> conflict = result
                }
            } catch (e: Exception) {
                status = "Non salvato: ${failureMessage(e)}"
            } finally {
                saving = false
            }
        }
    }

    fun reload() {
        scope.launch {
            try {
                val fresh = editor.load()
                value = TextFieldValue(fresh)
                saved = fresh
                status = "Ricaricato dal server"
            } catch (e: Exception) {
                status = "Non ricaricato: ${failureMessage(e)}"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(colors.background).onPreviewKeyEvent { event ->
            val save = event.type == KeyEventType.KeyDown && event.key == Key.S && (event.isMetaPressed || event.isCtrlPressed)
            if (save) save(force = false)
            save
        },
    ) {
        Row(
            Modifier.fillMaxWidth().background(colors.chrome).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tab.path, fontFamily = Carta.monospace, style = Carta.type.small, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                when {
                    saving -> "Salvataggio…"
                    dirty -> "Modificato"
                    else -> status ?: language.label
                },
                style = Carta.type.label,
                color = if (dirty) colors.accent else colors.textDim,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Button("Ricarica", onClick = ::reload, kind = ButtonKind.Ghost)
            Button("Salva", onClick = { save(force = false) }, enabled = dirty && !saving)
        }
        Divider()
        val vertical = rememberScrollState()
        val horizontal = rememberScrollState()
        val style = TextStyle(fontFamily = Carta.monospace, fontSize = 13.sp, lineHeight = 19.sp, color = colors.text)
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vertical)) {
            val lines = value.text.count { it == '\n' } + 1
            Text(
                (1..lines).joinToString("\n"),
                style = style.copy(color = colors.textDim, textAlign = TextAlign.End),
                modifier = Modifier.background(colors.chrome).padding(horizontal = 10.dp, vertical = 8.dp).widthIn(min = 28.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = style,
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = highlight,
                modifier = Modifier.weight(1f).horizontalScroll(horizontal).padding(horizontal = 10.dp, vertical = 8.dp).focusRequester(focus),
            )
        }
    }

    conflict?.let { changed ->
        Dialog(
            onDismiss = { conflict = null },
            title = "Il file è cambiato sul server",
            icon = Icons.Default.Warning,
            tone = DialogTone.Danger,
            actions = {
                Button("Annulla", onClick = { conflict = null }, kind = ButtonKind.Ghost)
                Button("Ricarica", kind = ButtonKind.Secondary, onClick = {
                    conflict = null
                    reload()
                })
                Button("Sovrascrivi", kind = ButtonKind.Danger, onClick = {
                    conflict = null
                    save(force = true)
                })
            },
        ) {
            Text("Qualcuno l'ha modificato (${formatFileTime(changed.current.modifiedEpochSeconds)}) dopo che l'hai aperto. Sovrascrivendo perdi quelle modifiche; ricaricando perdi le tue.")
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun nowSeconds() = kotlin.time.Clock.System.now().epochSeconds

internal enum class Language(val label: String) { Yaml("YAML"), Json("JSON"), Shell("Shell"), Config("Configurazione"), Plain("Testo") }

internal fun languageFor(path: String): Language {
    val name = path.substringAfterLast('/').lowercase()
    val extension = name.substringAfterLast('.', "")
    return when {
        extension in setOf("yaml", "yml") -> Language.Yaml
        extension == "json" -> Language.Json
        extension in setOf("sh", "bash", "zsh") || name in setOf(".bashrc", ".zshrc", ".profile", ".bash_profile") || name.startsWith(".env") -> Language.Shell
        extension in setOf("conf", "cfg", "ini", "toml", "properties", "service", "timer", "env") || name in setOf("hosts", "fstab", "crontab", "sshd_config", "ssh_config") -> Language.Config
        else -> Language.Plain
    }
}

/**
 * Colours keys, strings, numbers, comments and keywords with a few regular expressions:
 * enough for configuration files, not a parser. Big files are left plain.
 */
internal class SyntaxHighlight(private val language: Language, private val colors: CartaColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (language == Language.Plain || text.length > 200_000) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(highlight(text.text), OffsetMapping.Identity)
    }

    internal fun highlight(source: String): AnnotatedString = buildAnnotatedString {
        append(source)
        fun paint(regex: Regex, color: Color, group: Int = 0) {
            regex.findAll(source).forEach { match ->
                val range = match.groups[group]?.range ?: return@forEach
                addStyle(SpanStyle(color = color), range.first, range.last + 1)
            }
        }
        val numbers = Regex("""(?<![\w.])-?\d+(\.\d+)?(?![\w.])""")
        val strings = Regex(""""(?:[^"\\\n]|\\.)*"|'[^'\n]*'""")
        val comments = Regex("""(?m)(^|\s)#.*$""")
        when (language) {
            Language.Yaml -> {
                paint(numbers, colors.syntaxNumber)
                paint(Regex("""\b(true|false|null|yes|no|on|off)\b"""), colors.syntaxKeyword)
                paint(Regex("""(?m)^\s*-?\s*([\w.\-/]+)\s*:(?=\s|$)"""), colors.syntaxKey, group = 1)
                paint(strings, colors.syntaxString)
                paint(comments, colors.syntaxComment)
            }
            Language.Json -> {
                paint(numbers, colors.syntaxNumber)
                paint(Regex("""\b(true|false|null)\b"""), colors.syntaxKeyword)
                paint(strings, colors.syntaxString)
                paint(Regex(""""(?:[^"\\\n]|\\.)*"(?=\s*:)"""), colors.syntaxKey)
            }
            Language.Shell -> {
                paint(Regex("""\b(if|then|else|elif|fi|for|while|until|do|done|case|esac|function|in|export|local|return|exit|source)\b"""), colors.syntaxKeyword)
                paint(Regex("""\$\{?[A-Za-z_][A-Za-z0-9_]*\}?"""), colors.syntaxKey)
                paint(strings, colors.syntaxString)
                paint(comments, colors.syntaxComment)
            }
            Language.Config -> {
                paint(numbers, colors.syntaxNumber)
                paint(Regex("""(?m)^\s*\[[^\]\n]+\]"""), colors.syntaxKeyword)
                paint(Regex("""(?m)^\s*([\w.\-]+)\s*(?==|:|\s)"""), colors.syntaxKey, group = 1)
                paint(strings, colors.syntaxString)
                paint(Regex("""(?m)(^|\s)[#;].*$"""), colors.syntaxComment)
            }
            Language.Plain -> Unit
        }
    }
}
