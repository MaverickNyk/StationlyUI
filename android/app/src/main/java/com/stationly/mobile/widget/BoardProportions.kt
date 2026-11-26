package com.stationly.mobile.widget

/**
 * How tall each row of a STRETCHING board is, so the board keeps its hierarchy
 * at every size on every phone (owner, 2026-11-23):
 *
 *     station  >  platform  >  departure  >  status
 *
 * with the clock footer between platform and departure.
 *
 * ## The rule: one proportion, not one height
 * Departure rows stretch to fill the widget (`widget_departure_row_fill`, the
 * stepping pages, fallback messages), so their height depends on the launcher,
 * the screen, the grid and how many rows there are. Every other row used to be
 * a fixed height, which is how a tall widget ended up with departures deeper
 * than the platform bar naming them and the station strip above both.
 *
 * So every row is sized as a multiple of the departure row ([RATIOS]), each
 * floored at what its own text needs. iOS gets the same effect by giving every
 * cell an equal share of the surplus on top of its own minimum; Android cannot
 * share surplus across views in different containers, so it computes the
 * departure row height instead and sizes the others from it.
 *
 * ## Errors land in the rows, never in a clipped footer
 * Only the rows OUTSIDE the stretching container are set here. The container
 * keeps `0dp` + weight and takes whatever is left, so if the launcher's
 * reported height is a few dp off the departure rows absorb it. The platform
 * headers INSIDE a stretching container hold their ratio by weight
 * (`widget_platform_header_fill`, weight [PLATFORM]), exactly.
 *
 * ## When it does nothing
 * [solve] returns null when the departure rows would come out smaller than their
 * own text. That board is crowded, not stretched, and its natural heights are
 * already the right answer. A scrolling list never stretches, so it never asks.
 */
object BoardProportions {

    // ── Each row as a multiple of a departure row ──────────────────────────
    const val STATION = 1.45f
    /** Also the weight of `widget_platform_header_fill`, which must match it. */
    const val PLATFORM = 1.2f
    const val FOOTER = 1.1f
    const val STATUS = 0.8f

    /** The 2dp bezel between rows. */
    const val GAP_DP = 2f

    /**
     * Held back from the launcher's reported height. Reports run a little over
     * what is drawn on some launchers; this keeps an over-report from squeezing
     * the rows below their text.
     */
    const val SLACK = 0.97f

    /**
     * Rendered line height of one line of text, as a multiple of its size,
     * including Android's font padding.
     *
     * Was 1.32, which is right at font scale 1.0 and a few px short at 1.3: the
     * floors came out under what the text draws at, the solve still "fitted",
     * and the bottom departure row lost its descenders (QA 2026-11-23, finding
     * #12, Arsenal at font scale 1.3). 1.42 leaves the headroom; a board that
     * cannot afford it is crowded and keeps its natural heights instead.
     */
    internal const val LINE = 1.42f

    data class Heights(
        val stationDp: Float,
        /** Null when the platform pager is not shown. */
        val pagerDp: Float?,
        /** Null when the status row is not shown. */
        val statusDp: Float?,
        val footerDp: Float,
        /** What each departure row came out at, for the record and the tests. */
        val rowDp: Float,
    )

    /**
     * @param heightDp the widget's drawn height.
     * @param rows departure (and message) rows in the stretching container.
     * @param headers platform headers in the same container (fill mode); zero
     *   when stepping, where the pager names the platform instead.
     * @param pager whether the step-mode pager bar is showing.
     */
    fun solve(
        heightDp: Int,
        size: WidgetSize,
        fontScale: Float,
        rows: Int,
        headers: Int,
        pager: Boolean,
        status: Boolean,
    ): Heights? {
        if (heightDp <= 0 || rows + headers <= 0) return null
        val fs = fontScale.coerceAtLeast(0.5f)
        fun line(sp: Float) = sp * fs * LINE

        // What each row needs for its own content: the floors.
        val stationBase = maxOf(line(size.station), WidgetSize.HEADER_BOX_DP) + size.headerPadTopDp + size.headerPadBottomDp
        val pagerBase = line(size.platform) + 4f
        val statusBase = line(size.status) + 2 * size.statusPadDp
        val footerBase = maxOf(line(if (size.showsClock) size.clock else size.ago), 20f) +
            size.footerPadTopDp + size.footerPadBottomDp
        val rowBase = maxOf(line(size.row), size.rowFloorDp)

        val gaps = GAP_DP * (rows + headers +
            (if (pager) 1 else 0) + (if (status) 1 else 0) + 1)          // +1: footer
        val available = heightDp * SLACK - gaps

        // Outside the container: (ratio, floor). The container's units are
        // its rows at 1 and its headers at PLATFORM.
        data class Part(val ratio: Float, val base: Float)
        val parts = buildList {
            add(Part(STATION, stationBase))
            if (pager) add(Part(PLATFORM, pagerBase))
            if (status) add(Part(STATUS, statusBase))
            add(Part(FOOTER, footerBase))
        }
        val containerUnits = rows + headers * PLATFORM

        // Solve for the departure row height r. A part whose ratio * r is under
        // its floor sits at the floor and drops out of the proportion; repeat
        // until nothing new floors (at most once per part).
        val floored = BooleanArray(parts.size)
        var r: Float
        while (true) {
            var fixed = 0f
            var units = containerUnits
            parts.forEachIndexed { i, p -> if (floored[i]) fixed += p.base else units += p.ratio }
            r = (available - fixed) / units
            val newlyFloored = parts.indices.filter { !floored[it] && parts[it].ratio * r < parts[it].base }
            if (newlyFloored.isEmpty()) break
            newlyFloored.forEach { floored[it] = true }
        }

        // Crowded: rows would be under their own text, or under the status
        // row's (which would put status above departures on the ladder).
        // Natural heights win.
        if (r < rowBase || (status && r < statusBase)) return null

        fun height(p: Part) = maxOf(p.base, p.ratio * r)
        var i = 0
        val station = height(parts[i++])
        val pagerH = if (pager) height(parts[i++]) else null
        val statusH = if (status) height(parts[i++]) else null
        val footer = height(parts[i])
        return Heights(station, pagerH, statusH, footer, r)
    }
}
