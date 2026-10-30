package dev.eltermino.app

import dev.eltermino.core.data.UiSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {
    private val minute = 60_000L

    private class Device(override val biometricAvailable: Boolean = true) : Platform {
        var foreground = true
        var answer = true
        var asked = 0
        override val isDesktop = true
        override val isInForeground get() = foreground
        override suspend fun authenticate(reason: String): Boolean { asked++; return answer }
        override suspend fun pickFile(title: String): PickedFile? = null
        override fun readSshConfig(): String? = null
        override fun resolveSshInclude(pattern: String): List<Pair<String, String>> = emptyList()
        override fun readLocalFile(path: String): ByteArray? = null
    }

    private var now = 0L
    private val on = UiSettings(biometricLock = true)

    private fun lock(device: Device = Device(), settings: UiSettings = on) = AppLock(device, { settings }, { now })

    @Test
    fun offByDefault() {
        assertFalse(UiSettings().biometricLock)
        assertEquals(5, UiSettings().lockAfterMinutes)
        assertFalse(lock(settings = UiSettings()).locked.value)
    }

    @Test
    fun lockedAtStartWhenEnabled() {
        assertTrue(lock().locked.value)
    }

    @Test
    fun missingBiometricsNeverLocksTheUserOut() {
        val lock = lock(Device(biometricAvailable = false))
        assertFalse(lock.locked.value)
        assertTrue(lock.biometricsMissing)
    }

    @Test
    fun unlockNeedsTheSystemsAnswer() = runTest {
        val device = Device().apply { answer = false }
        val lock = lock(device)
        assertFalse(lock.unlock())
        assertTrue(lock.locked.value)
        device.answer = true
        assertTrue(lock.unlock())
        assertFalse(lock.locked.value)
    }

    private suspend fun unlocked(device: Device) = lock(device).also { it.unlock() }

    @Test
    fun locksAfterTimeoutInBackground() = runTest {
        val device = Device()
        val lock = unlocked(device)
        device.foreground = false
        lock.tick()
        now += 5 * minute
        lock.tick()
        assertTrue(lock.locked.value)
    }

    @Test
    fun doesNotLockBeforeTimeout() = runTest {
        val device = Device()
        val lock = unlocked(device)
        device.foreground = false
        lock.tick()
        now += 4 * minute
        lock.tick()
        assertFalse(lock.locked.value)
    }

    @Test
    fun shortFocusLossDoesNotAddUp() = runTest {
        val device = Device()
        val lock = unlocked(device)
        repeat(3) {
            device.foreground = false
            lock.tick()
            now += 3 * minute
            device.foreground = true
            lock.tick()
            now += minute
        }
        assertFalse(lock.locked.value)
    }

    @Test
    fun sleepCountsAsElapsedTime() = runTest {
        val device = Device()
        val lock = unlocked(device)
        device.foreground = false
        lock.tick()
        now += 60 * minute // No tick ran while the computer slept.
        device.foreground = true
        lock.tick()
        assertTrue(lock.locked.value)
    }

    @Test
    fun theSystemDialogDoesNotCountAgainstTheNewSession() = runTest {
        val device = Device()
        val lock = lock(device)
        device.foreground = false // The system's own dialog has the focus.
        lock.tick()
        now += 10 * minute
        lock.unlock()
        device.foreground = true
        lock.tick()
        assertFalse(lock.locked.value)
    }

    @Test
    fun chosenDelayIsUsed() = runTest {
        val device = Device()
        val lock = AppLock(device, { on.copy(lockAfterMinutes = 1) }, { now }).also { it.unlock() }
        device.foreground = false
        lock.tick()
        now += minute
        lock.tick()
        assertTrue(lock.locked.value)
    }

    @Test
    fun turningItOnDoesNotLockOnTheSpot() = runTest {
        var settings = UiSettings()
        val lock = AppLock(Device(), { settings }, { now })
        settings = on
        lock.tick()
        assertFalse(lock.locked.value)
    }

    @Test
    fun sleepingWithTheWindowInFrontCountsAsAbsence() {
        val lock = lock().also { runTest { it.unlock() } }
        lock.tick()
        now += 6 * minute
        lock.tick()
        assertTrue(lock.locked.value)
    }

    @Test
    fun theFingerprintWaitsForTheUserToComeBack() = runTest {
        val device = Device().apply { foreground = false }
        val lock = lock(device)
        val prompt = launch { lock.unlockWhenBack() }
        advanceTimeBy(5_000)
        assertEquals(0, device.asked)
        device.foreground = true
        advanceTimeBy(1_000)
        prompt.join()
        assertEquals(1, device.asked)
    }
}
