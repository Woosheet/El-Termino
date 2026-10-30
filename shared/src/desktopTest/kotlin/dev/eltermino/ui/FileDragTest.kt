package dev.eltermino.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.eltermino.app.AppState
import dev.eltermino.app.ConflictPolicy
import dev.eltermino.app.Endpoint
import dev.eltermino.app.FilesTab
import dev.eltermino.app.Location
import dev.eltermino.app.eventually
import dev.eltermino.app.testServices
import dev.eltermino.ui.theme.CartaTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real file panels side by side, headless, driven by the mouse events a window would get. */
class FileDragTest {
    private val services = testServices()
    private val state = AppState(services)
    private val drag = FileDrag()
    private val copier = CopyFlow(state, state.scope)
    private val root = File(runBlocking { services.localFiles!!.home() })
    private var onTheRight by mutableStateOf<FilesTab?>(null)

    @AfterEach
    fun tearDown() {
        state.scope.cancel()
    }

    private fun folder(name: String, vararg files: String) =
        File(root, name).apply { mkdirs(); files.forEach { File(this, it).writeText(it) } }

    private fun panel(dir: File): FilesTab = state.sessions.openFiles(null, dir.path).also { tab ->
        eventually { tab.browser.path.value != null && !tab.browser.loading.value }
    }

    private fun scene(left: FilesTab, block: ImageComposeScene.() -> Unit) = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        CartaTheme {
            CompositionLocalProvider(LocalFileDrag provides drag, LocalCopyFlow provides copier) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { key(left.id) { FilesPane(state, left, focused = true) } }
                    Box(Modifier.weight(1f)) { onTheRight?.let { key(it.id) { FilesPane(state, it, focused = false) } } }
                }
            }
        }
    }.use { scene ->
        scene.frames()
        scene.block()
    }

    private fun ImageComposeScene.frames(count: Int = 4) = repeat(count) {
        Snapshot.sendApplyNotifications()
        render(System.nanoTime())
        Thread.sleep(15)
    }

    private fun ImageComposeScene.mouse(type: PointerEventType, at: Offset, secondary: Boolean = false) {
        val held = type != PointerEventType.Release
        sendPointerEvent(
            type,
            at,
            type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = held && !secondary, isSecondaryPressed = held && secondary),
            button = if (type == PointerEventType.Move) null else if (secondary) PointerButton.Secondary else PointerButton.Primary,
        )
        frames(2)
    }

    private fun ImageComposeScene.click(at: Offset, secondary: Boolean = false) {
        mouse(PointerEventType.Press, at, secondary)
        mouse(PointerEventType.Release, at, secondary)
    }

    /** A row's gesture starts with the first event it gets, usually the mouse passing over it. */
    private fun ImageComposeScene.hover(y: Float) = mouse(PointerEventType.Move, Offset(100f, y))

    /** A click on a row; it counts once the wait for a double click is over. */
    private fun ImageComposeScene.select(panel: FilesTab, y: Float, path: String) {
        click(Offset(100f, y))
        eventually { frames(1); panel.browser.selection.value == setOf(path) }
    }

    /** From a row of the left panel to the middle of the right one. */
    private fun ImageComposeScene.dragToTheRight(y: Float) {
        val start = Offset(100f, y)
        val end = Offset(WIDTH * 0.75f, HEIGHT / 2f)
        mouse(PointerEventType.Press, start)
        mouse(PointerEventType.Move, start + Offset(40f, 0f))
        mouse(PointerEventType.Move, end)
        mouse(PointerEventType.Release, end)
    }

    /** Where each row of [left] is, found by picking them up and putting them down again. */
    private fun rows(left: FilesTab): Map<String, Float> {
        val found = mutableMapOf<String, Float>()
        scene(left) {
            for (y in 70..220 step 6) {
                val at = Offset(100f, y.toFloat())
                drag.items = emptyList()
                mouse(PointerEventType.Press, at)
                mouse(PointerEventType.Move, at + Offset(40f, 0f))
                mouse(PointerEventType.Release, at + Offset(40f, 0f))
                drag.items.singleOrNull()?.let { found.getOrPut(it.name) { y.toFloat() } }
                left.browser.selection.value = emptySet()
                frames(1)
            }
        }
        return found
    }

    @Test
    fun theFileDraggedIsTheOneThatArrives() {
        val from = folder("from", "a.txt", "b.txt")
        val to = folder("to")
        val left = panel(from)
        val rows = rows(left)
        onTheRight = panel(to)

        scene(left) {
            // Click a, pass over b and click it, then drag it.
            select(left, rows.getValue("a.txt"), "${from.path}/a.txt")
            hover(rows.getValue("b.txt"))
            select(left, rows.getValue("b.txt"), "${from.path}/b.txt")
            dragToTheRight(rows.getValue("b.txt"))
            eventually { File(to, "b.txt").exists() }
        }
        assertEquals(listOf("b.txt"), state.transfers.transfers.value.map { it.name })
        assertFalse(File(to, "a.txt").exists())
    }

    @Test
    fun aRowTakesTheFilesSelectedAfterItAppeared() {
        val from = folder("from", "a.txt", "b.txt", "c.txt")
        val to = folder("to")
        val left = panel(from)
        val rows = rows(left)
        onTheRight = panel(to)

        scene(left) {
            hover(rows.getValue("a.txt"))
            left.browser.selection.value = setOf("${from.path}/a.txt", "${from.path}/b.txt")
            frames()
            dragToTheRight(rows.getValue("a.txt"))
            eventually { File(to, "a.txt").exists() && File(to, "b.txt").exists() }
        }
        assertEquals(setOf("a.txt", "b.txt"), state.transfers.transfers.value.map { it.name }.toSet())
    }

    @Test
    fun aPanelOpenedAfterTheRowsAppearedTakesTheDrop() {
        val from = folder("from", "a.txt")
        val to = folder("to")
        val left = panel(from)
        val rows = rows(left)

        scene(left) {
            hover(rows.getValue("a.txt"))
            onTheRight = panel(to)
            frames()
            dragToTheRight(rows.getValue("a.txt"))
            eventually { File(to, "a.txt").exists() }
        }
    }

    @Test
    fun aPanelNoLongerShownDoesNotCatchTheDrop() {
        val from = folder("from", "a.txt")
        val hidden = List(4) { folder("hidden-$it") }
        val shown = folder("shown")
        val left = panel(from)
        val rows = rows(left)

        scene(left) {
            // Several, because which old place would answer first depends on the panels' ids.
            hidden.forEach {
                onTheRight = panel(it)
                frames()
            }
            onTheRight = panel(shown)
            frames()
            dragToTheRight(rows.getValue("a.txt"))
            eventually { File(shown, "a.txt").exists() }
        }
        hidden.forEach { assertFalse(File(it, "a.txt").exists()) }
    }

    @Test
    fun aFinishedCopyShowsUpInThePanelOfItsFolder() {
        val from = folder("from", "a.txt")
        val to = folder("to")
        val left = panel(from)
        val right = panel(to)

        state.transfers.enqueue(Endpoint.Local, left.browser.entries.value, Location(Endpoint.Local, to.path), ConflictPolicy.Overwrite)
        eventually { right.browser.entries.value.any { it.name == "a.txt" } }
    }

    @Test
    fun theEmptyPartOfAPanelHasAMenuToRefresh() {
        val dir = folder("menu", "a.txt")
        val left = panel(dir)
        scene(left) {
            File(dir, "new.txt").writeText("new")
            val blank = Offset(100f, 200f)
            click(blank, secondary = true)
            click(blank + Offset(30f, 24f))
            eventually { frames(1); left.browser.entries.value.any { it.name == "new.txt" } }
        }
    }

    @Test
    fun aRightClickOnARowSelectsIt() {
        val dir = folder("menu", "a.txt", "b.txt")
        val left = panel(dir)
        val rows = rows(left)
        scene(left) {
            left.browser.selection.value = setOf("${dir.path}/b.txt")
            frames()
            click(Offset(100f, rows.getValue("a.txt")), secondary = true)
            assertTrue("${dir.path}/a.txt" in left.browser.selection.value)
            assertEquals(1, left.browser.selection.value.size)
        }
    }

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 600
    }
}
