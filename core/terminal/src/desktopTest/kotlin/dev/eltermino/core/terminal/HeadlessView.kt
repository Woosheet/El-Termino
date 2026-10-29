package dev.eltermino.core.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** A [TerminalView] in an offscreen scene; frames only advance when [frames] is called. */
@OptIn(ExperimentalComposeUiApi::class)
class HeadlessView(
    session: TerminalSession,
    width: Int,
    height: Int,
    density: Float = 2f,
) : AutoCloseable {
    private val scene = ImageComposeScene(width, height, Density(density)) {
        TerminalView(session, Modifier.fillMaxSize())
    }
    private var timeNanos = 0L

    fun frames(count: Int = 5) {
        repeat(count) {
            scene.render(timeNanos)
            timeNanos += 16_000_000L
        }
    }

    // The desktop KeyEvent constructor is internal API; fine for tests pinned to one Compose version.
    @OptIn(InternalComposeUiApi::class)
    fun press(key: Key, codePoint: Int = 0, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, meta: Boolean = false): Boolean {
        val down = scene.sendKeyEvent(
            KeyEvent(key, KeyEventType.KeyDown, codePoint, isCtrlPressed = ctrl, isMetaPressed = meta, isAltPressed = alt, isShiftPressed = shift),
        )
        scene.sendKeyEvent(
            KeyEvent(key, KeyEventType.KeyUp, codePoint, isCtrlPressed = ctrl, isMetaPressed = meta, isAltPressed = alt, isShiftPressed = shift),
        )
        frames(1)
        return down
    }

    fun savePng(file: File) {
        frames(8)
        val image = scene.render(timeNanos)
        file.parentFile.mkdirs()
        file.writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
    }

    override fun close() = scene.close()
}

val snapshotDir = File("build/snapshots")
