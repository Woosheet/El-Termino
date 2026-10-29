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
}
