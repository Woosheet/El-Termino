package dev.eltermino.core.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Selecting with the mouse, as on desktop: drag to select, release to copy, right click for the menu. */
class TerminalSelectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shell = FakeShellChannel()
    private val session = TerminalSession(shell, scope)
    private val width = 1200
    private val height = 700

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /** Sizes the view, then prints a line to select from. */
    private fun HeadlessView.ready() {
        frames(5)
        eventually({ "size stayed ${session.size.value}" }) { session.size.value != TerminalSize(80, 24) }
        Thread.sleep(150)
        shell.emit("hello world\r\n")
        eventually({ session.snapshotText() }) { session.snapshotText().contains("hello world") }
        frames(3)
    }

    /** The middle of a cell on screen. */
    private fun cell(col: Int, row: Int): Offset {
        val size = session.size.value
        return Offset((col + 0.5f) * width / size.cols, (row + 0.5f) * height / size.rows)
    }

    private fun HeadlessView.drag(from: Offset, to: Offset) {
        mouse(PointerEventType.Press, from)
        // Past the renderer's long-press wait, which is a millisecond with a mouse.
        Thread.sleep(30)
        frames(2)
        mouse(PointerEventType.Move, Offset((from.x + to.x) / 2, to.y))
        mouse(PointerEventType.Move, to)
        mouse(PointerEventType.Release, to)
    }

    @Test
    fun draggingWithTheMouseSelectsAndCopies() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.drag(cell(0, 0), cell(4, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "hello" }
        }
    }

    /** With lines gone into the scrollback, the rows on screen still map to the right text. */
    @Test
    fun selectsOnTheLastLineWithAScrollback() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            shell.emit((1..80).joinToString("") { "riga $it\r\n" } + "fine")
            eventually({ session.snapshotText().takeLast(80) }) { session.snapshotText().trimEnd().endsWith("fine") }
            view.frames(8)
            val last = session.size.value.rows - 1
            view.drag(cell(0, last), cell(3, last))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "fine" }
        }
    }

    @Test
    fun aPlainClickLeavesTheClipboardAlone() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.clipboard.text = "prima"
            view.mouse(PointerEventType.Press, cell(2, 0))
            Thread.sleep(30)
            view.frames(2)
            view.mouse(PointerEventType.Release, cell(2, 0))
            Thread.sleep(100)
            assertEquals("prima", view.clipboard.text)
        }
    }

    @Test
    fun aRightClickOffersToCopyTheSelectionAndToPaste() {
        var menu: TerminalMenu? = null
        HeadlessView(session, width, height, contextMenu = { menu = it }).use { view ->
            view.ready()
            assertNull(menu)
            view.drag(cell(6, 0), cell(10, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "world" }

            view.clipboard.text = "ls -la"
            view.mouse(PointerEventType.Press, cell(20, 0), secondary = true)
            view.mouse(PointerEventType.Release, cell(20, 0), secondary = true)
            val shown = assertNotNull(menu, "the right click showed no menu")
            shown.copy!!.invoke()
            assertEquals("world", view.clipboard.text, "Copia takes what was selected when the menu opened")

            view.clipboard.text = "ls -la"
            shown.paste()
            eventually({ shell.writtenText().visible() }) { shell.writtenText().contains("ls -la") }
            shown.dismiss()
            view.frames(2)
            assertTrue(shell.writtenText().count { it == 'l' } >= 2)
        }
    }

    @Test
    fun withNothingSelectedTheMenuOnlyPastes() {
        var menu: TerminalMenu? = null
        HeadlessView(session, width, height, contextMenu = { menu = it }).use { view ->
            view.ready()
            view.mouse(PointerEventType.Press, cell(3, 1), secondary = true)
            view.mouse(PointerEventType.Release, cell(3, 1), secondary = true)
            assertNull(assertNotNull(menu).copy)
        }
    }
}
