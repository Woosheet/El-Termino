package dev.eltermino.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.Release
import dev.eltermino.app.UpdateState
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.CartaIcons
import dev.eltermino.ui.components.Checkbox
import dev.eltermino.ui.components.Dialog
import dev.eltermino.ui.components.Icon
import dev.eltermino.ui.components.Pressable
import dev.eltermino.ui.components.ProgressBar
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta
import kotlin.math.roundToInt

private val UpdateState.release: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Ready -> release
        is UpdateState.Failed -> release
        else -> null
    }

/** In the status bar while there's a new version to get, being downloaded or ready. */
@Composable
internal fun UpdateBadge(state: AppState) {
    val current by state.updates.state.collectAsState()
    val text = when (val s = current) {
        is UpdateState.Available -> "È uscita la ${s.release.version}"
        is UpdateState.Downloading -> "Scarico la ${s.release.version} · ${(s.progress * 100).roundToInt()}%"
        is UpdateState.Ready -> "La ${s.release.version} è pronta: riavvia"
        is UpdateState.Failed -> if (s.release != null) "Aggiornamento non riuscito" else return
        else -> return
    }
    val color = if (current is UpdateState.Failed) Carta.colors.danger else Carta.colors.accent
    Pressable(onClick = { state.updates.show() }, shape = RoundedCornerShape(4.dp)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CartaIcons.Import, contentDescription = null, tint = color, size = 14.dp)
            Text(text, style = Carta.type.label, color = color, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** What's new and the button that installs it; once it's in place the app restarts into it. */
@Composable
internal fun UpdateDialog(state: AppState, onExit: () -> Unit) {
    val updates = state.updates
    val platform = state.services.platform
    val showing by updates.showing.collectAsState()
    val current by updates.state.collectAsState()
    val restart = {
        platform.openUpdateOnExit()
        // With transfers running the usual question comes first.
        if (!state.requestExit()) onExit()
    }
    LaunchedEffect(current is UpdateState.Ready, showing) {
        if (current is UpdateState.Ready && showing) restart()
    }
    if (!showing) return
    val release = current.release ?: return
    Dialog(
        onDismiss = { updates.hide() },
        title = "È uscita El Termino ${release.version}",
        icon = CartaIcons.Import,
        actions = {
            when (current) {
                is UpdateState.Available -> {
                    Button("Più tardi", onClick = { updates.hide() }, kind = ButtonKind.Ghost)
                    if (release.asset != null) Button("Aggiorna e riavvia", onClick = { updates.install(release) })
                    else Button("Apri la pagina", onClick = { platform.openUrl(release.pageUrl) })
                }
                is UpdateState.Downloading -> Button("Continua in background", onClick = { updates.hide() }, kind = ButtonKind.Ghost)
                is UpdateState.Ready -> {
                    Button("Più tardi", onClick = { updates.hide() }, kind = ButtonKind.Ghost)
                    Button("Riavvia ora", onClick = restart)
                }
                else -> {
                    Button("Apri la pagina", onClick = { platform.openUrl(release.pageUrl) }, kind = ButtonKind.Ghost)
                    Button("Riprova", onClick = { updates.install(release) })
                }
            }
        },
    ) {
        val size = release.asset?.sizeBytes?.takeIf { it > 0 }?.let { " · ${(it / 1_000_000)} MB" }.orEmpty()
        Text("Hai la ${updates.current}$size", color = Carta.colors.textDim)
        val notes = readableNotes(release.notes)
        if (notes.isNotEmpty()) Text(notes, style = Carta.type.small, modifier = Modifier.padding(top = 12.dp))
        when (val s = current) {
            is UpdateState.Downloading -> {
                ProgressBar(s.progress, Modifier.padding(top = 16.dp))
                Text("Scarico e controllo la nuova versione… ${(s.progress * 100).roundToInt()}%", style = Carta.type.small, color = Carta.colors.textDim, modifier = Modifier.padding(top = 6.dp))
            }
            is UpdateState.Ready -> Text("È pronta: si apre appena El Termino si chiude.", modifier = Modifier.padding(top = 16.dp))
            is UpdateState.Failed -> Text(s.message, color = Carta.colors.danger, modifier = Modifier.padding(top = 16.dp))
            else -> if (release.asset == null) Text("Su questo computer va installata a mano dalla pagina della release.", modifier = Modifier.padding(top = 16.dp))
        }
    }
}

/** The settings' say on updates: this version, the startup check, and a check on demand. */
@Composable
internal fun UpdatesSection(state: AppState, checkAtStartup: Boolean, onCheckAtStartup: (Boolean) -> Unit) {
    if (!state.services.platform.isDesktop) return
    val updates = state.updates
    val current by updates.state.collectAsState()
    SettingsSection("Aggiornamenti", "Le nuove versioni escono su GitHub. L'app le cerca all'avvio e te le propone nella barra in basso.") {
        val version = updates.current
        if (version == null) {
            Text("Versione di sviluppo: si aggiorna da sola solo l'app installata.", color = Carta.colors.textDim)
            return@SettingsSection
        }
        Text("Versione $version", style = Carta.type.bodyStrong)
        Checkbox(checkAtStartup, onCheckAtStartup, "Cerca una nuova versione all'avvio", Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button("Cerca ora", onClick = { updates.check() }, kind = ButtonKind.Secondary, enabled = current !is UpdateState.Checking && current !is UpdateState.Downloading)
            val (status, failed) = when (val s = current) {
                UpdateState.Checking -> "Controllo…" to false
                UpdateState.UpToDate -> "Hai l'ultima versione" to false
                is UpdateState.Available -> "È uscita la ${s.release.version}" to false
                is UpdateState.Downloading -> "Scarico la ${s.release.version}…" to false
                is UpdateState.Ready -> "La ${s.release.version} è pronta" to false
                is UpdateState.Failed -> s.message to true
                else -> "" to false
            }
            Text(status, style = Carta.type.small, color = if (failed) Carta.colors.danger else Carta.colors.textDim, modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
            if (current.release != null) Button("Vedi", onClick = { updates.show() }, kind = ButtonKind.Ghost, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** GitHub's release notes without the Markdown: no headings, bullets as dots, at most a dozen lines. */
internal fun readableNotes(markdown: String): String =
    markdown.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("**Full Changelog**") }
        .map { line ->
            line.trimStart('#').trim()
                .replace("**", "")
                .let { if (it.startsWith("* ") || it.startsWith("- ")) "• " + it.drop(2) else it }
        }
        .take(12)
        .joinToString("\n")
