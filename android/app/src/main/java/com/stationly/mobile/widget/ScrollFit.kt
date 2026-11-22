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
 * Measured on a Pixel 10 Pro Fold (390 dpi) at its font scale of 1.1: the fixed
 * parts (10dp padding top and bottom, the header, the status row and the clock
 * row) took ~99dp and a row with its 2dp margin ~24dp. The constants are those
 * figures normalised to a font scale of 1.0, because both scale with the user's
 * font size. Scaling an already-scaled measurement again is what first made a
 * four-row board miss by 1.6dp.
 */
object ScrollFit {
    const val FIXED_DP = 91f
    const val ROW_PITCH_DP = 22f

    /** [rowScale]: the size class's row type against medium's (WidgetSize.rowScale). */
    fun allRowsFit(rowCount: Int, widgetHeightDp: Int, fontScale: Float = 1f, rowScale: Float = 1f): Boolean {
        if (rowCount <= 0 || widgetHeightDp <= 0) return false
        val scale = fontScale.coerceAtLeast(0.5f) * rowScale.coerceAtLeast(0.5f)
        return rowCount * ROW_PITCH_DP * scale <= widgetHeightDp - FIXED_DP * scale
    }
}
