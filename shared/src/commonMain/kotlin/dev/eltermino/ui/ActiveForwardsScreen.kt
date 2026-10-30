package dev.eltermino.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eltermino.app.AppState
import dev.eltermino.app.ForwardState
import dev.eltermino.app.runningForwards
import dev.eltermino.ui.components.Button
import dev.eltermino.ui.components.ButtonKind
import dev.eltermino.ui.components.Divider
import dev.eltermino.ui.components.Text
import dev.eltermino.ui.theme.Carta

/** Every forward running now, on any host, to see and stop them from one place. */
@Composable
internal fun ActiveForwardsScreen(state: AppState, modifier: Modifier = Modifier) {
    val workspace by state.services.workspace.workspace.collectAsState()
    val states by state.forwards.states.collectAsState()
    val running = runningForwards(workspace, states)
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        ScreenTitle("Forward attivi")
        Hint("Le regole si creano dal menu di ogni host.")
        if (running.isEmpty()) Text("Nessun forward in corso.", color = Carta.colors.textDim)
        running.forEachIndexed { index, forward ->
            if (index > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(forward.rule.name.ifBlank { forward.host.label }, style = Carta.type.bodyStrong)
                    Text(forward.rule.route(forward.host), style = Carta.type.small, color = Carta.colors.textDim, fontFamily = Carta.monospace)
                    when (val current = forward.state) {
                        is ForwardState.Active -> Text("Attiva sulla porta ${current.port}", style = Carta.type.small, color = Carta.colors.online)
                        ForwardState.Starting -> Text("In avvio…", style = Carta.type.small, color = Carta.colors.textDim)
                        is ForwardState.Failed -> Text(current.message, style = Carta.type.small, color = Carta.colors.danger)
                    }
                }
                Button("Ferma", onClick = { state.forwards.stop(forward.rule.id) }, kind = ButtonKind.Ghost)
            }
        }
    }
}
