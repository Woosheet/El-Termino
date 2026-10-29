package dev.eltermino.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.LocalContentColor
import dev.eltermino.ui.theme.LocalTextStyle

enum class DialogTone { Normal, Danger }

/**
 * A modal card: optional icon, title, free content, actions aligned to the end.
 * Put the primary action last.
 */
@Composable
fun Dialog(
    onDismiss: () -> Unit,
    title: String,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: DialogTone = DialogTone.Normal,
    width: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Carta.colors
    val shape = RoundedCornerShape(14.dp)
    val toneColor = if (tone == DialogTone.Danger) colors.danger else colors.text
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier
                .padding(16.dp)
                .widthIn(min = 320.dp, max = width)
                .shadow(18.dp, shape)
                .background(colors.background, shape)
                .border(1.dp, colors.line, shape)
                .padding(24.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = toneColor, size = 26.dp)
                Spacer(Modifier.height(12.dp))
            }
            Text(title, style = Carta.type.title, color = toneColor)
            Spacer(Modifier.height(12.dp))
            CompositionLocalProvider(LocalTextStyle provides Carta.type.body, LocalContentColor provides colors.text) {
                Column(content = content)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
        }
    }
}

/** Places a popup under its anchor, or above it when there's no room below. */
private class BelowAnchor(private val gap: Int, private val alignEnd: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left).coerceIn(0, maxX)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * A drop-down list of [MenuItem]s. Wrap it in a Box together with its anchor: the
 * popup is placed relative to that Box.
 */
@Composable
fun Menu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    alignEnd: Boolean = false,
    /** Fixed width, as for a [Picker]; by default the menu is as wide as its longest item. */
    width: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val colors = Carta.colors
    val shape = RoundedCornerShape(10.dp)
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(alignEnd, gap) { BelowAnchor(gap, alignEnd) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            // Without a bound, items that fill the width would stretch the popup across the window.
            (if (width != null) Modifier.width(width) else Modifier.width(IntrinsicSize.Max).widthIn(min = 180.dp, max = 320.dp))
                .shadow(10.dp, shape)
                .background(colors.background, shape)
                .border(1.dp, colors.line, shape)
                .padding(4.dp),
            content = content,
        )
    }
}

@Composable
fun MenuItem(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    icon: ImageVector? = null,
    /** Muted text at the end of the row, such as an address. */
    detail: String? = null,
    selected: Boolean = false,
) {
    val colors = Carta.colors
    val tint = when {
        danger -> colors.danger
        selected -> colors.accent
        else -> colors.text
    }
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        background = if (selected) colors.accentBackground else Color.Transparent,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, tint = if (danger || selected) tint else colors.textDim, size = 18.dp, modifier = Modifier.padding(end = 10.dp))
            Text(text, modifier = Modifier.weight(1f, fill = false), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, modifier = Modifier.padding(start = 12.dp), style = Carta.type.small, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A muted title over a group of menu items. */
@Composable
fun MenuLabel(text: String) {
    Text(text, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp), style = Carta.type.label, color = Carta.colors.textDim)
}

@Composable
fun MenuDivider() {
    Divider(Modifier.padding(vertical = 4.dp))
}

/** A labelled drop-down: looks like a field, opens a [Menu] as wide as itself. */
@Composable
fun <T> Picker(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Carta.colors
    var open by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Column(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(label, style = Carta.type.label, color = colors.textDim, modifier = Modifier.padding(bottom = 4.dp))
        Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
            Pressable(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
                background = colors.background,
                hover = colors.chrome,
                pressed = colors.hover,
                border = colors.line,
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(display(selected), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.textDim)
                }
            }
            Menu(expanded = open, onDismiss = { open = false }, width = with(density) { widthPx.toDp() }) {
                options.forEach { option ->
                    MenuItem(display(option), onClick = { open = false; onSelect(option) })
                }
            }
        }
    }
}
