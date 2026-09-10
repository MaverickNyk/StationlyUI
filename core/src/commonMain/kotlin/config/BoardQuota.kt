package com.stationly.core.config

/**
 * The two quota questions, as pure functions over a [BoardPolicy].
 *
 * ## Why these are not just inline `>=` checks in the ViewModels
 * They are asked from four places — the home screen's "+", the station step of
 * the selection flow, its save, and the line step — and all but the first live
 * in a ViewModel that cannot be constructed in a test without a `Platform`
 * behind it. Answered here, the rules are testable on their own and the two
 * screens cannot drift into disagreeing about what "full" means.
 *
 * Every function takes the policy explicitly rather than reading
 * [BoardPolicyStore] itself, so a test can state the limits it is testing
 * instead of mutating global state to get at them.
 */
object BoardQuota {

    /**
     * How many station cards a set of saved rows amounts to.
     *
     * Counted by grouping id, NOT by row: the home screen draws ONE card per
     * station hub however many lines the user picked there, so a user with four
     * rows across two hubs owns two boards and has two slots left. Counting rows
     * would tell someone with both directions of two lines at King's Cross that
     * they were full while their home screen showed a single card.
     *
     * Blank ids are dropped rather than folded into one bucket — a row with no
     * grouping id is not a station, and letting it count would inflate the total
     * against the user.
     */
    fun stationCount(groupingIds: List<String>): Int =
        groupingIds.filter { it.isNotBlank() }.distinct().size

    /**
     * Whether a station can be added to [groupingIds].
     *
     * [candidate] is the hub being picked, where one is known. A hub the user
     * ALREADY holds is always allowed through: editing an existing board, or
     * adding a second line to one, is not spending a slot, and blocking it
     * would strand a user at the cap with no way to change what they have.
     * Pass null to ask the general question — "is there a free slot at all" —
     * which is what the home screen's "+" needs, having no candidate yet.
     */
    fun canAddStation(
        groupingIds: List<String>,
        candidate: String? = null,
        policy: BoardPolicy = BoardPolicyStore.current,
    ): Boolean =
        (candidate != null && candidate in groupingIds) ||
            stationCount(groupingIds) < policy.maxBoards

    /**
     * Whether a line can be ticked on a station already holding [pickedLines].
     *
     * Unticking is never gated, so the caller only asks this when adding.
     *
     * Directions are deliberately NOT quota'd. A line runs inbound and outbound
     * and nothing else, so this limit already bounds a station at twice itself,
     * and a second gate counted in rows could only refuse a board this one calls
     * legal — three lines with both ways ticked.
     */
    fun canAddLine(
        pickedLines: Int,
        policy: BoardPolicy = BoardPolicyStore.current,
    ): Boolean = pickedLines < policy.maxLinesPerStation
}
