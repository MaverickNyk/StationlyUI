package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.Platform
import com.stationly.core.platform.V1Upgrade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Give every widget placed by v1 the station it was already showing.
 *
 * v1 had no bindings: its provider drew `getAllSelections().first()` on every
 * widget. v2 never guesses a station for an unbound widget ([WidgetBindingStore])
 * so, left alone, every v1 widget turned into "Choose a station" on the update.
 *
 * This is not the guess that rule forbids. The first selection is, literally,
 * what the widget had on screen a moment before. It runs once per device, only
 * after a real v1 database was migrated ([V1Upgrade]); a widget that loses or
 * never gets a station at any other time still says so.
 */
object V1WidgetAdoption {

    /** The (widget id, station) pairs to write. Pure. */
    internal fun plan(
        placed: List<Int>,
        alreadyBound: Set<Int>,
        selections: List<UserSelection>,
    ): Map<Int, String> {
        val station = selections.firstOrNull()?.groupingId ?: return emptyMap()
        return placed.filterNot { it in alreadyBound }.associateWith { station }
    }

    /**
     * Where each binding moved to when a v1 row gained its hub. Pure.
     *
     * Only for a binding whose old group is GONE and whose board is still here
     * under another one; anything else is left alone.
     */
    internal fun moves(
        bound: Map<Int, String>,
        before: List<UserSelection>,
        after: List<UserSelection>,
    ): Map<Int, String> {
        val groups = after.map { it.groupingId }.toSet()
        return bound.filterValues { it !in groups }.mapNotNull { (id, old) ->
            val boards = before.filter { it.groupingId == old }.map { it.boardKey }.toSet()
            after.firstOrNull { it.boardKey in boards }?.let { id to it.groupingId }
        }.toMap()
    }

    /**
     * Spend the note in the first process after the update.
     *
     * Not left to the first widget draw alone: a phone with no widgets draws
     * none, so the note would wait there and bind the first widget its owner
     * ever added to their first station, not the one they chose.
     */
    fun onAppStart(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // The read opens, and so migrates, the database that leaves the note.
            runCatching { adoptIfPending(context, Platform.sqlStorage.getAllSelections()) }
                .onFailure { Log.w("Widget", "v1 upgrade: widgets not adopted yet, next draw retries", it) }
        }
    }

    /**
     * Whether the note has to wait: v1 left widgets here but there is no station
     * to give them yet (signed out at the update). With no widgets there is
     * nothing to wait for; the next one is v2's, and v2 asks. Pure.
     */
    internal fun mustWait(placed: List<Int>, selections: List<UserSelection>): Boolean =
        placed.isNotEmpty() && selections.isEmpty()

    /**
     * Free when there is nothing to do: one preference read.
     *
     * Synchronized because the app start and a widget draw can arrive together.
     */
    @Synchronized
    fun adoptIfPending(context: Context, selections: List<UserSelection>) {
        if (!V1Upgrade.widgetsPending(context)) return
        val placed = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, DepartureWidgetProvider::class.java))
            .toList()
        if (mustWait(placed, selections)) return
        val plan = plan(placed, WidgetBindingStore.boundIds(context), selections)
        val uid = WidgetBindingStore.currentUid()
        plan.forEach { (id, station) -> WidgetBindingStore.bind(context, id, station, uid) }
        V1Upgrade.widgetsDone(context)
        Log.d("Widget", "v1 upgrade: ${plan.size} widget(s) keep ${plan.values.firstOrNull()}")
        if (plan.isNotEmpty()) WidgetPlacementProbe.observe(context)
    }

    /** Re-point this account's bindings after [moves]. */
    fun followHubs(context: Context, before: List<UserSelection>, after: List<UserSelection>) {
        val uid = WidgetBindingStore.currentUid()
        moves(WidgetBindingStore.all(context, uid), before, after).forEach { (id, station) ->
            WidgetBindingStore.bind(context, id, station, uid)
        }
    }
}
