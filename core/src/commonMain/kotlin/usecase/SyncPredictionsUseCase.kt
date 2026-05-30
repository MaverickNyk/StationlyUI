package com.stationly.core.usecase

import com.stationly.core.model.*
import com.stationly.core.repository.SqlStorage
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Sync Predictions Use Case
 * 
 * Unifies the logic for processing station predictions from both REST and FCM.
 * Handles filtering, formatting, sorting, and persistence.
 */
class SyncPredictionsUseCase(
    private val sqlStorage: SqlStorage
) {
    /**
     * Synthesize and save predictions for a specific selection
     * 
     * @param payload The raw FCM/REST payload
     * @param selection The user's specific board selection (Line/Direction)
     * @return Formatted predictions for display
     */
    suspend fun execute(payload: FcmPayload, selection: UserSelection): List<PredictionDisplay> {
        // 1. Extract line data (Loose matching for casing)
        val lineIdLower = selection.line.lowercase()
        val lineData = payload.lines[lineIdLower] 
            ?: payload.lines.entries.find { it.key.lowercase() == lineIdLower }?.value
            ?: return emptyList()
        
        // 2. Get predictions for the direction (Loose matching for casing)
        val dirIdLower = selection.direction.lowercase()
        val dirData = lineData.dirs[selection.direction]
            ?: lineData.dirs[dirIdLower]
            ?: lineData.dirs.entries.find { it.key.lowercase() == dirIdLower }?.value
        
        val rawPreds = dirData?.preds ?: emptyList()
        
        // 3. Determine a valid platform to fallback to if "Unknown" is encountered
        val knownPlatform = rawPreds.firstOrNull { 
            !it.platform.equals("Unknown", ignoreCase = true) && it.platform.isNotBlank() 
        }?.platform ?: "Unknown"

        // 4. Format predictions for display. Capture the absolute arrival
        //    time (parsed from the FCM's ISO timestamp) alongside the
        //    formatted string so downstream consumers can re-derive
        //    minutes-remaining on their own clock between FCM pushes.
        val formattedPredictions = rawPreds.map { pred ->
            val etaString = StationlyFormatters.formatETA(pred.eta)
            val displayPlatform = if (pred.platform.equals("Unknown", ignoreCase = true) || pred.platform.isBlank()) knownPlatform else pred.platform

            PredictionDisplay(
                destination = StationlyFormatters.formatDestination(pred.displayName),
                platform = displayPlatform,
                eta = etaString,
                isDue = etaString == "Due",
                stopLetter = pred.stopLetter,
                targetEpochMs = StationlyFormatters.parseTargetEpochMs(pred.eta),
            )
        // Dedupe on absolute arrival time, NOT the formatted eta string.
        // The earlier string-based dedup ("dest_platform_eta") silently
        // dropped a legitimate second train when both rounded to the same
        // minute bucket — e.g. two Cockfosters trains 40s apart, both
        // formatted "1 min", collapsed to one. The per-platform bump rule
        // in PredictionTicker.tickPredictions now handles the visible
        // duplicate problem at render time without losing the row from
        // SQL. Dedup by exact target catches genuine TfL duplicates
        // (same train returned twice in one /arrivals payload).
        }.distinctBy { "${it.destination}_${it.platform}_${it.targetEpochMs ?: it.eta}" }
        
        // 5. Use unified processor for sorting and platform grouping.
        //    Cap at 8 per platform (not 3) so the in-memory tick layer
        //    has a buffer of upcoming trains to shift into the visible
        //    3-row window once the current top row has departed. The
        //    display layer still caps at 3 — these are reserves, not
        //    everything shown.
        val processedPredictions = GlobalBoardProcessor.processPredictions(
            predictions = formattedPredictions,
            perPlatformCap = 8,
        )

        // 6. Save to SQL storage
        sqlStorage.savePredictions(selection.station, selection.line, processedPredictions)

        return processedPredictions
    }
}
