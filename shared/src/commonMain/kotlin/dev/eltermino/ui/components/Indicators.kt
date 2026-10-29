package dev.eltermino.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eltermino.ui.theme.Carta

@Composable
fun Divider(modifier: Modifier = Modifier, vertical: Boolean = false, color: Color = Carta.colors.line) {
    Box(
        (if (vertical) modifier.fillMaxHeight().width(1.dp) else modifier.fillMaxWidth().height(1.dp))
            .background(color),
    )
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 28.dp, color: Color = Carta.colors.accent) {
    val rotation by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    val track = Carta.colors.line
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawArc(track, 0f, 360f, useCenter = false, style = stroke)
        drawArc(color, rotation, 90f, useCenter = false, style = stroke)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
fun Badge(text: String, color: Color = Carta.colors.accent, background: Color = Carta.colors.accentBackground) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(background).padding(horizontal = 6.dp, vertical = 1.dp),
        style = Carta.type.caption,
        color = color,
    )
}
