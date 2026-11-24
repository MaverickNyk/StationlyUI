package com.stationly.core.platform

import android.content.Context

/**
 * One-shot notes left by the 1 → 2 database migration: "this install was v1
 * until a moment ago".
 *
 * Two things v1 never recorded have to be worked out exactly once, right after
 * that upgrade, and nothing else on the device can say it happened:
 *
 *  - **Widgets.** v1 had no bindings. Every widget drew the user's first
 *    station, so without this every v1 widget came back as "Choose a station".
 *    Spent by `V1WidgetAdoption`, in the first process after the update.
 *  - **Boards.** v1 saved only to the account's legacy `stations` list. An
 *    account that also has an iPhone already has a `boards` list, which does
 *    not contain them, and the first reconcile would delete them. Spent by
 *    `UserSyncCoordinator`.
 *
 * Written from the migration itself ([DriverFactory]) because it is the only
 * code that runs on every v1 device and on no other: not on a fresh install,
 * and not on a v2 → v2 update. The database opens lazily, so a reader must
 * have queried it before asking.
 *
 * Durable prefs, so a sign-out in between does not lose the note.
 */
object V1Upgrade {

    private const val WIDGETS = "v1_upgrade_widgets_pending"
    private const val BOARDS = "v1_upgrade_boards_pending"
    private const val BOARD_ATTEMPTS = "v1_upgrade_boards_attempts"

    /**
     * Completed attempts to publish v1's boards before giving up.
     *
     * Bounded because while the note stands, a board deleted on another device
     * is put back by this one. Offline does not count as an attempt.
     */
    private const val MAX_BOARD_ATTEMPTS = 5

    private fun prefs(context: Context) =
        context.getSharedPreferences(AndroidStorageManager.DURABLE_PREFS, Context.MODE_PRIVATE)

    /** `commit`, not `apply`: this happens once and the process may not last. */
    internal fun mark(context: Context) {
        prefs(context).edit()
            .putBoolean(WIDGETS, true)
            .putBoolean(BOARDS, true)
            .remove(BOARD_ATTEMPTS)
            .commit()
    }

    fun widgetsPending(context: Context): Boolean = prefs(context).getBoolean(WIDGETS, false)

    fun widgetsDone(context: Context) = prefs(context).edit().remove(WIDGETS).apply()

    fun boardsPending(context: Context): Boolean = prefs(context).getBoolean(BOARDS, false)

    fun boardsDone(context: Context) =
        prefs(context).edit().remove(BOARDS).remove(BOARD_ATTEMPTS).apply()

    /** Count one completed attempt. False once there have been too many. */
    fun mayRetryBoards(context: Context): Boolean {
        val attempts = prefs(context).getInt(BOARD_ATTEMPTS, 0) + 1
        prefs(context).edit().putInt(BOARD_ATTEMPTS, attempts).apply()
        return attempts < MAX_BOARD_ATTEMPTS
    }
}
