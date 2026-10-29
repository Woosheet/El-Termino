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

    val Folder: ImageVector by lazy {
        lineIcon("Folder") {
            moveTo(3.5f, 7.5f); quadTo(3.5f, 5.5f, 5.5f, 5.5f); lineTo(9.5f, 5.5f); lineTo(11.5f, 7.5f)
            lineTo(18.5f, 7.5f); quadTo(20.5f, 7.5f, 20.5f, 9.5f); lineTo(20.5f, 16.5f); quadTo(20.5f, 18.5f, 18.5f, 18.5f)
            lineTo(5.5f, 18.5f); quadTo(3.5f, 18.5f, 3.5f, 16.5f); close()
        }
    }

    /** A sheet with a folded corner: a file. */
    val Document: ImageVector by lazy {
        lineIcon("Document") {
            moveTo(6f, 3.5f); lineTo(13.5f, 3.5f); lineTo(18.5f, 8.5f); lineTo(18.5f, 20.5f); lineTo(6f, 20.5f); close()
            moveTo(13.5f, 3.5f); lineTo(13.5f, 8.5f); lineTo(18.5f, 8.5f)
        }
    }

    /** An arrow out of a tray. */
    val Upload: ImageVector by lazy {
        lineIcon("Upload") {
            moveTo(12f, 15f); lineTo(12f, 4f); moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
            moveTo(4.5f, 15f); lineTo(4.5f, 19.5f); lineTo(19.5f, 19.5f); lineTo(19.5f, 15f)
        }
    }

    /** Two arrows in opposite directions: files moving between places. */
    val Transfer: ImageVector by lazy {
        lineIcon("Transfer") {
            moveTo(4f, 8f); lineTo(19f, 8f); moveTo(15.5f, 4.5f); lineTo(19f, 8f); lineTo(15.5f, 11.5f)
            moveTo(20f, 16f); lineTo(5f, 16f); moveTo(8.5f, 12.5f); lineTo(5f, 16f); lineTo(8.5f, 19.5f)
        }
    }

    val Pause: ImageVector by lazy {
        lineIcon("Pause") { moveTo(9f, 6f); lineTo(9f, 18f); moveTo(15f, 6f); lineTo(15f, 18f) }
    }

    val Play: ImageVector by lazy {
        lineIcon("Play") { moveTo(8f, 5.5f); lineTo(18.5f, 12f); lineTo(8f, 18.5f); close() }
    }

    /** Stacked sheets: a saved set of tabs. */
    val Layers: ImageVector by lazy {
        lineIcon("Layers") {
            moveTo(12f, 4f); lineTo(20f, 8.5f); lineTo(12f, 13f); lineTo(4f, 8.5f); close()
            moveTo(4f, 12.5f); lineTo(12f, 17f); lineTo(20f, 12.5f)
            moveTo(4f, 16f); lineTo(12f, 20.5f); lineTo(20f, 16f)
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
