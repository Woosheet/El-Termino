package dev.eltermino.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lives as long as the process, not the window or activity, so open sessions survive
 * an Android rotation or a recomposition.
 */
private const val LOCK_CHECK_MILLIS = 1_000L

class AppState(val services: AppServices) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val actions = WorkspaceActions(services)
    val sessions = SessionsController(services, actions, scope)
    val transfers = TransferManager(scope, services, sessions.connections)
    val requests = HttpRunner(services, sessions.connections, scope)
    val forwards = PortForwards(services, sessions.connections, scope)
    val notifier = TransferNotifier(scope, services, transfers)
    val updates = UpdateChecker(scope, services)
    val lock = AppLock(services.platform, { services.workspace.workspace.value.ui }, { nowEpochSeconds() * 1000 })

    init {
        scope.launch {
            if (lock.biometricsMissing) notifier.post("Blocco non attivo", "Su questo dispositivo la biometria non è disponibile")
            while (true) {
                lock.tick()
                delay(LOCK_CHECK_MILLIS)
            }
        }
        // Old recordings go at startup, as long as the user asked to keep them.
        scope.launch {
            sessions.connections.keyRenewals.collect { host ->
                notifier.post("Chiave dell'host rinnovata", "$host ha una chiave nuova, garantita da una CA fidata")
            }
        }
        scope.launch { services.recordings?.prune(services.workspace.workspace.value.ui.recordingDays, nowEpochSeconds()) }
    }

    private val mutableCommands = kotlinx.coroutines.flow.MutableSharedFlow<AppCommand>(extraBufferCapacity = 16)

    /** Keyboard shortcuts and menu items, for the window to carry out. */
    val commands: kotlinx.coroutines.flow.SharedFlow<AppCommand> = mutableCommands

    private var lastCommand: Pair<AppCommand, kotlin.time.TimeSource.Monotonic.ValueTimeMark>? = null

    /**
     * Carries out [command]. The same command twice within a few milliseconds is one key press
     * that reached both the menu bar and the window, so the second is dropped.
     */
    fun run(command: AppCommand) {
        if (lock.locked.value) return
        val last = lastCommand
        if (last != null && last.first == command && last.second.elapsedNow() < 30.milliseconds) return
        lastCommand = command to kotlin.time.TimeSource.Monotonic.markNow()
        mutableCommands.tryEmit(command)
    }

    /** Whether the "+" menu after the tabs is open, so a shortcut can open it too. */
    val newTabMenu = kotlinx.coroutines.flow.MutableStateFlow(false)

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
