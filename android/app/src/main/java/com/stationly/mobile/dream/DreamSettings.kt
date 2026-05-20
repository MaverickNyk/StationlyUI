package com.stationly.mobile.dream

import android.content.Context
import android.content.SharedPreferences

/**
 * Style of clock the user picked for the Daydream screen.
 * Stored as a string in SharedPrefs so it survives across app updates.
 */
enum class ClockStyle(val storedAs: String, val displayName: String) {
    DIGITAL("digital", "Digital"),
    ANALOG ("analog",  "Analog");

    companion object {
        // Anyone who had the legacy "none" setting (now removed) gracefully
        // falls back to the digital clock — we always want some clock visible.
        fun fromStored(value: String?): ClockStyle =
            entries.firstOrNull { it.storedAs == value } ?: DIGITAL
    }
}

/**
 * Which overall layout the dream uses.
 *   CLOCK_AND_BOARD  — clock cluster + departure board side-by-side (or stacked
 *                      in portrait). Default; the layout people see first.
 *   FULLSCREEN_BOARD — only the departure board, scaled to fill the screen.
 *                      Looks like the home-screen widget zoomed up, with the
 *                      widget's own ticking TextClock + "X ago" timer along
 *                      the bottom and the station header + Stationly logo at
 *                      the top.
 */
enum class DreamLayout(val storedAs: String, val displayName: String, val description: String) {
    CLOCK_AND_BOARD(
        storedAs    = "clock_and_board",
        displayName = "Clock + Board",
        description = "Big clock with departure board alongside"
    ),
    FULLSCREEN_BOARD(
        storedAs    = "fullscreen_board",
        displayName = "Fullscreen Board",
        description = "Just the departure board, filling the screen"
    );

    companion object {
        fun fromStored(value: String?): DreamLayout =
            entries.firstOrNull { it.storedAs == value } ?: CLOCK_AND_BOARD
    }
}

/**
 * Read/write helper for everything the user can configure on the dream.
 * Lives in its own SharedPrefs file ("StationlyDreamPrefs") so a `clearAll()`
 * on the main StationlyPrefs (which happens on logout) doesn't reset the
 * user's screensaver preferences.
 */
object DreamSettings {
    private const val FILE = "StationlyDreamPrefs"
    private const val KEY_LAYOUT      = "layout"
    private const val KEY_CLOCK_STYLE = "clock_style"
    private const val KEY_STATION_ID  = "station_id"  // optional override

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getLayout(context: Context): DreamLayout =
        DreamLayout.fromStored(prefs(context).getString(KEY_LAYOUT, null))

    fun setLayout(context: Context, layout: DreamLayout) {
        prefs(context).edit().putString(KEY_LAYOUT, layout.storedAs).apply()
    }

    fun getClockStyle(context: Context): ClockStyle =
        ClockStyle.fromStored(prefs(context).getString(KEY_CLOCK_STYLE, null))

    fun setClockStyle(context: Context, style: ClockStyle) {
        prefs(context).edit().putString(KEY_CLOCK_STYLE, style.storedAs).apply()
    }

    /**
     * Optional override telling the dream WHICH of the user's saved stations
     * to display. Null → use the first selection on the home screen.
     */
    fun getStationId(context: Context): String? =
        prefs(context).getString(KEY_STATION_ID, null)

    fun setStationId(context: Context, stationId: String?) {
        val editor = prefs(context).edit()
        if (stationId == null) editor.remove(KEY_STATION_ID)
        else                   editor.putString(KEY_STATION_ID, stationId)
        editor.apply()
    }
}
