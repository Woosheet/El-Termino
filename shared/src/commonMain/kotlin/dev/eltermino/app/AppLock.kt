package dev.eltermino.app

import dev.eltermino.core.data.UiSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hides the app behind the fingerprint or the system password. It locks the interface only: the
 * vault's key stays where it is, and sessions and transfers keep running underneath.
 */
class AppLock(
    private val platform: Platform,
    private val settings: () -> UiSettings,
    /** Wall-clock milliseconds, so that a computer asleep counts as time gone by. */
    private val clock: () -> Long,
) {
    private val mutableLocked = MutableStateFlow(settings().biometricLock && platform.biometricAvailable)
    val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()

    private var awaySince: Long? = null
    private var lastTick: Long? = null

    /** The lock is on, but this device can't ask: better open with a warning than never open. */
    val biometricsMissing: Boolean get() = settings().biometricLock && !platform.biometricAvailable

    /** Call every second or so. */
    fun tick() {
        val ui = settings()
        if (!ui.biometricLock || !platform.biometricAvailable) {
            awaySince = null
            lastTick = null
            return
        }
        val now = clock()
        val since = awaySince
        val limit = ui.lockAfterMinutes * MILLIS_PER_MINUTE
        // A long gap between ticks is the computer asleep, whichever window was in front.
        val slept = lastTick?.let { now - it >= limit } == true
        lastTick = now
        if (slept || since != null && now - since >= limit) mutableLocked.value = true
        awaySince = if (platform.isInForeground) null else since ?: now
    }

    /** Asks only once the user is here: a fingerprint sheet shouldn't pop up over another app. */
    suspend fun unlockWhenBack() {
        while (!platform.isInForeground) delay(FOREGROUND_CHECK_MILLIS)
        unlock()
    }

    suspend fun unlock(): Boolean {
        if (!platform.authenticate("Sblocca El Termino")) return false
        awaySince = null
        lastTick = null
        mutableLocked.value = false
        return true
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val FOREGROUND_CHECK_MILLIS = 500L
    }
}
