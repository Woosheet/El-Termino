package dev.eltermino.app

enum class SplitLayout(val panes: Int) {
    Single(1),
    /** Two panes side by side. */
    Columns(2),
    /** Two panes, one above the other. */
    Rows(2),
    Grid(4),
}

/** Which tab each pane of the split view shows, and which pane has the keyboard. */
data class Panes(
    val layout: SplitLayout = SplitLayout.Single,
    val tabIds: List<String?> = listOf(null),
    val focused: Int = 0,
) {
    val focusedTabId: String? get() = tabIds.getOrNull(focused)

    /** Focuses the pane already showing [tabId], or shows it in the focused pane. */
    fun show(tabId: String): Panes {
        val index = tabIds.indexOf(tabId)
        return if (index >= 0) copy(focused = index) else copy(tabIds = tabIds.toMutableList().also { it[focused] = tabId })
    }

    /** Where a new tab goes: the first empty pane, so a split fills up, else the focused one. */
    fun place(tabId: String): Panes {
        val empty = tabIds.indexOf(null)
        return if (empty >= 0) showIn(empty, tabId) else show(tabId)
    }

    /** Shows [tabId] in pane [index]; a pane that showed it already gives it up. */
    fun showIn(index: Int, tabId: String): Panes {
        if (index !in tabIds.indices) return this
        val ids = tabIds.map { if (it == tabId) null else it }.toMutableList()
        ids[index] = tabId
        return copy(tabIds = ids, focused = index)
    }

    fun focus(index: Int): Panes = if (index in tabIds.indices) copy(focused = index) else this

    /**
     * Switches to [layout]. New panes fill with tabs that aren't on screen yet; the
     * focused tab stays visible when panes go away.
     */
    fun withLayout(layout: SplitLayout, allTabIds: List<String>): Panes {
        val focusedTab = focusedTabId
        val ids = tabIds.take(layout.panes).toMutableList()
        while (ids.size < layout.panes) ids += allTabIds.firstOrNull { it !in ids }
        val focusedIndex = minOf(focused, layout.panes - 1)
        if (focusedTab != null && focusedTab !in ids) ids[focusedIndex] = focusedTab
        return Panes(layout, ids, focusedIndex)
    }

    /** After [tabId] closed: the panes that showed it take a tab not on screen yet, if any. */
    fun without(tabId: String, remaining: List<String>): Panes {
        val ids = tabIds.toMutableList()
        for (i in ids.indices) {
            if (ids[i] == tabId) ids[i] = remaining.lastOrNull { it !in ids }
        }
        return copy(tabIds = ids)
    }
}
