package com.stationly.core.usecase

import com.stationly.core.model.UserSelection
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.platform.NotificationManager
import com.stationly.core.platform.WidgetManager
import com.stationly.core.model.WidgetState
import com.stationly.core.platform.StorageManager
import com.stationly.core.repository.SqlStorage
import kotlinx.datetime.Clock

/**
 * StationLifecycleUseCase - Centralized logic for station setup and removal.
 * 
 * This ensures that FCM subscriptions, local storage, widget state, and 
 * initial data fetching are always in sync when a station is added or removed.
 */
class StationLifecycleUseCase(
    private val selectionRepository: SelectionRepository,
    private val departureRepository: DepartureRepository,
    private val notificationManager: NotificationManager,
    private val widgetManager: WidgetManager,
    private val sqlStorage: SqlStorage,
    private val storageManager: StorageManager
) {

    /**
     * Set up a station end to end — used by login + cross-device reconcile,
     * where blocking until everything is done is fine.
     *
     * Composed from the same two building blocks the interactive "Setup the
     * board" flow uses:
     *  - [persistAndFetch] — persist + fetch the first predictions/line status
     *    (so the board renders populated), and
     *  - [completeSetupAsync] — subscribe to FCM topics + push to the widget.
     *
     * The interactive flow awaits [persistAndFetch] then runs [completeSetupAsync]
     * on a detached scope so navigation isn't delayed; here we simply await both.
     */
    suspend fun setupStation(selection: UserSelection, isFirstTime: Boolean = true) {
        persistAndFetch(selection)
        completeSetupAsync(selection)
    }

    /**
     * Essential setup the board needs to render POPULATED, in one awaited call:
     * persist the selection and eagerly fetch its first predictions + line status
     * into SQL. Await this BEFORE navigating to the board so the user lands on a
     * board that already has data, instead of a brief "no departures yet" flash
     * while a backgrounded fetch is still in flight.
     *
     * The REST fetch is best-effort — if it fails (offline, TfL hiccup) we still
     * return so the caller can navigate; the board falls back to its empty state
     * and FCM / pull-to-refresh fills it in. Does NOT subscribe FCM or touch the
     * widget — those are the non-blocking tail in [completeSetupAsync].
     */
    suspend fun persistAndFetch(selection: UserSelection) {
        selectionRepository.saveSelection(selection, null)
        sqlStorage.clearPredictions(selection.station, selection.line, selection.direction)
        try {
            departureRepository.fetchInitialData(selection)
        } catch (e: Exception) {
            // Best-effort: navigate anyway; live data arrives via FCM / refresh.
        }
        val now = Clock.System.now().toEpochMilliseconds()
        storageManager.saveString("predictions_${selection.station}_${selection.line}", "updated_$now")
        storageManager.saveString("line_status_data", "updated_$now")
    }

    /**
     * Non-essential setup tail, safe to run AFTER navigation on a detached scope:
     * subscribe to FCM topics for live updates and push the freshly-fetched data
     * to the widget. None of this blocks the board from showing.
     */
    suspend fun completeSetupAsync(
        selection: UserSelection,
        /**
         * Set false when the caller has already subscribed the DISTINCT topic
         * set for a batch. Several boards routinely share one topic — two bus
         * routes on one pole, several lines at one station — so subscribing per
         * board re-sends the same topic once per board.
         */
        subscribeTopics: Boolean = true,
    ) {
        if (subscribeTopics) {
            notificationManager.subscribeToTopics(topicsFor(selection))
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val preds = sqlStorage.getPredictions(selection.station, selection.line, selection.direction)
        val status = sqlStorage.getLineStatus(selection.mode, selection.line)
        widgetManager.updateWidget(
            WidgetState(
                stationName = selection.stationName,
                lineName = selection.line,
                predictions = preds,
                status = status?.statusSeverityDescription,
                lastUpdated = now / 1000,
                direction = selection.direction,
                mode = selection.mode
            )
        )
    }

    /**
     * Update a board that already exists, keeping its cached rows.
     *
     * For a board whose FILTER changed elsewhere — the user narrowed it to two
     * destinations on another device. Deliberately not `discardStation` +
     * `setupStation`: that pair tears down the topic subscription and clears the
     * predictions, so a board the user is looking at would empty and refill for
     * a change that touches which of its existing rows are shown and nothing
     * else.
     *
     * The re-apply is the necessary half. `matchesFilter` is computed once at
     * ingest so the board never filters on read, which means a filter change
     * has no effect at all until the stored rows are re-evaluated against it.
     */
    suspend fun updateBoardFilter(selection: UserSelection) {
        selectionRepository.updateSelectionInPlace(selection)
        sqlStorage.reapplyFilter(
            selection.station,
            selection.line,
            selection.direction,
            selection.destinationIds.toSet(),
            selection.viaKeys.toSet(),
        )
    }

    /**
     * Discard a station: Unsubscribe and Wipe Data
     *
     * @param remaining the selections that will still exist after this delete.
     *        A station topic is shared by every line tracked at that station,
     *        and a line-status topic by every station on that line — so once a
     *        user can track several lines at one station, tearing both topics
     *        down unconditionally silences the boards they are *keeping*.
     *        Deleting the Piccadilly board at King's Cross would unsubscribe
     *        `Station_940GZZLUKSX` out from under the Victoria board still
     *        sitting there, which then goes stale with no visible cause.
     *
     *        Defaults to empty, which reproduces the original unconditional
     *        teardown exactly — that is what the single-board Android call
     *        sites want, so they are unaffected by this parameter existing.
     */
    suspend fun discardStation(
        selection: UserSelection,
        clearSelectionInRepo: Boolean = true,
        remaining: List<UserSelection> = emptyList(),
    ) {
        // 1. Unsubscribe from FCM topics — but only those no survivor still needs.
        val topics = topicsToRelease(selection, remaining)
        if (topics.isNotEmpty()) notificationManager.unsubscribeFromTopics(topics)

        // 2. Clear local data (Predictions and Status). Only THIS direction —
        //    the user may still be tracking the opposite direction of the same
        //    line at this station.
        sqlStorage.clearPredictions(selection.station, selection.line, selection.direction)
        
        // Trigger UI pings to clear state
        storageManager.saveString("predictions_${selection.station}_${selection.line}", "discarded_${Clock.System.now().toEpochMilliseconds()}")
        storageManager.saveString("line_status_data", "discarded_${Clock.System.now().toEpochMilliseconds()}")
        
        // 3. Remove from selections if requested (not done for simple logout)
        if (clearSelectionInRepo) {
            selectionRepository.deleteSelection(selection)
        }

        // 4. Point the widget at whatever is left.
        //
        // This used to unconditionally drop to the waiting state, which was
        // right when deleting a board meant deleting the only board. With
        // several boards it would blank a widget that still has a perfectly
        // good station to show, until some later refresh happened to repopulate
        // it. Re-render the new primary instead, and only fall back to the
        // waiting state when nothing at all remains.
        val newPrimary = remaining.firstOrNull()
        if (newPrimary == null) {
            widgetManager.showWaitingState("No Station", "Select a station to begin")
        } else {
            completeSetupAsync(newPrimary)
        }
    }

    /**
     * Cleanup everything: logout, account deletion, forced sign-out.
     *
     * ## The topics go FIRST, and off the platform's own record
     * This used to derive the list from `getAllSelections()` and unsubscribe it
     * *after* the wipe, with a comment explaining that the ordering protected an
     * "unsubscription queue" written into storage for a Swift bridge to flush.
     * That queue is gone — iOS no longer links FirebaseMessaging and its topics
     * are live stream subscriptions now — so the ordering was protecting a
     * mechanism that no longer exists, while costing the one that does:
     * Android's ledger lives in the prefs file `storageManager.clearAll()`
     * wipes, so by the time the old code asked, the only record of anything not
     * derivable from the selections was already gone.
     *
     * [NotificationManager.clearAllTopics] asks the platform what it is actually
     * subscribed to, which is the same set on a healthy device and a strictly
     * larger one on a device that has been running for a year. The rows that
     * differ are exactly the ones that must not be left behind on a phone
     * somebody else is about to sign into.
     */
    suspend fun cleanupAll() {
        notificationManager.clearAllTopics()

        selectionRepository.clearAll()
        sqlStorage.clearAllData()
        widgetManager.clearWidgetData()
        storageManager.clearAll()
    }

    /**
     * Subscribe a batch of boards in ONE call, the distinct topics only.
     *
     * Several boards routinely share a topic: two bus routes at one pole, four
     * lines at one interchange, both directions of anything. Subscribing per
     * board re-sends the same topic once per board, and on FCM that is also one
     * wake-up per board for a single message.
     *
     * Here rather than at the call site because a topic name spelled anywhere
     * else is a second vocabulary — see [topicsFor].
     */
    suspend fun subscribeTopicsFor(selections: List<UserSelection>) {
        val topics = topicsFor(selections)
        if (topics.isNotEmpty()) notificationManager.subscribeToTopics(topics)
    }

    /**
     * State the whole subscription set from what is actually on this device.
     *
     * The counterpart to the per-board edits: those are correct and they leak,
     * because a device is not present for every change made to it. Call this
     * where the full list is known and settled — app foreground, the tail of a
     * cross-device reconcile — and the platform repairs the difference. What
     * that means per platform, and why an empty list is never a delete, is on
     * [com.stationly.core.platform.NotificationManager.reconcileTopics].
     */
    suspend fun reconcileTopics() {
        notificationManager.reconcileTopics(topicsFor(sqlStorage.getAllSelections()))
    }

    /**
     * The topic vocabulary, in one place.
     *
     * `Station_{naptan}` and `LineStatus_{mode}_{line}`, unchanged since v1 and
     * shared with the backend's fan-out — the names are a wire contract, not an
     * implementation detail, and `V1GoldenTest` pins their shape against
     * fixtures captured from the shipped app. They were spelled out at six call
     * sites across three modules; a subscribe that disagrees with an
     * unsubscribe by one character is a board that never stops receiving, or
     * never starts, and neither says anything.
     *
     * The station id here is [UserSelection.station] — the naptan departures are
     * FETCHED from, which on a bus route is the pole and not the hub the user
     * picked. That is what the backend publishes to, so it is what a device
     * subscribes to.
     */
    companion object {
        fun stationTopic(selection: UserSelection) = "Station_${selection.station}"

        fun lineStatusTopic(selection: UserSelection) =
            "LineStatus_${selection.mode}_${selection.line}"

        fun topicsFor(selection: UserSelection) =
            listOf(stationTopic(selection), lineStatusTopic(selection))

        fun topicsFor(selections: List<UserSelection>): List<String> =
            selections.flatMap(::topicsFor).distinct()

        /**
         * Which of a removed board's topics are now genuinely unused.
         *
         * A station topic is shared by every line tracked at that station, and a
         * line-status topic by every station on that line. Deleting the
         * Piccadilly board at King's Cross must not unsubscribe
         * `Station_940GZZLUKSX` out from under the Victoria board still sitting
         * there, which would then go quiet with nothing on screen saying why.
         *
         * Here rather than inline in [discardStation] so the golden fixtures
         * captured from the shipped v1 app can assert against THIS function
         * instead of against a restatement of it in a test file. A rule with two
         * implementations has no pinned behaviour, only two opinions.
         *
         * @param remaining the selections that will still exist after the delete.
         */
        fun topicsToRelease(
            removed: UserSelection,
            remaining: List<UserSelection>,
        ): List<String> = buildList {
            if (remaining.none { it.station == removed.station }) add(stationTopic(removed))
            if (remaining.none { it.mode == removed.mode && it.line == removed.line }) {
                add(lineStatusTopic(removed))
            }
        }
    }
}
