package dev.eltermino.app

import dev.eltermino.core.ssh.ShellChannel
import dev.eltermino.core.terminal.TerminalSession
import dev.eltermino.core.terminal.TerminalSessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** A recording played back in a terminal, as it appeared, at the chosen speed. */
class ReplayTab internal constructor(id: String, label: String, val cast: Asciicast, private val scope: CoroutineScope) : Tab(id, label) {
    override val hostId: String? = null

    val playing = MutableStateFlow(true)

    /** 1, 2, 4 or 8 times as fast as recorded. */
    val speed = MutableStateFlow(1f)

    internal val mutablePosition = MutableStateFlow(0.0)

    /** Seconds into the recording. */
    val position: StateFlow<Double> = mutablePosition.asStateFlow()

    internal val mutableFinished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = mutableFinished.asStateFlow()

    val terminal = TerminalSession(ReplayChannel(this), scope, TerminalSessionConfig(cols = cast.width, rows = cast.height))

    fun restart() {
        mutablePosition.value = 0.0
        mutableFinished.value = false
        playing.value = true
        scope.launch { terminal.attach(ReplayChannel(this@ReplayTab)) }
    }

    internal companion object {
        /** Pauses longer than this play as this long, as asciinema's idle limit does. */
        const val IDLE_LIMIT = 2.0
    }
}

/** A "shell" that plays [tab]'s recording; what's typed into it goes nowhere. */
private class ReplayChannel(private val tab: ReplayTab) : ShellChannel {
    override val output: Flow<ByteArray> = flow {
        // Starts from a clean screen, also when played again over the last frame.
        emit("\u001Bc".encodeToByteArray())
        var previous = 0.0
        for (event in tab.cast.events) {
            var left = (event.time - previous).coerceIn(0.0, ReplayTab.IDLE_LIMIT)
            previous = event.time
            // Small steps, so a pause or a new speed takes effect within a moment.
            while (left > 0) {
                tab.playing.first { it }
                val step = minOf(left, 0.05)
                delay((step * 1000 / tab.speed.value).toLong())
                left -= step
            }
            tab.playing.first { it }
            tab.mutablePosition.value = event.time
            if (event.kind == "o") emit(event.data.encodeToByteArray())
        }
        tab.mutableFinished.value = true
        tab.playing.value = false
        // Stays open, so the last frame stays on screen instead of a "closed" notice.
        awaitCancellation()
    }

    override suspend fun write(bytes: ByteArray) {}
    override suspend fun resize(cols: Int, rows: Int) {}
    override suspend fun close() {}
}
