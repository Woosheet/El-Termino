package dev.eltermino.app

import dev.eltermino.core.data.UiSettings
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class LockedCommandsTest {
    @Test
    fun commandsDoNothingBehindTheLock() = runBlocking {
        val services = testServices(platform = object : TestPlatform() {
            override val biometricAvailable = true
        })
        services.workspace.update { it.copy(ui = UiSettings(biometricLock = true)) }
        val state = AppState(services)
        try {
            val command = async { withTimeoutOrNull(500.milliseconds) { state.commands.first() } }
            kotlinx.coroutines.delay(100)
            state.run(AppCommand.NewTab)
            assertEquals(null, command.await())
        } finally {
            state.scope.cancel()
        }
    }
}
