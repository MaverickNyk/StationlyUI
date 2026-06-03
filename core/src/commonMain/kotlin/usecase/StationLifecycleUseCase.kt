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
        sqlStorage.clearPredictions(selection.station, selection.line)
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
    suspend fun completeSetupAsync(selection: UserSelection) {
        notificationManager.subscribeToTopics(
            listOf(
                "Station_${selection.station}",
                "LineStatus_${selection.mode}_${selection.line}"
            )
        )
        val now = Clock.System.now().toEpochMilliseconds()
        val preds = sqlStorage.getPredictions(selection.station, selection.line)
        val status = sqlStorage.getLineStatus(selection.mode, selection.line)
        widgetManager.updateWidget(
            WidgetState(
                stationName = selection.stationName,
                lineName = selection.line,
                predictions = preds,
                status = status?.statusSeverityDescription,
                lastUpdated = now / 1000
            )
        )
    }

    /**
     * Discard a station: Unsubscribe and Wipe Data
     */
    suspend fun discardStation(selection: UserSelection, clearSelectionInRepo: Boolean = true) {
        // 1. Unsubscribe from FCM topics
        val topics = listOf(
            "Station_${selection.station}",
            "LineStatus_${selection.mode}_${selection.line}"
        )
        notificationManager.unsubscribeFromTopics(topics)

        // 2. Clear local data (Predictions and Status)
        sqlStorage.clearPredictions(selection.station, selection.line)
        
        // Trigger UI pings to clear state
        storageManager.saveString("predictions_${selection.station}_${selection.line}", "discarded_${Clock.System.now().toEpochMilliseconds()}")
        storageManager.saveString("line_status_data", "discarded_${Clock.System.now().toEpochMilliseconds()}")
        
        // 3. Remove from selections if requested (not done for simple logout)
        if (clearSelectionInRepo) {
            selectionRepository.deleteSelection(selection)
        }

        // 4. Reset Widget to a clean state
        widgetManager.showWaitingState("No Station", "Select a station to begin")
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
