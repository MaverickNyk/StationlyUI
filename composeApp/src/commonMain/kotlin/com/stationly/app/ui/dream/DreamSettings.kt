package com.stationly.app.ui.dream

import com.stationly.app.platform.DreamPrefsBackend

/**
 * Style of clock the user picked for the dream screen. Stored as a string
 * so it survives across app updates. Port of Android `dream/DreamSettings.kt`.
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
 * User's theme preference for the dream's canvas + text-on-canvas. The
 * departure board card always renders in its dot-matrix dark style — this
 * only affects what sits around it.
 *
 *   SYSTEM — follow the app's current dark-mode setting (default)
 *   LIGHT  — force light canvas / dark text on canvas
 *   DARK   — force dark canvas / light text on canvas
 *
 * Order in this enum drives the order of the picker tiles — SYSTEM first.
 */
enum class DreamTheme(val storedAs: String, val displayName: String) {
    SYSTEM ("system", "System"),
    LIGHT  ("light",  "Light"),
    DARK   ("dark",   "Dark");

    companion object {
        fun fromStored(value: String?): DreamTheme =
            entries.firstOrNull { it.storedAs == value } ?: SYSTEM
    }
}

/**
 * Which overall layout the dream uses.
 *   CLOCK_AND_BOARD  — clock cluster + departure board side-by-side (or
 *                      stacked in portrait). Default.
 *   FULLSCREEN_BOARD — only the departure board, scaled to fill the screen.
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
 * Backed by [DreamPrefsBackend] — on iOS that's the app-group NSUserDefaults
 * suite, which (like Android's separate "StationlyDreamPrefs" file) survives
 * the logout-time `clearAll()` of the main app prefs.
 */
object DreamSettings {
    private const val KEY_LAYOUT      = "layout"
    private const val KEY_THEME      = "theme"
    private const val KEY_CLOCK_STYLE = "clock_style"
    private const val KEY_STATION_ID  = "station_id"  // optional override

    fun getLayout(): DreamLayout = DreamLayout.fromStored(DreamPrefsBackend.get(KEY_LAYOUT))
    fun setLayout(layout: DreamLayout) = DreamPrefsBackend.set(KEY_LAYOUT, layout.storedAs)

    fun getTheme(): DreamTheme = DreamTheme.fromStored(DreamPrefsBackend.get(KEY_THEME))
    fun setTheme(theme: DreamTheme) = DreamPrefsBackend.set(KEY_THEME, theme.storedAs)

    fun getClockStyle(): ClockStyle = ClockStyle.fromStored(DreamPrefsBackend.get(KEY_CLOCK_STYLE))
    fun setClockStyle(style: ClockStyle) = DreamPrefsBackend.set(KEY_CLOCK_STYLE, style.storedAs)

    /**
     * Optional override telling the dream WHICH of the user's saved stations
     * to display. Null → use the first selection on the home screen.
     */
    fun getStationId(): String? = DreamPrefsBackend.get(KEY_STATION_ID)?.ifBlank { null }
    fun setStationId(stationId: String?) = DreamPrefsBackend.set(KEY_STATION_ID, stationId)
}
