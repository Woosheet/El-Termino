package dev.eltermino.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Lives as long as the process, not the window or activity, so open sessions survive
 * an Android rotation or a recomposition.
 */
class AppState(val services: AppServices) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val actions = WorkspaceActions(services)
    val sessions = SessionsController(services, actions, scope)
    val transfers = TransferManager(scope, services, sessions.connections)
    val notifier = TransferNotifier(scope, services, transfers)

    private val mutableExitRequested = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Set when the window is closing with transfers still going, so the app can ask first. */
    val exitRequested: kotlinx.coroutines.flow.StateFlow<Boolean> = mutableExitRequested

    /** True when closing now would cut transfers short; the app then asks before closing. */
    fun requestExit(): Boolean {
        val summary = transfers.summary.value
        val busy = summary.active + summary.waiting > 0
        mutableExitRequested.value = busy
        return busy
    }

    fun cancelExit() {
        mutableExitRequested.value = false
    }
}
