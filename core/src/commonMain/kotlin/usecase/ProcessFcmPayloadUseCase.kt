package com.stationly.core.usecase

import com.stationly.core.model.FcmPayload
import com.stationly.core.model.LineStatus
import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SqlStorage
import com.stationly.core.platform.WidgetManager
import com.stationly.core.platform.StorageManager
import com.stationly.core.util.FreshDataNotifier
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.StationlyFormatters
import kotlinx.datetime.Clock

/**
 * Process FCM Payload Use Case
 *
 * This is the core of the real-time widget updates. It processes incoming
 * FCM messages and updates SQLite + the widget with new data. It mirrors the
 * Android FcmMessagingService routing (Station_* prediction payloads and
 * LineStatus_* status payloads) for platforms without a native FCM service
 * (iOS — invoked from FcmPayloadBridge).
 */
class ProcessFcmPayloadUseCase(
    private val departureRepository: DepartureRepository,
    private val widgetManager: WidgetManager,
    private val storageManager: StorageManager,
    private val formatDeparturesUseCase: FormatDeparturesUseCase,
    // Optional fallback selection source. Selections are persisted to SQLite
    // (SelectionRepository → sqlStorage), but NOT to storageManager — so on iOS
    // `storageManager.loadSelections()` is always empty and the widget never
    // updated on an FCM push. Passing sqlStorage lets us fall back to the real
    // source. Defaults to null so existing (Android) call sites are unchanged.
    private val sqlStorage: SqlStorage? = null
) {

    // Same persistence path the Android FcmMessagingService uses — writes the
    // filtered/formatted predictions to SQLite so every surface (board poll,
    // widget, FreshDataNotifier collectors) reads one consistent source.
    private val syncPredictions: SyncPredictionsUseCase? =
        sqlStorage?.let { SyncPredictionsUseCase(it) }

    /**
     * Legacy entry point (payload with no topic context). Kept for source
     * compatibility — Android constructs this class; iOS test pushes without a
     * `from` topic land here. Delegates to the station route.
     */
    suspend operator fun invoke(payload: FcmPayload) {
        processStationUpdate(topicStationId = null, payload = payload)
    }

    /**
     * Station_* topic push: sync predictions into SQLite for every selection
     * that matches the pushed station, then refresh the widget if (and only
     * if) the primary selection — the one the widget shows — was affected.
     * Mirrors Android's FcmMessagingService.handlePredictionUpdate.
     *
     * @param topicStationId station id extracted from the FCM topic
     *        ("/topics/Station_940GZZLUASL" → "940GZZLUASL"). Checked first so
     *        child-stop-id mismatches in the payload body don't drop the push.
     */
    suspend fun processStationUpdate(topicStationId: String?, payload: FcmPayload) {
        // Keep the in-memory prediction flow fresh for any live collectors.
        departureRepository.processFcmPayload(payload)

        val selections = allSelections()
        val matching = selections.filter {
            it.station.equals(topicStationId ?: "", ignoreCase = true) ||
                it.station.equals(payload.id, ignoreCase = true)
        }
        if (matching.isEmpty()) return

        matching.forEach { selection ->
            syncPredictions?.execute(payload, selection)
        }

        // The widget renders the primary (first) selection. Only rewrite it
        // when that selection actually received data — refreshing it for a
        // push that targeted another station would blank the board.
        val primary = selections.firstOrNull()
        if (primary != null && matching.any {
                it.station.equals(primary.station, ignoreCase = true) &&
                    it.line.equals(primary.line, ignoreCase = true)
            }
        ) {
            refreshWidgetFromStorage(primary)
        }

        // Tell any live in-app board to reload from SQLite right now, instead
        // of waiting on its 30 s poll.
        FreshDataNotifier.notifyFreshData()
    }

    /**
     * LineStatus_* topic push: persist the status, and refresh the widget's
     * status strip when the primary selection rides that line. Mirrors
     * Android's FcmMessagingService.handleLineStatusUpdate (minus the local
     * status-change notification, which is platform-native).
     */
    suspend fun processLineStatusUpdate(status: LineStatus) {
        sqlStorage?.saveLineStatus(status)

        val primary = allSelections().firstOrNull()
        if (primary != null && status.id.equals(primary.line, ignoreCase = true)) {
            refreshWidgetFromStorage(primary)
        }

        FreshDataNotifier.notifyFreshData()
    }

    private suspend fun allSelections(): List<UserSelection> =
        storageManager.loadSelections().ifEmpty {
            sqlStorage?.getAllSelections() ?: emptyList()
        }

    /**
     * Rebuild the widget state from SQLite — the same shape the in-app poll
     * writes (SummaryViewModel.loadPredictions) so the widget never flips
     * format depending on which trigger refreshed it.
     */
    private suspend fun refreshWidgetFromStorage(primary: UserSelection) {
        val sql = sqlStorage ?: return
        val dbPreds = GlobalBoardProcessor.processPredictions(
            sql.getPredictions(primary.station, primary.line, primary.direction)
        )
        if (dbPreds.isEmpty()) return

        val tsMs = sql.getLastUpdatedTimestamp(primary.station, primary.line, primary.direction)
            ?: Clock.System.now().toEpochMilliseconds()
        val widgetStatus = sql.getLineStatus(primary.mode, primary.line)?.let { s ->
            val reason = StationlyFormatters.formatStatusReason(s.reason ?: "").trim()
            if (reason.isNotEmpty()) "${s.statusSeverityDescription}: $reason"
            else s.statusSeverityDescription
        }

        widgetManager.updateWidget(
            WidgetState(
                stationName = primary.stationName,
                lineName = primary.line,
                predictions = dbPreds,
                status = widgetStatus,
                lastUpdated = tsMs / 1000,
                direction = primary.direction,
                mode = primary.mode,
            )
        )
    }
}
