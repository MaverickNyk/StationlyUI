package com.stationly.mobile.ui.util

/**
 * Compute a `maxEms` cap for the dot-matrix station strip's
 * `line_name` TextView so the station name uses all the horizontal
 * room each surface actually has — instead of the hardcoded `18`
 * the XML carries for the smallest case (a 2-cell home-screen widget).
 *
 * The XML default is reasonable for the widget but too tight on the
 * home Board (~full-width phone), on a fullscreen-dream card
 * (~full-width tablet), and on the cluster-dream's 70%-width board
 * slot. With `singleLine=true + ellipsize=end`, an under-sized
 * `maxEms` causes "Highbury & Islington Underg…" to truncate when
 * there's actually room for the whole name.
 *
 * Each surface call site passes its own available-width estimate:
 *
 *   Widget         — `OPTION_APPWIDGET_MIN_WIDTH` (varies per cell size)
 *   Home Board     — `LocalConfiguration.screenWidthDp` minus LazyColumn padding
 *   Cluster dream  — clock-cluster's 70% slot (landscape) / full width (portrait)
 *   Fullscreen drm — `cardMaxWidth` from the layout's BoxWithConstraints
 *
 * The text size on the strip's `line_name` is 16sp per
 * `widget_departure_board.xml`. 1 em ≈ the text size in dp, so a
 * 280dp-wide slot supports ~17 ems; a 600dp tablet slot supports
 * ~37 ems. We clamp to `[10, 60]` so a very narrow widget cell
 * still gets at least some letters and a billboard-wide tablet
 * doesn't get an absurd cap.
 *
 * Reserves 30dp for the mode roundel (22dp icon + 8dp marginEnd) and
 * a small safety margin so the text never kisses the parent edge.
 */
object StationStripFitter {

    private const val LINE_NAME_TEXT_SIZE_SP = 16
    /** Mode roundel (22dp) + its marginEnd (8dp). */
    private const val MODE_ICON_RESERVE_DP = 30
    /** Pixel-honest safety margin so the ellipsis doesn't bump the parent edge. */
    private const val EDGE_SAFETY_DP = 4

    /** Lower clamp — even a tiny widget cell should show ~ "Bank" without "…". */
    private const val MIN_EMS = 10
    /** Upper clamp — a tablet billboard shouldn't grow the cap indefinitely. */
    private const val MAX_EMS = 60

    /**
     * @param availableWidthDp the strip's effective width (typically
     * the parent container's width on this surface — `header_row` in
     * the inflated XML)
     */
    fun maxEmsForWidthDp(availableWidthDp: Int): Int {
        val textWidthDp = availableWidthDp - MODE_ICON_RESERVE_DP - EDGE_SAFETY_DP
        return (textWidthDp / LINE_NAME_TEXT_SIZE_SP).coerceIn(MIN_EMS, MAX_EMS)
    }
}
