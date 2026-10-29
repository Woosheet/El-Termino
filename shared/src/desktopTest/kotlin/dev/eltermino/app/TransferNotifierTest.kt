package dev.eltermino.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Copies on this device only: no servers needed. */
class TransferNotifierTest {
    @Test
    fun transfersFinishingTogetherBecomeOneNotice(): Unit = runBlocking {
        val platform = TestPlatform().apply { foreground = false }
        val state = AppState(testServices(platform = platform))
        val local = state.services.localFiles!!
        val home = local.home()
        File(home, "a.txt").writeText("uno")
        File(home, "b.txt").writeText("due")
        File(home, "copie").mkdirs()
        val files = listOf(local.stat("$home/a.txt")!!, local.stat("$home/b.txt")!!)
        state.transfers.enqueue(Endpoint.Local, files, Location(Endpoint.Local, "$home/copie"), ConflictPolicy.Overwrite)

        val notice = withTimeout(10_000) { state.notifier.notice.filterNotNull().first() }
        assertEquals("2 trasferimenti completati", notice.title)
        assertEquals("due", File(home, "copie/b.txt").readText())
        assertEquals(listOf(notice.title to notice.message), platform.notifications.toList(), "away from the app, the system tells too")

        platform.foreground = true
        File(home, "c.txt").writeText("tre")
        state.transfers.enqueue(Endpoint.Local, listOf(local.stat("$home/c.txt")!!), Location(Endpoint.Local, "$home/copie"), ConflictPolicy.Overwrite)
        val second = withTimeout(10_000) { state.notifier.notice.first { it != null && it.id != notice.id } }!!
        assertEquals("Trasferimento completato", second.title)
        assertTrue("c.txt" in second.message, second.message)
        assertEquals(1, platform.notifications.size, "in front of the app, only the message in the app")
    }

    @Test
    fun shellFolderComesFromTheWindowTitle() {
        assertEquals("/var/www", dev.eltermino.ui.shellFolder("dev@web-01: /var/www"))
        assertEquals("~/app", dev.eltermino.ui.shellFolder("deploy@db: ~/app"))
        assertEquals(null, dev.eltermino.ui.shellFolder("vim app.yaml"))
        assertEquals(null, dev.eltermino.ui.shellFolder(null))
    }
}
