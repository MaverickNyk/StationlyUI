package com.stationly.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService

/**
 * Put a widget for THIS station on the home screen, without the user going to
 * find the widget gallery.
 *
 * ## Why this is the front door on Android, and cannot exist on iOS
 * The moment somebody wants a widget is the moment they are looking at a
 * station — not later, in a settings screen, having remembered that widgets
 * exist. iOS has no way to act on that: an iOS app cannot place a widget, so its
 * flow is unavoidably "go to the home screen, long-press, find us in the
 * gallery, then configure". Android has `requestPinAppWidget`, so the app can
 * ask the launcher to place one, and the user gets a confirm dialog with a
 * preview instead of a scavenger hunt.
 *
 * With this, most people never see a station picker at all: they tap "Add to
 * Home Screen" on the station they were already reading, accept, and the widget
 * arrives showing it.
 *
 * ## How the station survives the trip
 * `requestPinAppWidget` does not let the caller name the new widget's id — the
 * launcher issues it — and the `successCallback` is not told it either. So the
 * chosen station is left HERE, in the same prefs file the bindings live in, and
 * [WidgetConfigureActivity] claims it: the launcher runs the configuration
 * Activity after pinning (we declare `android:configure` and deliberately do NOT
 * declare `configuration_optional`), and that Activity binds the new id to the
 * pending station and closes without asking.
 *
 * ## Why the pending value expires
 * A stale one would bind the NEXT widget the user drags out of the gallery to a
 * station they picked an hour ago — a silent wrong answer, which is the one
 * outcome this package exists to prevent. Two minutes is far longer than the
 * launcher takes to show a dialog and far shorter than a user's memory, and if
 * it lapses the widget simply asks. Asking is always safe; guessing is not.
 */
object WidgetPinner {

    private const val KEY_PENDING_STATION = "pending_pin_station"
    private const val KEY_PENDING_AT = "pending_pin_at"

    /**
     * How long a pending pin stays claimable. See the class KDoc — the failure
     * this bounds is binding somebody's next widget to a stale choice.
     */
    private const val PENDING_TTL_MS = 2 * 60_000L

    /**
     * Whether the launcher will accept a pin request at all.
     *
     * Most will; some third-party launchers, and every device below API 26, will
     * not. The caller HIDES the action rather than showing one that does
     * nothing — an "Add to Home Screen" button that silently fails is worse than
     * no button, because the user concludes the feature is broken rather than
     * absent.
     */
    fun canPin(context: Context): Boolean =
        context.getSystemService<AppWidgetManager>()?.isRequestPinAppWidgetSupported == true

    /**
     * Ask the launcher to place a widget already pointed at [groupingId].
     *
     * Returns false when the launcher declined to even show its dialog, so the
     * caller can fall back to telling the user how to add one by hand.
     *
     * The user still confirms: this raises the launcher's own dialog with a
     * preview. An app cannot put things on someone's home screen unasked, which
     * is correct.
     */
    fun pin(context: Context, groupingId: String): Boolean {
        val manager = context.getSystemService<AppWidgetManager>() ?: return false
        if (!manager.isRequestPinAppWidgetSupported) return false

        context.getSharedPreferences(WidgetBindingStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_STATION, groupingId)
            .putLong(KEY_PENDING_AT, System.currentTimeMillis())
            .apply()

        // A callback so the app knows the pin landed. It carries no widget id —
        // the platform does not provide one — so it exists to let us drop the
        // pending value promptly rather than wait out the TTL, and to give the
        // user a confirmation in the app.
        val callback = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DepartureWidgetProvider::class.java)
                .setAction(DepartureWidgetProvider.ACTION_UPDATE_WIDGET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return manager.requestPinAppWidget(
            ComponentName(context, DepartureWidgetProvider::class.java),
            null,
            callback,
        )
    }

    /**
     * Take the pending station, if there is a live one. Read-once: a second
     * widget placed straight afterwards must ask, not inherit.
     */
    fun claimPending(context: Context): String? {
        val prefs = context.getSharedPreferences(WidgetBindingStore.PREFS, Context.MODE_PRIVATE)
        val station = prefs.getString(KEY_PENDING_STATION, null)?.takeIf { it.isNotBlank() }
        val at = prefs.getLong(KEY_PENDING_AT, 0L)
        clearPending(context)

        if (station == null) return null
        val age = System.currentTimeMillis() - at
        // `age < 0` catches a clock moved backwards, which would otherwise make
        // a stale value look infinitely fresh.
        return if (age in 0..PENDING_TTL_MS) station else null
    }

    fun clearPending(context: Context) {
        context.getSharedPreferences(WidgetBindingStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_STATION)
            .remove(KEY_PENDING_AT)
            .apply()
    }
}
