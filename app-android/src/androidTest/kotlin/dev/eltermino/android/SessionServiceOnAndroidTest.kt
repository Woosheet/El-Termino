package dev.eltermino.android

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The manifest entry, the permissions and startForeground() agree, or Android kills the app. */
@RunWith(AndroidJUnit4::class)
class SessionServiceOnAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION") // Still returns the caller's own services, which is all we need.
    private fun serviceInForeground(): Boolean? = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .firstOrNull { it.service.className == SessionService::class.java.name }
        ?.foreground

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(100)
        }
        return true
    }

    @Test
    fun startsInTheForegroundAndStops() {
        SessionService.start(context)
        assertTrue("running as a foreground service", waitFor { serviceInForeground() == true })
        SessionService.stop(context)
        assertTrue("stopped", waitFor { serviceInForeground() == null })
    }
}
