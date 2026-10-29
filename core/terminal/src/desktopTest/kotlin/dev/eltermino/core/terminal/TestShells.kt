package dev.eltermino.core.terminal

import com.johnan.terminal.core.TerminalScreenState
import dev.eltermino.core.ssh.ShellChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections

class FakeShellChannel : ShellChannel {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    override val output: Flow<ByteArray> = incoming.receiveAsFlow()

    val written: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    val resizes: MutableList<TerminalSize> = Collections.synchronizedList(mutableListOf())
    @Volatile var closed = false

    fun emit(text: String) {
        incoming.trySend(text.encodeToByteArray())
    }

    fun endOutput() {
        incoming.close()
    }

    fun writtenText(): String = synchronized(written) { written.joinToString("") { it.decodeToString() } }

    override suspend fun write(bytes: ByteArray) {
        written += bytes
    }

    override suspend fun resize(cols: Int, rows: Int) {
        resizes += TerminalSize(cols, rows)
    }

    override suspend fun close() {
        closed = true
        incoming.close()
    }
}

/** Polls [condition] until it holds, failing after [timeoutMs]. */
fun eventually(message: () -> String = { "condition not met" }, timeoutMs: Long = 5_000, condition: () -> Boolean) = runBlocking {
    try {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    } catch (e: Exception) {
        throw AssertionError(message(), e)
    }
}

fun TerminalScreenState.line(row: Int): String = rows[row].joinToString("") { if (it.isWideSpacer) "" else it.glyphString() }.trimEnd()

fun TerminalScreenState.dump(): String = rows.indices.joinToString("\n") { line(it) }

fun String.visible(): String = replace("\u001B", "ESC").replace("\r", "\\r").replace("\n", "\\n")
