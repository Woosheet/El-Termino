package dev.eltermino.app

import dev.eltermino.core.data.UiSettings
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

    /** The lock is on, but this device can't ask: better open with a warning than never open. */
    val biometricsMissing: Boolean get() = settings().biometricLock && !platform.biometricAvailable

    /** Call every few seconds. */
    fun tick() {
        val ui = settings()
        if (!ui.biometricLock || !platform.biometricAvailable) {
            awaySince = null
            return
        }
        val now = clock()
        val since = awaySince
        val limit = ui.lockAfterMinutes * MILLIS_PER_MINUTE
        if (since != null && now - since >= limit) mutableLocked.value = true
        awaySince = if (platform.isInForeground) null else since ?: now
    }

    suspend fun unlock(): Boolean {
        if (!platform.authenticate("Sblocca El Termino")) return false
        awaySince = null
        mutableLocked.value = false
        return true
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
