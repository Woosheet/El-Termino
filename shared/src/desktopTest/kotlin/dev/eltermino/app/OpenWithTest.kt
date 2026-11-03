package dev.eltermino.app

import dev.eltermino.core.data.ExternalApp
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Apri con": the app chosen reaches the system, which opens the file with it. */
class OpenWithTest {
    @Test
    fun theChosenAppIsTheOneThatOpens() {
        val opened = java.util.Collections.synchronizedList(mutableListOf<Pair<String, ExternalApp?>>())
        val platform = object : TestPlatform() {
            override suspend fun openExternally(name: String, bytes: ByteArray, app: ExternalApp?): ExternalFile? {
                opened += name to app
                return null
            }
        }
        val state = AppState(testServices(platform = platform))
        try {
            val home = File(runBlocking { state.services.localFiles!!.home() })
            val file = File(home, "app.yaml").apply { writeText("porta: 8080\n") }
            val zed = ExternalApp("Zed", "/Applications/Zed.app")
            state.sessions.openExternalEditor(Endpoint.Local, file.path, zed)
            state.sessions.openExternalEditor(Endpoint.Local, file.path)
            eventually { opened.size == 2 }
            assertEquals(setOf("app.yaml" to zed, "app.yaml" to null), opened.toSet())
        } finally {
            state.sessions.closeAll()
            state.scope.cancel()
        }
    }
}
