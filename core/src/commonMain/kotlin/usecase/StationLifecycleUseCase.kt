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
     * Setup a station: Subscriptions, Initial Data, and Persistence
     */
    suspend fun setupStation(selection: UserSelection, isFirstTime: Boolean = true) {
        // 1. Save to local SQL (SelectionRepository handles Flow updates)
        selectionRepository.saveSelection(selection, null)

        // 2. Subscribe to FCM topics for real-time updates
        val topics = listOf(
            "Station_${selection.station}",
            "LineStatus_${selection.mode}_${selection.line}"
        )
        notificationManager.subscribeToTopics(topics)

        // 3. Clear any stale predictions for this station/line before fetch
        sqlStorage.clearPredictions(selection.station, selection.line)
        storageManager.saveString("predictions_${selection.station}_${selection.line}", "cleared_${Clock.System.now().toEpochMilliseconds()}")

        // 4. Show 'Connecting' widget state immediately
        widgetManager.showWaitingState(selection.stationName, selection.line)

        // 5. Eagerly fetch Line Status & Predictions via REST
        departureRepository.fetchInitialData(selection)
        
        // 6. Notify UI that fresh data is ready
        val now = Clock.System.now().toEpochMilliseconds()
        storageManager.saveString("predictions_${selection.station}_${selection.line}", "updated_$now")
        storageManager.saveString("line_status_data", "updated_$now")

        // 7. Push eagerly fetched data to the widget
        val refreshedPreds = sqlStorage.getPredictions(selection.station, selection.line)
        val refreshedStatus = sqlStorage.getLineStatus(selection.mode, selection.line)
        widgetManager.updateWidget(
            WidgetState(
                stationName = selection.stationName,
                lineName = selection.line,
                predictions = refreshedPreds,
                status = refreshedStatus?.statusSeverityDescription,
                lastUpdated = now
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
     */
    suspend fun cleanupAll() {
        val allSelections = sqlStorage.getAllSelections()
        allSelections.forEach { selection ->
            discardStation(selection, clearSelectionInRepo = false)
        }
        selectionRepository.clearAll()
        sqlStorage.clearAllData()
        storageManager.clearCache()
    }
}
