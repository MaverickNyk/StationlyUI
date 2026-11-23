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
) {
    COMPACT(station = 14.5f, platform = 14f, row = 13f, status = 10.5f, clock = 13.5f, ago = 11.5f,
        pagerCount = 10f, showsClock = false, centresStation = false),
    MEDIUM(station = 16.5f, platform = 16f, row = 15f, status = 12f, clock = 16f, ago = 12f,
        pagerCount = 11.5f, showsClock = true, centresStation = true),
    LARGE(station = 18.5f, platform = 18f, row = 17f, status = 13.5f, clock = 18f, ago = 13f,
        pagerCount = 13f, showsClock = true, centresStation = true);

    /** How this class's rows compare to the medium rows the layouts were measured at. */
    val rowScale: Float get() = row / MEDIUM.row

    companion object {
        const val COMPACT_BELOW_WIDTH_DP = 250
        const val LARGE_FROM_HEIGHT_DP = 320

        fun of(widthDp: Int, heightDp: Int): WidgetSize = when {
            widthDp in 1 until COMPACT_BELOW_WIDTH_DP -> COMPACT
            heightDp >= LARGE_FROM_HEIGHT_DP -> LARGE
            else -> MEDIUM
        }

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
