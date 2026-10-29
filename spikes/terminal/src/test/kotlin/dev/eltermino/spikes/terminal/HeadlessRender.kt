package dev.eltermino.spikes.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.johnan.terminal.core.TerminalColorScheme
import com.johnan.terminal.core.TerminalScreenState
import com.johnan.terminal.ui.TerminalRenderer
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.ceil

/** Light ANSI palette matching the "Carta" terminal tokens. */
val CartaTerminalScheme = TerminalColorScheme(
    id = "carta",
    name = "Carta",
    background = Color(0xFFFBFAF7),
    foreground = Color(0xFF2C2C2A),
    cursor = Color(0xFF2C2C2A),
    black = Color(0xFF2C2C2A),
    red = Color(0xFFA32D2D),
    green = Color(0xFF3B6D11),
    yellow = Color(0xFF854F0B),
    blue = Color(0xFF185FA5),
    magenta = Color(0xFF993556),
    cyan = Color(0xFF0F6E56),
    white = Color(0xFFD3D1C7),
    brightBlack = Color(0xFF888780),
    brightRed = Color(0xFFE24B4A),
    brightGreen = Color(0xFF639922),
    brightYellow = Color(0xFFBA7517),
    brightBlue = Color(0xFF378ADD),
    brightMagenta = Color(0xFFD4537E),
    brightCyan = Color(0xFF1D9E75),
    brightWhite = Color(0xFFF1EFE8),
)

private const val DENSITY = 2f
private const val FRAME_NANOS = 16_000_000L

@Composable
private fun SpikeTerminal(state: TerminalScreenState, onCell: (Float, Float) -> Unit = { _, _ -> }) {
    TerminalRenderer(
        terminalState = state,
        onInput = {},
        onArrowKey = { _, _ -> },
        onLog = {},
        enabled = false,
        colorScheme = CartaTerminalScheme,
        onCellMeasured = onCell,
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
fun measureCell(state: TerminalScreenState): Pair<Float, Float> {
    var cell = 0f to 0f
    val scene = ImageComposeScene(2400, 1600, Density(DENSITY)) { SpikeTerminal(state) { w, h -> cell = w to h } }
    repeat(3) { scene.render(it * FRAME_NANOS) }
    scene.close()
    check(cell.first > 0f) { "renderer never reported a cell size" }
    return cell
}

/** Renders [state] at exactly cols x rows cells and writes a PNG. */
@OptIn(ExperimentalComposeUiApi::class)
fun renderPng(state: TerminalScreenState, file: File) {
    val (cw, ch) = measureCell(state)
    val width = ceil(cw * state.cols).toInt()
    val height = ceil(ch * state.terminalRows).toInt()
    val scene = ImageComposeScene(width, height, Density(DENSITY)) { SpikeTerminal(state) }
    var image = scene.render(0)
    for (frame in 1..8) image = scene.render(frame * FRAME_NANOS)
    file.parentFile.mkdirs()
    file.writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
    scene.close()
}

/** Average milliseconds per full-screen frame when alternating between [states]. */
@OptIn(ExperimentalComposeUiApi::class)
fun timeRender(states: List<TerminalScreenState>, frames: Int): Double {
    val (cw, ch) = measureCell(states.first())
    val current: MutableState<TerminalScreenState> = mutableStateOf(states.first())
    val scene = ImageComposeScene(
        ceil(cw * states.first().cols).toInt(),
        ceil(ch * states.first().terminalRows).toInt(),
        Density(DENSITY),
    ) { SpikeTerminal(current.value) }
    repeat(10) { scene.render(it * FRAME_NANOS) }
    var t = 10 * FRAME_NANOS
    val start = System.nanoTime()
    for (i in 0 until frames) {
        current.value = states[i % states.size]
        t += FRAME_NANOS
        scene.render(t)
    }
    val elapsed = System.nanoTime() - start
    scene.close()
    return elapsed / 1e6 / frames
}
