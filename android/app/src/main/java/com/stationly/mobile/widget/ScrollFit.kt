package com.stationly.mobile.widget

/**
 * Whether a scroll-mode board's rows all fit in the widget, so it has nothing
 * to scroll.
 *
 * When they do, the rows go in the non-scrolling container as height-flexible
 * rows and share the spare height, like the iOS widget, instead of sitting at
 * the top of a list with an empty band under them (owner, 2026-11-22: the
 * Highbury & Islington widget, one platform, gap after the last row). When they
 * do not, the scrolling list stays: a list item cannot stretch, and its job is
 * to scroll.
 *
 * Measured on a Pixel 10 Pro Fold (390 dpi) at its font scale of 1.1, then
 * normalised to a font scale of 1.0, because everything here scales with the
 * user's font size. Scaling an already-scaled measurement again is what first
 * made a four-row board miss by 1.6dp.
 *
 *  - [FIXED_DP]: the station row, the status row and the clock row with their
 *    gaps. Re-derived when the board went full bleed (2026-11-23): the 20dp of
 *    board padding went and the header, status and footer grew by about as much
 *    between them (~90dp; 91 is kept).
 *  - [ROW_PITCH_DP]: a departure row and its 2dp gap.
 *  - [HEADER_PITCH_DP]: a platform header and its gap, a step deeper than a
 *    row since the platform type moved up a size and gained 2dp of padding top
 *    and bottom. Counting headers as rows under-measured a two-platform board
 *    by ~10dp, enough to pick the stretching layout for a board that does not
 *    fit and squeeze every row to its floor.
 */
object ScrollFit {
    const val FIXED_DP = 91f
    const val ROW_PITCH_DP = 22f
    const val HEADER_PITCH_DP = 27f

    /**
     * @param rowCount every row in the list, headers included.
     * @param headerCount how many of those are platform headers.
     * @param rowScale the size class's row type against medium's (WidgetSize.rowScale).
     */
    fun allRowsFit(
        rowCount: Int,
        widgetHeightDp: Int,
        fontScale: Float = 1f,
        rowScale: Float = 1f,
        headerCount: Int = 0,
    ): Boolean {
        if (rowCount <= 0 || widgetHeightDp <= 0) return false
        val scale = fontScale.coerceAtLeast(0.5f) * rowScale.coerceAtLeast(0.5f)
        val headers = headerCount.coerceIn(0, rowCount)
        val content = (rowCount - headers) * ROW_PITCH_DP + headers * HEADER_PITCH_DP
        return content * scale <= widgetHeightDp - FIXED_DP * scale
    }
}
