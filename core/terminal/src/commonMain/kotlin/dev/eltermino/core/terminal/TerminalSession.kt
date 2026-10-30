package dev.eltermino.core.terminal

import com.johnan.terminal.core.Osc52Policy
import com.johnan.terminal.core.TerminalConfig
import com.johnan.terminal.core.TerminalEmulator
import com.johnan.terminal.core.TerminalScreenState
import dev.eltermino.core.ssh.PtyConfig
import dev.eltermino.core.ssh.ShellChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

data class TerminalSessionConfig(
    val cols: Int = 80,
    val rows: Int = 24,
    val scrollbackLines: Int = 5000,
    /** Lets remote programs (tmux, vim) write the local clipboard through OSC 52. */
    val allowClipboardWrites: Boolean = false,
    /** Window resizes closer together than this are sent to the server once. */
    val resizeDebounceMillis: Long = 60,
) {
    /** The PTY to open the shell with, so the server and the emulator start at the same size. */
    fun ptyConfig(term: String = "xterm-256color") = PtyConfig(term = term, cols = cols, rows = rows)
}

data class TerminalSize(val cols: Int, val rows: Int)

/**
 * Connects a [ShellChannel] to a terminal emulator. Output is parsed off the main thread;
 * everything sent to the shell (keys, pastes, emulator replies) goes through one ordered queue.
 * After a reconnect, [attach] puts a new shell behind the same screen and scrollback.
 */
class TerminalSession(
    shell: ShellChannel,
    scope: CoroutineScope,
    val config: TerminalSessionConfig = TerminalSessionConfig(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + job)
    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)

    private val emulator = TerminalEmulator(
        config = TerminalConfig(
            initialRows = config.rows,
            initialCols = config.cols,
            maxScrollback = config.scrollbackLines,
            osc52Policy = if (config.allowClipboardWrites) Osc52Policy.ALWAYS_ALLOW else Osc52Policy.ALWAYS_DENY,
        ),
        // DA, CPR and mouse reports must reach the server in order with typed input.
        onTerminalResponse = { response -> outbound.trySend(response.encodeToByteArray()) },
    )

    val screenState: StateFlow<TerminalScreenState> = emulator.screenState

    /** Window title set by the remote program with OSC 0/2, if any. */
    val title: StateFlow<String?> = emulator.windowTitle

    private val _closed = MutableStateFlow(false)

    /** True once the current shell's output ended, the shell failed, or [close] was called. */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    /** Why the current shell ended, when it ended with an error. */
    var closeCause: Throwable? = null
        private set

    private val _size = MutableStateFlow(TerminalSize(config.cols, config.rows))
    val size: StateFlow<TerminalSize> = _size.asStateFlow()
    private val requestedSize = MutableStateFlow(_size.value)

    private val _stickyModifiers = MutableStateFlow(KeyModifiers.None)

    /** Ctrl/Alt latched from the on-screen key bar, applied to the next key or character. */
    val stickyModifiers: StateFlow<KeyModifiers> = _stickyModifiers.asStateFlow()

    private val _input = MutableSharedFlow<TerminalInput>(extraBufferCapacity = 256)

    /**
     * What the user typed, pressed or pasted here, for broadcasting to other sessions
     * with [receive]. Emulator replies and [receive]d input are not included.
     */
    val input: SharedFlow<TerminalInput> = _input.asSharedFlow()

    val keyboardMode: KeyboardMode
        get() = KeyboardMode(
            applicationCursorKeys = emulator.applicationCursorKeysEnabled,
            applicationKeypad = emulator.applicationKeypadModeEnabled,
        )

    /** Sees every chunk of shell output before the screen does, for recording a session. */
    @Volatile
    var onOutput: ((ByteArray) -> Unit)? = null

    @Volatile
    private var shell: ShellChannel = shell
    private var reader: Job? = null
    private var writer: Job? = null
    private val attachLock = Mutex()
    private var disposed = false

    init {
        sessionScope.launch {
            requestedSize.drop(1).collectLatest { size ->
                delay(config.resizeDebounceMillis)
                if (emulator.resize(size.rows, size.cols)) {
                    _size.value = size
                    runCatching { this@TerminalSession.shell.resize(size.cols, size.rows) }
                }
            }
        }
        start(shell)
    }

    private fun start(shell: ShellChannel) {
        this.shell = shell
        reader = sessionScope.launch(processingDispatcher) {
            try {
                shell.output.collect { bytes ->
                    onOutput?.invoke(bytes)
                    emulator.processOutput(bytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                closeCause = e
            } finally {
                _closed.value = true
            }
        }
        writer = sessionScope.launch {
            try {
                for (bytes in outbound) shell.write(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                closeCause = closeCause ?: e
                _closed.value = true
            }
        }
    }

    /**
     * Replaces the shell, typically after a reconnect, keeping screen and scrollback. Modes
     * the old program left on (alternate screen, mouse, bracketed paste) are reset, then
     * [notice] is written on its own line. Open the new shell with [size] so both agree.
     */
    suspend fun attach(shell: ShellChannel, notice: String? = null) = attachLock.withLock {
        check(!disposed) { "The terminal session is closed" }
        val old = this.shell
        writer?.cancel()
        reader?.cancelAndJoin()
        // Keys typed while disconnected, and replies meant for the old program, are stale.
        while (outbound.tryReceive().isSuccess) Unit
        withContext(processingDispatcher) {
            emulator.processOutput(RESET_MODES.encodeToByteArray())
            if (notice != null) emulator.processOutput("\r\n\u001B[90m$notice\u001B[0m\r\n".encodeToByteArray())
        }
        closeCause = null
        _closed.value = false
        sessionScope.launch { runCatching { old.close() } }
        start(shell)
    }

    /** Raw bytes, sent as they are. */
    fun sendBytes(bytes: ByteArray) = deliver(TerminalInput.Bytes(bytes), mirror = true)

    /** Text sent as it is, without modifiers or newline conversion. */
    fun sendText(text: String) = sendBytes(text.encodeToByteArray())

    /**
     * Text the user typed on a keyboard or IME. Newlines become Enter (CR), and latched
     * sticky modifiers apply to the first character.
     */
    fun type(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        val sticky = takeStickyModifiers()
        if (sticky.isEmpty) {
            deliver(TerminalInput.Text(normalized), mirror = true)
            return
        }
        val firstLength = if (normalized[0].isHighSurrogate() && normalized.length > 1) 2 else 1
        val codePoint = if (firstLength == 2) {
            0x10000 + ((normalized[0].code - 0xD800) shl 10) + (normalized[1].code - 0xDC00)
        } else {
            normalized[0].code
        }
        deliver(TerminalInput.Char(codePoint, sticky), mirror = true)
        if (normalized.length > firstLength) deliver(TerminalInput.Text(normalized.substring(firstLength)), mirror = true)
    }

    fun sendKey(key: TerminalKey, modifiers: KeyModifiers = KeyModifiers.None) =
        deliver(TerminalInput.Key(key, modifiers + takeStickyModifiers()), mirror = true)

    /** A character with modifiers, for example Ctrl+C from a hardware keyboard. */
    fun sendChar(codePoint: Int, modifiers: KeyModifiers = KeyModifiers.None) =
        deliver(TerminalInput.Char(codePoint, modifiers + takeStickyModifiers()), mirror = true)

    /**
     * Pasted text: newlines become CR and, when the program asked for bracketed paste,
     * the text is wrapped so it can't run as typed commands. An embedded end marker is
     * stripped for the same reason.
     */
    fun paste(text: String) = deliver(TerminalInput.Paste(text), mirror = true)

    /** Input mirrored from another session; it isn't mirrored again. */
    fun receive(input: TerminalInput) = deliver(input, mirror = false)

    private fun deliver(input: TerminalInput, mirror: Boolean) {
        val bytes = when (input) {
            is TerminalInput.Text -> input.text.encodeToByteArray()
            is TerminalInput.Key -> KeyEncoder.encode(input.key, input.modifiers, keyboardMode)
            is TerminalInput.Char -> KeyEncoder.encodeChar(input.codePoint, input.modifiers)
            is TerminalInput.Paste -> {
                val clean = input.text.replace("\u001B[201~", "").replace("\r\n", "\r").replace('\n', '\r')
                emulator.formatPaste(clean).encodeToByteArray()
            }
            is TerminalInput.Bytes -> input.bytes
        }
        if (bytes.isEmpty()) return
        outbound.trySend(bytes)
        if (mirror) _input.tryEmit(input)
    }

    /** The scrollback and the screen as plain text, one line per row. */
    fun snapshotText(): String {
        val state = screenState.value
        return (state.scrollback + state.rows).joinToString("\n") { row ->
            row.joinToString("") { if (it.isWideSpacer) "" else it.glyphString() }.trimEnd()
        }
    }

    fun toggleStickyCtrl() {
        _stickyModifiers.value = _stickyModifiers.value.copy(ctrl = !_stickyModifiers.value.ctrl)
    }

    fun toggleStickyAlt() {
        _stickyModifiers.value = _stickyModifiers.value.copy(alt = !_stickyModifiers.value.alt)
    }

    private fun takeStickyModifiers(): KeyModifiers {
        val sticky = _stickyModifiers.value
        if (!sticky.isEmpty) _stickyModifiers.value = KeyModifiers.None
        return sticky
    }

    /** Asks for a new size; quick successive calls are coalesced before reaching the server. */
    fun resize(cols: Int, rows: Int) {
        if (cols > 0 && rows > 0) requestedSize.value = TerminalSize(cols, rows)
    }

    suspend fun close() {
        attachLock.withLock { disposed = true }
        _closed.value = true
        outbound.close()
        job.cancelAndJoin()
        runCatching { shell.close() }
    }

    private companion object {
        /**
         * Leaves the alternate screen and turns off modes a full-screen program may have left
         * on. The scroll region reset moves the cursor home, hence the save and restore.
         */
        const val RESET_MODES = "\u001B[?1049l\u001B[?1l\u001B>\u001B[?2004l\u001B[?1000l\u001B[?1002l\u001B[?1003l\u001B[?1006l" +
            "\u001B[?25h\u001B[0m\u001B7\u001B[r\u001B8"
    }
}
