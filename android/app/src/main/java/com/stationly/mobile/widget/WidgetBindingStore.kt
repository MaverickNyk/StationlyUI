package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * Which station each placed widget is for.
 *
 * ## Why Android does not need — and cannot use — a widget stack
 * iOS solves "several stations" with a WidgetKit stack: one widget frame, many
 * configurations, the user swipes. Android has no such thing (a couple of OEM
 * launchers fake it; the Pixel launcher does not), and reaching for one is
 * looking for the wrong shape. Android's home screen is a free grid and has
 * supported **many instances of one provider** since widgets existed — each
 * with its own `appWidgetId`. Two stations is two widgets, side by side, and
 * that is the whole answer. This file is the map from that id to a station.
 *
 * What Android additionally has, and iOS cannot:
 *
 *  - `AppWidgetManager.getAppWidgetIds()` — the app can enumerate its own placed
 *    widgets. iOS's `getCurrentConfigurations` returns `[]` inside a timeline and
 *    an iOS app can never read a widget's AppIntent configuration at all.
 *  - **Read AND write.** Because this store is ours, a screen inside the app can
 *    re-point any widget at another board without the user touching the home
 *    screen (AV2-5.2). On iOS that is long-press-and-edit or nothing.
 *  - `requestPinAppWidget` — the app can ask the launcher to place a widget
 *    already bound to a chosen station, so "add a widget for this station" can
 *    be a button on the station itself rather than a hunt through the widget
 *    gallery.
 *
 * ## What the value is, and why it is not (station, line, direction)
 * The value is a [com.stationly.core.model.UserSelection.groupingId] — the HUB
 * the user picked, which is what the home screen keys a card on and what a user
 * means by "a station". Keying on the full board triple would bind a widget to
 * one direction of one line, so a user who later edits that board's lines would
 * find their widget silently unbound.
 *
 * ## Never guess
 * A widget whose binding is missing, or whose station is no longer one of the
 * user's boards, renders an honest empty state. It does not fall back to "the
 * first station" — that is [com.stationly.mobile.widget.DepartureWidgetProvider]'s
 * old behaviour and it is the single worst thing a departure board can do: show
 * somebody a train that is not theirs, at a stop they are not standing at, with
 * no indication anything is wrong.
 */
object WidgetBindingStore {

    /** The same file the manual-refresh debounce uses. */
    internal const val PREFS = "widget_prefs"

    /** `binding_<appWidgetId>` → grouping id. */
    internal const val KEY_PREFIX = "binding_"

    /**
     * `owner_<appWidgetId>` -> the uid that placed this widget.
     *
     * ## Why a stamp and not a namespace
     * `widget_prefs` is not keyed by uid the way `UserSettings` is, and no
     * sign-out path clears it: `cleanupAll` and `FirebaseAuthManager.logout`
     * wipe SQL, topics, widget CONTENT and `StationlyPrefs`, and none of them
     * touches this file. So a binding survives a logout, and if the NEXT
     * account happens to track the same hub the widget silently re-attaches and
     * comes back live in the previous person's configuration.
     *
     * Clearing the file on sign-out would fix that and break the common case:
     * somebody signing back in as themselves would find every widget blank for
     * no reason they can see. So the binding is stamped instead. Signing back
     * in matches and restores; another account does not match and gets the
     * honest empty state, which is the same state this package already shows
     * for a station that is no longer a board.
     */
    internal const val OWNER_PREFIX = "owner_"

    internal fun ownerKeyFor(appWidgetId: Int) = "$OWNER_PREFIX$appWidgetId"

    /**
     * The account signed in right now, read the way everything else reads it.
     *
     * Blocking, and that is safe for the same reason the widget's other prefs
     * reads are: on Android `loadString` is a `SharedPreferences` get wearing a
     * `suspend` modifier, with no dispatcher switch and nothing that can
     * actually suspend. It is here rather than at each call site so that
     * "who is asking" has one answer in this package.
     */
    fun currentUid(): String? = runCatching {
        kotlinx.coroutines.runBlocking { com.stationly.core.session.SessionStore.uid() }
    }.getOrNull()

    /**
     * Whether the account signed in now may see this widget.
     *
     * **Only a mismatch when BOTH are known and they differ.** An absent OWNER
     * is a widget placed before the stamp existed, and blanking every one of
     * those on upgrade would be a worse bug than the one being fixed. An absent
     * CURRENT is either signed out or the window between a launch and the
     * identity landing, and rejecting there would make widgets blink empty on
     * every cold start.
     */
    fun isOwnedBy(owner: String?, current: String?): Boolean =
        owner.isNullOrBlank() || current.isNullOrBlank() || owner == current

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Point [appWidgetId] at the board grouped under [groupingId]. */
    fun bind(context: Context, appWidgetId: Int, groupingId: String, uid: String?) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        prefs(context).edit()
            .putString(KEY_PREFIX + appWidgetId, groupingId)
            .putString(ownerKeyFor(appWidgetId), uid.orEmpty())
            .apply()
    }

    /**
     * The grouping id this widget is for, or null if it was never bound OR
     * belongs to another account.
     *
     * [currentUid] is the account asking, and it has NO DEFAULT on purpose: a
     * default would let a new call site skip the check silently and the
     * ownership stamp would be decorative. Pass [currentUid] where it genuinely
     * is not known; see [isOwnedBy] for why that is not a mismatch.
     *
     * An unstamped widget is ADOPTED here rather than merely tolerated: it is
     * stamped on this read, so the next account change is caught properly
     * instead of the widget staying permanently unowned and permanently
     * inheritable.
     */
    fun boundStation(context: Context, appWidgetId: Int, currentUid: String?): String? {
        val p = prefs(context)
        val owner = p.getString(ownerKeyFor(appWidgetId), null)
        if (!isOwnedBy(owner, currentUid)) return null
        val station = p.getString(KEY_PREFIX + appWidgetId, null)?.takeIf { it.isNotBlank() }
            ?: return null
        if (owner.isNullOrBlank() && !currentUid.isNullOrBlank()) {
            p.edit().putString(ownerKeyFor(appWidgetId), currentUid).apply()
        }
        return station
    }

    /** Every binding this device holds, keyed by `appWidgetId`. */
    fun all(context: Context, currentUid: String?): Map<Int, String> {
        val p = prefs(context)
        return p.all.mapNotNull { (key, value) ->
            // `owner_` also starts with no shared prefix, but `page_`, `wpin_`
            // and `wnav_` live in this file too — so the id is taken only from
            // keys that really are bindings.
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val id = key.removePrefix(KEY_PREFIX).toIntOrNull() ?: return@mapNotNull null
            val station = (value as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val owner = p.getString(ownerKeyFor(id), null)
            if (!isOwnedBy(owner, currentUid)) return@mapNotNull null
            id to station
        }.toMap()
    }

    /**
     * Forget these widgets. Called from `onDeleted`, which Android delivers when
     * the user drags a widget off the home screen.
     */
    fun unbind(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        prefs(context).edit().apply {
            appWidgetIds.forEach { remove(KEY_PREFIX + it); remove(ownerKeyFor(it)) }
        }.apply()
    }

    /**
     * Drop bindings for widgets that no longer exist, whatever the reason.
     *
     * `onDeleted` is the normal path and is usually enough. This is the backstop
     * for the cases where it is not: a launcher replaced or its data cleared, a
     * restore from backup onto a home screen that never had these widgets, or a
     * broadcast dropped while the app was force-stopped. Without it the store
     * accumulates ids forever and the in-app manager (AV2-5.2) lists widgets
     * that are not on any home screen.
     *
     * iOS has the same problem in a worse form — reinstalls left phantom widget
     * registrations that only a device REBOOT cleared, and the only honest count
     * came from what was observed rather than what the system claimed.
     * `getAppWidgetIds()` is more trustworthy than that, so on Android the
     * system's list is the authority and this simply agrees with it.
     */
    /**
     * Every binding in the file, ownership ignored.
     *
     * Only for housekeeping. Anything that DISPLAYS a widget must go through
     * [all] or [boundStation] with the account asking.
     */
    private fun allRegardlessOfOwner(context: Context): Map<Int, String> =
        prefs(context).all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val id = key.removePrefix(KEY_PREFIX).toIntOrNull() ?: return@mapNotNull null
            val station = (value as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to station
        }.toMap()

    fun prune(context: Context) {
        val live = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, DepartureWidgetProvider::class.java))
            .toSet()
        // EVERY binding, whoever owns it. Pruning is about which widgets still
        // exist on the home screen, not about who may see them: filtering by
        // the current account here would leave another account's dead bindings
        // in the file forever, which is the accumulation this exists to stop.
        val dead = allRegardlessOfOwner(context).keys.filterNot { it in live }
        if (dead.isEmpty()) return
        unbind(context, dead.toIntArray())
    }
}
