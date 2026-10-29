package dev.eltermino.app

enum class GroupColor { Blu, Verde, Viola, Corallo, Ambra, Rosa, Grigio }

data class TabGroup(val id: String, val name: String, val color: GroupColor, val collapsed: Boolean = false)

/** A tab in the bar: its own split layout, each pane showing one session. */
data class Frame(val id: String, val panes: Panes, val groupId: String? = null) {
    val sessionIds: List<String> get() = panes.tabIds.filterNotNull()
}

/** Where a tab dropped on another one goes. */
enum class Side { Left, Right, Top, Bottom }

/**
 * The tab bar as plain data: tabs (frames) in order, their groups and the selected one.
 * Every change returns a new bar; a group's tabs always sit next to each other.
 */
data class TabBar(
    val frames: List<Frame> = emptyList(),
    val groups: List<TabGroup> = emptyList(),
    val selected: String? = null,
) {
    val selectedFrame: Frame? get() = frames.firstOrNull { it.id == selected }

    fun frame(id: String?): Frame? = frames.firstOrNull { it.id == id }
    fun frameOf(sessionId: String): Frame? = frames.firstOrNull { sessionId in it.panes.tabIds }
    fun group(id: String?): TabGroup? = groups.firstOrNull { it.id == id }
    fun framesIn(groupId: String): List<Frame> = frames.filter { it.groupId == groupId }

    /** A new session goes into an empty pane of the selected tab, or into a tab of its own after it. */
    fun open(sessionId: String, newFrameId: String): TabBar {
        val current = selectedFrame
        if (current != null && null in current.panes.tabIds) {
            return replace(current.copy(panes = current.panes.place(sessionId))).copy(selected = current.id)
        }
        val frame = Frame(newFrameId, Panes(SplitLayout.Single, listOf(sessionId)))
        // Not into the middle of a group it doesn't belong to.
        val after = current?.groupId?.let { group -> framesIn(group).last().id } ?: current?.id
        return insertAfter(after, listOf(frame)).copy(selected = frame.id).normalized()
    }

    fun select(frameId: String): TabBar = if (frame(frameId) == null) this else copy(selected = frameId).normalized()

    /** Selects the tab showing [sessionId] and gives its pane the keyboard. */
    fun focusSession(sessionId: String): TabBar {
        val frame = frameOf(sessionId) ?: return this
        return replace(frame.copy(panes = frame.panes.show(sessionId))).copy(selected = frame.id).normalized()
    }

    fun focusPane(index: Int): TabBar = selectedFrame?.let { replace(it.copy(panes = it.panes.focus(index))) } ?: this

    /** After [sessionId] closed: its pane goes away and a split with one session left becomes a plain tab. */
    fun remove(sessionId: String): TabBar {
        val frame = frameOf(sessionId) ?: return this
        val index = frames.indexOf(frame)
        val panes = frame.panes.cleared(sessionId).compacted()
        if (panes.tabIds.filterNotNull().isNotEmpty()) return replace(frame.copy(panes = panes)).normalized()
        val rest = frames - frame
        val next = if (selected == frame.id) (rest.getOrNull(index) ?: rest.getOrNull(index - 1))?.id else selected
        return copy(frames = rest, selected = next).normalized()
    }

    /** Changes a tab's layout. Sessions that no longer fit each move to a tab of their own, right after. */
    fun setLayout(frameId: String, layout: SplitLayout, newFrameId: () -> String): TabBar {
        val frame = frame(frameId) ?: return this
        val ids = frame.sessionIds
        val focusedId = frame.panes.focusedTabId
        var keep = ids.take(layout.panes)
        if (focusedId != null && focusedId !in keep) keep = keep.dropLast(1) + focusedId
        val displaced = ids - keep.toSet()
        val panes = Panes(layout, keep + List(layout.panes - keep.size) { null }, keep.indexOf(focusedId).coerceAtLeast(0))
        val extra = displaced.map { Frame(newFrameId(), Panes(SplitLayout.Single, listOf(it)), frame.groupId) }
        return replace(frame.copy(panes = panes)).insertAfter(frame.id, extra).normalized()
    }

    /**
     * Puts [source]'s sessions next to [target]'s, on [side], in one tab. Null when the
     * result would need more than four panes.
     */
    fun merge(source: String, target: String, side: Side): TabBar? {
        val from = frame(source) ?: return null
        val into = frame(target) ?: return null
        if (from.id == into.id) return null
        val moving = from.sessionIds
        val staying = into.sessionIds
        if (moving.size + staying.size > 4) return null
        val ordered = if (side == Side.Left || side == Side.Top) moving + staying else staying + moving
        val layout = when (ordered.size) {
            1 -> SplitLayout.Single
            2 -> if (side == Side.Left || side == Side.Right) SplitLayout.Columns else SplitLayout.Rows
            else -> SplitLayout.Grid
        }
        val ids = ordered + List(layout.panes - ordered.size) { null }
        val merged = into.copy(panes = Panes(layout, ids, ids.indexOf(moving.first())))
        return copy(frames = frames.filterNot { it.id == from.id }).replace(merged).copy(selected = merged.id).normalized()
    }

    /** Every session of a split tab in a tab of its own, in the same place and group. */
    fun separate(frameId: String, newFrameId: () -> String): TabBar {
        val frame = frame(frameId) ?: return this
        val ids = frame.sessionIds
        if (ids.size < 2) return this
        val first = frame.copy(panes = Panes(SplitLayout.Single, listOf(ids.first())))
        val others = ids.drop(1).map { Frame(newFrameId(), Panes(SplitLayout.Single, listOf(it)), frame.groupId) }
        return replace(first).insertAfter(first.id, others).normalized()
    }

    /** Takes one session out of a split, into a new tab right after. */
    fun detach(sessionId: String, newFrameId: String): TabBar {
        val frame = frameOf(sessionId) ?: return this
        if (frame.sessionIds.size < 2) return this
        val alone = Frame(newFrameId, Panes(SplitLayout.Single, listOf(sessionId)), frame.groupId)
        return replace(frame.copy(panes = frame.panes.cleared(sessionId).compacted()))
            .insertAfter(frame.id, listOf(alone))
            .copy(selected = alone.id)
            .normalized()
    }

    /** Shows [sessionId] in pane [index] of the selected tab; what was there swaps places with it. */
    fun showIn(index: Int, sessionId: String): TabBar {
        val into = selectedFrame ?: return this
        if (index !in into.panes.tabIds.indices) return this
        val from = frameOf(sessionId) ?: return this
        val occupant = into.panes.tabIds[index]
        if (from.id == into.id) {
            val ids = into.panes.tabIds.toMutableList()
            ids[ids.indexOf(sessionId)] = occupant
            ids[index] = sessionId
            return replace(into.copy(panes = into.panes.copy(tabIds = ids, focused = index)))
        }
        val sourceIds = from.panes.tabIds.toMutableList().also { it[it.indexOf(sessionId)] = occupant }
        val targetIds = into.panes.tabIds.toMutableList().also { it[index] = sessionId }
        return replace(from.copy(panes = from.panes.copy(tabIds = sourceIds).compacted()))
            .replace(into.copy(panes = into.panes.copy(tabIds = targetIds, focused = index)))
            .normalized()
    }

    /** Moves a tab to [index] in the bar; it stays in its group, which keeps its tabs together. */
    fun move(frameId: String, index: Int): TabBar {
        val frame = frame(frameId) ?: return this
        val rest = frames - frame
        val at = index.coerceIn(0, rest.size)
        return copy(frames = rest.take(at) + frame + rest.drop(at)).normalized()
    }

    fun createGroup(frameIds: List<String>, groupId: String, name: String, color: GroupColor = nextColor()): TabBar {
        if (frameIds.none { frame(it) != null }) return this
        return copy(
            frames = frames.map { if (it.id in frameIds) it.copy(groupId = groupId) else it },
            groups = groups + TabGroup(groupId, name, color),
        ).normalized()
    }

    fun addToGroup(frameId: String, groupId: String): TabBar {
        if (group(groupId) == null) return this
        val frame = frame(frameId) ?: return this
        val last = framesIn(groupId).lastOrNull()
        val moved = frame.copy(groupId = groupId)
        val rest = frames - frame
        val at = last?.let { rest.indexOf(it) + 1 } ?: rest.size
        return copy(frames = rest.take(at) + moved + rest.drop(at)).normalized()
    }

    /** The tab leaves its group and lands right after it. */
    fun removeFromGroup(frameId: String): TabBar {
        val frame = frame(frameId) ?: return this
        val groupId = frame.groupId ?: return this
        val rest = frames - frame
        val last = rest.lastOrNull { it.groupId == groupId }
        val at = if (last != null) rest.indexOf(last) + 1 else frames.indexOf(frame)
        return copy(frames = rest.take(at) + frame.copy(groupId = null) + rest.drop(at)).normalized()
    }

    fun renameGroup(groupId: String, name: String) = copy(groups = groups.map { if (it.id == groupId) it.copy(name = name) else it })

    fun recolorGroup(groupId: String, color: GroupColor) = copy(groups = groups.map { if (it.id == groupId) it.copy(color = color) else it })

    /** Collapsing the group of the selected tab selects the next tab outside it, if there is one. */
    fun toggleCollapsed(groupId: String): TabBar {
        val group = group(groupId) ?: return this
        val collapsing = !group.collapsed
        val updated = copy(groups = groups.map { if (it.id == groupId) it.copy(collapsed = collapsing) else it })
        if (!collapsing || selectedFrame?.groupId != groupId) return updated
        val outside = frames.filter { it.groupId != groupId }
        if (outside.isEmpty()) return this
        val after = frames.indexOfLast { it.groupId == groupId }
        val next = frames.drop(after + 1).firstOrNull { it.groupId != groupId } ?: outside.last()
        return updated.copy(selected = next.id)
    }

    /** The tabs stay where they are, without the group. */
    fun ungroup(groupId: String) = copy(
        frames = frames.map { if (it.groupId == groupId) it.copy(groupId = null) else it },
        groups = groups.filterNot { it.id == groupId },
    )

    /** One group per host that has two or more ungrouped tabs, named after it. */
    fun groupByHost(hostOf: (String) -> String?, labelOf: (String) -> String, newGroupId: () -> String): TabBar {
        var bar = this
        frames.filter { it.groupId == null }
            .groupBy { frame -> frame.sessionIds.map(hostOf).distinct().singleOrNull() }
            .forEach { (host, members) ->
                if (host != null && members.size >= 2) bar = bar.createGroup(members.map { it.id }, newGroupId(), labelOf(host))
            }
        return bar
    }

    /** Merges the group's first tabs into one, up to four sessions, in a grid or side by side. */
    fun gridFromGroup(groupId: String): TabBar {
        val members = framesIn(groupId)
        if (members.size < 2) return this
        val taken = mutableListOf<Frame>()
        var count = 0
        for (frame in members) {
            if (count + frame.sessionIds.size > 4) break
            taken += frame
            count += frame.sessionIds.size
        }
        if (taken.size < 2) return this
        val ids = taken.flatMap { it.sessionIds }
        val layout = if (ids.size == 2) SplitLayout.Columns else SplitLayout.Grid
        val merged = taken.first().copy(panes = Panes(layout, ids + List(layout.panes - ids.size) { null }, 0))
        val gone = taken.drop(1).map { it.id }.toSet()
        return copy(frames = frames.filterNot { it.id in gone }).replace(merged).copy(selected = merged.id).normalized()
    }

    fun nextColor(): GroupColor = GroupColor.entries.firstOrNull { color -> groups.none { it.color == color } }
        ?: GroupColor.entries[groups.size % GroupColor.entries.size]

    private fun replace(frame: Frame) = copy(frames = frames.map { if (it.id == frame.id) frame else it })

    private fun insertAfter(frameId: String?, added: List<Frame>): TabBar {
        if (added.isEmpty()) return this
        val at = frames.indexOfFirst { it.id == frameId }.let { if (it < 0) frames.size else it + 1 }
        return copy(frames = frames.take(at) + added + frames.drop(at))
    }

    /** Drops empty tabs and groups, keeps each group's tabs together, and keeps a valid selection. */
    internal fun normalized(): TabBar {
        val alive = frames.filter { it.sessionIds.isNotEmpty() }
        val ordered = mutableListOf<Frame>()
        for (frame in alive) {
            if (frame in ordered) continue
            if (frame.groupId == null || group(frame.groupId) == null) ordered += frame.copy(groupId = frame.groupId?.takeIf { group(it) != null })
            else ordered += alive.filter { it.groupId == frame.groupId }
        }
        val used = ordered.mapNotNull { it.groupId }.toSet()
        val selection = selected?.takeIf { id -> ordered.any { it.id == id } } ?: ordered.firstOrNull()?.id
        val selectedGroup = ordered.firstOrNull { it.id == selection }?.groupId
        return TabBar(
            frames = ordered,
            groups = groups.filter { it.id in used }.map { if (it.id == selectedGroup && it.collapsed) it.copy(collapsed = false) else it },
            selected = selection,
        )
    }
}

/** [tabId]'s pane becomes empty. */
fun Panes.cleared(tabId: String): Panes = copy(tabIds = tabIds.map { if (it == tabId) null else it })

/** A split that lost sessions shrinks: one left is a plain tab, two in a grid sit side by side. */
fun Panes.compacted(): Panes {
    val ids = tabIds.filterNotNull()
    val focusedId = focusedTabId
    fun focusOf(list: List<String?>) = list.indexOf(focusedId).takeIf { it >= 0 } ?: 0
    return when {
        ids.isEmpty() -> Panes(SplitLayout.Single, listOf(null))
        layout == SplitLayout.Single -> this
        ids.size == 1 -> Panes(SplitLayout.Single, ids)
        layout == SplitLayout.Grid && ids.size == 2 -> Panes(SplitLayout.Columns, ids, focusOf(ids))
        else -> copy(focused = focused.takeIf { tabIds.getOrNull(it) != null } ?: tabIds.indexOfFirst { it != null })
    }
}
