package dev.eltermino.app

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the tab shortcuts do to the sessions, on local file tabs that need no connection. */
class TabShortcutsTest {
    private val state = AppState(testServices())
    private val sessions = state.sessions

    @AfterTest
    fun stop() {
        sessions.closeAll()
        state.scope.cancel()
    }

    private fun selectedFrame() = sessions.bar.value.selected
    private fun frames() = sessions.bar.value.frames.map { it.id }

    @Test
    fun nextAndPreviousGoRoundAndNumbersPickATab() {
        repeat(3) { sessions.openFiles(null) }
        val (a, b, c) = frames()
        sessions.selectFrame(a)
        sessions.selectNeighbour(1)
        assertEquals(b, selectedFrame())
        sessions.selectNeighbour(1)
        sessions.selectNeighbour(1)
        assertEquals(a, selectedFrame(), "after the last comes the first")
        sessions.selectNeighbour(-1)
        assertEquals(c, selectedFrame(), "before the first comes the last")
        sessions.selectFrameAt(1)
        assertEquals(b, selectedFrame())
        sessions.selectFrameAt(-1)
        assertEquals(c, selectedFrame(), "⌘9 is the last tab")
        sessions.selectFrameAt(7)
        assertEquals(c, selectedFrame(), "a tab that isn't there changes nothing")
    }

    @Test
    fun aCollapsedGroupIsSkipped() {
        repeat(3) { sessions.openFiles(null) }
        val (a, b, c) = frames()
        sessions.createGroup(listOf(b), "Nascosto")
        sessions.toggleGroup(sessions.bar.value.groups.single().id)
        sessions.selectFrame(a)
        sessions.selectNeighbour(1)
        assertEquals(c, selectedFrame())
    }

    @Test
    fun theFocusedTabIsTheOneCloseActsOn() {
        val first = sessions.openFiles(null)
        val second = sessions.openFiles(null)
        assertEquals(second.id, sessions.focusedTab()?.id)
        sessions.select(first.id)
        assertEquals(first.id, sessions.focusedTab()?.id)
    }

    @Test
    fun oneKeyPressThatArrivesTwiceRunsOnce() = runBlocking {
        val seen = mutableListOf<AppCommand>()
        // Undispatched, so it's listening before the first command goes out.
        val collecting = state.scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { state.commands.collect { seen += it } }
        state.run(AppCommand.Close)
        state.run(AppCommand.Close)
        state.run(AppCommand.NextTab)
        Thread.sleep(60)
        state.run(AppCommand.Close)
        withTimeout(5_000) { while (seen.size < 3) kotlinx.coroutines.delay(10) }
        assertEquals(listOf(AppCommand.Close, AppCommand.NextTab, AppCommand.Close), seen)
        collecting.cancel()
    }
}
