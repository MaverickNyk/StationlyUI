package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.PlatformNav
import com.stationly.core.util.PlatformPages
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
import com.stationly.mobile.ui.util.buildFallbackRowRemoteViews
import com.stationly.mobile.ui.util.computeBoardFallbackState
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
                // ── The board's rules are served, and nothing here has a
                // screen above it to have fetched them ─────────────────────
                //
                // A widget is redrawn by a push in a process where no Activity
                // need ever have started, so without this it runs on the values
                // that were compiled while the app beside it runs on the ones
                // the backend sent. See SduiConfig.ensureLoaded.
                com.stationly.core.config.SduiConfig.ensureLoaded()
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
        // The page index belongs with the binding: both answer "what is this
        // widget showing", and leaving one behind means a widget id reissued by
        // the launcher inherits a stranger's platform.
        WidgetPageStore.forget(context, appWidgetIds)
        WidgetMotion.forget(context, appWidgetIds)
        WidgetSettings.forget(context, appWidgetIds)
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

    /**
     * The user resized the widget. Redraw it for its new size class (WidgetSize):
     * without this a resize kept whatever was drawn for the old size until the
     * next push happened to land.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                com.stationly.core.config.SduiConfig.ensureLoaded()
                updateOne(context, appWidgetId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // The app was just updated. Without this, a widget placed
                // before the update can keep the OLD layout's view tree, because
                // the launcher reapplies new actions onto it rather than
                // inflating the new XML (same layout id). That would keep, for
                // example, the Android 17 missing-rows fix off every widget
                // already on a home screen. A blank frame of a different layout
                // first forces the next real render to inflate fresh.
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    android.content.ComponentName(context, DepartureWidgetProvider::class.java),
                )
                ids.forEach { id ->
                    manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_reinflate))
                }
                android.util.Log.d("Widget", "App updated: re-inflating ${ids.size} widget(s)")
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        com.stationly.core.config.SduiConfig.ensureLoaded()
                        updateFromStorage(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
                return
            }
            WidgetPinner.ACTION_PINNED -> {
                // "Add to Home Screen" landed. The launcher put the id on the
                // callback; bind it to the station the user asked for and draw
                // it, so it never appears as "Choose a station". See WidgetPinner.
                val id = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
                // Home FIRST, before the bind and the draw: the launcher has the
                // new widget queued and places it when it next comes to the front.
                WidgetPinner.showHomeScreen(context)
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val bound = WidgetPinner.bindPinned(context, id)
                        android.util.Log.d("Widget", "Pinned widget $id ${if (bound) "bound" else "left as-is"}")
                        com.stationly.core.config.SduiConfig.ensureLoaded()
                        if (bound) updateOne(context, id)
                    } finally {
                        pendingResult.finish()
                    }
                }
                return
            }
            ACTION_ETA_TICK -> {
                // Watchdog fired — FCM has been silent for ≥ 90s.
                // Re-render from SQL so the ETAs catch up to the wall
                // clock, then reschedule the next watchdog (handled
                // automatically by updateFromStorage → updateAppWidget
                // → scheduleEtaTickWatchdog).
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        com.stationly.core.config.SduiConfig.ensureLoaded()
                        updateFromStorage(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
                return
            }
        }
        // ── Stepping to the next or previous platform ────────────────────
        //
        // The chevrons on a paging widget are PendingIntents and nothing more,
        // because a RemoteViews tree holds no state. So the tap lands here: it
        // moves the stored index and redraws THAT widget only. The step wraps
        // (see PlatformPages.step) and is computed against the board as it is
        // right now, not as it was when the intent was created.
        if (intent.action == ACTION_PLATFORM_STEP) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            val delta = intent.getIntExtra(EXTRA_PLATFORM_DELTA, 1)
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    com.stationly.core.config.SduiConfig.ensureLoaded()
                    runCatching { stepPlatform(context, id, delta) }
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        val actions = listOf(ACTION_UPDATE_WIDGET, ACTION_MANUAL_REFRESH)
        if (intent.action in actions) {
            // ── Coalesce a tap into a fetch already running ─────────────────
            //
            // NOT a time lockout. A refresh that has completed costs nothing to
            // run again, and dropping the tap somebody made because they want
            // newer numbers gives them a control that did nothing and said
            // nothing. See REFRESH_IN_FLIGHT_CEILING_MS, and
            // `WidgetRefreshService.swift` where iOS reached this first.
            //
            // ACTION_UPDATE_WIDGET is exempt: it repaints from SQL and never
            // reaches the backend, so there is no work to coalesce.
            val refreshPrefs =
                context.getSharedPreferences(WidgetBindingStore.PREFS, Context.MODE_PRIVATE)
            if (intent.action == ACTION_MANUAL_REFRESH) {
                val startedAt = refreshPrefs.getLong(KEY_REFRESH_STARTED_AT, 0L)
                val now = System.currentTimeMillis()
                if (!mayRefresh(startedAt, now)) {
                    android.util.Log.d(
                        "Widget",
                        "Refresh coalesced into the one started ${(now - startedAt) / 1000}s ago",
                    )
                    // ── A press that rides a fetch already running ──────────
                    //
                    // Most often the silent refresh an unlock starts, which is
                    // exactly when somebody glances and presses. It used to be
                    // dropped with nothing on screen, so the button looked dead.
                    // Now the press still gets its spinner and its roll: the
                    // running fetch clears the spinner and redraws this widget
                    // when it lands (see `stopSpinners`).
                    val pressed = intent.getIntExtra(
                        AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID,
                    )
                    if (pressed != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        WidgetMotion.armRefresh(context, pressed, now)
                        val pending = goAsync()
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            try { showRefreshSpinner(context, pressed) }
                            catch (e: Exception) { android.util.Log.e("Widget", "Spinner failed", e) }
                            finally { pending.finish() }
                        }
                        // Backstop: if the running fetch landed between the
                        // check above and the spinner going up, nothing else
                        // would take it down.
                        val app = context.applicationContext
                        redrawExecutor.schedule({
                            val stillRunning = refreshPrefs.getLong(KEY_REFRESH_STARTED_AT, 0L) != 0L
                            if (pressed in refreshingIds && !stillRunning) stopSpinners(app)
                        }, RECEIVER_BUDGET_MS + 1_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    return
                }
                refreshPrefs.edit().putLong(KEY_REFRESH_STARTED_AT, now).apply()
                // ── This press, and only this press, is owed an animation ────
                //
                // Armed here rather than decided at render time because by the
                // time the board is redrawn there is nothing left to say who
                // asked for it: a refresh redraw and a push redraw arrive on
                // the same path, with the same data, and one of them is direct
                // manipulation the user is waiting on while the other is
                // ambient. Only the tap knows, so the tap writes it down.
                //
                // One-shot and scoped to the widget that was pressed. A refresh
                // fans out several redraws and touches every widget on the same
                // hub; flipping all of them, several times each, is what the
                // owner saw as flashing. See WidgetMotion.
                WidgetMotion.armRefresh(
                    context,
                    intent.getIntExtra(
                        AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID,
                    ),
                    now,
                )
            }
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    // ── Inside the broadcast's deadline, always ─────────────
                    //
                    // A widget tap arrives as a FOREGROUND broadcast, which
                    // Android gives ~10s before it declares the app not
                    // responding. This used to hold the broadcast open for the
                    // whole network fetch, and a slow one produced an ANR (Pixel
                    // 10 Pro Fold, staging, 2026-11-21: "ANR in
                    // com.stationly.mobile ... ACTION_MANUAL_REFRESH"). Past the
                    // budget the fetch is cancelled and the board is redrawn
                    // from what SQL already holds; the guard below is released
                    // either way, so the button is never left dead.
                    // ── The spinner is the tap's feedback: first, and seen ──
                    //
                    // It used to appear after the config load and vanish as
                    // soon as a fast fetch finished, a fraction of a second, so
                    // the press looked like it did nothing (owner, 2026-11-21:
                    // "the refresh animation is not rotating"). Now it is the
                    // first thing a press does and stays up for at least one
                    // visible turn, MIN_SPINNER_MS, before the redraw replaces it.
                    //
                    // ONLY a press spins (owner, 2026-11-23). An unlock or a
                    // boot sends the same action through `triggerRefresh`, with
                    // no widget id, and used to spin every widget on the home
                    // screen each time the phone woke: ambient work dressed as
                    // a busy control. A press always names its widget, so "no
                    // id" is the test, the same one `WidgetMotion.armRefresh`
                    // uses for the roll.
                    val pressedId = intent.getIntExtra(
                        AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID,
                    )
                    val spins = intent.action == ACTION_MANUAL_REFRESH &&
                        pressedId != AppWidgetManager.INVALID_APPWIDGET_ID
                    if (spins) showRefreshSpinner(context, pressedId)
                    val completed = kotlinx.coroutines.withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    com.stationly.core.config.SduiConfig.ensureLoaded()
                    if (intent.action != ACTION_UPDATE_WIDGET) {
                        val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
                        val repo = com.stationly.core.repository.DepartureRepository(
                            com.stationly.core.service.TflApiServiceFactory.create(),
                            com.stationly.core.platform.Platform.storageManager,
                            com.stationly.core.platform.Platform.sqlStorage,
                            com.stationly.core.usecase.SyncPredictionsUseCase(com.stationly.core.platform.Platform.sqlStorage)
                        )
                        // ── Fetch everything, THEN redraw once ──────────
                        //
                        // This used to redraw inside the loop, once per stop.
                        // A user tracking eight boards got eight full
                        // RemoteViews rebuilds per widget from one tap, and on
                        // a paging widget each rebuild replaces the flipper's
                        // children: the board visibly flickered several times
                        // for a single press.
                        //
                        // ── This widget's board, not every board ────────
                        //
                        // The refresh button belongs to ONE widget and means
                        // "bring THIS board up to date". It used to fetch every
                        // tracked stop, which is why refreshing one widget
                        // visibly disturbed the others: eight fetches, a
                        // status push per line in reply, and every placed
                        // widget redrawn for each.
                        //
                        // Scoped to the hub this widget is bound to. An
                        // INVALID id means a programmatic caller that really
                        // does mean everything, and it still gets everything.
                        val tappedId = intent.getIntExtra(
                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                            AppWidgetManager.INVALID_APPWIDGET_ID,
                        )
                        val boundHub =
                            if (tappedId == AppWidgetManager.INVALID_APPWIDGET_ID) null
                            else WidgetBindingStore.boundStation(
                                context, tappedId, WidgetBindingStore.currentUid(),
                            )
                        val toFetch =
                            if (boundHub == null) selections
                            else selections.filter { it.groupingId == boundHub }

                        // ── iOS's refresh rules, where Android needs them ────
                        //
                        // Signed out fetches nothing (iOS `refresh`: a tap on a
                        // signed-out widget must not refill it). Otherwise one
                        // concurrent, deduplicated fan-out over this station's
                        // feeds, and a failure recorded against THIS station so
                        // its widget shows the retry glyph (RefreshFailures).
                        // Unlike iOS, still only the tapped station: Android has
                        // FCM keeping the other widgets fresh (README rule 3).
                        if (WidgetBindingStore.currentUid() == null) {
                            android.util.Log.d("Widget", "Refresh skipped: signed out")
                        } else {
                            val result = repo.refreshBoards(toFetch)
                            RefreshFailures.record(
                                context,
                                toFetch.map { it.groupingId }.distinct(),
                                failed = result.allFailed,
                            )
                            android.util.Log.d(
                                "Widget",
                                "Refresh fetched ${result.succeeded}/${result.requested}" +
                                    if (result.allFailed) " (marked failed)" else "",
                            )
                        }

                        // Hold the spinner for one visible turn before the
                        // redraw below swaps the icon back. Measured from when
                        // a spinner last went up, which may be a press that
                        // joined this (silent) refresh; with none up, a silent
                        // refresh redraws at once.
                        if (refreshingIds.isNotEmpty()) {
                            val shownFor = System.currentTimeMillis() - spinnerSince
                            if (shownFor < MIN_SPINNER_MS) kotlinx.coroutines.delay(MIN_SPINNER_MS - shownFor)
                        }
                        // Spinner off BEFORE the redraw below, so it draws the icon.
                        stopSpinners(context)

                        // The app and the dream still need to know, and they
                        // are a flow emit either way. The WIDGET half is the
                        // expensive one, so it is aimed: this widget when the
                        // tap named one, everything when it did not.
                        toFetch.map { it.station }.distinct().forEach { station ->
                            com.stationly.mobile.util.FreshDataNotifier
                                .notifyPredictions(context, station)
                        }
                    } else {
                        // ── ACTION_UPDATE_WIDGET: redraw from SQL ───────────
                        //
                        // Scoped when the sender said which board changed.
                        // `ProcessPredictionsUseCase` broadcasts this on EVERY
                        // processed payload, and without an id the receiver had
                        // to assume all of them: one refresh over several stops
                        // measured 156 renders across four widgets, arriving as
                        // a burst of flashes, and every push did a smaller
                        // version of the same thing.
                        //
                        // Blank still means everything, which is what
                        // `showWaitingState` and `clearWidgetData` mean.
                        val changed = intent.getStringExtra("stationId").orEmpty()
                        if (changed.isBlank()) updateFromStorage(context)
                        else updateForStation(context, changed)
                    }
                    true
                    }
                    if (completed == null) {
                        // A timed-out refresh is a failed one for the board the
                        // user tapped: give it the retry glyph.
                        if (intent.action == ACTION_MANUAL_REFRESH) {
                            val tapped = intent.getIntExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID,
                                AppWidgetManager.INVALID_APPWIDGET_ID,
                            )
                            WidgetBindingStore.boundStation(context, tapped, WidgetBindingStore.currentUid())
                                ?.let { RefreshFailures.record(context, listOf(it), failed = true) }
                        }
                        android.util.Log.w(
                            "Widget",
                            "${intent.action} passed ${RECEIVER_BUDGET_MS}ms; redrawing from storage",
                        )
                        updateFromStorage(context)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Widget", "Error during refresh", e)
                    updateFromStorage(context)
                } finally {
                    // Released here and nowhere else, so a fetch that throws,
                    // returns early or is killed cannot leave the guard raised
                    // and the button inert. A guard that leaks is a button that
                    // stops working and never says why.
                    if (intent.action == ACTION_MANUAL_REFRESH) {
                        refreshPrefs.edit().remove(KEY_REFRESH_STARTED_AT).apply()
                        if (refreshingIds.isNotEmpty()) {
                            // Never leave a spinner up after a timeout or a throw.
                            refreshingIds.clear()
                            updateFromStorage(context)
                        }
                    }
                    pendingResult.finish()
                }
            }
        }
    }
    
    companion object {
        const val ACTION_UPDATE_WIDGET = "com.stationly.mobile.ACTION_UPDATE_WIDGET"
        const val ACTION_MANUAL_REFRESH = "com.stationly.mobile.ACTION_MANUAL_REFRESH"

        /**
         * How long a widget broadcast may keep itself alive. A tap is a
         * foreground broadcast with a ~10s ANR deadline; 8s leaves room for
         * the redraw-from-storage that follows a timeout.
         */
        private const val RECEIVER_BUDGET_MS = 8_000L

        // ── Header and pager geometry, in dp ────────────────────────────────
        // Mirrors widget_departure_board.xml. The board has NO side padding
        // (rows run edge to edge), so these are measured from the widget edge.

        /** Width assumed when a launcher reports no size. */
        private const val FALLBACK_WIDTH_DP = 250

        /**
         * What the centred station name keeps clear on EACH side: the refresh
         * box (12dp margin + 32dp) plus 4dp of air, mirrored on the left so the
         * name stays optically centred. Matches the XML title margins.
         */
        private const val HEADER_SIDE_RESERVE_DP = 48f

        /** Where the station block starts on compact, which reads flush left. */
        private const val COMPACT_STATION_START_DP = 12f

        /** Roundel's end margin before the name. */
        private const val STATION_ICON_GAP_DP = 8f

        /** Kept spare beside the station name; see its use. */
        private const val STATION_SLACK_DP = 16f

        /**
         * Kept spare when picking a header wording. Like [STATION_SLACK_DP]: the
         * reported width runs over what the launcher draws, so a rung measured
         * as an exact fit was being ellipsised ("Picc. Plat. 2 (Westbou...").
         */
        private const val HEADER_SLACK_DP = 12f

        /** Text inset inside every row and platform header. */
        private const val ROW_TEXT_INSET_DP = 12f
        /** The empty time column's 8dp beside a message, and a little for the launcher's own type. */
        private const val MESSAGE_SLACK_DP = 14f

        // ── The pager bar ────────────────────────────────────────────────
        //
        // Everything on it that is not the title: 10dp padding each side and
        // an arrow column at each end. The "1/2" sits beside the title and is
        // measured per render. On a 2x2 (COMPACT, Android 12+) the columns
        // slim down: at full width a ~170dp widget had nothing left for the
        // title and showed only "1/2" (owner, 2026-11-23).
        private const val PAGER_PADDING_DP = 10f
        private const val CHEVRON_DP = 52f
        private const val COMPACT_CHEVRON_DP = 30f
        /** The chevron's edge inset and ink box; the rest of a column is inward padding. */
        private const val CHEVRON_EDGE_DP = 2f
        private const val CHEVRON_INK_DP = 8f
        /** The tap zones laid over the arrows, wider than the columns. */
        private const val CHEVRON_ZONE_DP = 80f
        private const val COMPACT_CHEVRON_ZONE_DP = 56f
        /** The gap between the platform name and its "1/2". */
        private const val PAGER_COUNT_GAP_DP = 6f

        private const val MIN_REFRESH_ZONE_DP = 40f
        /** The pager bar's natural height, near enough, at medium type. */
        private const val NATURAL_PAGER_DP = 24f
        /** How far a chevron zone reaches past the bar, into the row under it. */
        private const val CHEVRON_ZONE_OVERHANG_DP = 6f

        /** The shortest a refresh spinner stays up: about one visible turn. */
        private const val MIN_SPINNER_MS = 900L

        /**
         * How long scroll mode shows the refresh roll before the list returns.
         * The row-by-row roll takes about 0.6s for nine rows
         * (widget_rows_refresh), so this leaves it room to finish.
         */
        private const val REFRESH_SNAPSHOT_MS = 900L

        /** Widgets whose refresh is in flight; the render draws their spinner. */
        private val refreshingIds: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * A chevron on a [BoardConfig.platformNav] = STEP widget was tapped.
         *
         * Fired as an EXPLICIT broadcast at this provider, the same way
         * [ACTION_MANUAL_REFRESH] is, so it needs no `<action>` in the manifest
         * — an explicit intent is delivered by component, not by filter, and
         * the receiver is `exported="false"` anyway.
         */
        const val ACTION_PLATFORM_STEP = "com.stationly.mobile.ACTION_PLATFORM_STEP"

        /** +1 or -1, carried on [ACTION_PLATFORM_STEP]. */
        const val EXTRA_PLATFORM_DELTA = "platform_delta"

        /**
         * How long an in-flight refresh may be assumed alive before a new tap
         * is allowed to start another.
         *
         * ## This replaced a 15-second lockout, and the distinction matters
         * The old rule was a DEBOUNCE: after any refresh, every tap for fifteen
         * seconds was silently dropped. That protects TfL from a drummed
         * button, and it also refuses the tap a user makes because they
         * genuinely want newer numbers. The control did nothing and said
         * nothing, which is indistinguishable from broken.
         *
         * It is also unnecessary. The thing worth preventing was never "two
         * refreshes close together", it was "two refreshes at once": a refresh
         * that has COMPLETED costs nothing to run again, and the fan-out is one
         * request per unique naptan either way.
         *
         * So the guard is on CONCURRENCY. A tap while a fetch is running is
         * coalesced into it; a tap after one finishes goes straight through,
         * however soon.
         *
         * The ceiling exists only so a refresh killed mid-flight, the process
         * reclaimed or a crash, cannot leave the button permanently inert.
         * Above the network timeout, so a legitimately slow fetch is never
         * mistaken for a dead one.
         *
         * iOS reached this first and for the same reasons; see
         * `WidgetRefreshService.inFlightCeiling`.
         */
        const val REFRESH_IN_FLIGHT_CEILING_MS: Long = 12_000L

        /** `widget_prefs` key holding the start time of a refresh in flight. */
        internal const val KEY_REFRESH_STARTED_AT = "refresh_started_at"

        /**
         * Whether a tap may start a refresh, given what is already running.
         *
         * A stamp from the FUTURE is treated as stale rather than as a lockout:
         * a clock that went backwards, a user changing it or an NTP step, would
         * otherwise wedge the button until real time caught up. Erring towards
         * the button working is the right side to err on.
         */
        fun mayRefresh(startedAt: Long, now: Long): Boolean {
            if (startedAt <= 0L) return true
            val elapsed = now - startedAt
            return elapsed < 0L || elapsed >= REFRESH_IN_FLIGHT_CEILING_MS
        }
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
         *
         * Carries NO widget id, and that is what keeps it quiet: no spinner,
         * no roll. Only a press is owed an animation (owner, 2026-11-23:
         * spinning on every unlock was "too much distraction").
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

        /**
         * Spin the widget that was pressed, and only that one.
         *
         * Called only for a press, which always names its own widget: spinning
         * every board because one was pressed is three widgets claiming to be
         * busy on behalf of one, and it is half of what the owner saw as
         * flickering. The programmatic path (unlock, boot) refreshes silently
         * and never calls this. `INVALID` still means all, for safety.
         */
        fun showRefreshSpinner(context: Context, appWidgetId: Int) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids =
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) intArrayOf(appWidgetId)
                else appWidgetManager.getAppWidgetIds(
                    android.content.ComponentName(context, DepartureWidgetProvider::class.java)
                )
            // A FULL render, not partiallyUpdateAppWidget: the Pixel launcher on
            // Android 17 dropped the partial update, so the spinner never showed
            // (2026-11-21). The render reads `refreshingIds`.
            spinnerSince = System.currentTimeMillis()
            refreshingIds.addAll(ids.toList())
            ids.forEach { updateOne(context, it) }
        }

        /**
         * Take every spinner down, and make sure each of those widgets is
         * redrawn. The station redraws that follow a fetch cover the widgets
         * of the stations it fetched; a press that joined a fetch for ANOTHER
         * station would otherwise keep spinning until its next push. The ids
         * join the same burst, so a widget covered twice still draws once.
         */
        private fun stopSpinners(context: Context) {
            val spinning = refreshingIds.toList()
            refreshingIds.removeAll(spinning.toSet())
            scheduleRedraw(context, spinning)
        }

        /** When a spinner last went up, for the MIN_SPINNER_MS hold. */
        @Volatile private var spinnerSince = 0L

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
        /**
         * Redraw only the widgets whose board rides [pushedLineId].
         *
         * The line twin of [updateForStation], and it exists for the same
         * measured reason: a status push used to redraw every placed widget, so
         * one refresh tap produced 44 renders as the backend answered with a
         * push per line. See WidgetRedrawTargets.hubsOn.
         */
        fun updateForLine(context: Context, pushedLineId: String) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DepartureWidgetProvider::class.java)
            )
            if (appWidgetIds.isEmpty()) return

            val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
            val hubs = WidgetRedrawTargets.hubsOn(selections, pushedLineId)
            if (hubs.isEmpty()) return

            val uid = WidgetBindingStore.currentUid()
            val targets = WidgetRedrawTargets.widgetsShowing(
                bindings = appWidgetIds.toList()
                    .associateWith { WidgetBindingStore.boundStation(context, it, uid) },
                hubs = hubs,
            )
            scheduleRedraw(context, targets)
        }

        fun updateForStation(context: Context, pushedStationId: String) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DepartureWidgetProvider::class.java)
            )
            if (appWidgetIds.isEmpty()) return

            val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
            val affectedHubs = WidgetRedrawTargets.hubsFedBy(selections, pushedStationId)
            if (affectedHubs.isEmpty()) return

            // Read once for the whole sweep rather than per widget: this runs on
            // every prediction push, and the answer cannot change inside a loop.
            val uid = WidgetBindingStore.currentUid()
            val targets = WidgetRedrawTargets.widgetsShowing(
                bindings = appWidgetIds.toList().associateWith { WidgetBindingStore.boundStation(context, it, uid) },
                hubs = affectedHubs,
            )
            scheduleRedraw(context, targets)
        }

        // ── One redraw per burst, not one per push ──────────────────────────
        //
        // A station with several lines, or a bus stop with several poles, is
        // several pushes landing within half a second: measured 2 renders of
        // every Highbury & Islington widget inside 130 ms, and 4 of the
        // Smithwood Close widget inside 400 ms. Each full render re-applies the
        // status text, and a TextView restarts its marquee from the start
        // (with its ~1.2 s pause) whenever its text is set, so the status row
        // stuttered after every refresh (owner, 2026-11-23: "da da da da").
        //
        // So push-driven redraws (station and line) gather here and draw once
        // the burst goes quiet: REDRAW_QUIET_MS after the last request, and
        // never later than REDRAW_MAX_WAIT_MS after the first, so a steady
        // stream cannot starve a widget. The watchdog tick, the refresh
        // spinner and `updateFromStorage` stay immediate.

        private val redrawLock = Any()
        private val pendingRedraws = mutableSetOf<Int>()
        private var redrawTask: java.util.concurrent.ScheduledFuture<*>? = null
        private var burstStartedAt = 0L
        private val redrawExecutor: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

        private const val REDRAW_QUIET_MS = 300L
        private const val REDRAW_MAX_WAIT_MS = 1_200L

        private fun scheduleRedraw(context: Context, ids: Collection<Int>) {
            if (ids.isEmpty()) return
            val app = context.applicationContext
            synchronized(redrawLock) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (pendingRedraws.isEmpty()) burstStartedAt = now
                pendingRedraws.addAll(ids)
                redrawTask?.cancel(false)
                redrawTask = redrawExecutor.schedule(
                    { flushRedraws(app) },
                    coalesceDelay(now - burstStartedAt),
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            }
        }

        /** How long to wait, [waitedMs] into a burst. Pure; see the test. */
        internal fun coalesceDelay(waitedMs: Long): Long =
            minOf(REDRAW_QUIET_MS, (REDRAW_MAX_WAIT_MS - waitedMs).coerceAtLeast(0L))

        private fun flushRedraws(context: Context) {
            val ids = synchronized(redrawLock) {
                pendingRedraws.toList().also { pendingRedraws.clear() }
            }
            if (ids.isEmpty()) return
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                // A widget removed while the burst gathered is not drawn.
                val placed = appWidgetManager.getAppWidgetIds(
                    android.content.ComponentName(context, DepartureWidgetProvider::class.java)
                ).toSet()
                val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
                ids.filter { it in placed }.forEach { renderWidget(context, appWidgetManager, it, selections) }
            } catch (e: Exception) {
                android.util.Log.e("Widget", "Coalesced redraw failed", e)
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
            /**
             * The motion this redraw was asked for, when the caller knows.
             *
             * Null is the ordinary case and means "ask the store" — a refresh
             * press arms a one-shot flag that the first redraw after it spends,
             * because by the time the fetch lands there is nothing on the redraw
             * itself to say a finger started it. Every other caller (a push, the
             * minute tick, a sync, a rebind) leaves this null and gets
             * [WidgetMotion.NONE].
             *
             * A chevron passes its direction explicitly: it redraws immediately
             * and has no flag to leave.
             */
            motion: WidgetMotion? = null,
            /** The platform being left, for an animated step. */
            fromPage: Int? = null,
        ) {
            V1WidgetAdoption.adoptIfPending(context, selections)
            val boundTo = WidgetBindingStore.boundStation(context, appWidgetId, WidgetBindingStore.currentUid())
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
                    emptyState = WidgetEmptyState.resolve(
                        signedIn = WidgetBindingStore.currentUid() != null,
                        hasAnyBoard = selections.isNotEmpty(),
                        wasBound = boundTo != null,
                    ),
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
            //
            // Keyed on the grouping id — the HUB — which is exactly what
            // `configOf` is keyed on everywhere else (the station settings
            // screen passes the same thing). `selection.groupingId` rather than
            // `boundTo` only because the latter is nullable here and they are
            // the same string by construction.
            // The widget's own settings, NOT the board's. Changing a widget must
            // not change the card inside the app — see WidgetSettings.
            val widgetPin = WidgetSettings.pinOf(context, appWidgetId)
            val widgetNav = WidgetSettings.navOf(context, appWidgetId)
            // FIXED, and not the board's `rowsPerPlatform`.
            //
            // A widget that changes height is a widget that moves everything
            // else on somebody's home screen. Stepping between a platform with
            // three trains and one with two did exactly that, because the row
            // floor is a floor for the whole BOARD and a page is not a board.
            // So the widget claims a size and keeps it: three per platform,
            // padded with blanks when the data is thinner. See
            // PlatformPages.bodyPadded, and the owner's call on 2026-09-12.
            val rowCap = ROWS_PER_PLATFORM
            val notices = noticesFor(boardSelections, nowMs)
            val stationNotice = notices.values.sortedByDescending { it.hides }.firstOrNull()
            val openSelections = boardSelections.filterNot { notices[it]?.hides == true }
            val tickedGroups = tickedGroupsFor(openSelections, selection, widgetPin, rowCap, nowMs)
            val boardRows = com.stationly.core.util.MultiLineBoardProcessor.rowsFrom(tickedGroups, rowCap = rowCap)

            // Still the flat list, for the three things that are not rows: has
            // anything loaded, the SDUI template binding, and the fallback
            // state. Union across every board at this hub, so a station with one
            // quiet line and one busy one reads as having data. Taken from the
            // TICKED blocks, so it agrees with the rows about what is still
            // coming.
            val predictions = com.stationly.core.util.GlobalBoardProcessor.processPredictions(
                tickedGroups.flatMap { g -> g.departures.map { it.prediction } },
                perPlatformCap = rowCap,
            )

            // ── One status line for a board that may carry several ──
            //
            // `LineStatusRanker.rotation` is the home screen's rule: disrupted
            // lines worst-first, de-duplicated on (severity, reason) because the
            // sub-surface lines share track and share incidents, and a single
            // spoken entry when everything is healthy — "Good Service" four
            // times tells you nothing four times. The widget has room for one,
            // so it takes the first, which is the worst.
            // Of the boards still open, if any is: a closed one's line is not the story.
            val statusEntries = openSelections.ifEmpty { boardSelections }.distinctBy { it.mode to it.line }.mapNotNull { sel ->
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
            val lineStatusSeverity: String? = worst?.let { com.stationly.core.util.LineStatusRanker.label(it) }
            val lineStatusReason: String? = worst?.reason
            val stationStrip = stationNotice?.stripWith(
                lineStatusSeverity?.let { it to lineStatusReason.orEmpty() }?.takeIf { openSelections.isNotEmpty() },
            )

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
                closedNotice = stationNotice.takeIf { openSelections.isEmpty() },
                stationStrip = stationStrip,
                sduiPayload = sduiPayload,
                hasLoadedData = predictions.isNotEmpty(),
                lastUpdatedMs = lastUpdatedMs,
                isBound = true,
                hasAnyBoard = true,
                mode = selection.mode,
                boundSelection = selection,
                boardRows = boardRows,
                platformNav = widgetNav,
                // The page holding the block this widget was on, wherever that
                // block sorts today; the stored index only if it has gone.
                platformPage = com.stationly.core.util.PlatformPages.resolve(
                    boardRows.orEmpty(),
                    WidgetPageStore.blockOf(context, appWidgetId),
                    WidgetPageStore.pageOf(context, appWidgetId),
                ),
                // Spend the flag even when the caller named a motion, so a
                // refresh followed straight away by a chevron does not leave a
                // flip owed and play it minutes later against a push.
                // The spinner's own redraw must not spend the tap's animation:
                // the roll belongs to the redraw that brings the NEW rows, not
                // the one drawn the instant the button was pressed.
                motion = if (appWidgetId in refreshingIds) WidgetMotion.NONE
                    else WidgetMotion.consumeRefresh(context, appWidgetId)
                    .let { armed -> motion ?: armed },
                fromPage = fromPage,
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
         * A chevron's broadcast.
         *
         * The request code mixes the widget id with the direction, because two
         * PendingIntents that differ only in an EXTRA are the SAME PendingIntent
         * as far as the system is concerned — `FLAG_UPDATE_CURRENT` would then
         * make both chevrons carry whichever delta was registered last, and the
         * widget would step one way whichever arrow you pressed.
         */
        private fun platformStepIntent(
            context: Context,
            appWidgetId: Int,
            delta: Int,
        ): android.app.PendingIntent {
            val intent = Intent(context, DepartureWidgetProvider::class.java).apply {
                action = ACTION_PLATFORM_STEP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_PLATFORM_DELTA, delta)
            }
            return android.app.PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + if (delta > 0) 1 else 0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * What TfL says about these boards' stations, for those it says anything of.
         * One that hides is left out of the board, by the render and by the chevrons.
         * `runBlocking` for the reason given where the render reads the board's config.
         */
        private fun noticesFor(
            boards: List<com.stationly.core.model.UserSelection>,
            nowMs: Long,
        ): Map<com.stationly.core.model.UserSelection, com.stationly.core.model.BoardNotice> {
            if (!com.stationly.core.config.FeatureFlags.isOn(com.stationly.core.config.FeatureFlags.STATION_STATUS)) return emptyMap()
            kotlinx.coroutines.runBlocking { com.stationly.core.repository.StationNoticeStore.ensureLoaded() }
            val sql = com.stationly.core.platform.Platform.sqlStorage
            return boards.mapNotNull { sel ->
                val platforms = sql.getPredictions(sel.station, sel.line, sel.direction).map { it.platform }
                com.stationly.core.repository.StationNoticeStore.forBoard(sel, nowMs, platforms)?.let { sel to it }
            }.toMap()
        }

        /**
         * This station's board, as the widget draws it.
         *
         * Extracted because [stepPlatform] has to count the platforms before it
         * can wrap, and counting them means building the same rows the renderer
         * builds. Two copies of "what the board is" would drift the moment one
         * of them learned about a new preference — which is precisely what the
         * depth setting just did.
         */
        internal fun boardRowsFor(
            selections: List<com.stationly.core.model.UserSelection>,
            selection: com.stationly.core.model.UserSelection,
            boundTo: String,
            rowCap: Int,
            pin: com.stationly.core.model.user.BoardPin? = null,
        ): List<com.stationly.core.util.MultiLineBoardProcessor.Row> {
            val nowMs = System.currentTimeMillis()
            val all = selections.filter { it.groupingId == boundTo }
            val notices = noticesFor(all, nowMs)
            val boards = all.filterNot { notices[it]?.hides == true }
            return com.stationly.core.util.MultiLineBoardProcessor.rowsFrom(
                tickedGroupsFor(boards, selection, pin, rowCap, nowMs),
                rowCap = rowCap,
            )
        }

        /**
         * One station's board at [nowMs], ticked by the SAME rules as the app
         * and the screensaver: [com.stationly.core.util.BoardTicker] with the
         * served [com.stationly.core.config.BoardPolicy] (departed grace,
         * retention of "Gone" rows, the departed label, the row reserve).
         *
         * ## Why this replaced `tickPredictions` here (2026-11-23)
         * The widget ticked each line's rows through the Android-only
         * `tickPredictions`, which drops a train a hard-coded 30 s after its
         * time and knows nothing of retention or the label. iOS added those as
         * SDUI-driven rules (`board.tick.*`), the app board and screensaver
         * adopted them through `BoardTicker`, and the widget was the one surface
         * still on compiled values: it could show a train the app had already
         * marked "Gone", or empty a platform the app was still holding.
         *
         * Same order as the app (`Board.kt`): group the RAW stored rows with the
         * full reserve, then tick, then let the caller cut to [displayRows].
         * Capping before ticking leaves a block nothing to shift up.
         *
         * Shared by the render and by the chevrons ([boardRowsFor]), so the
         * page count a tap steps through is the page count drawn.
         */
        internal fun tickedGroupsFor(
            boards: List<com.stationly.core.model.UserSelection>,
            selection: com.stationly.core.model.UserSelection,
            pin: com.stationly.core.model.user.BoardPin?,
            displayRows: Int,
            nowMs: Long,
        ): List<com.stationly.core.util.MultiLineBoardProcessor.Group> {
            val sql = com.stationly.core.platform.Platform.sqlStorage
            val policy = com.stationly.core.config.BoardPolicyStore.current
            val feeds = boards.map { sel ->
                com.stationly.core.util.MultiLineBoardProcessor.Feed(
                    stationId = sel.station,
                    line = sel.line,
                    direction = sel.direction,
                    terminus = sel.singleTerminus,
                    nextStop = sel.directionTowards,
                    predictions = sql.getPredictions(sel.station, sel.line, sel.direction),
                )
            }
            val lastUpdated = boards.mapNotNull { sel ->
                sql.getLastUpdatedTimestamp(sel.station, sel.line, sel.direction)
            }.maxOrNull() ?: 0L
            return com.stationly.core.util.BoardTicker.tick(
                groups = com.stationly.core.util.MultiLineBoardProcessor.buildGroups(
                    feeds = feeds,
                    isBus = com.stationly.core.util.MultiLineBoardProcessor.isBus(selection.mode),
                    // A BoardConfig carrying ONLY the widget's pin: the widget's
                    // settings are not the board's (see WidgetSettings).
                    prefs = BoardConfig(pin = pin),
                    rowCap = com.stationly.core.util.MultiLineBoardProcessor.rowReserve,
                    nowMs = nowMs,
                    policy = policy,
                ),
                nowMs = nowMs,
                // Never-updated data has no age: treat as fresh, so an empty
                // first paint does not resurrect rows (same as Board.kt).
                payloadAgeMs = if (lastUpdated <= 0L) 0L else (nowMs - lastUpdated).coerceAtLeast(0L),
                displayRows = displayRows,
                policy = policy,
            )
        }

        /**
         * Move one stepping widget to the next or previous platform.
         *
         * Computed against the board as it is NOW rather than against whatever
         * it looked like when the chevron's intent was created: the page count
         * changes under a widget nightly, and a delta applied to a stale count
         * is how a tap lands on a platform that no longer exists.
         *
         * Redraws that one widget. Stepping widget A must not repaint widget B,
         * which is sitting on somebody's home screen showing a platform they
         * chose.
         */
        private fun stepPlatform(context: Context, appWidgetId: Int, delta: Int) {
            val boundTo = WidgetBindingStore.boundStation(context, appWidgetId, WidgetBindingStore.currentUid()) ?: return
            val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
            val selection = selections.firstOrNull { it.groupingId == boundTo } ?: return
            val rows = boardRowsFor(
                selections, selection, boundTo,
                ROWS_PER_PLATFORM,
                WidgetSettings.pinOf(context, appWidgetId),
            )
            val pageCount = PlatformPages.count(rows)
            if (pageCount <= 1) return
            // Step from where the user actually IS (the block they were on), not
            // from a position the blocks may have sorted away from.
            val current = PlatformPages.resolve(
                rows,
                WidgetPageStore.blockOf(context, appWidgetId),
                WidgetPageStore.pageOf(context, appWidgetId),
            )
            val next = PlatformPages.step(current, delta, pageCount)
            WidgetPageStore.setPage(
                context, appWidgetId, next,
                blockKey = PlatformPages.key(PlatformPages.page(rows, next)),
            )

            // ── A FULL redraw, carrying the direction it was pressed in ──────
            //
            // This used to be a `partiallyUpdateAppWidget` that appended the
            // destination to the flipper and moved onto it, because a
            // ViewFlipper animates only between children it ALREADY holds and a
            // full rebuild left the out-animation with nothing to play against.
            //
            // It does not need to any more: [applyPagesToFlipper] writes BOTH
            // frames itself — the board being left and the board arriving — so
            // the animation has its pair whether the update is partial or full.
            // Full is the better of the two here:
            //
            //  - it redraws from SQL, so a step also brings the ETAs up to the
            //    current minute instead of sliding onto a stale copy of a board
            //    that was built whenever the last push landed;
            //  - it goes through the one render path, so the pager bar, the
            //    status line, the timer and the rows cannot drift apart the way
            //    a hand-maintained partial makes them;
            //  - a full update with the same layout id is `reapply`, not a
            //    re-inflate, so it costs no more on screen than the partial did.
            //
            // The direction is passed rather than inferred: forward slides in
            // from the right, back slides in from the left, and each lives in
            // its own flipper because the animation cannot be set at runtime.
            renderWidget(
                context,
                AppWidgetManager.getInstance(context),
                appWidgetId,
                selections,
                motion = WidgetMotion.step(delta),
                fromPage = current,
            )
        }

        /**
         * One answer, used by both of the widget's body wirings.
         *
         * A bound widget opens the app from anywhere on it. An unbound one
         * opens its configuration instead: the empty state says "tap to choose
         * a station", so the tap has to land somewhere that can choose one.
         * Opening the app would be an instruction the app cannot carry out —
         * the binding lives on the widget, and the user would arrive at a home
         * screen with nothing to do.
         *
         * It is a function rather than two `if (isBound)` expressions because
         * that is what it was: two of them, forty lines apart, one for the
         * board container and one for the rows collection. Changing one and not
         * the other leaves an unbound widget whose header opens the picker and
         * whose rows open the app — a dead end reachable only by tapping the
         * wrong half of a widget that is already in its broken state.
         */
        /**
         * Whether the widget draws its own settings gear: only below Android 12,
         * where the launcher offers no "Settings" in the long-press menu.
         */
        fun showsSettingsButton(sdkInt: Int = android.os.Build.VERSION.SDK_INT): Boolean =
            sdkInt < android.os.Build.VERSION_CODES.S

        fun tapTargetFor(isBound: Boolean, emptyState: WidgetEmptyState? = null): WidgetTapTarget =
            if (isBound) WidgetTapTarget.APP
            // An empty state names its own destination: signed out and no
            // stations go to the app (where you sign in and add one), the two
            // "which station?" states go to this widget's configuration.
            else emptyState?.tapTarget ?: WidgetTapTarget.CONFIGURE

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
            /** What TfL says closes every board here: the message in place of the rows. */
            closedNotice: com.stationly.core.model.BoardNotice? = null,
            /** The status row in place of the line's, severity to reason. Only the row: an empty board's reason is the line's own. */
            stationStrip: Pair<String, String>? = null,
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
            /**
             * Why an unbound widget is empty, which decides both its message
             * and where a tap on it goes. Null for a bound widget.
             */
            emptyState: WidgetEmptyState? = null,
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
            /**
             * How this station's platforms are reached — see [PlatformNav].
             *
             * SCROLL is the default and is what every widget did before the
             * setting existed, so a caller that does not pass it gets the old
             * behaviour rather than a surprise.
             */
            platformNav: PlatformNav = PlatformNav.SCROLL,
            /** Which platform a STEP widget is parked on. Ignored when scrolling. */
            platformPage: Int = 0,
            /**
             * Whether this redraw moves anything, and how — see [WidgetMotion].
             *
             * Defaults to NONE so that every caller who has not thought about it
             * gets a silent redraw, which is the right answer for all of them:
             * the waiting state, the clear, the initial render and the dozen
             * ambient paths that redraw a board nobody touched.
             */
            motion: WidgetMotion = WidgetMotion.NONE,
            /**
             * The platform being left, for [WidgetMotion.NEXT] / [WidgetMotion.PREV].
             *
             * The outgoing frame has to be the board the user is looking at, or
             * the slide has nothing to slide away. Null (and every non-stepping
             * motion) uses [platformPage] itself, which is right for a refresh:
             * the same board turning over in place.
             */
            fromPage: Int? = null,
        ) {
            // ── One platform at a time, when the station asks for it ────────
            //
            // The rows are cut here rather than in the loop below, so
            // everything downstream — the row builder, the fallback, the
            // logging — sees "the board" and does not need to know which mode
            // it is in.
            //
            // The bar hides itself on a single-platform board even in STEP
            // mode: two chevrons that can only return you to where you are is a
            // control that lies about having somewhere to go. That is a
            // rendering decision and not a setting, because the platform count
            // changes nightly and a user should not have to re-choose when TfL
            // drops one.
            val pageCount = boardRows?.let { PlatformPages.count(it) } ?: 0
            val stepping = platformNav == PlatformNav.STEP && pageCount > 1
            val safePage = PlatformPages.clamp(platformPage, pageCount)
            val pagedRows = PlatformPages.page(boardRows.orEmpty(), safePage)
            // `bodyPadded`, not `body`: the bar between the chevrons already
            // names the platform (so the header comes off), and every page is
            // the same height (so the widget does not resize as you step).
            val drawnRows =
                if (stepping) PlatformPages.bodyPadded(pagedRows, ROWS_PER_PLATFORM)
                else boardRows

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

            val widgetOptions = appWidgetManager.getAppWidgetOptions(appWidgetId)
            // ── Size class: the type scale for this widget's actual size ────
            //
            // The Android counterpart of iOS's per-family BoardMetrics. See
            // WidgetSize. Medium is the XML defaults, so a 4x2 is unchanged.
            val (widgetWidthDp, widgetHeightDpMeasured) = WidgetSize.measured(context, widgetOptions)
            val fontScale = context.resources.configuration.fontScale
            val trustHeight = WidgetSize.trustsHeight(context)
            val widgetSize = WidgetSize.of(
                widgetWidthDp, widgetHeightDpMeasured, fontScale,
                pager = stepping, trustHeight = trustHeight, sdk = android.os.Build.VERSION.SDK_INT,
            )
            // Rows that cannot scroll, sized to what is really left for them.
            val sdk = android.os.Build.VERSION.SDK_INT
            fun fixedRowSp(rows: Int, pager: Boolean, status: Boolean) =
                if (!trustHeight) widgetSize.row
                else widgetSize.fixedRowSp(widgetHeightDpMeasured, fontScale, rows, pager, status, sdk)
            val dropsStatus = stepping && trustHeight && widgetHeightDpMeasured > 0 &&
                widgetSize.dropsStatus(widgetHeightDpMeasured, fontScale, ROWS_PER_PLATFORM, pager = true, sdk = sdk)
            val pageRowSp = fixedRowSp(ROWS_PER_PLATFORM, pager = true, status = !dropsStatus)
            val sp = android.util.TypedValue.COMPLEX_UNIT_SP
            views.setTextViewTextSize(R.id.line_name, sp, widgetSize.station)
            views.setTextViewTextSize(R.id.platform_pager_title, sp, widgetSize.platform)
            views.setTextViewTextSize(R.id.platform_pager_count, sp, widgetSize.pagerCount)
            views.setTextViewTextSize(R.id.status_severity, sp, widgetSize.status)
            views.setTextViewTextSize(R.id.status_reason, sp, widgetSize.status)
            views.setTextViewTextSize(R.id.status_separator, sp, widgetSize.status)
            applyRowPadding(context, views, widgetSize)
            views.setTextViewTextSize(R.id.clock, sp, widgetSize.clock)
            views.setTextViewTextSize(R.id.last_updated_timer, sp, widgetSize.ago)
            // Compact drops the wall clock, as iOS small does: the status bar
            // already says the time, and the "ago" timer, which nothing else on
            // the phone says, gets the footer.
            views.setViewVisibility(
                R.id.clock,
                if (widgetSize.showsClock) android.view.View.VISIBLE else android.view.View.GONE,
            )
            // ── The station row: sized from the space it really has ─────────
            //
            // The name used to get a floor of 10 ems at 16sp (~160dp) whatever
            // the widget, which on a 2x2 is wider than the whole header: the
            // centred name-and-roundel block spilled out both sides and the
            // roundel was clipped at the edge (owner, 2026-11-22). Now the name
            // is capped to the measured space at this size class's type, and on
            // compact the block sits flush LEFT, roundel at the edge, as iOS
            // small reads (`centresStationName = false`). Elsewhere it stays
            // centred between equal reserves.
            val stationIconDp = widgetSize.iconDp
            val headerWidthDp = (widgetWidthDp.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP).toFloat()
            val stationStartDp =
                if (widgetSize.centresStation || showsSettingsButton()) HEADER_SIDE_RESERVE_DP else COMPACT_STATION_START_DP
            val stationEndDp = HEADER_SIDE_RESERVE_DP
            // STATION_SLACK_DP: the launcher's reported width runs a little over
            // what it draws, and a block wider than its space is centred OVER
            // both edges, which is what clipped the roundel on a 2x2.
            val stationTextDp = (headerWidthDp - stationStartDp - stationEndDp - stationIconDp -
                STATION_ICON_GAP_DP - STATION_SLACK_DP).coerceAtLeast(24f)
            views.setInt(
                R.id.line_name, "setMaxWidth",
                (stationTextDp * context.resources.displayMetrics.density).toInt(),
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val dip = android.util.TypedValue.COMPLEX_UNIT_DIP
                views.setViewLayoutWidth(R.id.mode_icon, stationIconDp, dip)
                views.setViewLayoutHeight(R.id.mode_icon, stationIconDp, dip)
                // Flush left: give the slack to the END margin, so the centred
                // block lands at the start.
                val nameDp = WidgetText.widthDp(context, stationName, widgetSize.station, bold = true)
                    .coerceAtMost(stationTextDp)
                val endDp = if (widgetSize.centresStation) stationEndDp
                    else stationEndDp + (stationTextDp - nameDp).coerceAtLeast(0f)
                views.setViewLayoutMargin(R.id.station_lockup, RemoteViews.MARGIN_START, stationStartDp, dip)
                views.setViewLayoutMargin(R.id.station_lockup, RemoteViews.MARGIN_END, endDp, dip)
            }

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
            
            // Status and timer always visible — layout never shifts.
            //
            // But only for a widget that is DRAWING a board. `hasSelection`
            // is the ACCOUNT's question; an unbound widget on an account with
            // boards used to print "Good Service :" for a line it does not
            // have and an "ago" for data it does not show (Pixel 10 Pro Fold,
            // 2026-11-21). The empty state is the whole message.
            val showsBoard = isBound && hasSelection
            views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.last_updated_timer, if (showsBoard) android.view.View.VISIBLE else android.view.View.INVISIBLE)

            if (showsBoard) {
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
            if (!showsBoard) {
                views.setViewVisibility(R.id.status_container, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
                val rowSeverity = stationStrip?.first ?: lineStatusSeverity
                views.setTextViewCompoundDrawablesRelative(
                    R.id.status_severity, if (stationStrip != null) R.drawable.ic_board_warning else 0, 0, 0, 0,
                )
                views.setInt(R.id.status_severity, "setMaxLines", if (stationStrip != null) 1 else Int.MAX_VALUE)
                if (rowSeverity != null) {
                    views.setTextViewText(R.id.status_severity, rowSeverity)
                    views.setTextViewText(R.id.status_reason, StationlyFormatters.formatStatusReason(stationStrip?.second ?: lineStatusReason ?: ""))
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
            // The bigger target over it (widget_departure_board.xml, tap zones).
            views.setOnClickPendingIntent(R.id.zone_settings, configureIntent)
            // ── The gear only where the launcher has no Settings of its own ──
            //
            // Android 12+ launchers put "Settings" in a widget's long-press menu
            // (we declare `reconfigurable`), and an unbound widget's tap opens
            // configuration anyway. There the gear was a second door to a rare
            // task, taking the header's space and catching taps meant for
            // refresh. Android 8 to 11 have no reconfigure in the launcher, so
            // the gear stays: it is their only door from the widget itself.
            // Owner decision, 2026-11-22.
            val gear = if (showsSettingsButton()) android.view.View.VISIBLE else android.view.View.GONE
            views.setViewVisibility(R.id.btn_settings, gear)
            views.setViewVisibility(R.id.zone_settings, gear)

            // The BOARD opens the app, with launcher semantics so the Splash
            // Screen API shows the icon (a widget-triggered launch without
            // ACTION_MAIN/CATEGORY_LAUNCHER shows only the splash background).
            //
            // Except when the widget is unbound, where there is no board to have
            // tapped: the empty state says "tap to choose", so the tap has to
            // land somewhere that can choose. Sending it to the home screen
            // would be an instruction the app cannot carry out.
            // ── Opens the app ON THIS WIDGET'S STATION ──────────────────────
            //
            // The same link the iOS widget uses (`StationlyDeepLink.board`):
            // `<scheme>://home?station=<groupingId>`. MainActivity hands it to
            // the shared `BoardFocus`, exactly as iOS's ContentView does, so a
            // tap on widget A opens station A and on widget B station B, on both
            // platforms, through one mechanism. Without a station (unbound) it
            // is a plain launch. Scheme from BuildConfig so staging and prod
            // each match what their manifest registers.
            val focusStation = boundSelection?.groupingId?.takeIf { isBound && it.isNotBlank() }
            val openApp = Intent(context, com.stationly.mobile.MainActivity::class.java).apply {
                if (focusStation != null) {
                    action = Intent.ACTION_VIEW
                    data = android.net.Uri.parse(
                        "${com.stationly.mobile.BuildConfig.DEEP_LINK_SCHEME}://home?station=" +
                            android.net.Uri.encode(focusStation),
                    )
                } else {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val boardIntent = when (tapTargetFor(isBound, emptyState)) {
                WidgetTapTarget.APP -> android.app.PendingIntent.getActivity(
                    // Per widget: each one opens its own station.
                    context, appWidgetId, openApp,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                WidgetTapTarget.CONFIGURE -> configureIntent
            }
            views.setOnClickPendingIntent(android.R.id.background, boardIntent)

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
            //
            // The SAME answer the board container uses — see `tapTargetFor`.
            // These were two independent `if (isBound)` expressions and had to
            // agree, forty lines apart, forever.
            val rowsTemplate = android.app.PendingIntent.getActivity(
                context,
                appWidgetId,
                when (tapTargetFor(isBound, emptyState)) {
                    WidgetTapTarget.APP -> openApp
                    WidgetTapTarget.CONFIGURE ->
                        WidgetConfigureActivity.reconfigureIntent(context, appWidgetId)
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.rows_list, rowsTemplate)

            // Set up manual refresh intent
            val refreshIntent = Intent(context, DepartureWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
                // Which widget was pressed. Without it the spinner had no
                // choice but to spin EVERY widget, so tapping refresh on one
                // board set all of them going.
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val refreshPendingIntent = android.app.PendingIntent.getBroadcast(
                // Request code per widget: two PendingIntents differing only in
                // an extra are the SAME PendingIntent, so a shared code would
                // give every widget whichever id was registered last.
                context, appWidgetId, refreshIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_refresh, refreshPendingIntent)
            views.setOnClickPendingIntent(R.id.zone_refresh, refreshPendingIntent)

            // ── The platform pager bar ───────────────────────────────────────
            //
            // Hidden entirely unless this widget is stepping AND has somewhere
            // to step to. When it is shown the collection below holds one
            // platform, so the bar is the only thing naming which.
            if (stepping) {
                views.setViewVisibility(R.id.platform_pager, android.view.View.VISIBLE)
                val pagerChromeDp = applyPagerChrome(context, views, compact = widgetSize.isCompact)
                // "2/3" rather than dots: a RemoteViews cannot draw a pager
                // indicator without a view per dot, and the number says the
                // same thing in less space on a control this size. Shown at
                // EVERY size and never squeezed (iOS): the title gives way.
                val pageMarker = "${safePage + 1}/$pageCount"
                views.setTextViewText(R.id.platform_pager_count, pageMarker)
                val markerDp = WidgetText.widthDp(context, pageMarker, widgetSize.pagerCount, bold = true) +
                    PAGER_COUNT_GAP_DP
                // The ladder from its short rung (the pager always shows the
                // short forms, S019: "DLR Plat. 9", as the in-app board prints
                // it), narrowed further when the arrows and the marker leave
                // too little room, and shrunk to 70% as a last resort, as
                // iOS's pager header does.
                val pagerTitle = WidgetText.fit(
                    context,
                    com.stationly.core.util.MultiLineBoardProcessor
                        .headerVariants(PlatformPages.title(pagedRows)).drop(1)
                        .ifEmpty { listOf(PlatformPages.compactTitle(pagedRows)) },
                    widgetSize.platform, bold = true,
                    availableDp = (widgetWidthDp.takeIf { it > 0 }
                        ?.minus(pagerChromeDp + markerDp + HEADER_SLACK_DP)) ?: 0f,
                )
                views.setTextViewText(R.id.platform_pager_title, pagerTitle.text)
                views.setTextViewTextSize(R.id.platform_pager_title, sp, pagerTitle.sp)
                views.setOnClickPendingIntent(
                    R.id.btn_platform_prev,
                    platformStepIntent(context, appWidgetId, delta = -1),
                )
                views.setOnClickPendingIntent(
                    R.id.btn_platform_next,
                    platformStepIntent(context, appWidgetId, delta = 1),
                )
                // The bigger chevron targets, only while the bar is showing.
                views.setViewVisibility(R.id.zone_platform_prev, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.zone_platform_next, android.view.View.VISIBLE)
                views.setOnClickPendingIntent(
                    R.id.zone_platform_prev,
                    platformStepIntent(context, appWidgetId, delta = -1),
                )
                views.setOnClickPendingIntent(
                    R.id.zone_platform_next,
                    platformStepIntent(context, appWidgetId, delta = 1),
                )
            } else {
                views.setViewVisibility(R.id.platform_pager, android.view.View.GONE)
                views.setViewVisibility(R.id.zone_platform_prev, android.view.View.GONE)
                views.setViewVisibility(R.id.zone_platform_next, android.view.View.GONE)
            }
            val refreshing = appWidgetId in refreshingIds
            val refreshFailed = RefreshFailures.isFailed(context, boundSelection?.groupingId)
            views.setImageViewResource(
                R.id.btn_refresh,
                if (refreshFailed) R.drawable.ic_refresh_failed else R.drawable.ic_refresh,
            )
            val refreshLabel = if (refreshFailed) "Refresh failed, tap to retry" else "Refresh departures"
            views.setContentDescription(R.id.btn_refresh, refreshLabel)
            views.setContentDescription(R.id.zone_refresh, refreshLabel)
            views.setViewVisibility(R.id.btn_refresh, if (refreshing) android.view.View.GONE else android.view.View.VISIBLE)
            views.setViewVisibility(R.id.progress_refresh, if (refreshing) android.view.View.VISIBLE else android.view.View.GONE)

            // Clear existing rows setup
            val rowViews = mutableListOf<RemoteViews>()
            // Which of [rowViews] are platform headers, so BoardProportions knows
            // how deep the stretching container's content is in departure rows.
            val headerViews = mutableSetOf<RemoteViews>()
            // ── Platform headers: the longest wording that fits (iOS HeaderLadder) ──
            //
            // A header that wrapped to two lines and lost its second ("Mildmay
            // Platform not / assigned" on a 2x2). The shared ladder steps
            // "Platform" -> "Plat.", line names -> short forms, then drops the
            // direction; the first rung that fits at this size's type wins.
            val headerAvailDp = (widgetWidthDp.takeIf { it > 0 }?.minus(2 * ROW_TEXT_INSET_DP + HEADER_SLACK_DP)) ?: 0f
            fun fitHeader(header: RemoteViews, text: String) {
                val fitted = WidgetText.fit(
                    context, com.stationly.core.util.MultiLineBoardProcessor.headerVariants(text),
                    widgetSize.platform, bold = true, availableDp = headerAvailDp,
                )
                header.setTextViewText(R.id.platform_name, fitted.text)
                header.setTextViewTextSize(R.id.platform_name, sp, fitted.sp)
            }
            // ── Scroll mode with nothing to scroll fills the widget ─────────
            //
            // When every row fits, the rows are height-flexible and go in the
            // non-scrolling container, sharing the spare height like the iOS
            // widget, instead of leaving a band under the last row (owner,
            // 2026-11-22). Decided BEFORE the rows are built, from their count,
            // because a RemoteViews cannot change its layout afterwards.
            val rowCountEstimate = sduiPayload?.components
                ?.count { it !is com.stationly.core.model.sdui.SduiWidgetComponent.Status }
                ?: drawnRows?.size ?: 0
            val headerCountEstimate = sduiPayload?.components
                ?.count { it is com.stationly.core.model.sdui.SduiWidgetComponent.Header }
                ?: drawnRows?.count { it is com.stationly.core.util.MultiLineBoardProcessor.Row.PlatformHeader }
                ?: 0
            val fitsAll = !stepping && ScrollFit.allRowsFit(
                rowCountEstimate, widgetHeightDpMeasured, context.resources.configuration.fontScale,
                rowScale = widgetSize.rowScale, headerCount = headerCountEstimate, size = widgetSize, sdk = sdk,
            )
            val rowLayout = if (fitsAll) R.layout.widget_departure_row_fill else R.layout.widget_departure_row
            val headerLayout = if (fitsAll) R.layout.widget_platform_header_fill else R.layout.widget_platform_header
            
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
                    views.setInt(android.R.id.background, "setBackgroundColor", dynBgColor)
                }

                views.setTextViewText(R.id.line_name, sduiPayload.title)
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)
                // Keep status container visible as set above
                
                
                sduiPayload.components.forEach { component ->
                    when (component) {
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Header -> {
                            val header = newHeader(context, headerLayout, widgetSize).also { headerViews += it }
                            // Prefix every platform header with the line
                            // context so each platform row carries the
                            // same identity ("Piccadilly: Platform 1",
                            // "Piccadilly: Platform 2", …).
                            val title = StationlyFormatters.platformHeaderText(linePrefix, component.title)
                            fitHeader(header, title)
                             val headerColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.color, dynTextColor)
                             header.setTextColor(R.id.platform_name, headerColor)
                            rowViews.add(header)
                        }
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Row -> {
                            val row = newRow(context, rowLayout, widgetSize)
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
                            val row = newRow(context, rowLayout, widgetSize)
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
                
                if (drawnRows != null && drawnRows.isNotEmpty()) {
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
                    drawnRows.forEach { row ->
                        when (row) {
                            is com.stationly.core.util.MultiLineBoardProcessor.Row.PlatformHeader -> {
                                val header = newHeader(context, headerLayout, widgetSize).also { headerViews += it }
                                fitHeader(header, row.title)
                                header.setOnClickFillInIntent(R.id.platform_name, Intent())
                                rowViews.add(header)
                            }
                            is com.stationly.core.util.MultiLineBoardProcessor.Row.Departure -> {
                                val dep = newRow(context, rowLayout, widgetSize)
                                val destination = if (row.linePrefix.isBlank()) row.destination
                                else row.linePrefix + " " + row.destination
                                dep.setTextViewText(R.id.destination_text, destination)
                                dep.setTextViewText(R.id.eta_text, row.eta)
                                tintDeparted(context, dep, row.departed)
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
                            val header = newHeader(context, headerLayout, widgetSize).also { headerViews += it }
                            header.setOnClickFillInIntent(R.id.platform_name, Intent())
                            // Prefix every platform header with the line
                            // context — same line, but each platform row
                            // carries its own identity.
                            val title = StationlyFormatters.platformHeaderText(linePrefix, row.title)
                            fitHeader(header, title)
                            rowViews.add(header)
                        }
                        is com.stationly.core.util.LegacyRow.Departure -> {
                            val dep = newRow(context, rowLayout, widgetSize)
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
                            val msg = newHeader(context, headerLayout, widgetSize).also { headerViews += it }
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
            val messageSp = fixedRowSp(
                com.stationly.mobile.ui.util.BOARD_FALLBACK_ROW_COUNT, pager = false, status = showsBoard,
            )
            val finalRowViews = run {
                val nowMs = System.currentTimeMillis()
                val londonTime = java.time.Instant.ofEpochMilli(nowMs)
                    .atZone(java.time.ZoneId.of("Europe/London"))
                    .toLocalTime()
                // An unbound widget says WHY it is empty (signed out, no
                // stations, which station?, station removed) rather than
                // showing a board fallback for a board it does not have.
                val empty = emptyState
                    ?: if (!isBound && !hasSelection) WidgetEmptyState.NO_STATIONS else null
                val fallbackState = if (empty != null) {
                    BoardFallbackState(
                        BoardFallbackKind.CONNECTING, 0L
                    )
                } else if (closedNotice != null && predictions.isEmpty()) {
                    BoardFallbackState(
                        BoardFallbackKind.DISRUPTED,
                        message = closedNotice.title to closedNotice.rows(com.stationly.mobile.ui.util.BOARD_FALLBACK_ROW_COUNT - 1) {
                            widgetWidthDp > 0 && WidgetText.widthDp(context, it, messageSp, bold = false) <= widgetWidthDp - 2 * ROW_TEXT_INSET_DP - MESSAGE_SLACK_DP
                        },
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
                    // The SERVED strings, the same `board.fallback.*` copy the
                    // home screen and the screensaver show. This used to pass an
                    // empty map, so the widget alone printed compiled defaults
                    // for "signal lost", "late night" and the rest (AV2-10.7).
                    //
                    // An empty state rides the CONNECTING kind and overrides
                    // its two keys with the state's own words.
                    val widgetStrings = if (empty != null) sduiStrings + mapOf(
                        "board.fallback.connecting.title"  to empty.title(sduiStrings),
                        "board.fallback.connecting.detail" to empty.detail(sduiStrings),
                    ) else sduiStrings
                    buildFallbackRowRemoteViews(
                        context, fallbackState, widgetStrings,
                        rowLayout = R.layout.widget_departure_row_fill,
                        textSizeSp = messageSp,
                        compact = !widgetSize.showsClock,
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
                        sduiStrings,
                        rowLayout = R.layout.widget_departure_row_fill,
                        textSizeSp = messageSp,
                    )
                } else rowViews
            }

            // ── Stepping goes to the flipper, scrolling to the list ─────────
            //
            // Only when there is more than one platform AND real rows to show.
            // A fallback board (no upcoming departures, signal lost) keeps the
            // ordinary path: its rows are a MESSAGE, and cross-fading a message
            // between two identical pages would be motion for its own sake.
            if (stepping && finalRowViews === rowViews) {
                applyPagesToFlipper(
                    context = context,
                    views = views,
                    pages = PlatformPages.split(boardRows.orEmpty()),
                    page = safePage,
                    rowCap = ROWS_PER_PLATFORM,
                    motion = motion,
                    fromPage = PlatformPages.clamp(fromPage ?: safePage, pageCount),
                    size = widgetSize,
                    rowSp = pageRowSp,
                )
                if (dropsStatus) views.setViewVisibility(R.id.status_container, android.view.View.GONE)
                if (motion == WidgetMotion.REFRESH) settleAfterRoll(context, appWidgetId)
            } else if (
                motion == WidgetMotion.REFRESH &&
                finalRowViews === rowViews && rowViews.isNotEmpty() &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            ) {
                // ── Scroll mode gets the refresh roll too ───────────────────
                //
                // The scrolling list cannot animate its contents, so a refresh
                // the user asked for draws the board once in the refresh
                // flipper, which plays the same roll as step mode, and a render
                // moments later puts the scrolling list back with the same rows
                // (owner, 2026-11-22: "not on the departure rows"). The motion
                // flag is one-shot, so only the tap animates.
                applyRefreshSnapshot(context, views, rowViews)
                settleAfterRoll(context, appWidgetId)
            } else {
                // A message (fallback or empty state) fills the board; a real
                // board keeps the scrolling list.
                applyRowsToWidget(views, finalRowViews, fill = finalRowViews !== rowViews || fitsAll)
            }

            // ── Row proportions: the hierarchy at any size (BoardProportions) ──
            //
            // Only a board whose rows STRETCH is proportioned. Stepping pages
            // always stretch (ROWS_PER_PLATFORM rows under the pager); the other
            // containers stretch when they hold a message or everything fits. A
            // scrolling list keeps natural heights: its rows are fixed and the
            // space is theirs to scroll through.
            val paging = stepping && finalRowViews === rowViews
            fun solve(rows: Int, headers: Int) = BoardProportions.solve(
                heightDp = widgetHeightDpMeasured, size = widgetSize,
                fontScale = context.resources.configuration.fontScale,
                rows = rows, headers = headers, pager = stepping, status = showsBoard && !(paging && dropsStatus),
            )
            val proportions = when {
                paging -> solve(rows = ROWS_PER_PLATFORM, headers = 0)
                finalRowViews !== rowViews || fitsAll -> {
                    val headers = finalRowViews.count { it in headerViews }
                    solve(rows = finalRowViews.size - headers, headers = headers)
                }
                else -> null
            }
            applyProportions(views, proportions, widgetSize, stepping)

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

        /**
         * Draw the paged board into the flipper whose animation this motion wants.
         *
         * ## Three flippers, one slot
         * `ViewFlipper.setInAnimation` is not a `@RemotableViewMethod`, so an
         * update cannot choose an animation — a flipper's pair is fixed when the
         * layout is inflated. One flipper therefore means one animation for
         * every kind of change, which is how "next platform", "previous
         * platform" and "you pressed refresh" were all the same apologetic
         * cross-fade, and why a slide was impossible: it would have travelled
         * the same way whichever arrow you pressed.
         *
         * So the layout stacks three, each with its own pair, and this picks
         * one. The two it is not using are emptied and hidden; the one it is
         * gets both frames written into it. Because the outgoing frame is a
         * fresh copy of the board already on screen, swapping which flipper is
         * VISIBLE is invisible — the pixels do not change at the moment of the
         * swap, only afterwards, as the animation runs.
         *
         * ## Why a silent redraw is silent
         * [WidgetMotion.NONE] writes ONE child and never calls
         * `setDisplayedChild`. That matters more than it sounds: a flipper with
         * `animateFirstView` left at its default animates the first child shown
         * after a `removeAllViews`, so the previous version of this function —
         * which ended on `setDisplayedChild(flipper, 0)` and carried a comment
         * explaining that index 0 could not animate — played the in-animation on
         * every single redraw. One refresh tap measured 132 redraws across four
         * placed widgets. That is the "multiple flashes ... zoo zoo zoo" the
         * owner reported, and it is why scoping the redraws had not fixed it:
         * one redraw was already one flash.
         *
         * `animateFirstView="false"` in the layout plus no `setDisplayedChild`
         * here makes an ambient redraw cost nothing visually, however many of
         * them arrive.
         */
        private fun applyPagesToFlipper(
            context: Context,
            views: RemoteViews,
            pages: List<List<com.stationly.core.util.MultiLineBoardProcessor.Row>>,
            page: Int,
            rowCap: Int,
            motion: WidgetMotion,
            fromPage: Int,
            size: WidgetSize = WidgetSize.MEDIUM,
            rowSp: Float = size.row,
        ) {
            views.setViewVisibility(R.id.rows_list, android.view.View.GONE)
            views.setViewVisibility(R.id.rows_container, android.view.View.GONE)
            // Every flipper emptied and hidden first, including the one about to
            // be used. A stage left holding children from the last motion is how
            // a step lands on a stale page, and a stage left VISIBLE behind the
            // live one is a second board taking up the slot.
            WidgetMotion.flippers.forEach { id ->
                views.removeAllViews(id)
                views.setViewVisibility(id, android.view.View.GONE)
            }
            val stage = motion.flipperId
            views.setViewVisibility(stage, android.view.View.VISIBLE)

            if (!motion.animates) {
                views.addView(stage, pageViews(context, pages.getOrNull(page), rowCap, size, rowSp = rowSp))
                // NO `setDisplayedChild`. A flipper with one child is already
                // showing it, and asking again is exactly what flashed.
                return
            }

            // The board being left, then the board arriving.
            views.addView(stage, pageViews(context, pages.getOrNull(fromPage), rowCap, size, rowSp = rowSp))
            views.addView(stage, pageViews(context, pages.getOrNull(page), rowCap, size, arrivingPageLayout(motion), rowSp))
            // ── Two calls, and both are needed ──────────────────────────────
            //
            // `ViewAnimator.showOnly` animates when `!mFirstTime ||
            // mAnimateFirstTime`, and `removeAllViews` has just set mFirstTime.
            // With `animateFirstView="false"` in the layout the first call is
            // therefore silent — it only lands on the outgoing frame and clears
            // mFirstTime — and the second one moves off it and animates. One
            // call alone would be the silent one, and the board would cut.
            views.setDisplayedChild(stage, 0)
            views.setDisplayedChild(stage, 1)
        }

        /**
         * Sizes the rows outside the stretching container (BoardProportions),
         * or puts them back to their natural heights when [heights] is null.
         *
         * Android 12+ only: `setViewLayoutHeight` arrived in API 31. Older
         * versions keep natural heights, which still hold the order of the
         * type ladder; they just do not grow with a tall widget.
         *
         * Always written, never skipped: a full update onto a widget on screen
         * is `reapply`, which keeps the last render's layout params, so a board
         * going from stretched to scrolling has to be put back explicitly.
         *
         * The tap zones move with the header, since they are laid over it at
         * fixed offsets.
         */
        private fun applyProportions(views: RemoteViews, heights: BoardProportions.Heights?, size: WidgetSize, stepping: Boolean) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
            val dip = android.util.TypedValue.COMPLEX_UNIT_DIP
            val px = android.util.TypedValue.COMPLEX_UNIT_PX
            val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT.toFloat()
            fun height(id: Int, dp: Float?) =
                if (dp == null) views.setViewLayoutHeight(id, wrap, px) else views.setViewLayoutHeight(id, dp, dip)

            height(R.id.header_row, heights?.stationDp)
            height(R.id.platform_pager, heights?.pagerDp)
            height(R.id.status_container, heights?.statusDp)
            height(R.id.footer_row, heights?.footerDp)

            // Zones: the header and the gap under it for gear/refresh, and the
            // pager's band (and a little of the row below) for the chevrons.
            val header = heights?.stationDp ?: size.naturalHeaderDp
            val pagerTop = header + BoardProportions.GAP_DP
            views.setViewLayoutHeight(R.id.zone_settings, pagerTop, dip)
            // A short board's header is shorter than a thumb. Nothing is under the
            // corner but the first row, so refresh keeps its reach; with arrows the
            // pager is there and it cannot.
            val refreshZone = if (stepping) pagerTop else maxOf(pagerTop, MIN_REFRESH_ZONE_DP)
            views.setViewLayoutHeight(R.id.zone_refresh, refreshZone, dip)
            val pagerBand = (heights?.pagerDp ?: NATURAL_PAGER_DP) + CHEVRON_ZONE_OVERHANG_DP
            listOf(R.id.zone_platform_prev, R.id.zone_platform_next).forEach { id ->
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, pagerTop, dip)
                views.setViewLayoutHeight(id, pagerBand, dip)
            }
        }

        /**
         * The pager bar's arrow columns for this size: full on medium and large,
         * slim on COMPACT (Android 12+; older versions cannot resize a view).
         * Written both ways every time, because `reapply` keeps the last
         * render's state. Returns the chrome's width in dp, for the title
         * fitter; the "1/2" beside the title is measured separately.
         */
        private fun applyPagerChrome(context: Context, views: RemoteViews, compact: Boolean): Float {
            val legacy = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
            val column = if (compact && !legacy) COMPACT_CHEVRON_DP else CHEVRON_DP
            if (!legacy) {
                val density = context.resources.displayMetrics.density
                fun px(dp: Float) = (dp * density).toInt()
                val edge = px(CHEVRON_EDGE_DP)
                val inward = px(column - CHEVRON_EDGE_DP - CHEVRON_INK_DP)
                val dip = android.util.TypedValue.COMPLEX_UNIT_DIP
                views.setViewLayoutWidth(R.id.btn_platform_prev, column, dip)
                views.setViewLayoutWidth(R.id.btn_platform_next, column, dip)
                views.setViewPadding(R.id.btn_platform_prev, edge, edge, inward, edge)
                views.setViewPadding(R.id.btn_platform_next, inward, edge, edge, edge)
                // Narrower zones on a 2x2, so the two do not meet over the title.
                val zone = if (compact) COMPACT_CHEVRON_ZONE_DP else CHEVRON_ZONE_DP
                views.setViewLayoutWidth(R.id.zone_platform_prev, zone, dip)
                views.setViewLayoutWidth(R.id.zone_platform_next, zone, dip)
            }
            return 2 * PAGER_PADDING_DP + 2 * column
        }

        /** A departure row in this size class's type (WidgetSize). */
        private fun newRow(context: Context, layout: Int, size: WidgetSize): RemoteViews =
            newRow(context, layout, size.row)

        private fun newRow(context: Context, layout: Int, rowSp: Float): RemoteViews =
            RemoteViews(context.packageName, layout).apply {
                setTextViewTextSize(R.id.destination_text, android.util.TypedValue.COMPLEX_UNIT_SP, rowSp)
                setTextViewTextSize(R.id.eta_text, android.util.TypedValue.COMPLEX_UNIT_SP, rowSp)
            }

        /**
         * The fixed rows' padding for this size class. Written every time, as
         * `reapply` keeps the last render's. The side insets are the layout's.
         */
        private fun applyRowPadding(context: Context, views: RemoteViews, size: WidgetSize) {
            val density = context.resources.displayMetrics.density
            fun px(dp: Float) = Math.round(dp * density)
            val header = size.headerPadding(android.os.Build.VERSION.SDK_INT)
            views.setViewPadding(R.id.header_row, 0, px(header.headerPadTopDp), 0, px(header.headerPadBottomDp))
            val status = px(ROW_TEXT_INSET_DP)
            views.setViewPadding(R.id.status_container, status, px(size.statusPadDp), status, px(size.statusPadDp))
            val footer = px(size.footerInsetDp)
            views.setViewPadding(R.id.footer_row, footer, px(size.footerPadTopDp), footer, px(size.footerPadBottomDp))
        }

        /** A platform header in this size class's type (WidgetSize). */
        private fun newHeader(context: Context, layout: Int, size: WidgetSize): RemoteViews =
            RemoteViews(context.packageName, layout).apply {
                setTextViewTextSize(R.id.platform_name, android.util.TypedValue.COMPLEX_UNIT_SP, size.platform)
            }

        /**
         * Scroll mode's refresh roll: the board, twice, in the refresh flipper.
         *
         * Both frames carry the new rows (the old ones were overwritten by the
         * fetch), so it reads as the board turning over onto itself, the same
         * motion step mode plays. The same two-call `setDisplayedChild` as
         * [applyPagesToFlipper], for the same reason.
         */
        private fun applyRefreshSnapshot(context: Context, views: RemoteViews, rowViews: List<RemoteViews>) {
            views.setViewVisibility(R.id.rows_list, android.view.View.GONE)
            views.setViewVisibility(R.id.rows_container, android.view.View.GONE)
            WidgetMotion.flippers.forEach { id ->
                views.removeAllViews(id)
                views.setViewVisibility(id, android.view.View.GONE)
            }
            val stage = WidgetMotion.REFRESH.flipperId
            views.setViewVisibility(stage, android.view.View.VISIBLE)
            // The leaving frame is the plain page; the arriving one rolls its
            // rows in one by one (arrivingPageLayout).
            listOf(R.layout.widget_platform_page, arrivingPageLayout(WidgetMotion.REFRESH)).forEach { layout ->
                val page = RemoteViews(context.packageName, layout)
                rowViews.forEach { page.addView(R.id.platform_page_rows, it) }
                views.addView(stage, page)
            }
            views.setDisplayedChild(stage, 0)
            views.setDisplayedChild(stage, 1)
        }

        /**
         * Redraw [appWidgetId] at rest once the refresh roll has played.
         *
         * Scroll mode needs it to get its list back. Step mode needs it because
         * a launcher re-applies the LAST update when it rebuilds a widget
         * (unfolding the phone, the launcher restarting), and a stored refresh
         * would replay the fade and the row cascade with nobody pressing
         * anything. The motion flag is spent by now, so this draws still. It
         * lands before the marquee's own start delay, so the status row does
         * not visibly restart.
         */
        private fun settleAfterRoll(context: Context, appWidgetId: Int) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                kotlinx.coroutines.delay(REFRESH_SNAPSHOT_MS)
                runCatching { updateOne(context, appWidgetId) }
            }
        }

        /**
         * The page a motion brings in. A refresh press gets the page whose rows
         * roll in one after another (owner, 2026-11-23: "pull every row", not
         * the whole board as one curtain). Everything else gets the plain page:
         * its layout animation would otherwise play on every push and tick.
         */
        private fun arrivingPageLayout(motion: WidgetMotion): Int =
            if (motion == WidgetMotion.REFRESH) R.layout.widget_platform_page_refresh
            else R.layout.widget_platform_page

        /** One platform, padded to a fixed height so the widget cannot resize. */
        private fun pageViews(
            context: Context,
            page: List<com.stationly.core.util.MultiLineBoardProcessor.Row>?,
            rowCap: Int,
            size: WidgetSize = WidgetSize.MEDIUM,
            layout: Int = R.layout.widget_platform_page,
            rowSp: Float = size.row,
        ): RemoteViews {
            val pageViews = RemoteViews(context.packageName, layout)
            // `bodyPadded`: the pager bar names the platform, so the header comes
            // off, and every page is the same height whatever the data does.
            com.stationly.core.util.PlatformPages.bodyPadded(page.orEmpty(), rowCap)
                .forEach { row ->
                    departureRowViews(context, row, rowSp)?.let {
                        pageViews.addView(R.id.platform_page_rows, it)
                    }
                }
            return pageViews
        }

        /** One departure row, or null for a header (the pager bar names it). */
        private fun departureRowViews(
            context: Context,
            row: com.stationly.core.util.MultiLineBoardProcessor.Row,
            rowSp: Float,
        ): RemoteViews? {
            if (row !is com.stationly.core.util.MultiLineBoardProcessor.Row.Departure) return null
            // The FILL row: a page never scrolls, so its rows share the slot's
            // height equally, as the iOS widget's cells do.
            val dep = newRow(context, R.layout.widget_departure_row_fill, rowSp)
            val destination =
                if (row.linePrefix.isBlank()) row.destination
                else row.linePrefix + " " + row.destination
            dep.setTextViewText(R.id.destination_text, destination)
            dep.setTextViewText(R.id.eta_text, row.eta)
            tintDeparted(context, dep, row.departed)
            dep.setInt(
                R.id.destination_text, "setGravity",
                android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL,
            )
            dep.setOnClickFillInIntent(R.id.departure_row_root, Intent())
            return dep
        }

        /**
         * A retained already-departed train ("Gone", [BoardTicker]'s label) in
         * dim amber, live trains in amber: the same two shades the app board
         * uses (`BoardAmberDim` / `BoardAmber` in Board.kt), so one fact never
         * reads as two. Set both ways every time: rows are recycled RemoteViews.
         */
        private fun tintDeparted(context: Context, row: RemoteViews, departed: Boolean) {
            val color = context.getColor(if (departed) R.color.tfl_amber_dim else R.color.tfl_amber)
            row.setTextColor(R.id.destination_text, color)
            row.setTextColor(R.id.eta_text, color)
        }

        /**
         * @param fill the rows are a MESSAGE (a fallback or an empty state):
         *   a handful of `widget_departure_row_fill` rows that never scroll. They
         *   go in the non-scrolling container on every API level, so they
         *   stretch to fill the board instead of sitting at the top of a list
         *   with an empty band under them.
         */
        private fun applyRowsToWidget(views: RemoteViews, rowViews: List<RemoteViews>, fill: Boolean = false) {
            // ── EVERY flipper, not just the resting one ─────────────────────
            //
            // A full update onto a widget already on screen is `reapply`, which
            // runs these actions against the live views and does NOT reset
            // anything back to what the XML says. So a widget whose last render
            // was a backward step left `platform_flipper_prev` VISIBLE, and
            // switching that station to scroll would have drawn the old paged
            // board on top of the new list. The XML default only ever applies to
            // the first inflate.
            WidgetMotion.flippers.forEach { id ->
                views.removeAllViews(id)
                views.setViewVisibility(id, android.view.View.GONE)
            }
            if (!fill && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
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

/**
 * Where a tap on the widget's BODY goes — see
 * [DepartureWidgetProvider.tapTargetFor].
 *
 * Top level rather than nested in the companion: Kotlin does not flatten a
 * companion's nested types into the outer class's namespace, so it would have
 * to be spelled `DepartureWidgetProvider.Companion.TapTarget` at every call
 * site and in every test.
 */
enum class WidgetTapTarget { APP, CONFIGURE }
