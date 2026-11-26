package com.stationly.core.config

import com.stationly.core.model.UserSelection
import com.stationly.core.model.user.Board

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
     * Which of the account's [incoming] boards a phone can take on top of the
     * [local] rows it already holds without passing [maxBoards] stations.
     *
     * For the first launch after a v1 upgrade, the one sync that MERGES: the v1
     * board plus four from an iPhone made five on the Pixel on 2026-11-26, past a
     * limit the app only ever checked at "+". (AV2-11.49.)
     *
     * - Everything already on the phone stays.
     * - What the phone holds is counted the way the account will see it: a local
     *   row that belongs to an account board counts as that board, matched by hub
     *   or by stop. A v1 bus row has no hub, so without this one stop tracked both
     *   ways would count as two stations, and leave out a board that fits.
     * - The account's other boards are taken in its order until the limit, and
     *   the rest are returned as [Fit.left]. They are not set up, and the upgrade's
     *   save then drops them from the account: the save is a full replace.
     */
    fun fit(local: List<UserSelection>, incoming: List<Board>, maxBoards: Int): Fit {
        fun boardOf(row: UserSelection) =
            incoming.firstOrNull { it.id == row.groupingId || row.station in it.naptanIds }?.id
        val heldBoards = local.mapNotNull(::boardOf).toSet()
        var used = stationCount(local.map { boardOf(it) ?: it.groupingId })
        val (kept, left) = incoming.partition { board ->
            when {
                board.id in heldBoards -> true
                used < maxBoards -> { used++; true }
                else -> false
            }
        }
        return Fit(kept, left)
    }

    data class Fit(val kept: List<Board>, val left: List<Board>)

    /**
     * [fit] against a limit the server has JUST stated, or no cut at all.
     *
     * A board left out ends up deleted from the account, so this must never act
     * on a limit it assumes: not the compiled default, not a cached config from
     * before a change, not a served config that does not mention the limit. One
     * network fetch, which this path can afford (it runs once per upgraded phone);
     * failing that, everything is kept, which costs at most a phone over the limit.
     */
    suspend fun fitMerge(local: List<UserSelection>, incoming: List<Board>): Fit {
        val limit = runCatching { servedLimit() }.getOrNull() ?: return Fit(incoming, emptyList())
        return fit(local, incoming, limit)
    }

    /** Where [fitMerge] gets the limit. A seam for tests; the app never sets it. */
    internal var servedLimit: suspend () -> Int? = {
        if (SduiConfig.refreshFromNetwork()) BoardPolicyStore.servedMaxBoards else null
    }

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
