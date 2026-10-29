package dev.eltermino.app

import dev.eltermino.core.data.KnownHost
import dev.eltermino.core.ssh.HostKeyFingerprint
import dev.eltermino.core.ssh.KeyboardInteractiveResponder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A question the connection flow is waiting on; the UI shows it as a dialog. */
sealed class PendingPrompt<T> {
    internal val reply = CompletableDeferred<T?>()

    fun answer(value: T) {
        reply.complete(value)
    }

    fun cancel() {
        reply.complete(null)
    }

    class HostKey(
        val host: String,
        val port: Int,
        val fingerprint: HostKeyFingerprint,
        /** Set when we already trust a different key of the same type. */
        val previous: KnownHost?,
    ) : PendingPrompt<Boolean>()

    class Secret(
        val title: String,
        val label: String,
        val allowSave: Boolean,
    ) : PendingPrompt<SecretAnswer>()

    class Text(
        val title: String,
        val label: String,
    ) : PendingPrompt<String>()

    class Interactive(
        val hostLabel: String,
        val name: String,
        val instruction: String,
        val prompts: List<KeyboardInteractiveResponder.Prompt>,
    ) : PendingPrompt<List<String>>()
}

class SecretAnswer(val value: ByteArray, val save: Boolean)

/** One prompt at a time, even when several connections ask together. */
class Prompter {
    private val mutex = Mutex()
    private val current = MutableStateFlow<PendingPrompt<*>?>(null)
    val prompt: StateFlow<PendingPrompt<*>?> = current.asStateFlow()

    /** Shows [prompt] and suspends until it's answered; null means cancelled. */
    suspend fun <T> ask(prompt: PendingPrompt<T>): T? = mutex.withLock {
        current.value = prompt
        try {
            prompt.reply.await()
        } finally {
            current.value = null
        }
    }
}

class PromptCancelledException : Exception("Annullato")
