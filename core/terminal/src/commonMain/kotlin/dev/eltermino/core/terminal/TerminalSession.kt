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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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
 * Connects one [ShellChannel] to a terminal emulator. Output is parsed off the main thread;
 * everything sent to the shell (keys, pastes, emulator replies) goes through one ordered queue.
 */
class TerminalSession(
    private val shell: ShellChannel,
    scope: CoroutineScope,
    val config: TerminalSessionConfig = TerminalSessionConfig(),
    processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
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

    /** True once the shell's output ended, the shell failed, or [close] was called. */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    /** Why the session ended, when it ended with an error. */
    var closeCause: Throwable? = null
        private set

    private val _size = MutableStateFlow(TerminalSize(config.cols, config.rows))
    val size: StateFlow<TerminalSize> = _size.asStateFlow()
    private val requestedSize = MutableStateFlow(_size.value)

    private val _stickyModifiers = MutableStateFlow(KeyModifiers.None)

    /** Ctrl/Alt latched from the on-screen key bar, applied to the next key or character. */
    val stickyModifiers: StateFlow<KeyModifiers> = _stickyModifiers.asStateFlow()

    val keyboardMode: KeyboardMode
        get() = KeyboardMode(
            applicationCursorKeys = emulator.applicationCursorKeysEnabled,
            applicationKeypad = emulator.applicationKeypadModeEnabled,
        )

    init {
        sessionScope.launch(processingDispatcher) {
            try {
                shell.output.collect { emulator.processOutput(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                closeCause = e
            } finally {
                _closed.value = true
            }
        }
        sessionScope.launch {
            try {
                for (bytes in outbound) shell.write(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                closeCause = closeCause ?: e
                _closed.value = true
            }
        }
        sessionScope.launch {
            requestedSize.drop(1).collectLatest { size ->
                delay(config.resizeDebounceMillis)
                if (emulator.resize(size.rows, size.cols)) {
                    _size.value = size
                    runCatching { shell.resize(size.cols, size.rows) }
                }
            }
        }
    }

    /** Raw bytes, sent as they are. */
    fun sendBytes(bytes: ByteArray) {
        if (bytes.isNotEmpty()) outbound.trySend(bytes)
    }

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
            sendText(normalized)
            return
        }
        val firstLength = if (normalized[0].isHighSurrogate() && normalized.length > 1) 2 else 1
        val codePoint = if (firstLength == 2) {
            0x10000 + ((normalized[0].code - 0xD800) shl 10) + (normalized[1].code - 0xDC00)
        } else {
            normalized[0].code
        }
        sendBytes(KeyEncoder.encodeChar(codePoint, sticky) + normalized.substring(firstLength).encodeToByteArray())
    }

    fun sendKey(key: TerminalKey, modifiers: KeyModifiers = KeyModifiers.None) {
        sendBytes(KeyEncoder.encode(key, modifiers + takeStickyModifiers(), keyboardMode))
    }

    /** A character with modifiers, for example Ctrl+C from a hardware keyboard. */
    fun sendChar(codePoint: Int, modifiers: KeyModifiers = KeyModifiers.None) {
        sendBytes(KeyEncoder.encodeChar(codePoint, modifiers + takeStickyModifiers()))
    }

    /**
     * Pasted text: newlines become CR and, when the program asked for bracketed paste,
     * the text is wrapped so it can't run as typed commands. An embedded end marker is
     * stripped for the same reason.
     */
    fun paste(text: String) {
        val clean = text.replace("\u001B[201~", "").replace("\r\n", "\r").replace('\n', '\r')
        sendText(emulator.formatPaste(clean))
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
        _closed.value = true
        outbound.close()
        job.cancelAndJoin()
        runCatching { shell.close() }
    }
}
