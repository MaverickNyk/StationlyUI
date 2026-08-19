package com.stationly.core.usecase

import com.stationly.core.model.PredictionItem
import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.repository.SqlStorage
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Format Departures Use Case
 * 
 * Formats prediction data for widget display.
 * This contains the business logic for ETA formatting and platform grouping.
 * 
 * Mirrors the formatDepartures and formatPredictions logic from
 * MindTheTimeAndroid's DepartureWidgetProvider
 */
class FormatDeparturesUseCase {
    
    /**
     * Format predictions into widget state
     * 
     * @param predictions Raw prediction items
     * @param selection User selection for context
     * @return Formatted widget state
     */
    operator fun invoke(
        predictions: List<PredictionItem>,
        selection: UserSelection
    ): WidgetState {
        // Apply the board's destination/via allow-list, FAILING OPEN.
        //
        // `destinationIds` used to be permanently empty, so this filter never
        // actually ran; it does now that boards can carry one. Falling back to
        // the unfiltered list when nothing matches matters more here than
        // anywhere else — the widget has no empty state to explain itself with,
        // so a filter that matches nothing would leave a silently blank widget.
        val filteredPredictions =
            filterByDestinations(predictions, selection.destinationIds, selection.viaKeys)
                .ifEmpty { predictions }

        // Format predictions for display
        val formattedPredictions = filteredPredictions.map { pred ->
            formatPrediction(pred)
        }

        // Sort by ACTUAL arrival time, earliest first.
        //
        // This previously sorted on the formatted `eta` STRING, which orders
        // lexicographically: "1 min" < "10 min" < "2 min" < "Due". Combined with
        // the take(3) below, the widget could show the wrong three trains in the
        // wrong order. Nulls last so a row whose timestamp failed to parse
        // cannot jump to the front.
        val sortedPredictions = formattedPredictions.sortedWith(
            compareBy({ it.targetEpochMs == null }, { it.targetEpochMs })
        )
        
        // Get line status if available (from previous fetch)
        val lineStatus = null // This would come from repository
        
        return WidgetState(
            stationName = selection.stationName,
            lineName = selection.line,
            predictions = sortedPredictions.take(3), // Top 3 predictions
            status = lineStatus,
            lastUpdated = Clock.System.now().epochSeconds,
            direction = selection.direction,
            mode = selection.mode
        )
    }
    
    /**
     * Filter predictions by allowed destination IDs
     */
    private fun filterByDestinations(
        predictions: List<PredictionItem>,
        allowedDestIds: List<String>,
        allowedViaKeys: List<String> = emptyList(),
    ): List<PredictionItem> {
        if (allowedDestIds.isEmpty()) return predictions
        // Same check the board and the re-apply path use, so the widget can
        // never disagree with the card it mirrors. In particular it fails open
        // on a blank destId: TfL sends "Check Front of Train" and depot moves
        // that map to no station, and a plain `in` test would drop them here
        // while the board still showed them.
        val allowed = allowedDestIds.toSet()
        val allowedVia = allowedViaKeys.toSet()
        return predictions.filter {
            SqlStorage.matchesFilter(it.destId, allowed, it.viaKey, allowedVia)
        }
    }
    
    /**
     * Format a single prediction for display
     */
    private fun formatPrediction(prediction: PredictionItem): PredictionDisplay {
        val etaString = com.stationly.core.util.StationlyFormatters.formatETA(prediction.eta)
        val isDue = etaString == "Due"

        return PredictionDisplay(
            destination = com.stationly.core.util.StationlyFormatters.formatDestination(prediction.displayName),
            platform = prediction.platform,
            eta = etaString,
            isDue = isDue,
            // Carried through so a widget row is the same shape as a board row.
            // Dropping it here meant anything downstream of the widget path had
            // no id to match a filter on.
            destId = prediction.destId,
            // Capture the absolute arrival timestamp so the UI can self-tick.
            targetEpochMs = com.stationly.core.util.StationlyFormatters.parseTargetEpochMs(prediction.eta),
        )
    }
}