package dev.eltermino.core.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class ExtraKeysColors(
    val background: Color = Color(0xFFF4F3EF),
    val key: Color = Color(0xFFFFFFFF),
    val keyBorder: Color = Color(0xFFE2E0D8),
    val text: Color = Color(0xFF2C2C2A),
    val activeKey: Color = Color(0xFFE6F1FB),
    val activeText: Color = Color(0xFF185FA5),
)

private sealed interface ExtraKey {
    val label: String

    data class Special(override val label: String, val key: TerminalKey) : ExtraKey
    data class Text(override val label: String) : ExtraKey
    data object Ctrl : ExtraKey { override val label = "Ctrl" }
    data object Alt : ExtraKey { override val label = "Alt" }
}

private val extraKeys = listOf(
    ExtraKey.Special("Esc", TerminalKey.Escape),
    ExtraKey.Special("Tab", TerminalKey.Tab),
    ExtraKey.Ctrl,
    ExtraKey.Alt,
    ExtraKey.Text("|"),
    ExtraKey.Text("~"),
    ExtraKey.Text("/"),
    ExtraKey.Text("-"),
    ExtraKey.Special("←", TerminalKey.Left),
    ExtraKey.Special("↓", TerminalKey.Down),
    ExtraKey.Special("↑", TerminalKey.Up),
    ExtraKey.Special("→", TerminalKey.Right),
)

/**
 * The key bar shown above the soft keyboard. Ctrl and Alt latch until the next key or
 * typed character, then release.
 */
@Composable
fun TerminalExtraKeys(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    colors: ExtraKeysColors = ExtraKeysColors(),
) {
    val sticky by session.stickyModifiers.collectAsState()
    Row(
        modifier
            .background(colors.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (key in extraKeys) {
            val active = (key == ExtraKey.Ctrl && sticky.ctrl) || (key == ExtraKey.Alt && sticky.alt)
            Box(
                Modifier
                    .defaultMinSize(minWidth = 40.dp, minHeight = 34.dp)
                    .background(if (active) colors.activeKey else colors.key, RoundedCornerShape(6.dp))
                    .border(1.dp, colors.keyBorder, RoundedCornerShape(6.dp))
                    .clickable {
                        when (key) {
                            ExtraKey.Ctrl -> session.toggleStickyCtrl()
                            ExtraKey.Alt -> session.toggleStickyAlt()
                            is ExtraKey.Special -> session.sendKey(key.key)
                            is ExtraKey.Text -> session.type(key.label)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    key.label,
                    style = TextStyle(color = if (active) colors.activeText else colors.text, fontSize = 13.sp),
                )
            }
        }
    }
}
