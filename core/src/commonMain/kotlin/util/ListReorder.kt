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
}
