package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.UserSelection
// ── ⚠️ Editing THIS FILE breaks the incremental compile. Rebuild the module. ──
//
// Change one character in `DepartureWidgetProvider.kt` — a comment will do —
// and the next `:android:app:compileStagingDebugKotlin` reports the three
// top-level functions below as unresolved references, on a tree that built
// cleanly a second earlier. It is not your edit. The fix is a full module
// recompile:
//
//     rm -rf android/app/build/kotlin android/app/build/tmp/kotlin-classes
//     ./gradlew :android:app:compileStagingDebugKotlin
//
// Characterised rather than guessed at, because it cost an hour once. Ruled
// OUT, each by a deliberate experiment on a freshly rebuilt tree:
//
//   · fully-qualified names vs these imports — fails identically either way,
//     and with the imports it fails ON the import lines
//   · the Gradle build cache — fails the same with `--no-build-cache`
//   · `@JvmStatic` on the companion member below — fails without it
//   · deleted sibling packages / stale outputs — fails after a clean rebuild
//   · the module in general — editing `WidgetBindingStore.kt` in the same
//     package is fine, so it is this file
//
// The imports stay because seven fully-qualified call sites read worse, not
// because they help.
import com.stationly.mobile.ui.util.BoardFallbackKind
import com.stationly.mobile.ui.util.BoardFallbackState
import com.stationly.mobile.ui.util.NetworkState
import com.stationly.mobile.ui.util.StationStripFitter
import com.stationly.mobile.ui.util.buildFallbackRowRemoteViews
import com.stationly.mobile.ui.util.computeBoardFallbackState
import com.stationly.mobile.ui.util.tickPredictions
import com.stationly.core.util.StationlyFormatters
import com.stationly.mobile.R
import com.stationly.mobile.util.HomeConfigStore
import com.stationly.mobile.util.ModeColors
import com.stationly.mobile.util.ModeIconCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DepartureWidgetProvider - Android Home Screen Widget
 * 
 * This is the EXACT same widget implementation as MindTheTimeAndroid,
 * but adapted to use KMP core for data processing.
 * 
 * Key features preserved:
 * - Authentic London departure board layout
 * - Platform grouping
 * - ETA formatting ("Due", "X min")
 * - Line status display
 * - Click-to-open app
 * - Real-time updates via FCM
 */
class DepartureWidgetProvider : AppWidgetProvider() {
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                updateFromStorage(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
    
    /**
     * The user dragged these widgets off the home screen. Forget what they were
     * for, or the store accumulates dead ids forever and the in-app manager
     * (AV2-5.2) lists widgets that are not on any home screen.
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetBindingStore.unbind(context, appWidgetIds)
        // Tell the app, in case it is open behind the home screen — the station
        // screen's delete warning and the SDUI `widget.count` fact both read
        // this, and both would otherwise describe a widget the user has just
        // dragged off. The foreground probe catches it eventually; this makes it
        // immediate when the app is alive to care.
        WidgetPlacementProbe.observe(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Last widget removed from the home screen — drop the watchdog
        // so we're not waking the device for a UI surface that no
        // longer exists. (Other alarms — timer-colour, etc. — fire
        // once and self-terminate; only the rescheduling watchdog
        // would leak.)
        cancelEtaTickWatchdog(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_ETA_TICK -> {
                // Watchdog fired — FCM has been silent for ≥ 90s.
                // Re-render from SQL so the ETAs catch up to the wall
                // clock, then reschedule the next watchdog (handled
                // automatically by updateFromStorage → updateAppWidget
                // → scheduleEtaTickWatchdog).
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        updateFromStorage(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
                return
            }
        }
        val actions = listOf(ACTION_UPDATE_WIDGET, ACTION_MANUAL_REFRESH)
        if (intent.action in actions) {
            // Debounce the user-tap path. The btn_refresh PendingIntent
            // fires this action on every tap with no spam protection of
            // its own — and TfL rate-limits aggressive callers — so we
            // gate the refresh on at least MANUAL_REFRESH_DEBOUNCE_MS
            // since the last successful one. ACTION_UPDATE_WIDGET (the
            // programmatic-redraw path from AndroidWidgetManager) is
            // exempt because it doesn't hit the backend.
            if (intent.action == ACTION_MANUAL_REFRESH) {
                val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                val lastRefresh = prefs.getLong("last_refresh_ms", 0L)
                if (System.currentTimeMillis() - lastRefresh < MANUAL_REFRESH_DEBOUNCE_MS) {
                    android.util.Log.d(
                        "Widget",
                        "Manual refresh debounced — last fired ${(System.currentTimeMillis() - lastRefresh) / 1000}s ago"
                    )
                    return
                }
                prefs.edit().putLong("last_refresh_ms", System.currentTimeMillis()).apply()
            }
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    if (intent.action != ACTION_UPDATE_WIDGET) {
                        showRefreshSpinner(context)
                        val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
                        val repo = com.stationly.core.repository.DepartureRepository(
                            com.stationly.core.service.TflApiServiceFactory.create(),
                            com.stationly.core.platform.Platform.storageManager,
                            com.stationly.core.platform.Platform.sqlStorage,
                            com.stationly.core.usecase.SyncPredictionsUseCase(com.stationly.core.platform.Platform.sqlStorage)
                        )
                        selections.forEach { selection ->
                            repo.fetchInitialData(selection)
                            // Same fan-out the FCM service uses — tells the
                            // app's board and the dream to re-read SQL, then
                            // redraws the widget. Without this, tapping the
                            // widget's refresh button would update only the
                            // widget; an open app or active dream would stay
                            // on stale data until the next FCM landed.
                            com.stationly.mobile.util.FreshDataNotifier.notifyPredictions(
                                context,
                                stationId = selection.station,
                            )
                        }
                    } else {
                        // ACTION_UPDATE_WIDGET path: someone (typically the
                        // SummaryViewModel via AndroidWidgetManager) wants
                        // us to redraw from the current SQL state. No
                        // backend fetch involved, no other surfaces to
                        // notify — just paint.
                        updateFromStorage(context)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Widget", "Error during refresh", e)
                    updateFromStorage(context)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
    
    companion object {
        const val ACTION_UPDATE_WIDGET = "com.stationly.mobile.ACTION_UPDATE_WIDGET"
        const val ACTION_MANUAL_REFRESH = "com.stationly.mobile.ACTION_MANUAL_REFRESH"

        /**
         * Minimum gap between two `ACTION_MANUAL_REFRESH` broadcasts that
         * will actually round-trip to the backend. A user tapping the
         * widget's refresh button repeatedly used to hit TfL each time
         * — fine until the tenth tap pushed us past the rate-limit cap
         * for the device's outbound IP. 15s is long enough to discourage
         * spam-tapping, short enough that a legitimate "I want fresh
         * data right now" retry isn't ignored.
         */
        const val MANUAL_REFRESH_DEBOUNCE_MS: Long = 15_000L
        /**
         * Watchdog tick — fires only when FCM has gone silent for
         * [ETA_TICK_DEBOUNCE_MS]. On fire, re-renders the widget so the
         * row ETAs catch up to the wall clock (5 min → 4 min etc) and
         * reschedules another watchdog. Every render (FCM-driven OR
         * watchdog-driven OR manual) calls [scheduleEtaTickWatchdog],
         * so a healthy FCM stream (≈ every 30s) keeps pushing the
         * deadline forward and the alarm never actually fires — zero
         * wake-ups in steady state.
         */
        const val ACTION_ETA_TICK = "com.stationly.mobile.ACTION_ETA_TICK"

        /**
         * Programmatic-trigger entry point used by callers outside the
         * widget receiver (boot-completed receiver, timezone-changed
         * receiver, etc.). Sends `ACTION_MANUAL_REFRESH`; the receiver's
         * own debounce ([MANUAL_REFRESH_DEBOUNCE_MS]) is the single
         * source of truth for "did we actually round-trip to the
         * backend". Don't duplicate the debounce here — they'd race on
         * the SharedPrefs key and one would always win.
         */
        fun triggerRefresh(context: Context) {
            context.sendBroadcast(Intent(context, DepartureWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
            })
        }
        
        /**
         * Schedule (or replace) the ETA watchdog. The alarm fires at
         * the next wall-clock minute boundary so the widget ticks at
         * the same instant as the home + dream rows — visual parity is
         * the whole point. Same PendingIntent + FLAG_UPDATE_CURRENT
         * means every render cancels any pending alarm and installs a
         * fresh one (debounce semantics): a healthy FCM stream (every
         * ~30s) keeps pushing the deadline past the next FCM render,
         * so the alarm rarely fires in steady state.
         *
         * Uses inexact `set()` rather than `setExact*` for two reasons:
         *   - `setExactAndAllowWhileIdle` and `setExact` require the
         *     `SCHEDULE_EXACT_ALARM` permission on API 31+, which we
         *     deliberately don't request — minute-precision is a UX
         *     polish, not a clinical-grade trigger.
         *   - Inexact alarms get batched with other system alarms, so
         *     the OS coalesces wake-ups. On modern Android this lands
         *     within a few seconds of the requested time, which is
         *     well under the user's "is it ticking?" perception
         *     threshold.
         */
        private fun scheduleEtaTickWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pendingIntent = etaTickPendingIntent(context)
            val fireAtRtc = nextWatchdogFireRtc()
            val deltaMs = (fireAtRtc - System.currentTimeMillis()).coerceAtLeast(1_000L)
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + deltaMs,
                pendingIntent,
            )
        }

        /**
         * Next wall-clock minute boundary that's at least 30s in the
         * future. If we're already within 30s of the next minute, skip
         * to the one after — that gives FCM a debounce window to land
         * before the watchdog fires (the typical Syncer cadence is
         * ~30s). Result is always between 30s and 90s out, always on
         * a round minute.
         */
        private fun nextWatchdogFireRtc(): Long {
            val now = System.currentTimeMillis()
            val nextMinute = ((now / 60_000L) + 1L) * 60_000L
            return if (nextMinute - now >= 30_000L) nextMinute else nextMinute + 60_000L
        }

        private fun cancelEtaTickWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.cancel(etaTickPendingIntent(context))
        }

        private fun etaTickPendingIntent(context: Context): android.app.PendingIntent =
            android.app.PendingIntent.getBroadcast(
                context, 12,
                Intent(context, DepartureWidgetProvider::class.java).apply {
                    action = ACTION_ETA_TICK
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )

        fun showRefreshSpinner(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DepartureWidgetProvider::class.java)
            )
            val views = RemoteViews(context.packageName, com.stationly.mobile.R.layout.widget_departure_board)
            views.setViewVisibility(com.stationly.mobile.R.id.btn_refresh, android.view.View.GONE)
            views.setViewVisibility(com.stationly.mobile.R.id.progress_refresh, android.view.View.VISIBLE)
            for (id in ids) appWidgetManager.partiallyUpdateAppWidget(id, views)
        }

        /**
         * Redraw every placed widget, each from the station it is bound to.
         *
         * ## What this used to do, and why it had to change
         * It read `selections.first()` and pushed the SAME board to every widget
         * id. Two widgets meant two copies of one station — the multi-station
         * story did not exist, and a widget could not be wrong because it was
         * never right about anything in particular.
         *
         * Android does not need a widget stack for this, and there is no point
         * looking for one: the home screen is a free grid and has always
         * supported many instances of one provider, each with its own
         * `appWidgetId`. Two stations is two widgets. See [WidgetBindingStore]
         * for the map from id to station and for what Android can do here that
         * iOS cannot.
         *
         * One `getAllSelections()` for the whole pass rather than one per
         * widget: it is a SQL read and the answer cannot change between two
         * renders in the same loop.
         */
        fun updateFromStorage(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DepartureWidgetProvider::class.java)
            )
            if (appWidgetIds.isEmpty()) return

            // Backstop for bindings whose `onDeleted` never arrived — a
            // launcher replaced, a restore, a broadcast dropped while the app
            // was force-stopped. See WidgetBindingStore.prune.
            WidgetBindingStore.prune(context)

            val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
            for (id in appWidgetIds) {
                renderWidget(context, appWidgetManager, id, selections)
            }
        }

        /**
         * Redraw only the widgets showing the station this push was about.
         *
         * ## Why not just redraw everything
         * A `Station_{naptan}` push arrives every ~30 seconds per tracked stop.
         * The old behaviour drew every placed widget on every one of them, so a
         * phone with four widgets did four full RemoteViews rebuilds — SQL read,
         * tick, platform grouping, row inflation — to change one of them. The
         * other three were re-rendered with the bytes they already had.
         *
         * ## The naptan is not the binding, and this is where that bites
         * The push names the naptan departures were FETCHED from
         * ([UserSelection.station]); a binding holds the HUB the user picked
         * ([UserSelection.groupingId]). On tube they coincide. On bus they do
         * not — every pole has its own naptan, so Smithwood Close resolves route
         * 39 inbound to 490008805N and outbound to 490012211N while the widget
         * is bound to the stop. Matching the push's id against bindings directly
         * would silently never update a single bus widget.
         *
         * So the push's naptan is resolved through the selections to the hubs it
         * feeds, and the widgets bound to those hubs are the ones drawn.
         */
        fun updateForStation(context: Context, pushedStationId: String) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DepartureWidgetProvider::class.java)
            )
            if (appWidgetIds.isEmpty()) return

            val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
            val affectedHubs = WidgetRedrawTargets.hubsFedBy(selections, pushedStationId)
            if (affectedHubs.isEmpty()) return

            val targets = WidgetRedrawTargets.widgetsShowing(
                bindings = appWidgetIds.toList().associateWith { WidgetBindingStore.boundStation(context, it) },
                hubs = affectedHubs,
            )
            for (id in targets) {
                renderWidget(context, appWidgetManager, id, selections)
            }
        }

        /**
         * Redraw exactly ONE widget.
         *
         * For the in-app manager: rebinding widget A must not repaint widget B,
         * which is on screen showing a station that did not change. The full
         * `updateFromStorage` sweep is right after a data change (every board
         * may have moved) and wrong after a binding change (one did).
         */
        fun updateOne(context: Context, appWidgetId: Int) {
            renderWidget(
                context,
                AppWidgetManager.getInstance(context),
                appWidgetId,
                com.stationly.core.platform.Platform.sqlStorage.getAllSelections(),
            )
        }

        /**
         * Draw ONE widget, for the station it is bound to and no other.
         *
         * An unbound widget, or one whose station is no longer among the user's
         * boards, renders the honest empty state and offers the configuration
         * screen. It does **not** fall back to the first station: showing
         * somebody a train that is not theirs, at a stop they are not standing
         * at, with nothing on screen to say so, is the worst thing a departure
         * board can do. That rule is why the binding exists at all.
         */
        private fun renderWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            selections: List<UserSelection>,
        ) {
            val boundTo = WidgetBindingStore.boundStation(context, appWidgetId)
            // The board's selections, in the user's own order. A hub can hold
            // several (lines, directions); the widget renders the first, which
            // is the depth v1 had. Rendering a whole multi-line hub in
            // RemoteViews is a rendering change, not a binding one — AV2-5.3.
            val selection = selections.firstOrNull { it.groupingId == boundTo }

            if (selection == null) {
                // ── "Missing" and "being rewritten" are not the same answer ──
                //
                // A cross-device reconcile discards boards and sets them up
                // again, and between those two calls the bound hub genuinely is
                // not in the table. A redraw landing in that gap drew a widget
                // with a perfectly good binding as "Choose a station" — seen on a
                // Pixel 7 Pro, mid-reconcile, in the release build:
                //
                //   Updating widget 6 for Hackney Wick Rail Station with 6 departures
                //   Updating widget 6 for Stationly with 0 departures (UNBOUND)   <- here
                //   Updating widget 6 for Hackney Wick Rail Station with 6 departures
                //
                // Leaving the last good render on screen is the right answer:
                // stale by a second beats telling somebody their board is gone.
                // The rewrite says so itself — `WidgetRestore`, which the login
                // restore has always used and which `reconcileBoards` now does.
                if (com.stationly.core.platform.WidgetRestore.inProgress) {
                    android.util.Log.d(
                        "Widget",
                        "Widget $appWidgetId left as-is — board list is mid-rewrite",
                    )
                    return
                }
                updateAppWidget(
                    context, appWidgetManager, appWidgetId,
                    isBound = false,
                    hasAnyBoard = selections.isNotEmpty(),
                )
                return
            }

            val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)

            // ── THE WHOLE STATION, not the first board at it ────────────────
            //
            // A binding names a HUB, and a hub can carry several boards: both
            // directions of one line, or four lines at one interchange. Until
            // now the widget resolved the binding to `selections.first { … }`
            // and drew that one — so a user tracking Royal Victoria in both
            // directions had a widget showing half of what the app showed them,
            // with nothing on it to say the other half existed. It looked
            // correct in every screenshot ever taken of it, because a widget
            // showing one platform looks exactly like a widget that only knows
            // about one platform.
            //
            // The fix is not a widget-shaped one. `MultiLineBoardProcessor` is
            // what the home screen and the screensaver already render from, and
            // it takes exactly this: one `Feed` per (pole, line, direction),
            // grouped into platform blocks, ordered, labelled and padded. The
            // widget feeds it the same way, so the three surfaces cannot
            // disagree about what a station looks like.
            val boardSelections = selections.filter { it.groupingId == boundTo }
            val nowMs = System.currentTimeMillis()

            // Re-derive each row's `eta` from its absolute `targetEpochMs`
            // against the current wall clock AND drop rows whose train
            // has already departed (more than 60s past target). Single
            // helper shared with the Compose-side tick layer so home +
            // dream + widget cannot drift — same formula, same threshold,
            // same source of truth. See PredictionTicker.tickPredictions.
            val feeds = boardSelections.map { sel ->
                val raw = com.stationly.core.platform.Platform.sqlStorage
                    .getPredictions(sel.station, sel.line, sel.direction)
                com.stationly.core.util.MultiLineBoardProcessor.Feed(
                    stationId = sel.station,
                    line = sel.line,
                    direction = sel.direction,
                    predictions = com.stationly.core.util.StationlyFormatters.sortPredictions(
                        tickPredictions(raw, nowMs)
                    ),
                )
            }

            // Rows per platform: three, unless this station asks for more.
            //
            // This briefly varied with the widget's HEIGHT — 2 on a short one, 4
            // on a tall one — which was a nice idea and the wrong one. Height
            // decides how many BLOCKS you can see at once, which the scroll
            // already handles; it does not get to decide how deep a platform is,
            // because a widget showing fewer departures than the app for the
            // same platform is not a smaller widget, it is a widget missing
            // trains.
            //
            // Then it was a hard 3, which was wrong in the other direction: the
            // station's own "Show up to N per platform" already exists and the
            // home screen and screensaver obey it. Three is its DEFAULT. Reading
            // the board keeps the product rule for everyone and keeps the
            // settings screen's promise to whoever changed it.
            //
            // `runBlocking` around a suspend read, deliberately. Android's
            // `loadDurable` is a plain SharedPreferences get wearing a `suspend`
            // modifier — no dispatcher switch, no I/O wait, nothing that can
            // actually suspend — so this completes without ever parking a
            // thread. It is here because `renderWidget` is reached from a
            // broadcast receiver AND from `FreshDataNotifier`, and making the
            // whole chain suspend to carry one prefs read would be a larger
            // change than the thing it enables. The surrounding code already
            // reads SQL synchronously on this thread, so the contract is
            // unchanged: callers are off the main thread already.
            val rowCap = kotlinx.coroutines.runBlocking {
                com.stationly.core.repository.UserSettings.ensureLoaded()
                // Keyed on the grouping id — the HUB — which is exactly what
                // `configOf` is keyed on everywhere else (the station settings
                // screen passes the same thing). `selection.groupingId` rather
                // than `boundTo` only because the latter is nullable here and
                // they are the same string by construction.
                rowCapFor(com.stationly.core.repository.UserSettings.configOf(selection.groupingId))
            }
            val boardRows = com.stationly.core.util.MultiLineBoardProcessor.rowsFrom(
                com.stationly.core.util.MultiLineBoardProcessor.buildGroups(
                    feeds = feeds,
                    isBus = com.stationly.core.util.MultiLineBoardProcessor.isBus(selection.mode),
                    rowCap = rowCap,
                ),
                rowCap = rowCap,
            )

            // Still the flat list, for the three things that are not rows: has
            // anything loaded, the SDUI template binding, and the fallback
            // state. Union across every board at this hub, so a station with one
            // quiet line and one busy one reads as having data.
            val predictions = com.stationly.core.util.GlobalBoardProcessor
                .processPredictions(feeds.flatMap { it.predictions }, perPlatformCap = rowCap)

            // ── One status line for a board that may carry several ──
            //
            // `LineStatusRanker.rotation` is the home screen's rule: disrupted
            // lines worst-first, de-duplicated on (severity, reason) because the
            // sub-surface lines share track and share incidents, and a single
            // spoken entry when everything is healthy — "Good Service" four
            // times tells you nothing four times. The widget has room for one,
            // so it takes the first, which is the worst.
            val statusEntries = boardSelections.distinctBy { it.mode to it.line }.mapNotNull { sel ->
                com.stationly.core.platform.Platform.sqlStorage
                    .getLineStatus(sel.mode, sel.line)
                    ?.let {
                        com.stationly.core.util.LineStatusRanker.Entry(
                            lineLabel = com.stationly.core.util.LineShortNames.displayName(sel.line),
                            severity = it.statusSeverityDescription,
                            reason = it.reason.orEmpty(),
                        )
                    }
            }
            val worst = com.stationly.core.util.LineStatusRanker.rotation(statusEntries).firstOrNull()
            val lineStatusSeverity: String? = worst?.let {
                com.stationly.core.util.LineStatusRanker.label(it)
            }
            val lineStatusReason: String? = worst?.reason

            // Pull the SQL row timestamp so the chronometer reflects when
            // FCM/REST last gave us this data — not when this redraw fired.
            // The NEWEST across the hub's boards: the timer means "when did we
            // last hear anything about this station", and taking the first
            // board's would make a station look stale because one of its lines
            // is quiet.
            val lastUpdatedMs = boardSelections.mapNotNull { sel ->
                com.stationly.core.platform.Platform.sqlStorage
                    .getLastUpdatedTimestamp(sel.station, sel.line, sel.direction)
            }.maxOrNull() ?: System.currentTimeMillis()

            var sduiPayload: com.stationly.core.model.sdui.SduiWidgetPayload? = null
            val sduiJson = prefs.getString("sdui_layout_${selection.station}", null)
            if (sduiJson != null) {
                try {
                    val format = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    sduiPayload = format.decodeFromString<com.stationly.core.model.sdui.SduiWidgetPayload>(sduiJson)
                } catch (e: Exception) {
                    android.util.Log.w("Widget", "Failed to parse SDUI layout for ${selection.station}", e)
                }
            }

            if (sduiPayload != null && predictions.isNotEmpty()) {
                sduiPayload = com.stationly.core.util.GlobalBoardProcessor.bindSduiTemplate(
                    sduiPayload,
                    predictions,
                    lineStatusSeverity,
                    lineStatusReason
                )
            }

            updateAppWidget(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetId = appWidgetId,
                stationName = selection.stationName,
                lineName = selection.line.replaceFirstChar { it.uppercase() },
                predictions = predictions,
                lineStatusSeverity = lineStatusSeverity,
                lineStatusReason = lineStatusReason,
                sduiPayload = sduiPayload,
                hasLoadedData = predictions.isNotEmpty(),
                lastUpdatedMs = lastUpdatedMs,
                isBound = true,
                hasAnyBoard = true,
                mode = selection.mode,
                boundSelection = selection,
                boardRows = boardRows,
            )
        }

        /**
         * Departures drawn per platform block — three, unless the station says
         * otherwise.
         *
         * This is the DEFAULT and not a number the widget owns. Every station
         * carries `BoardConfig.rowsPerPlatform` ("Show up to N per platform",
         * 2 to 5), which the home screen and the screensaver already obey. Read
         * it here too, via [rowCapFor], or the widget silently overrides a
         * setting the app made a promise about: set a station to 5 and the app
         * shows five while the home screen shows three, with nothing to explain
         * the difference.
         *
         * The product rule and the setting are the same answer — 3 is what the
         * setting says when nobody has touched it — so reading it from the board
         * satisfies both.
         */
        const val ROWS_PER_PLATFORM = BoardConfig.DEFAULT_ROWS_PER_PLATFORM

        /**
         * How deep each platform goes on the widget for this board.
         *
         * Through `rowCap` rather than the raw field, which clamps: storage is
         * not trusted, and a hand-edited 40 would otherwise build forty rows per
         * platform into a RemoteViews collection.
         */
        fun rowCapFor(config: BoardConfig): Int = config.rowCap

        /**
         * Update a single widget instance
         * This mirrors the MindTheTimeAndroid implementation exactly
         */
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            stationName: String = "Stationly",
            lineName: String = "",
            predictions: List<PredictionDisplay> = emptyList(),
            lineStatusSeverity: String? = null,
            lineStatusReason: String? = null,
            sduiPayload: com.stationly.core.model.sdui.SduiWidgetPayload? = null,
            hasLoadedData: Boolean = true,
            /**
             * Wall-clock millis when the data was last synced from the
             * backend. Drives the "X ago" timer. Defaults to "now" for
             * the empty/initial-render path; real refreshes pass the SQL
             * row timestamp so the counter stays honest even if the
             * widget was redrawn long after the last FCM landed.
             */
            lastUpdatedMs: Long = System.currentTimeMillis(),
            /**
             * Whether THIS widget is pointed at a station the user still has.
             * False renders the "which station?" state and points the tap at
             * the configuration screen. See [WidgetBindingStore].
             */
            isBound: Boolean = false,
            /** Whether the ACCOUNT has any boards — a different question, and a
             *  different empty state ("pick a station" vs "which station?"). */
            hasAnyBoard: Boolean = false,
            /** The bound board's mode, for the platform-header line prefix and
             *  the mode roundel. Passed in rather than guessed from the first
             *  selection, which is how a widget used to wear another station's
             *  roundel. */
            mode: String? = null,
            /** The bound board, for the one query that needs its full identity. */
            boundSelection: UserSelection? = null,
            /**
             * The WHOLE station's board, already grouped into platform blocks by
             * `MultiLineBoardProcessor` — the same rows the home screen draws.
             *
             * Null for every caller that is not rendering a bound board (the
             * waiting state, the clear, the initial render), which is why this is
             * optional rather than a parameter every call site has to answer.
             * When it is null the legacy single-line path below runs exactly as
             * it did.
             */
            boardRows: List<com.stationly.core.util.MultiLineBoardProcessor.Row>? = null,
        ) {
            // Says what it DREW, not just how many rows it had.
            //
            // "with 6 departures" was true of a widget showing one platform and
            // of one showing three, which is exactly the distinction that
            // mattered when this widget rendered only the first board at a hub:
            // the log looked identical either way, so the defect was invisible
            // from a device log as well as from a screenshot. The platform count
            // is the one number that separates them.
            val platformCount = boardRows
                ?.count { it is com.stationly.core.util.MultiLineBoardProcessor.Row.PlatformHeader }
            android.util.Log.d(
                "Widget",
                "Updating widget $appWidgetId for $stationName with " +
                    "${predictions.size} departures" +
                    (platformCount?.let { " across $it platform(s)" } ?: "") +
                    if (!isBound) " (UNBOUND)" else "",
            )

            val views = RemoteViews(context.packageName, R.layout.widget_departure_board)
            // `hasSelection` asks whether the ACCOUNT has boards; `isBound` asks
            // whether THIS widget has one. Both were previously derived from a
            // single `getAllSelections().isNotEmpty()` inside this function —
            // one SQL read per widget per redraw, answering neither question.
            val hasSelection = hasAnyBoard
            val resolvedMode = mode
            val sduiStrings = HomeConfigStore.read(context)
            val linePrefix = StationlyFormatters.formatLinePrefix(resolvedMode, lineName, sduiStrings)

            // Station strip stays as just the station name — line context
            // belongs on the platform row per the agreed signage layout.
            views.setTextViewText(R.id.line_name, stationName)

            // Size the strip's `maxEms` to the widget cell's actual width.
            // The XML default (18) is calibrated for the smallest 2-cell
            // case; on a 4-cell or fold-out cell we want the station name
            // to use the room it has instead of truncating "Highbury &
            // Islington Underground" mid-word. AppWidgetManager returns
            // the system-measured cell width in dp via
            // `OPTION_APPWIDGET_MIN_WIDTH` — that's our budget.
            val widgetOptions = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val cellWidthDp = widgetOptions
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val lineNameMaxEms = com.stationly.mobile.ui.util.StationStripFitter
                .maxEmsForWidthDp(cellWidthDp)
            views.setInt(R.id.line_name, "setMaxEms", lineNameMaxEms)

            // Mode roundel on the station strip. Preferred path: the
            // backend-shipped icon cached locally by ModeIconCache during
            // board setup — those are the proper TfL mode marks (tube
            // roundel, bus roundel, DLR train, etc.). Fall back to a
            // tinted generic roundel if the cache isn't populated yet
            // (e.g. user installed and hasn't run selection setup).
            if (hasSelection && resolvedMode != null) {
                views.setViewVisibility(R.id.mode_icon, android.view.View.VISIBLE)
                // TalkBack — announce the mode so the station strip is
                // legible without sight ("Tube", "DLR", "Bus", …).
                views.setContentDescription(
                    R.id.mode_icon,
                    StationlyFormatters.formatModeName(resolvedMode, sduiStrings),
                )
                val cached = ModeIconCache.cachedBitmap(context, resolvedMode)
                if (cached != null) {
                    views.setImageViewBitmap(R.id.mode_icon, cached)
                    // Clear any leftover tint from a prior render pass.
                    views.setInt(R.id.mode_icon, "setColorFilter", 0)
                } else {
                    // Tint precedence: backend-shipped tintHex (via /modes)
                    // > hardcoded ModeColors fallback for offline safety.
                    val tint = ModeIconCache.tintFor(context, resolvedMode)
                        ?: ModeColors.forMode(resolvedMode)
                    views.setImageViewResource(R.id.mode_icon, R.drawable.mode_roundel)
                    views.setInt(R.id.mode_icon, "setColorFilter", tint)
                }
            } else {
                views.setViewVisibility(R.id.mode_icon, android.view.View.GONE)
            }
            
            // Status and timer always visible — layout never shifts
            views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.last_updated_timer, if (hasSelection) android.view.View.VISIBLE else android.view.View.INVISIBLE)

            if (hasSelection) {
                // "X ago" = time since the backend gave us this data, NOT
                // since this widget redraw fired. Anchor the chronometer
                // base so its zero point sits at the wall-clock moment
                // the SQL row was persisted.
                val ageMs = (System.currentTimeMillis() - lastUpdatedMs).coerceAtLeast(0L)
                views.setChronometer(
                    R.id.last_updated_timer,
                    SystemClock.elapsedRealtime() - ageMs,
                    "%s ago",
                    true,
                )
                // Paint the chronometer colour for the data's true age.
                // The watchdog (`scheduleEtaTickWatchdog` below) re-renders
                // the widget every wall-clock minute boundary, so the
                // colour transitions amber → grey → red ride on the same
                // tick that drives the row re-derivation. No separate
                // colour alarms — one tick mechanism, two outputs.
                //
                // Trade-off: the transition fires at the next minute
                // boundary after the threshold, not exactly at +60s/+180s.
                // 0-60s of slop, but the widget, home Board, and dream
                // Board all align to the same boundary so they never
                // disagree on colour at any wall-clock moment.
                views.setTextColor(
                    R.id.last_updated_timer,
                    com.stationly.core.util.StaleColor.colorForAge(ageMs),
                )
                // Re-arm the watchdog. Whatever path got us here (FCM
                // push, manual refresh, prior watchdog fire), we just
                // produced an up-to-date render — so the next forced
                // re-render is 90s out unless an FCM lands first and
                // re-arms us closer.
                scheduleEtaTickWatchdog(context)
            }

            // Status row: hidden entirely when there's no selection (a
            // "Good Service" default would be a lie since there's no line
            // to report on). Otherwise always visible to keep the widget
            // size stable across selected-board states; real status when
            // available, "Good Service" as the default.
            if (!hasSelection) {
                views.setViewVisibility(R.id.status_container, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
                if (lineStatusSeverity != null) {
                    views.setTextViewText(R.id.status_severity, lineStatusSeverity)
                    views.setTextViewText(R.id.status_reason, StationlyFormatters.formatStatusReason(lineStatusReason ?: ""))
                } else {
                    views.setTextViewText(R.id.status_severity, "Good Service")
                    views.setTextViewText(R.id.status_reason, "")
                }
            }
            
            // Where a tap goes, and the two taps mean different things.
            //
            // The GEAR is this widget's settings, always — the screen that asks
            // which station it is for. Straight there rather than into the app's
            // own settings, because the user tapped the gear ON a particular
            // widget and that is the widget they mean. (The same screen lists
            // every placed widget when it is opened from inside the app, so
            // "change a different one" is one tap further, not unreachable.)
            // The request code is the widget id so two widgets do not share one
            // PendingIntent and configure each other.
            val configureIntent = android.app.PendingIntent.getActivity(
                context,
                appWidgetId,
                WidgetConfigureActivity.reconfigureIntent(context, appWidgetId),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.btn_settings, configureIntent)

            // The BOARD opens the app, with launcher semantics so the Splash
            // Screen API shows the icon (a widget-triggered launch without
            // ACTION_MAIN/CATEGORY_LAUNCHER shows only the splash background).
            //
            // Except when the widget is unbound, where there is no board to have
            // tapped: the empty state says "tap to choose", so the tap has to
            // land somewhere that can choose. Sending it to the home screen
            // would be an instruction the app cannot carry out.
            val openApp = Intent(context, com.stationly.mobile.MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val boardIntent = if (isBound) {
                android.app.PendingIntent.getActivity(
                    context, 0, openApp,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                configureIntent
            }
            views.setOnClickPendingIntent(R.id.departure_board, boardIntent)

            // ── The ROWS have to be given the tap separately ──
            //
            // On API 31+ the rows live in a `RemoteCollectionItems` adapter, and
            // a collection swallows its parent's click: tapping a departure did
            // nothing at all, so the only live targets were the thin margins
            // around the list. With the gear now opening this widget's own
            // configuration, "tap the board to open the app" is the only way in
            // — it had to actually work everywhere on the board.
            //
            // A collection item cannot carry its own `PendingIntent`; the
            // platform wants one TEMPLATE on the collection plus a fill-in per
            // item, and the template must be MUTABLE for the fill-in to merge.
            // The fill-in is empty because every row does the same thing.
            val rowsTemplate = if (isBound) {
                android.app.PendingIntent.getActivity(
                    context, appWidgetId, openApp,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                )
            } else {
                android.app.PendingIntent.getActivity(
                    context, appWidgetId,
                    WidgetConfigureActivity.reconfigureIntent(context, appWidgetId),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                )
            }
            views.setPendingIntentTemplate(R.id.rows_list, rowsTemplate)

            // Set up manual refresh intent
            val refreshIntent = Intent(context, DepartureWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
            }
            val refreshPendingIntent = android.app.PendingIntent.getBroadcast(
                context, 1, refreshIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_refresh, refreshPendingIntent)
            views.setViewVisibility(R.id.btn_refresh, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.progress_refresh, android.view.View.GONE)

            // Clear existing rows setup
            val rowViews = mutableListOf<RemoteViews>()
            
            // Checking actual selection state
             // (Already defined at start of function)
            
            if (sduiPayload != null) {
                // SDUI Rendering Path (Server-Driven)
                
                // 1. Check for dynamic theming
                val defaultTextColor = context.getColor(R.color.tfl_amber)
                var dynTextColor = defaultTextColor
                val theme = sduiPayload.theme
                
                theme?.primaryColor?.let {
                    dynTextColor = com.stationly.mobile.util.SduiThemeManager.parseColor(it, defaultTextColor)
                    views.setTextColor(R.id.line_name, dynTextColor)
                    // Don't override timer color — stale alarms manage it independently
                }
                theme?.backgroundColor?.let {
                    val dynBgColor = com.stationly.mobile.util.SduiThemeManager.parseColor(it, android.graphics.Color.BLACK)
                    views.setInt(R.id.departure_board, "setBackgroundColor", dynBgColor)
                }

                views.setTextViewText(R.id.line_name, sduiPayload.title)
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)
                // Keep status container visible as set above
                
                
                sduiPayload.components.forEach { component ->
                    when (component) {
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Header -> {
                            val header = RemoteViews(context.packageName, R.layout.widget_platform_header)
                            // Prefix every platform header with the line
                            // context so each platform row carries the
                            // same identity ("Piccadilly: Platform 1",
                            // "Piccadilly: Platform 2", …).
                            val title = StationlyFormatters.platformHeaderText(linePrefix, component.title)
                            header.setTextViewText(R.id.platform_name, title)
                             val headerColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.color, dynTextColor)
                             header.setTextColor(R.id.platform_name, headerColor)
                            rowViews.add(header)
                        }
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Row -> {
                            val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
                            row.setTextViewText(R.id.destination_text, component.destination)
                            row.setTextViewText(R.id.eta_text, component.eta)
                            val etaColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.etaColor, dynTextColor)
                            row.setTextColor(R.id.destination_text, dynTextColor)
                            row.setTextColor(R.id.eta_text, etaColor)
                            rowViews.add(row)
                        }
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Status -> {
                            views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
                            views.setTextViewText(R.id.status_severity, component.severity)
                            views.setTextViewText(R.id.status_reason, component.reason)
                            if (dynTextColor != defaultTextColor) {
                                views.setTextColor(R.id.status_severity, dynTextColor)
                                views.setTextColor(R.id.status_reason, dynTextColor)
                            }
                        }
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Message -> {
                            val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
                            row.setTextViewText(R.id.destination_text, component.text)
                            row.setTextViewText(R.id.eta_text, "")
                            val msgColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.color, dynTextColor)
                            row.setTextColor(R.id.destination_text, msgColor)
                            rowViews.add(row)
                        }
                    }
                }
            } else {
                // Unified Legacy Path (Perfectly Synced with Inside-App Board)
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)

                val isLoggedIn = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
                // THIS widget's board, not the account's first one. The old
                // form asked whether the primary station had ever loaded and
                // used the answer to describe a different station's widget.
                val hasEverUpdated: Boolean = if (boundSelection != null) {
                    com.stationly.core.platform.Platform.sqlStorage.hasPredictionsInDatabase(
                        boundSelection.station, boundSelection.line, boundSelection.direction,
                    )
                } else {
                    false
                }
                
                if (boardRows != null && boardRows.isNotEmpty()) {
                    // ── The multi-line board ────────────────────────────────
                    //
                    // Every line and direction the user tracks at this hub, in
                    // the blocks `MultiLineBoardProcessor` grouped them into.
                    // The two row types map onto the two layouts this widget has
                    // always had, so nothing about the dot-matrix changes — what
                    // changes is how many blocks reach it.
                    //
                    // `linePrefix` comes from the ROW, not from the widget's idea
                    // of "the line": the processor decides per block whether a
                    // prefix is needed at all — a block with one line does not
                    // need one, a mixed block does — and that is the difference
                    // between "(Cir.) Edgware Road" where it helps and a prefix
                    // on every row where it is noise.
                    boardRows.forEach { row ->
                        when (row) {
                            is com.stationly.core.util.MultiLineBoardProcessor.Row.PlatformHeader -> {
                                val header = RemoteViews(context.packageName, R.layout.widget_platform_header)
                                header.setTextViewText(R.id.platform_name, row.title)
                                header.setOnClickFillInIntent(R.id.platform_name, Intent())
                                rowViews.add(header)
                            }
                            is com.stationly.core.util.MultiLineBoardProcessor.Row.Departure -> {
                                val dep = RemoteViews(context.packageName, R.layout.widget_departure_row)
                                val destination = if (row.linePrefix.isBlank()) row.destination
                                else row.linePrefix + " " + row.destination
                                dep.setTextViewText(R.id.destination_text, destination)
                                dep.setTextViewText(R.id.eta_text, row.eta)
                                dep.setInt(
                                    R.id.destination_text, "setGravity",
                                    android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL,
                                )
                                // Tapping a departure opens the app — see the
                                // template on `rows_list`.
                                dep.setOnClickFillInIntent(R.id.departure_row_root, Intent())
                                rowViews.add(dep)
                            }
                        }
                    }
                } else {

                val legacyRows = com.stationly.core.util.GlobalBoardProcessor.prepareLegacyRows(
                    predictions,
                    lineName,
                    hasSelection,
                    isLoggedIn,
                    hasEverUpdated,
                    lineStatusSeverity,
                    lineStatusReason,
                    java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                    isBound,
                )

                legacyRows.forEach { row ->
                    when (row) {
                        is com.stationly.core.util.LegacyRow.Header -> {
                            val header = RemoteViews(context.packageName, R.layout.widget_platform_header)
                            header.setOnClickFillInIntent(R.id.platform_name, Intent())
                            // Prefix every platform header with the line
                            // context — same line, but each platform row
                            // carries its own identity.
                            val title = StationlyFormatters.platformHeaderText(linePrefix, row.title)
                            header.setTextViewText(R.id.platform_name, title)
                            rowViews.add(header)
                        }
                        is com.stationly.core.util.LegacyRow.Departure -> {
                            val dep = RemoteViews(context.packageName, R.layout.widget_departure_row)
                            dep.setOnClickFillInIntent(R.id.departure_row_root, Intent())
                            dep.setTextViewText(R.id.destination_text, row.destination)
                            dep.setTextViewText(R.id.eta_text, row.eta)
                            dep.setInt(
                                R.id.destination_text, "setGravity",
                                if (row.index == 0) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                            )
                            rowViews.add(dep)
                        }
                        is com.stationly.core.util.LegacyRow.Message -> {
                            val msg = RemoteViews(context.packageName, R.layout.widget_platform_header)
                            msg.setOnClickFillInIntent(R.id.platform_name, Intent())
                            msg.setTextViewText(R.id.platform_name, row.text)
                            rowViews.add(msg)
                        }
                    }
                }
                } // end the legacy single-line path
            }
            
            // Shared fallback message — when there's nothing real to render
            // we swap `rowViews` for `widget_departure_row` rows (bold
            // title + normal-weight detail lines + filler padding) so the
            // message sits inline with the dot-matrix, looking identical
            // to home + dream. Going through the same `applyRowsToWidget`
            // plumbing means it correctly lands in `rows_list` on API ≥ 31
            // and `rows_container` below — no double-rendering / phantom
            // empty container above.
            val finalRowViews = run {
                val nowMs = System.currentTimeMillis()
                val londonTime = java.time.Instant.ofEpochMilli(nowMs)
                    .atZone(java.time.ZoneId.of("Europe/London"))
                    .toLocalTime()
                val fallbackState = if (!hasSelection) {
                    BoardFallbackState(
                        BoardFallbackKind.CONNECTING, 0L
                    )
                } else {
                    computeBoardFallbackState(
                        hasPredictions = predictions.isNotEmpty(),
                        isOnline = NetworkState.isOnline.value,
                        lastUpdatedMs = lastUpdatedMs,
                        nowMs = nowMs,
                        londonTime = londonTime,
                        lineStatusSeverity = lineStatusSeverity,
                        lineStatusReason = lineStatusReason,
                    )
                }
                if (fallbackState != null) {
                    // No-selection state piggybacks on the CONNECTING kind
                    // but overrides the copy so it doesn't duplicate the
                    // "Stationly" header above; instead the title nudges
                    // the user toward adding a board.
                    val widgetStrings = if (!hasSelection) mapOf(
                        "board.fallback.connecting.title"  to "No boards yet",
                        "board.fallback.connecting.detail" to "Tap to add your first board",
                    ) else emptyMap()
                    buildFallbackRowRemoteViews(
                        context, fallbackState, widgetStrings,
                    )
                } else if (rowViews.isEmpty()) {
                    // Edge case: hasSelection && hasPredictions logically true, but
                    // the row list ended up empty (cap=0 or filter drop). Pad with
                    // an empty 4-row placeholder so the widget doesn't collapse.
                    buildFallbackRowRemoteViews(
                        context,
                        BoardFallbackState(
                            BoardFallbackKind.NO_UPCOMING
                        ),
                        emptyMap(),
                    )
                } else rowViews
            }

            applyRowsToWidget(views, finalRowViews)

            // Important: Handle appWidgetId correctly if updating all from invalid
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } else {
                val componentName = android.content.ComponentName(context, DepartureWidgetProvider::class.java)
                appWidgetManager.updateAppWidget(componentName, views)
            }
        }
        
        /**
         * Update widget content with predictions
         * This is called from the FCM service or WorkManager
         */
        // `updateWidgetContent` was here. It took one board and pushed it to
        // EVERY placed widget id — which is what "one logical widget over the
        // primary selection" meant in practice, and exactly what per-instance
        // binding replaces. Its loop is now `updateFromStorage`, which resolves
        // each id's own station before drawing it. Deleted rather than left
        // unused: a helper that fans one station out to every widget is a
        // loaded gun in a file whose one rule is never to show the wrong stop.

        private fun applyRowsToWidget(views: RemoteViews, rowViews: List<RemoteViews>) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                views.setViewVisibility(R.id.rows_container, android.view.View.GONE)
                views.setViewVisibility(R.id.rows_list, android.view.View.VISIBLE)
                val builder = RemoteViews.RemoteCollectionItems.Builder()
                rowViews.forEachIndexed { index, rv -> builder.addItem(index.toLong(), rv) }
                views.setRemoteAdapter(R.id.rows_list, builder.setHasStableIds(false).build())
            } else {
                views.setViewVisibility(R.id.rows_list, android.view.View.GONE)
                views.setViewVisibility(R.id.rows_container, android.view.View.VISIBLE)
                views.removeAllViews(R.id.rows_container)
                rowViews.forEach { views.addView(R.id.rows_container, it) }
            }
        }
    }
}