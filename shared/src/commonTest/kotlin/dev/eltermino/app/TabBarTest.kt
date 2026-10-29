package dev.eltermino.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TabBarTest {
    private var next = 0
    private fun id() = "f${++next}"

    /** Tabs a, b, c, d, one session each, frame ids fa..fd. */
    private fun bar(vararg sessions: String): TabBar = sessions.fold(TabBar()) { bar, s -> bar.open(s, "f$s") }

    private fun TabBar.shape(): List<String> = frames.map { frame ->
        val group = frame.groupId?.let { "${group(it)?.name}:" } ?: ""
        group + frame.panes.layout.name + frame.panes.tabIds.joinToString(",", "[", "]") { it ?: "-" }
    }

    @Test
    fun newSessionsGetTheirOwnTabUnlessAPaneIsFree() {
        val tabs = bar("a", "b")
        assertEquals(listOf("Single[a]", "Single[b]"), tabs.shape())
        assertEquals("fb", tabs.selected)
        val split = tabs.setLayout("fb", SplitLayout.Columns, ::id).open("c", "fc")
        assertEquals(listOf("Single[a]", "Columns[b,c]"), split.shape(), "c fills the empty pane")
    }

    @Test
    fun droppingATabOnAnotherSplitsIt() {
        val merged = bar("a", "b").merge(source = "fb", target = "fa", side = Side.Left)!!
        assertEquals(listOf("Columns[b,a]"), merged.shape())
        assertEquals(0, merged.selectedFrame!!.panes.focused, "the dropped session has the keyboard")
        val stacked = bar("a", "b").merge("fb", "fa", Side.Bottom)!!
        assertEquals(listOf("Rows[a,b]"), stacked.shape())
        val grid = bar("a", "b", "c").merge("fb", "fa", Side.Right)!!.merge("fc", "fa", Side.Right)!!
        assertEquals(listOf("Grid[a,b,c,-]"), grid.shape())
        assertNull(grid.open("d", "fd").open("e", "fe").merge("fe", "fa", Side.Right), "five panes don't fit")
    }

    @Test
    fun separatingAndDetaching() {
        val grid = bar("a", "b", "c").merge("fb", "fa", Side.Right)!!.merge("fc", "fa", Side.Right)!!
        assertEquals(listOf("Single[a]", "Single[b]", "Single[c]"), grid.separate("fa", ::id).shape())
        val detached = grid.detach("b", "new")
        assertEquals(listOf("Columns[a,c]", "Single[b]"), detached.shape(), "two left in a grid sit side by side")
        assertEquals("new", detached.selected)
    }

    @Test
    fun closingSessionsShrinksTheSplit() {
        val grid = bar("a", "b", "c").merge("fb", "fa", Side.Right)!!.merge("fc", "fa", Side.Right)!!
        assertEquals(listOf("Columns[a,c]"), grid.remove("b").shape())
        assertEquals(listOf("Single[c]"), grid.remove("b").remove("a").shape())
        assertEquals(emptyList(), grid.remove("a").remove("b").remove("c").frames)
    }

    @Test
    fun shrinkingALayoutMovesTheRestToNewTabs() {
        val grid = bar("a", "b", "c").merge("fb", "fa", Side.Right)!!.merge("fc", "fa", Side.Right)!!.focusSession("c")
        assertEquals(listOf("Single[c]", "Single[a]", "Single[b]"), grid.setLayout("fa", SplitLayout.Single, ::id).shape())
    }

    @Test
    fun showInSwapsWithWhatWasThere() {
        val tabs = bar("a", "b", "c").select("fa").setLayout("fa", SplitLayout.Columns, ::id).showIn(1, "c")
        assertEquals(listOf("Columns[a,c]", "Single[b]"), tabs.shape())
        val swapped = tabs.showIn(1, "b")
        assertEquals(listOf("Columns[a,b]", "Single[c]"), swapped.shape())
    }

    @Test
    fun groupsKeepTheirTabsTogether() {
        val tabs = bar("a", "b", "c", "d").createGroup(listOf("fa", "fc"), "g", "Produzione")
        assertEquals(listOf("Produzione:Single[a]", "Produzione:Single[c]", "Single[b]", "Single[d]"), tabs.shape())
        val added = tabs.addToGroup("fd", "g")
        assertEquals(listOf("Produzione:Single[a]", "Produzione:Single[c]", "Produzione:Single[d]", "Single[b]"), added.shape())
        assertEquals(listOf("Produzione:Single[a]", "Produzione:Single[d]", "Single[c]", "Single[b]"), added.removeFromGroup("fc").shape())
        assertEquals(listOf("Single[a]", "Single[c]", "Single[d]", "Single[b]"), added.ungroup("g").shape())
        // Dragged past the group, a grouped tab stays in it, as its last tab.
        assertEquals(listOf("Produzione:Single[c]", "Produzione:Single[d]", "Produzione:Single[a]", "Single[b]"), added.move("fa", 4).shape())
    }

    @Test
    fun aGroupDisappearsWithItsLastTab() {
        val tabs = bar("a", "b").createGroup(listOf("fa"), "g", "Solo")
        assertEquals(emptyList(), tabs.remove("a").groups)
    }

    @Test
    fun collapsingMovesTheSelectionOut() {
        val tabs = bar("a", "b", "c").createGroup(listOf("fa", "fb"), "g", "Web").select("fb")
        val collapsed = tabs.toggleCollapsed("g")
        assertEquals(true, collapsed.group("g")!!.collapsed)
        assertEquals("fc", collapsed.selected)
        assertEquals(false, collapsed.select("fa").group("g")!!.collapsed, "selecting a tab inside opens the group")
    }

    @Test
    fun groupByHostAndOpenInGrid() {
        val hosts = mapOf("a" to "web", "b" to "db", "c" to "web", "d" to "web")
        val grouped = bar("a", "b", "c", "d").groupByHost(hosts::get, { it.uppercase() }, ::id)
        assertEquals(listOf("WEB:Single[a]", "WEB:Single[c]", "WEB:Single[d]", "Single[b]"), grouped.shape())
        val group = grouped.groups.single().id
        assertEquals(listOf("WEB:Grid[a,c,d,-]", "Single[b]"), grouped.gridFromGroup(group).shape())
    }
}
