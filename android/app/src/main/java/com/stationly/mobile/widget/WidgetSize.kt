package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle

/**
 * The widget's size class and its type scale: the Android counterpart of iOS's
 * per-family `BoardMetrics` (WidgetViews.swift).
 *
 * iOS has three fixed families. An Android widget resizes continuously, so its
 * measured size is mapped onto the same three classes:
 *
 *  - [COMPACT]: narrower than ~250dp, a 2x2 or any 2-3 column widget. iOS
 *    `.small`: smaller type, no wall clock (the status bar already says the
 *    time, so the "ago" timer gets the footer), and the station name reads from
 *    the left edge instead of holding an empty column to stay centred.
 *  - [MEDIUM]: the 4x2 board everything was tuned on. The sizes here ARE the
 *    XML defaults, so a medium widget looks exactly as it did.
 *  - [SHORT]: wide, but too short for MEDIUM to hold three departures under a
 *    platform, which is what two rows of a dense home screen give (158dp on a
 *    Redmi 13C 5G's 6x7). iOS `.medium` is this tall too, and this is its scale.
 *  - [COMPACT_SHORT]: the same for a 2x2: compact's type, SHORT's padding.
 *  - [LARGE]: wide and at least ~320dp tall, a 4x4. Larger type, like iOS
 *    `.large`.
 *
 * One step per rung (owner, 2026-11-23): station > platform > departure >
 * status, as iOS's ladder. The platform used to be the departure rows' size,
 * so the only thing marking a platform bar out was bold. The station's step is
 * half a point on medium and large: its ROW is already the deepest on the board
 * (BoardProportions), and a full point cut "Arsenal Underground Station" short
 * on a 4x2.
 *
 * The pager's "1/2" is the platform size x 0.72, as iOS.
 *
 * The footer clock sits a step BELOW the station ("it does not outrank the
 * station name", iOS). It was 19sp on medium, the in-app board's size, which on
 * a widget made the footer the heaviest row on the board.
 *
 * The steps between classes follow iOS's ratios (small/medium/large rows
 * 11/12.5/14.5pt) applied to Android's 15sp medium baseline.
 */
enum class WidgetSize(
    val station: Float,
    val platform: Float,
    val row: Float,
    val status: Float,
    val clock: Float,
    val ago: Float,
    val pagerCount: Float,
    val showsClock: Boolean,
    val centresStation: Boolean,
    /** The mode roundel beside the station name. */
    val iconDp: Float = 22f,
    /** Padding of the fixed rows, as the layout has it except on [SHORT]. */
    val headerPadTopDp: Float = 7f,
    val headerPadBottomDp: Float = 5f,
    val statusPadDp: Float = 3f,
    val footerPadTopDp: Float = 2f,
    val footerPadBottomDp: Float = 3f,
    /** The footer's side inset: deeper on a tight one, whose logo is nearer the corner. */
    val footerInsetDp: Float = 14f,
    /** The least a stretching departure row may be, if its text needs less. */
    val rowFloorDp: Float = 22f,
) {
    COMPACT(station = 14.5f, platform = 14f, row = 13f, status = 10.5f, clock = 13.5f, ago = 11.5f,
        pagerCount = 10f, showsClock = false, centresStation = false, iconDp = 16f),
    COMPACT_SHORT(station = 14.5f, platform = 14f, row = 13f, status = 10.5f, clock = 13.5f, ago = 11.5f,
        pagerCount = 10f, showsClock = false, centresStation = false, iconDp = 16f,
        headerPadTopDp = 2f, headerPadBottomDp = 2f, statusPadDp = 1f,
        footerPadTopDp = 0f, footerPadBottomDp = 2f, footerInsetDp = 18f, rowFloorDp = 17f),
    SHORT(station = 14f, platform = 13.5f, row = 12.5f, status = 10.5f, clock = 13.5f, ago = 11f,
        pagerCount = 9.5f, showsClock = true, centresStation = true, iconDp = 18f,
        headerPadTopDp = 2f, headerPadBottomDp = 2f, statusPadDp = 1f,
        footerPadTopDp = 0f, footerPadBottomDp = 2f, footerInsetDp = 18f, rowFloorDp = 17f),
    MEDIUM(station = 16.5f, platform = 16f, row = 15f, status = 12f, clock = 16f, ago = 12f,
        pagerCount = 11.5f, showsClock = true, centresStation = true),
    LARGE(station = 18.5f, platform = 18f, row = 17f, status = 13.5f, clock = 18f, ago = 13f,
        pagerCount = 13f, showsClock = true, centresStation = true);

    /** How this class's rows compare to the medium rows the layouts were measured at. */
    val rowScale: Float get() = row / MEDIUM.row

    /** The station row with nothing stretched: the refresh button's box and the padding. */
    val naturalHeaderDp: Float get() = HEADER_BOX_DP + headerPadTopDp + headerPadBottomDp

    /** A 2x2 or any narrow widget, whichever its padding. */
    val isCompact: Boolean get() = this == COMPACT || this == COMPACT_SHORT

    /** Whether the fixed rows have their tight padding. */
    val isTight: Boolean get() = this == SHORT || this == COMPACT_SHORT

    /** Whose header padding this is: below Android 12 always MEDIUM's, see [sdk] in [of]. */
    fun headerPadding(sdk: Int): WidgetSize = if (sdk < ANDROID_12) MEDIUM else this

    /** The rows outside the departures, unstretched, with their gaps. */
    fun fixedDp(fontScale: Float, pager: Boolean, status: Boolean = true, sdk: Int = ANDROID_12): Float {
        fun line(sp: Float) = sp * fontScale * TEXT_LINE
        val header = headerPadding(sdk)
        return maxOf(line(station), HEADER_BOX_DP) + header.headerPadTopDp + header.headerPadBottomDp +
            (if (status) GAP_DP + line(this.status) + 2 * statusPadDp else 0f) +
            GAP_DP + maxOf(line(if (showsClock) clock else ago), LOGO_DP) + footerPadTopDp + footerPadBottomDp +
            (if (pager) GAP_DP + line(platform) + PAGER_PAD_DP else 0f)
    }

    /** What this class needs for [ROWS] departures under a platform. */
    fun needsDp(fontScale: Float, pager: Boolean, status: Boolean = true, sdk: Int = ANDROID_12): Float {
        fun line(sp: Float) = sp * fontScale * TEXT_LINE
        return fixedDp(fontScale, pager, status, sdk) + ROWS * (GAP_DP + line(row)) +
            (if (pager) 0f else GAP_DP + line(platform))
    }

    /**
     * The row type for [rows] rows that cannot scroll (a message, or a page under
     * the arrows), shrunk only as far as it must be. At large text they were cut.
     */
    fun fixedRowSp(
        heightDp: Int,
        fontScale: Float,
        rows: Int,
        pager: Boolean,
        status: Boolean,
        sdk: Int = ANDROID_12,
    ): Float {
        if (heightDp <= 0 || rows <= 0) return row
        val room = (heightDp - fixedDp(fontScale, pager, status, sdk)) / rows - GAP_DP
        return (room / (TEXT_LINE * fontScale)).coerceIn(MIN_ROW_SP, row)
    }

    /** Whether rows that cannot scroll are only readable without the status row. */
    fun dropsStatus(heightDp: Int, fontScale: Float, rows: Int, pager: Boolean, sdk: Int = ANDROID_12): Boolean =
        fixedRowSp(heightDp, fontScale, rows, pager, status = true, sdk = sdk) < MIN_WITH_STATUS_SP

    companion object {
        const val COMPACT_BELOW_WIDTH_DP = 250
        const val LARGE_FROM_HEIGHT_DP = 320
        const val HEADER_BOX_DP = 26f
        private const val LOGO_DP = 20f
        private const val GAP_DP = 2f
        private const val PAGER_PAD_DP = 4f
        private const val ROWS = 3
        private const val MIN_ROW_SP = 8f
        private const val MIN_WITH_STATUS_SP = 10f
        const val ANDROID_12 = 31

        /** A line of text as drawn, against its size: 15sp is 20.5dp on a Redmi. */
        const val TEXT_LINE = 1.367f

        /**
         * The largest type that fits. A short class is a last resort: only where
         * the ordinary one cannot hold three departures under a platform.
         *
         * [fontScale] counts only when the board cannot scroll ([pager]). One that
         * can keeps the size the user asked for, and scrolls.
         *
         * [trustHeight] false keeps the ordinary class; see [trustsHeight].
         *
         * [sdk]: below Android 12 the tap zones cannot move with the header, so it
         * keeps MEDIUM's padding and a tight board is taller than it is above.
         */
        fun of(
            widthDp: Int,
            heightDp: Int,
            fontScale: Float = 1f,
            pager: Boolean = false,
            trustHeight: Boolean = true,
            sdk: Int = ANDROID_12,
        ): WidgetSize {
            val compact = widthDp in 1 until COMPACT_BELOW_WIDTH_DP
            val ordinary = when {
                compact -> COMPACT
                heightDp >= LARGE_FROM_HEIGHT_DP -> LARGE
                else -> MEDIUM
            }
            if (heightDp <= 0 || !trustHeight) return ordinary
            val scale = if (pager) fontScale.coerceAtLeast(1f) else 1f
            if (ordinary.needsDp(scale, pager, sdk = sdk) <= heightDp) return ordinary
            return when {
                compact -> COMPACT_SHORT
                ordinary == LARGE && MEDIUM.needsDp(scale, pager, sdk = sdk) <= heightDp -> MEDIUM
                else -> SHORT
            }
        }

        /**
         * Only upright. On its side a launcher reports the height its widget has
         * sideways, which is not what is on the home screen while a video plays in
         * front of it, nor on an unfolded foldable (that is the FOLDED height).
         * Believing it made a widget's type jump on the next push.
         */
        fun trustsHeight(context: Context): Boolean =
            context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

        /**
         * The size the widget is actually drawn at. Launchers report a range:
         * in portrait the width is the MIN and the height the MAX, in landscape
         * the other way round.
         */
        fun measured(context: Context, options: Bundle?): Pair<Int, Int> {
            if (options == null) return 0 to 0
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val w = options.getInt(
                if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0,
            )
            val h = options.getInt(
                if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0,
            )
            return w to h
        }
    }
}
