package dev.eltermino.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PanesTest {
    private val tabs = listOf("a", "b", "c", "d", "e")

    @Test
    fun showFocusesThePaneThatAlreadyHasTheTab() {
        val panes = Panes(SplitLayout.Columns, listOf("a", "b"), focused = 0)
        assertEquals(Panes(SplitLayout.Columns, listOf("a", "b"), focused = 1), panes.show("b"))
        assertEquals(Panes(SplitLayout.Columns, listOf("c", "b"), focused = 0), panes.show("c"))
    }

    @Test
    fun growingFillsNewPanesWithHiddenTabs() {
        val panes = Panes(SplitLayout.Single, listOf("c"), focused = 0).withLayout(SplitLayout.Grid, tabs)
        assertEquals(listOf("c", "a", "b", "d"), panes.tabIds)
        assertEquals(0, panes.focused)
    }

    @Test
    fun growingWithFewTabsLeavesPanesEmpty() {
        val panes = Panes(SplitLayout.Single, listOf("a")).withLayout(SplitLayout.Grid, listOf("a", "b"))
        assertEquals(listOf("a", "b", null, null), panes.tabIds)
    }

    @Test
    fun shrinkingKeepsTheFocusedTabOnScreen() {
        val panes = Panes(SplitLayout.Grid, listOf("a", "b", "c", "d"), focused = 3).withLayout(SplitLayout.Columns, tabs)
        assertEquals(Panes(SplitLayout.Columns, listOf("a", "d"), focused = 1), panes)
        assertEquals(Panes(SplitLayout.Single, listOf("d"), focused = 0), panes.withLayout(SplitLayout.Single, tabs))
    }

    @Test
    fun closingATabShowsOneThatWasHidden() {
        val panes = Panes(SplitLayout.Columns, listOf("a", "b"), focused = 0)
        assertEquals(listOf("e", "b"), panes.without("a", remaining = listOf("b", "c", "e")).tabIds)
        assertEquals(listOf(null, "b"), panes.without("a", remaining = listOf("b")).tabIds)
    }

    @Test
    fun showInMovesATabBetweenPanes() {
        val panes = Panes(SplitLayout.Columns, listOf("a", "b"), focused = 0).showIn(1, "a")
        assertEquals(Panes(SplitLayout.Columns, listOf(null, "a"), focused = 1), panes)
    }
}
