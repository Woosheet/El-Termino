package dev.eltermino.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.CartaColors
import dev.eltermino.ui.theme.LocalContentColor
import dev.eltermino.ui.theme.LocalTextStyle

enum class ButtonKind { Primary, Secondary, Ghost, Danger }

private class ButtonColors(val background: Color, val hover: Color, val pressed: Color, val content: Color, val border: Color?)

private fun CartaColors.forKind(kind: ButtonKind) = when (kind) {
    ButtonKind.Primary -> ButtonColors(accent, accentHover, accentPressed, background, null)
    ButtonKind.Secondary -> ButtonColors(background, hover, pressed, text, lineStrong)
    // Secondary actions stay in ink: the accent is for the one primary action and for data.
    ButtonKind.Ghost -> ButtonColors(Color.Transparent, hover, pressed, text, null)
    ButtonKind.Danger -> ButtonColors(danger, danger.copy(alpha = 0.9f), danger.copy(alpha = 0.8f), background, null)
}

/**
 * The surface every pressable thing shares: background that follows hover and press,
 * a focus ring for keyboard users, and the hand cursor on desktop.
 */
@Composable
internal fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(6.dp),
    background: Color = Color.Transparent,
    hover: Color = Carta.colors.hover,
    pressed: Color = Carta.colors.pressed,
    border: Color? = null,
    role: Role = Role.Button,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val isPressed by interaction.collectIsPressedAsState()
    val isFocused by interaction.collectIsFocusedAsState()
    // A click focuses too on desktop, but the ring is only meant for keyboard users.
    var pointerDriven by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        var focused = false
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press, is HoverInteraction.Enter -> pointerDriven = true
                is HoverInteraction.Exit -> if (!focused) pointerDriven = false
                is FocusInteraction.Focus -> focused = true
                is FocusInteraction.Unfocus -> {
                    focused = false
                    pointerDriven = false
                }
            }
        }
    }
    val showFocusRing = isFocused && !pointerDriven
    val fill = when {
        !enabled -> background
        isPressed -> pressed
        isHovered -> hover
        else -> background
    }
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .then(
                when {
                    showFocusRing -> Modifier.border(2.dp, Carta.colors.accent, shape)
                    border != null -> Modifier.border(1.dp, border, shape)
                    else -> Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    /** 38.dp beside a Field or a Picker, so the row shares one height. */
    minHeight: Dp = 32.dp,
) {
    val colors = Carta.colors.forKind(kind)
    val content = if (enabled) colors.content else colors.content.copy(alpha = 0.45f)
    Pressable(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = minHeight),
        enabled = enabled,
        background = if (enabled) colors.background else colors.background.copy(alpha = colors.background.alpha * 0.45f),
        hover = colors.hover,
        pressed = colors.pressed,
        border = colors.border,
    ) {
        Row(Modifier.defaultMinSize(minHeight = minHeight).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides content, LocalTextStyle provides Carta.type.bodyStrong) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, size = 16.dp, modifier = Modifier.padding(end = 6.dp))
                }
                Text(text, maxLines = 1)
            }
        }
    }
}

@Composable
fun IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Carta.colors.textDim,
    size: Dp = 32.dp,
    iconSize: Dp = 18.dp,
) {
    Pressable(onClick = onClick, modifier = modifier.size(size).semantics { this.contentDescription = contentDescription }, shape = RoundedCornerShape(6.dp)) {
        Icon(icon, contentDescription = null, tint = tint, size = iconSize)
    }
}

/** The round "+" button floating over lists on phones. */
@Composable
fun Fab(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Pressable(
        onClick = onClick,
        modifier = modifier.size(52.dp).semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        background = Carta.colors.accent,
        hover = Carta.colors.accentHover,
        pressed = Carta.colors.accentPressed,
    ) {
        Icon(icon, contentDescription = null, tint = Carta.colors.background, size = 24.dp)
    }
}
