package dev.eltermino.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A short message about the transfer queue, shown in the app and maybe by the system. */
data class Notice(val id: Long, val title: String, val message: String, val failed: Boolean)

/**
 * Turns finished transfers into notices. Transfers that end close together become one
 * notice ("4 trasferimenti completati"), so a folder of files doesn't ring 40 times.
 */
class TransferNotifier(
    private val scope: CoroutineScope,
    private val services: AppServices,
    private val transfers: TransferManager,
    private val windowMillis: Long = 1_500,
) {
    private val mutableNotice = MutableStateFlow<Notice?>(null)

    /** The latest notice, until [dismiss]. */
    val notice: StateFlow<Notice?> = mutableNotice.asStateFlow()

    private val mutex = Mutex()
    private val pending = mutableListOf<Transfer>()
    private val seen = mutableSetOf<String>()
    private var counter = 0L

    init {
        scope.launch { watch() }
    }

    fun dismiss() {
        mutableNotice.value = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watch() {
        transfers.transfers
            .flatMapLatest { list ->
                if (list.isEmpty()) flowOf(emptyList())
                else combine(list.map { t -> t.state.map { t to it } }) { it.toList() }
            }
            .collect { states ->
                // Cancelled by the user: nothing to tell them.
                val finished = states.filter { (t, s) -> (s is TransferState.Done || s is TransferState.Failed) && t.id !in seen }
                if (finished.isEmpty()) return@collect
                mutex.withLock {
                    finished.forEach { (t, _) -> seen += t.id }
                    val first = pending.isEmpty()
                    pending += finished.map { it.first }
                    if (first) scope.launch { flushLater() }
                }
            }
    }

    private suspend fun flushLater() {
        delay(windowMillis)
        val batch = mutex.withLock { pending.toList().also { pending.clear() } }
        if (batch.isEmpty()) return
        val failed = batch.filter { it.state.value is TransferState.Failed }
        val workspace = services.workspace.workspace.value
        fun where(endpoint: Endpoint) = when (endpoint) {
            Endpoint.Local -> "questo computer"
            is Endpoint.Remote -> workspace.host(endpoint.hostId)?.label ?: "host eliminato"
        }
        val notice = when {
            batch.size == 1 && failed.isEmpty() -> Notice(++counter, "Trasferimento completato", "${batch[0].name} è su ${where(batch[0].targetDir.endpoint)}", failed = false)
            batch.size == 1 -> Notice(++counter, "Trasferimento non riuscito", "${batch[0].name}: ${(batch[0].state.value as TransferState.Failed).message}", failed = true)
            failed.isEmpty() -> Notice(++counter, "${batch.size} trasferimenti completati", batch.take(3).joinToString(", ") { it.name } + if (batch.size > 3) "…" else "", failed = false)
            else -> Notice(++counter, "${failed.size} trasferimenti non riusciti", "${batch.size - failed.size} completati, ${failed.size} con errori: riprova dalla barra dei trasferimenti", failed = true)
        }
        mutableNotice.value = notice
        val platform = services.platform
        if (workspace.ui.notifyTransfers && !platform.isInForeground) platform.notify(notice.title, notice.message)
    }
}
