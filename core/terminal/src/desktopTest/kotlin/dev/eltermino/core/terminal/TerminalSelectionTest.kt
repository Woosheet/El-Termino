package dev.eltermino.core.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
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
    private companion object {
        /** What a key without a character reports on desktop, AWT's CHAR_UNDEFINED. */
        const val NO_CHARACTER = 0xFFFF
    }

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

    private fun HeadlessView.longOutput() {
        shell.emit((1..80).joinToString("") { "riga $it\r\n" } + "fine")
        eventually({ session.snapshotText().takeLast(80) }) { session.snapshotText().trimEnd().endsWith("fine") }
        frames(8)
    }

    /** Keeps the button down past an edge for [millis]; with [jiggle] the mouse never stops moving. */
    private fun HeadlessView.holdAt(at: Offset, millis: Long, jiggle: Boolean = false) {
        mouse(PointerEventType.Move, at)
        val deadline = System.currentTimeMillis() + millis
        var step = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            frames(1)
            if (jiggle) mouse(PointerEventType.Move, at + Offset(if (step++ % 2 == 0) 3f else 0f, 0f))
        }
    }

    /** Selects from [from] with the button held at [at], past an edge, and returns what got copied. */
    private fun HeadlessView.selectPastAnEdge(from: Offset, at: Offset, jiggle: Boolean = false): List<String> {
        clipboard.text = null
        mouse(PointerEventType.Press, from)
        Thread.sleep(30)
        frames(2)
        holdAt(at, 900, jiggle)
        mouse(PointerEventType.Release, at)
        eventually({ "nothing copied" }) { clipboard.text != null }
        return clipboard.text!!.lines()
    }

    @Test
    fun draggingPastTheTopScrollsUpAndKeepsSelecting() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.longOutput()
            val rows = session.size.value.rows
            val copied = view.selectPastAnEdge(cell(3, rows - 1), Offset(cell(0, 0).x, -30f))
            assertTrue(copied.size >= rows + 4, "only ${copied.size} lines of $rows on screen: ${copied.first()}")
            assertEquals("fine", copied.last())
        }
    }

    @Test
    fun draggingPastTheBottomScrollsDownAndKeepsSelecting() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.longOutput()
            val rows = session.size.value.rows
            // Up into the scrollback, then down from there.
            view.selectPastAnEdge(cell(3, rows - 1), Offset(cell(0, 0).x, -30f))
            val copied = view.selectPastAnEdge(cell(0, 0), Offset(cell(3, 0).x, height + 30f))
            assertTrue(copied.size >= rows + 4, "only ${copied.size} lines of $rows on screen: ${copied.last()}")
            // It may reach the end of the output, whose last line isn't numbered.
            val numbers = copied.filter { it.startsWith("riga ") }.map { it.removePrefix("riga ").toInt() }
            assertEquals((numbers.first()..numbers.last()).toList(), numbers, "lines skipped")
        }
    }

    /** A mouse moves all the time, and that must not hold the scrolling back. */
    @Test
    fun keepsScrollingWhileTheMouseMoves() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.longOutput()
            val rows = session.size.value.rows
            val copied = view.selectPastAnEdge(cell(3, rows - 1), Offset(cell(0, 0).x, -30f), jiggle = true)
            assertTrue(copied.size >= rows + 4, "only ${copied.size} lines of $rows on screen")
        }
    }

    private fun HeadlessView.shiftClick(at: Offset) {
        mouse(PointerEventType.Press, at, shift = true)
        Thread.sleep(30)
        frames(2)
        mouse(PointerEventType.Release, at, shift = true)
    }

    @Test
    fun shiftClickExtendsTheSelectionAndKeepsIt() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.drag(cell(0, 0), cell(4, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "hello" }
            view.shiftClick(cell(7, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "hello wo" }
            // Still selected after the release, so a second one goes on from there.
            Thread.sleep(100)
            view.frames(3)
            view.shiftClick(cell(10, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "hello world" }
        }
    }

    /** A real Shift+click starts with Shift going down on its own, which has no character. */
    @Test
    fun pressingShiftLeavesTheSelectionAloneAndSendsNothing() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.drag(cell(0, 0), cell(4, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "hello" }
            val written = shell.writtenText()
            view.press(Key.ShiftLeft, codePoint = NO_CHARACTER, shift = true)
            view.shiftClick(cell(10, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "hello world" }
            assertEquals(written, shell.writtenText(), "Shift reached the shell")
        }
    }

    @Test
    fun shiftClickBeforeTheSelectionKeepsWhatWasSelected() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.drag(cell(6, 0), cell(10, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "world" }
            view.shiftClick(cell(0, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "hello world" }
        }
    }

    @Test
    fun shiftClickInsideTheSelectionMovesTheNearerEnd() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.drag(cell(0, 0), cell(10, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "hello world" }
            view.shiftClick(cell(8, 0))
            eventually({ "clipboard has ${view.clipboard.text}" }) { view.clipboard.text == "hello wor" }
        }
    }

    @Test
    fun shiftClickWithNothingSelectedStaysAClick() {
        HeadlessView(session, width, height).use { view ->
            view.ready()
            view.clipboard.text = "prima"
            view.shiftClick(cell(4, 0))
            Thread.sleep(100)
            assertEquals("prima", view.clipboard.text)
        }
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
