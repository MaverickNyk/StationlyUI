package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.stationly.core.model.user.BoardPin
import com.stationly.core.model.user.PlatformNav

/**
 * How one widget looks, owned by that widget.
 *
 * ## What this replaces
 * The configure screen used to write through `UserSettings.update(groupingId)`
 * — the same `BoardConfig` the station's settings screen edits and the home
 * screen renders from. So changing a widget silently changed the card inside
 * the app, and the two could never be set differently even though they are
 * different shapes answering different questions: a card sits in a scrolling
 * page, a widget sits in a fixed cell on somebody's home screen.
 *
 * The owner's rule, 2026-09-12: widget settings are the widget's alone.
 *
 * ## Two settings, and the one that is deliberately absent
 * [PlatformNav] and [BoardPin]. There is no depth: a control that changes the
 * HEIGHT of a home-screen widget is a control for moving everything around it,
 * and stepping between a two-train platform and a three-train one made that
 * visible. The widget is always three per platform, padded — see
 * `PlatformPages.bodyPadded`.
 *
 * ## Keyed per widget
 * Two widgets showing one station are two things somebody placed for two
 * reasons, and one must not re-point the other. The binding and the page index
 * key the same way, and all three live in the same prefs file, because all
 * three answer "what is this widget doing".
 *
 * ## Why the pin is a string and not JSON
 * It is two fields that will never be three: `BoardPin` is a kind and an id,
 * and the kind is a closed enum. A serializer would pull kotlinx.serialization
 * into a class the widget renderer touches on every draw for no gain, and a
 * malformed blob would need a policy this does not.
 */
object WidgetSettings {

    /** `wpin_<appWidgetId>` — the pin, `KIND|id`, or absent. */
    internal fun pinKeyFor(appWidgetId: Int) = "wpin_$appWidgetId"

    /** `wnav_<appWidgetId>` — [PlatformNav] by name, or absent. */
    internal fun navKeyFor(appWidgetId: Int) = "wnav_$appWidgetId"

    private const val SEP = '|'

    private fun prefs(context: Context) =
        context.getSharedPreferences(WidgetBindingStore.PREFS, Context.MODE_PRIVATE)

    // ── the pure halves, which is where the decisions are ───────────────────

    /**
     * Absent, blank or unrecognised all mean SCROLL.
     *
     * Scrolling is what every widget did before this setting existed, so an
     * absent value has to keep meaning "behave as it already does" — and a
     * value written by a future build with a third mode must degrade rather
     * than throw.
     */
    fun navFrom(stored: String?): PlatformNav =
        PlatformNav.entries.firstOrNull { it.name == stored } ?: PlatformNav.SCROLL

    /**
     * `KIND|id`, split on the FIRST separator only.
     *
     * A platform id is whatever TfL prints on the sign, and "Platform 9
     * (Eastbound)" is the ordinary case rather than the awkward one. Splitting
     * on every separator would truncate any id that happened to contain it.
     */
    fun pinFrom(stored: String?): BoardPin? {
        val raw = stored?.takeIf { it.isNotBlank() } ?: return null
        val cut = raw.indexOf(SEP)
        if (cut <= 0 || cut == raw.lastIndex) return null
        val kind = BoardPin.Kind.entries.firstOrNull { it.name == raw.substring(0, cut) }
            ?: return null
        return BoardPin(kind, raw.substring(cut + 1))
    }

    /** Blank for "no pin", so clearing writes nothing a reader has to decode. */
    fun pinToStored(pin: BoardPin?): String =
        pin?.let { "${it.kind.name}$SEP${it.id}" } ?: ""

    // ── the store ───────────────────────────────────────────────────────────

    fun navOf(context: Context, appWidgetId: Int): PlatformNav =
        navFrom(prefs(context).getString(navKeyFor(appWidgetId), null))

    fun pinOf(context: Context, appWidgetId: Int): BoardPin? =
        pinFrom(prefs(context).getString(pinKeyFor(appWidgetId), null))

    fun setNav(context: Context, appWidgetId: Int, nav: PlatformNav) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        prefs(context).edit().putString(navKeyFor(appWidgetId), nav.name).apply()
    }

    fun setPin(context: Context, appWidgetId: Int, pin: BoardPin?) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        prefs(context).edit().putString(pinKeyFor(appWidgetId), pinToStored(pin)).apply()
    }

    /**
     * Forget a widget's settings, beside the binding and the page they belong
     * with. Called from `onDeleted`.
     */
    fun forget(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        prefs(context).edit().apply {
            appWidgetIds.forEach { remove(pinKeyFor(it)); remove(navKeyFor(it)) }
        }.apply()
    }

    /**
     * Point a widget at another station and its pin stops meaning anything —
     * "Platform 9" is a sentence about the station it used to show. The nav
     * mode is about the WIDGET rather than the station, so it stays.
     */
    fun resetForRebind(context: Context, appWidgetId: Int) = setPin(context, appWidgetId, null)
}
