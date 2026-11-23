package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.Context

/**
 * Which platform each STEPPING widget is currently showing.
 *
 * ## Why this has to be stored at all
 * A `RemoteViews` tree holds no state. The chevrons on a paging widget are
 * `PendingIntent`s that fire a broadcast and nothing more, so "which platform
 * am I on" cannot live in the view — it has to be written down between one tap
 * and the redraw that answers it.
 *
 * Per `appWidgetId` rather than per station, because two widgets on the same
 * station are two things a user placed for two reasons and stepping one must
 * not move the other. The binding store keys the same way and for the same
 * reason.
 *
 * ## It is a hint, never a fact
 * The board changes shape under this constantly — TfL stops serving a platform
 * and a three-page board becomes two, nightly. Nothing here validates the
 * stored value; [com.stationly.core.util.PlatformPages.clamp] does that at the
 * point of use, so a stale index draws the last real platform instead of
 * nothing. Writing a "valid" index would only mean validating it twice and
 * still being wrong by the time it is read.
 *
 * Lives in the same prefs file as the binding — one file for "what this widget
 * is showing", which is what both of these are.
 */
object WidgetPageStore {

    /** `page_<appWidgetId>`. */
    internal const val KEY_PREFIX = "page_"

    /**
     * `pagekey_<appWidgetId>`: the BLOCK the widget is on (the platform, or the
     * bus pole's naptan), stored beside the index.
     *
     * The index alone sent a widget to the other direction whenever the blocks
     * re-sorted (QA 2026-11-23, finding #5). The key is what a render resolves
     * first ([com.stationly.core.util.PlatformPages.resolve]); the index is the
     * fallback for a block that has left the board. Not prefixed `page_`, so
     * the binding store's prefix sweep treats the two as separate keys.
     */
    internal const val BLOCK_KEY_PREFIX = "pagekey_"

    /**
     * Where a widget starts when it is pointed at a different station.
     *
     * Keeping the old index would open a Bank widget on "the third one", which
     * is a sentence about the station it used to show.
     */
    const val PAGE_ON_REBIND = 0

    internal fun keyFor(appWidgetId: Int) = "$KEY_PREFIX$appWidgetId"
    internal fun blockKeyFor(appWidgetId: Int) = "$BLOCK_KEY_PREFIX$appWidgetId"

    private fun prefs(context: Context) =
        context.getSharedPreferences(WidgetBindingStore.PREFS, Context.MODE_PRIVATE)

    /** The stored index, unvalidated — clamp it against the live board. */
    fun pageOf(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt(keyFor(appWidgetId), 0)

    /** The block the widget was last on, or null. See [BLOCK_KEY_PREFIX]. */
    fun blockOf(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(blockKeyFor(appWidgetId), null)

    /**
     * Records the page AND the block on it. [blockKey] null clears the block,
     * which is right for a rebind: the old station's block means nothing here.
     */
    fun setPage(context: Context, appWidgetId: Int, page: Int, blockKey: String? = null) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        prefs(context).edit().apply {
            putInt(keyFor(appWidgetId), page)
            if (blockKey.isNullOrBlank()) remove(blockKeyFor(appWidgetId))
            else putString(blockKeyFor(appWidgetId), blockKey)
        }.apply()
    }

    /** Called when a widget is pointed at another station. */
    fun reset(context: Context, appWidgetId: Int) = setPage(context, appWidgetId, PAGE_ON_REBIND)

    /** Called from `onDeleted`, beside the binding it belongs with. */
    fun forget(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        prefs(context).edit().apply {
            appWidgetIds.forEach { remove(keyFor(it)); remove(blockKeyFor(it)) }
        }.apply()
    }
}
