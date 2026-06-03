package com.stationly.mobile.util

import android.graphics.Color

/**
 * Maps a transport mode (as stored in `UserSelection.mode`) to the
 * canonical TfL colour that tints the mode roundel on the station strip
 * of the widget and the fullscreen dream.
 *
 * Tints fall through to TfL amber for anything we don't recognise so the
 * roundel always renders something rather than disappearing.
 */
object ModeColors {

    private val TFL_AMBER = Color.parseColor("#FFC819")

    fun forMode(mode: String?): Int = when (mode?.lowercase()?.trim()) {
        "tube"                   -> Color.parseColor("#DC241F") // Underground red
        "overground"             -> Color.parseColor("#EE7C0E") // Overground orange
        "dlr"                    -> Color.parseColor("#00A4A7") // DLR turquoise
        "elizabeth-line",
        "elizabeth"              -> Color.parseColor("#6950A1") // Elizabeth line purple
        "bus"                    -> Color.parseColor("#DC241F") // London bus red
        "tram"                   -> Color.parseColor("#84B817") // Tramlink green
        "national-rail",
        "national_rail"          -> Color.parseColor("#1D3E89") // BR blue
        "river-bus",
        "river_bus"              -> Color.parseColor("#1D3E89") // Thames Clippers blue
        "cable-car",
        "cable_car"              -> Color.parseColor("#E21836") // Cable Car red
        else                     -> TFL_AMBER
    }
}
