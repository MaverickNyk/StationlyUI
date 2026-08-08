package com.stationly.core.util

import kotlinx.serialization.Serializable

/**
 * How ONE station's departure board is arranged, on top of the platform grouping
 * every board always has.
 *
 * ## The grouping is not one of the options, and that is the whole design
 * A board groups by platform (rail) or by pole (bus) because that is what a
 * passenger experiences: everything under one header is one queue, in one place,
 * that you can walk to. Offering "sort by destination" as a flat re-sort of the
 * whole board would dissolve that — the rows would be in a true global order and
 * the user would have to read the platform off every line to know where to
 * stand.
 *
 * So every setting here operates at ONE of two levels, never across them:
 *  - [sort] decides the order of the BLOCKS, or of the rows INSIDE a block,
 *    depending on which level the key it names exists at (see [BoardSort]);
 *  - [pin] promotes a whole block. It never lifts rows out of one.
 *
 * ## Local, per station, and not on [com.stationly.core.model.UserSelection]
 * Lives on `StationPrefs` in the app layer, keyed by grouping id, for the reason
 * written up there: a selection is one (line, direction) board and a station has
 * several, so storing "three rows per platform" on it would be the same fact
 * written N times with no rule for which copy wins.
 *
 * The types are in `core` rather than beside `StationPrefs` because the
 * [MultiLineBoardProcessor] is what applies them, and it is core's — which is
 * also what makes every rule here testable without a device.
 */
@Serializable
data class BoardDisplayPrefs(
    val sort: BoardSort = BoardSort.TIME,
    /**
     * Ceiling on the departures shown under each platform header.
     *
     * A CEILING, not a promise: TfL sends what it sends, and a quiet platform
     * with two trains due shows two however high this is set. The board's
     * whole-board floor ([MultiLineBoardProcessor.MIN_BOARD_ROWS]) is a separate
     * limit and is deliberately not user-facing — it stops the panel collapsing
     * to a sliver late at night, which is not a preference.
     */
    val rowsPerPlatform: Int = DEFAULT_ROWS_PER_PLATFORM,
    val pin: BoardPin? = null,
) {
    /**
     * [rowsPerPlatform] as the board will actually apply it.
     *
     * Clamped on READ rather than on write. A stored value can arrive from a
     * build whose limits differed, and a board that renders twelve rows because
     * an old preference said so is a worse failure than one that quietly honours
     * today's range.
     */
    val rowCap: Int get() = rowsPerPlatform.coerceIn(MIN_ROWS_PER_PLATFORM, MAX_ROWS_PER_PLATFORM)

    companion object {
        /**
         * Two is the floor because one departure is not a board — it answers
         * "when is the next one" (which the hero already does) and says nothing
         * about the one after, which is what you need to decide whether to run.
         *
         * Five is the ceiling because the panel is height-capped: past five per
         * platform a two-platform station is scrolling before it has said
         * anything, and the rows the user came for are the ones off-screen.
         */
        const val MIN_ROWS_PER_PLATFORM = 2
        const val MAX_ROWS_PER_PLATFORM = 5
        const val DEFAULT_ROWS_PER_PLATFORM = 3
    }
}

/**
 * What the board is ordered BY, once it has been grouped.
 *
 * Each of these names a fact that exists at exactly one level, and that is what
 * decides where it applies. There is no setting here that can be honoured at
 * both levels at once, and inventing one would mean re-sorting rows across
 * platforms — see [BoardDisplayPrefs].
 */
@Serializable
enum class BoardSort {
    /**
     * The default and the board's natural order: blocks led by whichever has the
     * soonest train, rows inside each block in arrival order.
     */
    TIME,

    /**
     * BLOCK order, by platform number — Platform 1, 2, 3, 10 — rather than by
     * whose train is soonest.
     *
     * For someone who always leaves from the same platform this makes the board
     * readable without scanning: their block is where it was yesterday. Rows
     * inside a block stay in arrival order, because within one platform there is
     * no platform left to order by.
     *
     * Numeric, not alphabetic: "Platform 10" sorts after "Platform 9", which a
     * string sort gets wrong at exactly the interchanges with enough platforms
     * for this setting to matter.
     */
    PLATFORM,

    /**
     * ROW order inside each block, by where the train is going, so every train
     * to the same place sits together.
     *
     * Block order is untouched — a destination says nothing about which platform
     * should lead the board, and ordering blocks by their alphabetically-first
     * destination would be an arbitrary rank dressed up as a rule.
     *
     * The trains SHOWN are still the soonest ones (see
     * [MultiLineBoardProcessor.buildRows]); this only orders them once chosen.
     * Picking the alphabetically-first three instead would fill the board with
     * trains an hour away and hide the one leaving now.
     */
    DESTINATION,
}

/**
 * One block promoted to the top of the board — "show this one first".
 *
 * ## Why this promotes a BLOCK and never extracts rows
 * The obvious reading of "pin my line to the top" is a block of that line's
 * departures above everything else. That cannot be built without breaking the
 * board: a line at an interchange calls at several platforms, so its rows would
 * have to be lifted out of the platform blocks they belong to and shown under a
 * header that names no place you can stand. The rows would be in arrival order
 * and still be unactionable.
 *
 * So a pinned LINE promotes every block that carries it, in their usual order
 * among themselves, and a pinned PLATFORM promotes that one block. In both cases
 * the board is still a set of places with a queue at each, which is the one
 * thing about it that must not change.
 *
 * ## One pin, not a set
 * Deliberately a single nullable value. A rank that every platform can claim
 * ranks nothing — the same argument that removed the per-station `pinned` flag
 * from the home screen (see `StationPrefs`) — and here it has a sharper edge:
 * pin every block and the board is exactly the board you started with.
 *
 * A pin that matches nothing on today's board (a platform TfL is not using this
 * evening, a line whose trains have stopped) simply does nothing. It is not
 * pruned: the platform will be back tomorrow, and silently forgetting a setting
 * the user made is worse than a setting that waits.
 */
@Serializable
data class BoardPin(
    val kind: Kind,
    /**
     * The platform LABEL verbatim ("Platform 4", "Stop C") or the canonical line
     * id ("victoria"), depending on [kind].
     *
     * The platform is matched on its label rather than on the group key because
     * the label is what the user picked off their own board, and on rail the two
     * are the same string anyway. On bus the group key is a naptan, which is not
     * something a user has ever seen.
     */
    val id: String,
) {
    @Serializable
    enum class Kind { PLATFORM, LINE }
}
