package dev.eltermino.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.eltermino.app.AppState
import dev.eltermino.app.PendingPrompt
import dev.eltermino.app.SecretAnswer
import dev.eltermino.app.TabStatus
import dev.eltermino.app.createAppServices
import dev.eltermino.core.data.Host
import dev.eltermino.core.data.newId
import dev.eltermino.core.ssh.ConnectionState
import dev.eltermino.core.vault.AndroidKeystoreMasterKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The whole connection flow on ART: Keystore-backed vault, MINA, terminal session.
 * Needs the testenv servers on the host, reachable from the emulator as 10.0.2.2.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowOnAndroidTest {
    @Test
    fun passwordIsSavedInTheKeystoreVaultAndReused() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ElTerminoApp
        val dir = File(context.cacheDir, "flow-${System.nanoTime()}")
        val alias = "eltermino-test-${System.nanoTime()}"
        val state = AppState(createAppServices(dir, AndroidKeystoreMasterKeyProvider(File(dir, "vault.key"), alias), app.platform))
        val asked = mutableListOf<String>()
        val answering = CoroutineScope(Dispatchers.Default)
        answering.launch {
            state.sessions.prompter.prompt.filterNotNull().collect { prompt ->
                asked += prompt::class.simpleName.orEmpty()
                when (prompt) {
                    is PendingPrompt.HostKey -> prompt.answer(true)
                    is PendingPrompt.Secret -> prompt.answer(SecretAnswer("devpass".encodeToByteArray(), save = true))
                    else -> prompt.cancel()
                }
            }
        }
        try {
            val host = Host(newId(), "basic", "10.0.2.2", 2201, username = "dev")
            state.actions.saveHost(host)

            suspend fun connect(): TabStatus.Open {
                val tab = state.sessions.connect(state.services.workspace.workspace.value.host(host.id)!!)
                val status = withTimeout(30_000) { tab.status.first { it !is TabStatus.Connecting } }
                assertTrue((status as? TabStatus.Failed)?.message ?: "", status is TabStatus.Open)
                return status as TabStatus.Open
            }

            val first = connect()
            assertEquals("ok", first.connection.exec("echo ok").stdout.decodeToString().trim())
            assertEquals(listOf("HostKey", "Secret"), asked)

            // Tabs on one host share a connection: close it so the second login is a real one.
            state.sessions.closeAll()
            withTimeout(10_000) { first.connection.state.first { it is ConnectionState.Closed } }
            asked.clear()
            val second = connect()
            assertEquals("ok", second.connection.exec("echo ok").stdout.decodeToString().trim())
            assertEquals("the password comes back from the Keystore vault", emptyList<String>(), asked)

            state.sessions.tabs.value.forEach { state.sessions.close(it) }
        } finally {
            answering.cancel()
            state.scope.cancel()
            dir.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
