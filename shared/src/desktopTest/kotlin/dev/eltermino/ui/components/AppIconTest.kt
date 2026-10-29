package dev.eltermino.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
    fun unknownIdFallsBackToBigote() {
        assertEquals(AppIconStyle.Bigote, AppIconStyle.of("nope"))
        assertEquals(AppIconStyle.Pixel, AppIconStyle.of("pixel"))
    }
}
