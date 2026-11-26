package com.stationly.mobile.widget

import android.content.Context

/**
 * Whether the last refresh of a STATION failed, so its widgets can show the
 * retry glyph.
 *
 * Mirrors iOS's per-station `refreshFailed` flag (WidgetRefreshService.swift,
 * `markFailed`). Per station rather than global: with several widgets on a home
 * screen, one flag would put a failure warning on boards that refreshed fine.
 *
 * Set only when EVERY fetch for the station failed. A partial success already
 * moved the board, and the rows changing is the confirmation. Cleared by the
 * next refresh that gets anything back. Nothing rate-limits the retry: the
 * in-flight guard releases when an attempt ends, so a user who sees the glyph
 * can act on it at once, which is the whole reason the old lockout went.
 */
object RefreshFailures {
    private const val KEY_PREFIX = "refresh_failed_"

    fun record(context: Context, stations: Collection<String>, failed: Boolean) {
        if (stations.isEmpty()) return
        val edit = context.getSharedPreferences(WidgetBindingStore.PREFS, Context.MODE_PRIVATE).edit()
        stations.filter { it.isNotBlank() }.forEach { station ->
            if (failed) edit.putBoolean(KEY_PREFIX + station, true) else edit.remove(KEY_PREFIX + station)
        }
        edit.apply()
    }

    fun isFailed(context: Context, station: String?): Boolean =
        !station.isNullOrBlank() &&
            context.getSharedPreferences(WidgetBindingStore.PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PREFIX + station, false)
}
