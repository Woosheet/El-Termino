package dev.eltermino.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.eltermino.ui.App
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the main window headless to build/snapshots/, to eyeball layout and theme. */
class AppSnapshotTest {
    @Test
    fun desktopWindow() {
        val png = ImageComposeScene(width = 2400, height = 1520, density = Density(2f)) { App() }.use { scene ->
            scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val out = File("build/snapshots/app-desktop.png").apply { parentFile.mkdirs(); writeBytes(png) }
        assertTrue(out.length() > 0)
    }

    @Test
    fun phoneWidth() {
        val png = ImageComposeScene(width = 780, height = 1600, density = Density(2f)) { App() }.use { scene ->
            scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        File("build/snapshots/app-phone.png").apply { parentFile.mkdirs(); writeBytes(png) }
    }
}
