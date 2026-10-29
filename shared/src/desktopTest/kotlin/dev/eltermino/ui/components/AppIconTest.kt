package dev.eltermino.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIconTest {
    private fun render(style: AppIconStyle, size: Int = 512) =
        AppIconPainter(style).toAwtImage(Density(1f), LayoutDirection.Ltr, Size(size.toFloat(), size.toFloat())).let { image ->
            java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB).also { it.graphics.drawImage(image, 0, 0, null) }
        }

    @Test
    fun bothIconsSitOnTheAccentTileWithTransparentCorners() {
        for (style in AppIconStyle.entries) {
            val image = render(style)
            assertEquals(0, image.getRGB(2, 2) ushr 24, "$style corner")
            assertEquals(0xFF185FA5.toInt(), image.getRGB(256, 270), "$style middle")
            // Scaled down, the tile still fills its whole square.
            assertEquals(0xFF185FA5.toInt(), render(style, 128).getRGB(110, 64), "$style scaled")
        }
    }

    @Test
    fun pixelDrawsTheHatAndTheMoustacheInTheirCells() {
        val image = render(AppIconStyle.Pixel)
        assertEquals(0xFFF7C548.toInt(), image.getRGB(24 + 7 * 29 + 14, 24 + 1 * 29 + 14))
        assertEquals(0xFFFFFFFF.toInt(), image.getRGB(24 + 9 * 29 + 14, 24 + 13 * 29 + 14))
    }

    @Test
    fun theSpinnerSombreroLetsTheBackgroundThroughTheBand() {
        val hat = object : androidx.compose.ui.graphics.painter.Painter() {
            override val intrinsicSize = Size(256f, 256f)
            override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
                translate(128f, 128f) { sombrero(androidx.compose.ui.graphics.Color.Red) }
            }
        }
        val image = hat.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(256f, 256f))
        val pixels = java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_ARGB).also { it.graphics.drawImage(image, 0, 0, null) }
        assertEquals(0xFFFF0000.toInt(), pixels.getRGB(128, 128 - 40), "crown")
        assertEquals(0, pixels.getRGB(128, 128 - 11) ushr 24, "band")
        assertEquals(0xFFFF0000.toInt(), pixels.getRGB(128, 128 + 20), "brim")
    }

    @Test
    fun thePixelHatStandsOnTheOriginInPixelColours() {
        val hat = object : androidx.compose.ui.graphics.painter.Painter() {
            override val intrinsicSize = Size(160f, 160f)
            override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
                translate(80f, 100f) { pixelHat(10f) }
            }
        }
        val image = hat.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(160f, 160f))
        val pixels = java.awt.image.BufferedImage(160, 160, java.awt.image.BufferedImage.TYPE_INT_ARGB).also { it.graphics.drawImage(image, 0, 0, null) }
        // Top of the crown (grid row 1, column 7), the shaded underside (row 7), and a gap that stays empty.
        assertEquals(0xFFF7C548.toInt(), pixels.getRGB(80 - 15, 100 - 65))
        assertEquals(0xFFD9A030.toInt(), pixels.getRGB(80, 100 - 5))
        assertEquals(0, pixels.getRGB(80 - 65, 100 - 65) ushr 24)
    }

    @Test
    fun unknownIdFallsBackToBigote() {
        assertEquals(AppIconStyle.Bigote, AppIconStyle.of("nope"))
        assertEquals(AppIconStyle.Pixel, AppIconStyle.of("pixel"))
    }
}
