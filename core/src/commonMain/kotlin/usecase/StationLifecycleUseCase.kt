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
            notificationManager.subscribeToTopics(
                listOf(
                    "Station_${selection.station}",
                    "LineStatus_${selection.mode}_${selection.line}"
                )
            )
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
        val topics = buildList {
            if (remaining.none { it.station == selection.station }) {
                add("Station_${selection.station}")
            }
            if (remaining.none { it.mode == selection.mode && it.line == selection.line }) {
                add("LineStatus_${selection.mode}_${selection.line}")
            }
        }
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
     * Cleanup everything (Logout/Clear All)
     *
     * Topic collection must happen before clearAll() so the unsubscription
     * queue written by unsubscribeFromTopics() is not immediately wiped.
     */
    suspend fun cleanupAll() {
        val allSelections = sqlStorage.getAllSelections()
        val allTopics = allSelections.flatMap { sel ->
            listOf("Station_${sel.station}", "LineStatus_${sel.mode}_${sel.line}")
        }.distinct()

        selectionRepository.clearAll()
        sqlStorage.clearAllData()
        widgetManager.clearWidgetData()
        storageManager.clearAll()

        // Re-queue unsubscriptions after clearAll so they survive the wipe
        if (allTopics.isNotEmpty()) {
            notificationManager.unsubscribeFromTopics(allTopics)
        }
    }
}
