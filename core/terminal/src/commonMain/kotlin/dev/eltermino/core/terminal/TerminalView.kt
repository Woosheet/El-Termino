package dev.eltermino.core.terminal

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import com.johnan.terminal.core.ArrowDirection
import com.johnan.terminal.core.MouseTrackingMode
import com.johnan.terminal.core.SelectionState
import com.johnan.terminal.core.TerminalScreenState
import com.johnan.terminal.core.TerminalSelection
import com.johnan.terminal.core.TerminalSelectionLogic
import com.johnan.terminal.ui.TerminalCursorConfig
import com.johnan.terminal.ui.TerminalCursorStyle
import com.johnan.terminal.ui.TerminalRenderer
import com.johnan.terminal.ui.TerminalTypographyConfig
import com.johnan.terminal.ui.TerminalUiConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What a right click in the terminal offers; the app draws it with its own menu. */
class TerminalMenu(
    /** Copies the text that was selected when the menu opened; null with nothing selected. */
    val copy: (() -> Unit)?,
    val paste: () -> Unit,
    val dismiss: () -> Unit,
)

/**
 * Draws [session] and sends keyboard input to it. The view sizes the terminal to fit its
 * bounds. Dragging with the mouse selects text, scrolling when the pointer goes past the top
 * or bottom edge, and Shift+click moves an end of the selection to the point; on a touch
 * screen holding and dragging selects. The selection is copied on release. Cmd+C/V on macOS and Ctrl+Shift+C/V elsewhere
 * copy and paste without reaching the shell, and a right click shows [contextMenu] where
 * the pointer is. With several views on screen, only the [focused] one takes the keyboard.
 */
@Composable
fun TerminalView(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    theme: TerminalTheme = TerminalTheme.CartaLight,
    fontSize: TextUnit = 14.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    lineHeight: Float = 1.2f,
    focused: Boolean = true,
    contextMenu: @Composable (TerminalMenu) -> Unit = {},
) {
    val state by session.screenState.collectAsState()
    val colorScheme = remember(theme) { theme.toColorScheme() }
    val uiConfig = remember(colorScheme, theme.cursor, fontSize, fontFamily, lineHeight) {
        TerminalUiConfig(
            typography = TerminalTypographyConfig(fontSize = fontSize, fontFamily = fontFamily, lineHeightMultiplier = lineHeight),
            colorScheme = colorScheme,
            // The block is drawn over the glyph, so it has to stay translucent.
            cursor = TerminalCursorConfig(style = TerminalCursorStyle.BLOCK, overrideColor = theme.cursor.copy(alpha = 0.45f)),
        )
    }
    var selection by remember(session) { mutableStateOf<SelectionState>(SelectionState.None) }
    // The renderer takes the click of a Shift+click for a tap, which would clear what it just extended.
    var ignoreTap by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    val viewFocus = remember { FocusRequester() }
    val keyHandler = remember { TerminalKeyHandler(isMacOs) }
    var cellWidthPx by remember { mutableFloatStateOf(0f) }
    var cellHeightPx by remember { mutableFloatStateOf(0f) }
    // Ours, so a mouse position can be turned into a row of the scrollback.
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    fun selectedText(): String? = selection.toTerminalSelection()?.let { state.textIn(it) }?.takeIf { it.isNotEmpty() }

    fun copySelection() {
        selectedText()?.let { clipboard.setText(AnnotatedString(it)) }
    }

    /** Where a right click opened the menu, and what was selected then. */
    var menu by remember { mutableStateOf<Pair<Offset, String?>?>(null) }

    // The renderer drops its input field while a selection is shown; keep the keyboard on the view meanwhile.
    val selecting = selection !is SelectionState.None
    LaunchedEffect(selecting, focused) {
        if (!focused) return@LaunchedEffect
        withFrameNanos { }
        runCatching { if (selecting) viewFocus.requestFocus() else inputFocus.requestFocus() }
    }

    BoxWithConstraints(
        modifier
            .onPreviewKeyEvent { event ->
                keyHandler.onKeyEvent(event, inputFieldActive = !selecting) { action ->
                    when (action) {
                        KeyAction.Copy -> copySelection()
                        KeyAction.Paste -> clipboard.getText()?.text?.let(session::paste)
                        is KeyAction.SendKey -> {
                            selection = SelectionState.None
                            session.sendKey(action.key, action.modifiers)
                        }
                        is KeyAction.SendChar -> {
                            selection = SelectionState.None
                            session.sendChar(action.codePoint, action.modifiers)
                        }
                        KeyAction.PassThrough -> Unit
                    }
                }
            }
            .focusRequester(viewFocus)
            .focusable()
            // Ahead of the renderer, so a right click opens the menu instead of starting a selection.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            menu = event.changes.first().position to selectedText()
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            // With a mouse, dragging selects at once; the renderer's own selection waits for a
            // long press, as a touch screen needs. A program that reads the mouse gets the clicks,
            // unless Shift is held, as in xterm.
            .pointerInput(selectsOnPress) {
                if (!selectsOnPress) return@pointerInput
                // The rows drawn end here; any strip left below them belongs to no row.
                fun rowsBottom() = floor(size.height / cellHeightPx) * cellHeightPx
                fun cellAt(at: Offset): Pair<Int, Int>? {
                    if (cellWidthPx <= 0f || cellHeightPx <= 0f) return null
                    // Past an edge the pointer stands for the row on that edge.
                    val y = at.y.coerceIn(0f, (rowsBottom() - 1f).coerceAtLeast(0f))
                    val row = floor((y + scroll.value) / cellHeightPx).toInt().coerceAtLeast(0)
                    return row to floor(at.x / cellWidthPx).toInt().coerceAtLeast(0)
                }
                // Lines to scroll at each step: none inside the view, more the farther out the pointer is.
                fun linesPastEdge(y: Float): Int {
                    if (cellHeightPx <= 0f) return 0
                    val past = if (y < 0f) -y else y - rowsBottom()
                    if (past <= 0f) return 0
                    val lines = (1 + (past / (cellHeightPx * 4)).toInt()).coerceAtMost(MAX_AUTO_SCROLL_LINES)
                    return if (y < 0f) -lines else lines
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val press = currentEvent
                    val mine = down.type == PointerType.Mouse && press.buttons.isPrimaryPressed &&
                        (state.mouseTrackingMode == MouseTrackingMode.None || press.keyboardModifiers.isShiftPressed)
                    val clicked = if (mine) cellAt(down.position) else null
                    if (clicked == null) return@awaitEachGesture
                    ignoreTap = false
                    val extended = selection.toTerminalSelection().takeIf { press.keyboardModifiers.isShiftPressed }
                    val start = extended?.anchorFor(clicked) ?: clicked
                    var dragged = extended != null
                    if (extended != null) {
                        selection = SelectionState.EndCursorDragging(start.first, start.second, clicked.first, clicked.second)
                        down.consume()
                    }
                    var pointer = down.position
                    fun extendTo(at: Offset): Boolean {
                        val end = cellAt(at) ?: return false
                        if (end == start && !dragged) return false
                        dragged = true
                        selection = SelectionState.EndCursorDragging(start.first, start.second, end.first, end.second)
                        return true
                    }
                    // Past an edge the view scrolls on its own clock, whether the mouse moves or not.
                    val autoScroll = scope.launch {
                        while (isActive) {
                            delay(AUTO_SCROLL_MILLIS)
                            val lines = linesPastEdge(pointer.y)
                            if (lines != 0) {
                                scroll.dispatchRawDelta(lines * cellHeightPx)
                                extendTo(pointer)
                            }
                        }
                    }
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            pointer = change.position
                            if (extendTo(pointer)) change.consume()
                        }
                    } finally {
                        autoScroll.cancel()
                    }
                    ignoreTap = extended != null
                    // A plain click clears the selection and leaves the clipboard alone.
                    if (dragged) {
                        selection = TerminalSelectionLogic.finalizeEndCursor(selection)
                        copySelection()
                    } else {
                        selection = SelectionState.None
                    }
                }
            },
    ) {
        // The renderer fits whole rows but scrolls in its full height, so a leftover strip
        // showed half a scrollback line at the top. Give it exactly the rows it uses.
        val density = LocalDensity.current
        val rendererHeight = if (cellHeightPx > 0f && maxHeight != Dp.Infinity) {
            with(density) { (floor(maxHeight.toPx() / cellHeightPx) * cellHeightPx).toDp() }
        } else {
            maxHeight
        }
        TerminalRenderer(
            terminalState = state,
            onInput = session::type,
            onArrowKey = { direction, alt -> session.sendKey(direction.toKey(), if (alt) KeyModifiers.Alt else KeyModifiers.None) },
            onLog = {},
            onCellMeasured = { width, height ->
                cellWidthPx = width
                cellHeightPx = height
            },
            modifier = Modifier.fillMaxWidth().height(rendererHeight),
            config = uiConfig,
            focusRequester = inputFocus,
            colorScheme = colorScheme,
            fontSize = fontSize.value,
            scrollState = scroll,
            selectionState = selection,
            onSingleTap = { _, _ -> if (ignoreTap) ignoreTap = false else selection = SelectionState.None },
            onStartDirectTouchSelection = { row, col -> selection = SelectionState.EndCursorDragging(row, col, row, col) },
            onStartDraggingStartCursor = { row, col -> selection = TerminalSelectionLogic.startDraggingStartCursor(selection, row, col) },
            onUpdateStartCursor = { row, col -> selection = TerminalSelectionLogic.updateStartCursor(selection, row, col) },
            onFinalizeStartCursor = { selection = TerminalSelectionLogic.finalizeStartCursor(selection) },
            onStartDraggingEndCursor = { row, col -> selection = TerminalSelectionLogic.startDraggingEndCursor(selection, row, col) },
            onUpdateEndCursor = { row, col -> selection = TerminalSelectionLogic.updateEndCursor(selection, row, col) },
            onFinalizeEndCursorAndCopy = {
                selection = TerminalSelectionLogic.finalizeEndCursor(selection)
                copySelection()
            },
            onExitCopyMode = { if (!ignoreTap) selection = SelectionState.None },
            onTerminalResize = session::resize,
        )
        menu?.let { (at, text) ->
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                contextMenu(
                    TerminalMenu(
                        copy = text?.let { { clipboard.setText(AnnotatedString(it)) } },
                        paste = { clipboard.getText()?.text?.let(session::paste) },
                        dismiss = { menu = null },
                    ),
                )
            }
        }
    }
}

private fun ArrowDirection.toKey() = when (this) {
    ArrowDirection.UP -> TerminalKey.Up
    ArrowDirection.DOWN -> TerminalKey.Down
    ArrowDirection.LEFT -> TerminalKey.Left
    ArrowDirection.RIGHT -> TerminalKey.Right
}

/** Between two scroll steps while a selection is dragged past an edge. */
private const val AUTO_SCROLL_MILLIS = 80L

private const val MAX_AUTO_SCROLL_LINES = 5

/**
 * The end of this selection that stays put when Shift+click moves the other one to [cell]: the
 * farther, so a click outside only grows the selection.
 */
internal fun TerminalSelection.anchorFor(cell: Pair<Int, Int>): Pair<Int, Int> {
    val range = normalized()
    val start = range.startRow to range.startCol
    val end = range.endRow to range.endCol
    fun distanceFrom(point: Pair<Int, Int>) = abs((cell.first - point.first) * 10_000L + (cell.second - point.second))
    return if (distanceFrom(start) >= distanceFrom(end)) start else end
}

/** Plain text of [selection], whose rows count from the top of the scrollback. */
internal fun TerminalScreenState.textIn(selection: TerminalSelection): String {
    val range = selection.normalized()
    val total = scrollback.size + rows.size
    if (range.startRow < 0 || range.startRow >= total) return ""
    return (range.startRow..minOf(range.endRow, total - 1)).joinToString("\n") { row ->
        val cells = if (row < scrollback.size) scrollback[row] else rows[row - scrollback.size]
        val from = if (row == range.startRow) range.startCol else 0
        val to = if (row == range.endRow) range.endCol else cells.lastIndex
        buildString {
            for (col in from..minOf(to, cells.lastIndex)) {
                if (!cells[col].isWideSpacer) append(cells[col].glyphString())
            }
        }.trimEnd()
    }
}
