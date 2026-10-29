package dev.eltermino.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eltermino.ui.theme.Carta

internal expect val HorizontalResizeIcon: PointerIcon
internal expect val VerticalResizeIcon: PointerIcon

/**
 * The divider between two panes side by side, wide enough to grab: dragging it reports
 * the horizontal movement; it turns accent while hovered or dragged.
 */
@Composable
fun VerticalResizeHandle(onDrag: (Dp) -> Unit, modifier: Modifier = Modifier, onDragStopped: () -> Unit = {}) {
    ResizeHandle(Orientation.Horizontal, onDrag, onDragStopped, modifier.fillMaxHeight().width(7.dp))
}

/** The divider between two panes one above the other; dragging reports the vertical movement. */
@Composable
fun HorizontalResizeHandle(onDrag: (Dp) -> Unit, modifier: Modifier = Modifier, onDragStopped: () -> Unit = {}) {
    ResizeHandle(Orientation.Vertical, onDrag, onDragStopped, modifier.fillMaxWidth().height(7.dp))
}

@Composable
private fun ResizeHandle(orientation: Orientation, onDrag: (Dp) -> Unit, onDragStopped: () -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnStopped by rememberUpdatedState(onDragStopped)
    val active = hovered || dragged
    val horizontal = orientation == Orientation.Horizontal
    Box(
        modifier
            .hoverable(interaction)
            .pointerHoverIcon(if (horizontal) HorizontalResizeIcon else VerticalResizeIcon)
            .draggable(
                state = rememberDraggableState { delta -> currentOnDrag(with(density) { delta.toDp() }) },
                orientation = orientation,
                interactionSource = interaction,
                onDragStopped = { currentOnStopped() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val thickness = if (active) 2.dp else 1.dp
        val line = if (horizontal) Modifier.fillMaxHeight().width(thickness) else Modifier.fillMaxWidth().height(thickness)
        Box(line.background(if (active) Carta.colors.accent else Carta.colors.line))
    }
}
