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

    /**
     * Notification that a setting changed. **Nothing subscribes to it today.**
     *
     * ## What this comment used to claim, and why it was wrong
     * It said the hook was "called after every SYNCED write, so the change
     * reaches the backend", and that "`UserStateSync` sets it at startup,
     * alongside the equivalent hook on `UserSettings`". None of that is true:
     * `UserStateSync.start()` is `= Unit`, there is no `onChanged` on
     * `UserSettings`, and there is no preferences endpoint to reach —
     * `syncProfile`, `syncStations`, `syncBoards`, `getUserProfile`, `logOut`,
     * `deleteAccount` and the two FCM calls are the entire user-sync surface.
     *
     * Screensaver settings are device-local and go nowhere. See
     * `USER_STATE_AND_ACTIVITY.md` §2b, which now states that plainly, and the
     * 2026-08-15 audit for the decision to keep it that way.
     *
     * The hook is left in place because it is the correct seam if that is ever
     * revisited — a callback rather than a direct call, because the sync layer
     * needs an API client and this object is read by the dream host on a
     * background thread with no dependency on the network stack. Delete it
     * rather than let it accumulate more false documentation if the answer
     * stays no.
     *
     * [applyingRemote] suppression is likewise pre-wiring: adopting a setting
     * pushed from elsewhere must not push it straight back. Today its only live
     * use is `UserStateSync.clearDreamSettings`, which resets the four keys at
     * logout without treating the reset as a user edit.
     */
    var onChanged: (() -> Unit)? = null

    private var applyingRemote = false

    /**
     * Every write goes through here.
     *
     * One funnel rather than a notify at each setter, because the failure mode
     * of the alternative is invisible: a setting added later writes the value,
     * the screen shows it, and nothing downstream ever hears about it. Adding a
     * setting here cannot forget to notify.
     *
     * ("Every SYNCED write" is what this said, and nothing here is synced — see
     * [onChanged].)
     */
    private fun write(key: String, value: String?) {
        DreamPrefsBackend.set(key, value)
        if (!applyingRemote) onChanged?.invoke()
    }

    /**
     * Apply a change that is NOT a user edit, without notifying [onChanged].
     *
     * Its one live caller is `UserStateSync.clearDreamSettings`, which resets
     * these four keys at logout — a teardown, not something the user did.
     *
     * (This used to cite `UserSettings.applyRemotePreferences` as precedent for
     * suppressing a cloud-originated echo. No such function exists, and nothing
     * arrives from the cloud — see [onChanged]. The suppression is still the
     * right shape for that seam if preferences are ever synced, which is why it
     * stays.)
     */
    fun applyRemote(block: () -> Unit) {
        applyingRemote = true
        try { block() } finally { applyingRemote = false }
    }

    fun getLayout(): DreamLayout = DreamLayout.fromStored(DreamPrefsBackend.get(KEY_LAYOUT))
    fun setLayout(layout: DreamLayout) = write(KEY_LAYOUT, layout.storedAs)

    fun getTheme(): DreamTheme = DreamTheme.fromStored(DreamPrefsBackend.get(KEY_THEME))
    fun setTheme(theme: DreamTheme) = write(KEY_THEME, theme.storedAs)

    fun getClockStyle(): ClockStyle = ClockStyle.fromStored(DreamPrefsBackend.get(KEY_CLOCK_STYLE))
    fun setClockStyle(style: ClockStyle) = write(KEY_CLOCK_STYLE, style.storedAs)

    /**
     * Optional override telling the dream WHICH of the user's saved stations
     * to display. Null → use the first selection on the home screen.
     */
    fun getStationId(): String? = DreamPrefsBackend.get(KEY_STATION_ID)?.ifBlank { null }
    fun setStationId(stationId: String?) = write(KEY_STATION_ID, stationId)

    // `hasEverStarted()` / `markStarted()` and their `ever_started` key were
    // removed on 2026-08-23 along with the home "Set as Screensaver" promo,
    // which was their only reader and writer. They tracked one thing — has this
    // device ever run the dream — purely so the promo could retire itself.
    //
    // Nothing records that fact now. If it is wanted, it belongs in the activity
    // trail beside `settings.dream_changed`, not in a preferences store: the
    // trail is where "what do people actually use" is answered, and it already
    // crosses devices and reaches the backend. A boolean here could only ever
    // answer it for one phone.
}
