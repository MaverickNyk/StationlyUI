package com.stationly.core.usecase

import com.stationly.core.model.PredictionItem
import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import com.stationly.core.model.PredictionDisplay
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
        // Filter predictions by destination
        val filteredPredictions = filterByDestinations(predictions, selection.destinationIds)
        
        // Format predictions for display
        val formattedPredictions = filteredPredictions.map { pred ->
            formatPrediction(pred)
        }
        
        // Sort by ETA (earliest first)
        val sortedPredictions = formattedPredictions.sortedBy { it.eta }
        
        // Get line status if available (from previous fetch)
        val lineStatus = null // This would come from repository
        
        return WidgetState(
            stationName = selection.stationName,
            lineName = selection.line,
            predictions = sortedPredictions.take(3), // Top 3 predictions
            status = lineStatus,
            lastUpdated = Clock.System.now().epochSeconds
        )
    }
    
    /**
     * Filter predictions by allowed destination IDs
     */
    private fun filterByDestinations(
        predictions: List<PredictionItem>,
        allowedDestIds: List<String>
    ): List<PredictionItem> {
        if (allowedDestIds.isEmpty()) return predictions
        return predictions.filter { it.destId in allowedDestIds }
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
            // Capture the absolute arrival timestamp so the UI can self-tick.
            targetEpochMs = com.stationly.core.util.StationlyFormatters.parseTargetEpochMs(prediction.eta),
        )
    }
}