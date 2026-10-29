package dev.eltermino.core.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * A [TerminalView] in an offscreen scene; frames only advance when [frames] is called.
 * Copies and pastes go to [clipboard], never to the system's.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HeadlessView(
    session: TerminalSession,
    width: Int,
    height: Int,
    density: Float = 2f,
    val clipboard: FakeClipboard = FakeClipboard(),
    contextMenu: @Composable (TerminalMenu) -> Unit = {},
) : AutoCloseable {
    private val scene = ImageComposeScene(width, height, Density(density)) {
        @Suppress("DEPRECATION")
        CompositionLocalProvider(LocalClipboardManager provides clipboard) {
            TerminalView(session, Modifier.fillMaxSize(), contextMenu = contextMenu)
        }
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

    /** A mouse event at [at], in pixels; [secondary] is the right button. */
    fun mouse(type: PointerEventType, at: Offset, secondary: Boolean = false) {
        val held = type != PointerEventType.Release
        scene.sendPointerEvent(
            type,
            at,
            type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = held && !secondary, isSecondaryPressed = held && secondary),
            button = if (type == PointerEventType.Move) null else if (secondary) PointerButton.Secondary else PointerButton.Primary,
        )
        frames(1)
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

/** A clipboard of its own, so tests leave the user's alone. */
@Suppress("DEPRECATION")
class FakeClipboard : androidx.compose.ui.platform.ClipboardManager {
    @Volatile
    var text: String? = null

    override fun getText(): AnnotatedString? = text?.let { AnnotatedString(it) }

    override fun setText(annotatedString: AnnotatedString) {
        text = annotatedString.text
    }
}
