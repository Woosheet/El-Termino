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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
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

/**
 * The sombrero of the app icon hopping from left to right: the app's one waiting indicator.
 * [height] includes the room for the jump; the width follows.
 */
@Composable
fun HoppingSombrero(modifier: Modifier = Modifier, height: Dp = 56.dp, color: Color = Carta.colors.accent) {
    val time by rememberInfiniteTransition(label = "sombrero").animateFloat(
        initialValue = 0f,
        targetValue = CROSSING,
        animationSpec = infiniteRepeatable(tween(CROSSING.toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "time",
    )
    val pixel = LocalAppIcon.current == AppIconStyle.Pixel
    Canvas(modifier.size(height * (STAGE_W / STAGE_H), height).clipToBounds()) {
        // A 560 × 150 stage with the ground at y 136; four hops per crossing so the loop is seamless.
        val phase = (time % HOP) / HOP
        val up = phase < 0.5f
        val u = if (up) phase / 0.5f else (phase - 0.5f) / 0.5f
        val lift = if (up) 1 - (1 - u) * (1 - u) else 1 - u * u
        val squash = when {
            phase < 0.12f -> 0.78f + 0.30f * phase / 0.12f
            up -> 1.08f - 0.08f * (phase - 0.12f) / 0.38f
            else -> 1f - 0.22f * u
        }
        val x = -90f + 740f * time / CROSSING
        scale(size.height / STAGE_H, pivot = Offset.Zero) {
            if (pixel) {
                // Píxel moves like a sprite: whole cells, no squash or tilt.
                translate(snap(x, 2 * CELL), 136f) {
                    val shadow = snap(124f * (1f - 0.5f * lift), 2 * CELL)
                    drawRect(color.copy(alpha = 0.3f - 0.18f * lift), Offset(-shadow / 2, 0f), Size(shadow, CELL))
                    translate(0f, -snap(72f * lift, CELL)) { pixelHat(CELL) }
                }
                return@scale
            }
            translate(x, 136f) {
                scale(1f - 0.5f * lift, 1f, pivot = Offset.Zero) {
                    drawOval(color.copy(alpha = 0.3f - 0.18f * lift), Offset(-62f, 0f), Size(124f, 12f))
                }
                translate(0f, -72f * lift) {
                    rotate(-6f * lift, pivot = Offset.Zero) {
                        scale(0.55f, 0.55f * squash, pivot = Offset.Zero) {
                            translate(0f, -28f) { sombrero(color) }
                        }
                    }
                }
            }
        }
    }
}

private const val STAGE_W = 560f
private const val STAGE_H = 150f
private const val CROSSING = 2600f
private const val HOP = 650f
private const val CELL = 9f

private fun snap(value: Float, step: Float) = kotlin.math.round(value / step) * step

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

/** A thin track that fills with accent; [fraction] from 0 to 1. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Carta.colors.accent) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Carta.colors.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}
