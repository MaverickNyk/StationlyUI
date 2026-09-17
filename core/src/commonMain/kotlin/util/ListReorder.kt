package com.stationly.core.util

/**
 * Moving one item within a list the user has arranged by hand.
 *
 * Lives in core, like every other rule with real branching in it
 * ([MultiLineBoardProcessor], [LineStatusRanker], [BoardLabels]), because getting
 * it wrong does not crash — it silently puts a row in the wrong place, which is
 * exactly the class of bug that wants a test rather than a careful read.
 */
object ListReorder {

    /**
     * The item at [from] moved to [to], with everything it passes shuffling one
     * place to fill the gap.
     *
     * Out-of-range indices return the list untouched rather than throwing. The
     * caller's indices come from a UI whose list can change underneath it — a
     * station deleted on another screen, a reload landing mid-tap — and a stale
     * index must not take the app down.
     */
    fun <T> List<T>.moved(from: Int, to: Int): List<T> =
        if (from == to || from !in indices || to !in indices) this
        else toMutableList().also { it.add(to, it.removeAt(from)) }

    /**
     * Where the row at [index] should SIT while a drag is in flight, given the
     * row picked up at [from] is currently hovering over [to].
     *
     * The list itself is not reordered until the finger lifts — reordering it
     * mid-drag reorders the composables under the gesture. So the rows are moved
     * by position only, and this says where each one goes: the dragged row takes
     * the slot it is hovering over, everything between the two shuffles one place
     * towards the origin, and everything outside that span stays where it is.
     *
     * This must always agree with [moved]: on release the caller commits
     * `moved(from, to)`, and the drop is jump-free only if every row is already
     * standing at the index that list is about to give it. `slotOf` and `moved`
     * agreeing is what makes the commit invisible, so they are tested against
     * each other rather than separately.
     *
     * [from] < 0 means no drag, and every row sits at its own index.
     */
    fun slotOf(index: Int, from: Int, to: Int): Int = when {
        from < 0 -> index
        index == from -> to
        from < to && index in (from + 1)..to -> index - 1
        to < from && index in to..(from - 1) -> index + 1
        else -> index
    }
}
