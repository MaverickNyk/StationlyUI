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
 * ## Two settings, both of which promote a BLOCK or bound one
 *  - [pin] promotes a whole block to the top. It never lifts rows out of one.
 *  - [rowsPerPlatform] bounds how deep each block goes.
 *
 * Neither can reorder rows across blocks, which is the invariant the grouping
 * exists to hold.
 *
 * ## There was a third — a `sort` — and it should not come back
 * `BoardSort` offered Time / Platform / Destination in one control, and it was
 * wrong in a way that only shows up once you write down which LEVEL each option
 * acts at. Time and Platform ordered the BLOCKS; Destination ordered the ROWS
 * inside a block and left block order on Time. Three segments that read as three
 * alternatives were two alternatives plus an orthogonal switch, and the shape
 * forbade the one combination a user might actually want — platforms in number
 * order with destinations grouped inside each.
 *
 * It was also dead on the surface it was most visible on. A bus pole has no
 * number and, at the unlettered stops most people use, no label either, so
 * "order by stop" compared empty strings and quietly fell back to the order the
 * user's selections happened to be in.
 *
 * And [pin] already answers the question Platform-order was justified by. "My
 * platform is where it was yesterday" is served better by putting that platform
 * FIRST than by fixing it at position four.
 *
 * Block order is therefore fixed: unassigned last, then the pin, then whichever
 * block has the soonest train. Rows inside a block are always in arrival order.
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
 * among themselves, and a pinned PLATFORM or STOP promotes that one block. In
 * every case the board is still a set of places with a queue at each, which is
 * the one thing about it that must not change.
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
     * The platform LABEL verbatim ("Platform 4", "Stop C"), the pole's naptan, or
     * the canonical line id ("victoria"), depending on [kind].
     */
    val id: String,
) {
    @Serializable
    enum class Kind {
        /**
         * Matched on the platform LABEL rather than on the group key, because the
         * label is what the user picked off their own board and on rail the two
         * are the same string anyway.
         */
        PLATFORM,

        /**
         * One bus POLE, matched on its naptan — the board's own group key for
         * buses.
         *
         * A pole cannot be pinned by label the way a platform can. TfL letters
         * stops only at multi-stop interchanges, so at the ordinary stops most
         * people use every pole's label is blank, and a blank pin would match all
         * of them at once. The naptan is the only thing that tells two poles
         * apart.
         *
         * It is not a thing any user has seen, so the picker must label these
         * with something a person recognises — where the buses from that pole are
         * going. That is also the ONLY pin worth having at a hub: both sides of
         * the road usually run the same routes, so a pinned LINE there promotes
         * every pole and changes nothing.
         */
        STOP,

        LINE,
    }
}
