package dev.eltermino.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter

/** The app icons the user can pick in Settings, by the id saved in `UiSettings.appIcon`. */
enum class AppIconStyle(val id: String, val label: String) {
    Bigote("bigote", "El Bigote"),
    Pixel("pixel", "Píxel");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Bigote
    }
}

/**
 * Draws an app icon on the 512-unit grid of docs/logo/proposte.html: a 464 tile centred in the canvas.
 * El Bigote matches app-desktop/packaging/MakeIcons.java, which makes the packaged icon.
 */
class AppIconPainter(private val style: AppIconStyle) : Painter() {
    override val intrinsicSize = Size(512f, 512f)

    override fun DrawScope.onDraw() {
        scale(size.minDimension / 512f, pivot = Offset.Zero) {
            val tile = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(24f, 24f, 488f, 488f, CornerRadius(112f)))
            }
            clipPath(tile) {
                when (style) {
                    AppIconStyle.Bigote -> bigote()
                    AppIconStyle.Pixel -> pixel()
                }
            }
        }
    }
}

private val Accent = Color(0xFF185FA5)

private fun DrawScope.bigote() {
    // In grid units: `size` is the real canvas, not the 512 grid.
    drawRect(Accent, Offset.Zero, Size(512f, 512f))
    // The prompt wears the hat, tipped a little, and its cursor is a moustache.
    translate(256f, 168f) {
        rotate(-7f, pivot = Offset.Zero) {
            scale(1.15f, pivot = Offset.Zero) { sombrero() }
        }
    }
    translate(166f, 330f) {
        val chevron = Path().apply { moveTo(-30f, -56f); lineTo(30f, 0f); lineTo(-30f, 56f) }
        drawPath(chevron, Color.White, style = Stroke(40f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    translate(330f, 368f) {
        scale(1.02f, pivot = Offset.Zero) { mustache() }
    }
}

private fun DrawScope.sombrero() {
    val brim = Path().apply {
        moveTo(-120f, -16f)
        cubicTo(-102f, 4f, -60f, 12f, 0f, 12f)
        cubicTo(60f, 12f, 102f, 4f, 120f, -16f)
        cubicTo(114f, 10f, 72f, 28f, 0f, 28f)
        cubicTo(-72f, 28f, -114f, 10f, -120f, -16f)
        close()
    }
    val crown = Path().apply {
        moveTo(-36f, 8f)
        cubicTo(-38f, -30f, -26f, -76f, 0f, -78f)
        cubicTo(26f, -76f, 38f, -30f, 36f, 8f)
        cubicTo(20f, 12f, -20f, 12f, -36f, 8f)
        close()
    }
    drawPath(brim, Color.White)
    drawPath(crown, Color.White)
    // The band, cut out in the tile colour.
    clipPath(crown) { drawRect(Accent, Offset(-40f, -18f), Size(80f, 14f)) }
}

/** A handlebar moustache: two lobes that meet under the nose and curl up at the tips. */
private fun DrawScope.mustache() {
    val both = Path()
    for (side in listOf(1f, -1f)) {
        both.moveTo(0f, -10f)
        both.cubicTo(14f * side, -30f, 46f * side, -30f, 62f * side, -12f)
        both.cubicTo(72f * side, -2f, 84f * side, 0f, 96f * side, -18f)
        both.cubicTo(102f * side, 6f, 82f * side, 30f, 52f * side, 28f)
        both.cubicTo(32f * side, 26f, 14f * side, 18f, 0f, 22f)
        both.close()
    }
    drawPath(both, Color.White)
}

/** Píxel: the same subject on a 16 × 16 grid, like a terminal's bitmap font. */
private val PixelGrid = listOf(
    "BBBBBBBBBBBBBBBB",
    "BBBBBBYYYYBBBBBB",
    "BBBBBYYYYYYBBBBB",
    "BBBBBYYYYYYBBBBB",
    "BBBBBRGRGRGBBBBB",
    "BYBBBYYYYYYBBBYB",
    "BYYYYYYYYYYYYYYB",
    "BBDDDDDDDDDDDDBB",
    "BBBBBBBBBBBBBBBB",
    "BWBBBBBBBBBBBBBB",
    "BBWBBBBBBBBBBBBB",
    "BBBWBWBBBBBBBBWB",
    "BBWBBWWWWBBWWWWB",
    "BWBBBBWWWWWWWWBB",
    "BBBBBBBBBBBBBBBB",
    "BBBBBBBBBBBBBBBB",
)

private val PixelColors = mapOf(
    'B' to Accent,
    'Y' to Color(0xFFF7C548),
    'D' to Color(0xFFD9A030),
    'R' to Color(0xFFE4007C),
    'G' to Color(0xFF2F6B3A),
    'W' to Color.White,
)

private fun DrawScope.pixel() {
    // 29-unit cells fill the 464 tile. One path per colour, so neighbouring cells join without seams.
    PixelColors.forEach { (key, color) ->
        val cells = Path()
        PixelGrid.forEachIndexed { y, row ->
            row.forEachIndexed { x, c -> if (c == key) cells.addRect(androidx.compose.ui.geometry.Rect(Offset(24f + x * 29f, 24f + y * 29f), Size(29f, 29f))) }
        }
        drawPath(cells, color)
    }
}
