package dev.eltermino.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn for the app, where the Material core set has nothing that fits.
 * Same 24-unit grid and stroke as each other; [Icon] tints them.
 */
object CartaIcons {
    val SplitSingle: ImageVector by lazy { lineIcon("SplitSingle") { frame() } }

    val SplitColumns: ImageVector by lazy {
        lineIcon("SplitColumns") {
            frame()
            moveTo(12f, 5f); lineTo(12f, 19f)
        }
    }

    val SplitRows: ImageVector by lazy {
        lineIcon("SplitRows") {
            frame()
            moveTo(4f, 12f); lineTo(20f, 12f)
        }
    }

    val SplitGrid: ImageVector by lazy {
        lineIcon("SplitGrid") {
            frame()
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(4f, 12f); lineTo(20f, 12f)
        }
    }

    /** A point with waves on both sides: input going out to several places. */
    val Broadcast: ImageVector by lazy {
        lineIcon("Broadcast") {
            moveTo(12f, 10.6f); quadTo(13.4f, 10.6f, 13.4f, 12f); quadTo(13.4f, 13.4f, 12f, 13.4f)
            quadTo(10.6f, 13.4f, 10.6f, 12f); quadTo(10.6f, 10.6f, 12f, 10.6f); close()
            moveTo(8.6f, 8.6f); quadTo(6.4f, 12f, 8.6f, 15.4f)
            moveTo(15.4f, 8.6f); quadTo(17.6f, 12f, 15.4f, 15.4f)
            moveTo(5.6f, 5.6f); quadTo(1.8f, 12f, 5.6f, 18.4f)
            moveTo(18.4f, 5.6f); quadTo(22.2f, 12f, 18.4f, 18.4f)
        }
    }

    /** A shell prompt, for saved commands. */
    val Snippet: ImageVector by lazy {
        lineIcon("Snippet") {
            moveTo(5f, 7f); lineTo(10f, 12f); lineTo(5f, 17f)
            moveTo(12.5f, 17f); lineTo(19f, 17f)
        }
    }

    /** Two overlapping sheets: another session like this one. */
    val Duplicate: ImageVector by lazy {
        lineIcon("Duplicate") {
            // Only the part of the back sheet that the front one doesn't cover.
            moveTo(8.5f, 8.5f); lineTo(8.5f, 6f); quadTo(8.5f, 4f, 10.5f, 4f)
            lineTo(18f, 4f); quadTo(20f, 4f, 20f, 6f)
            lineTo(20f, 13.5f); quadTo(20f, 15.5f, 18f, 15.5f)
            lineTo(15.5f, 15.5f)
            roundRect(4f, 8.5f, 15.5f, 20f)
        }
    }

    private fun PathBuilder.frame() = roundRect(4f, 5f, 20f, 19f)

    private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float = 2f) {
        moveTo(left + radius, top)
        lineTo(right - radius, top); quadTo(right, top, right, top + radius)
        lineTo(right, bottom - radius); quadTo(right, bottom, right - radius, bottom)
        lineTo(left + radius, bottom); quadTo(left, bottom, left, bottom - radius)
        lineTo(left, top + radius); quadTo(left, top, left + radius, top)
        close()
    }

    private fun lineIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()
}
