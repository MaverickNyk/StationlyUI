package com.stationly.core.usecase

import com.stationly.core.model.*
import com.stationly.core.repository.SqlStorage
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock

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
        // ONE timestamp for this whole sync. We stamp the board's "last
        // backend update" time the moment the payload lands — BEFORE we know
        // whether it even contains rows for this line/direction — so a 0-row
        // update still resets the "X ago" timer. The same value is later
        // handed to savePredictions, so the sync stamp and the prediction
        // rows agree to the millisecond and all three surfaces (home, widget,
        // dream) read one consistent time via getLastUpdatedTimestamp.
        val syncMs = Clock.System.now().toEpochMilliseconds()
        sqlStorage.saveSyncTimestamp(selection.station, selection.line, selection.direction, syncMs)

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

        // The board's destination/via allow-list, materialised ONCE per stream
        // frame rather than per row and never at render time. This is the whole
        // performance argument for the design: predictions are written once per
        // frame but read on every recomposition and every one-second countdown
        // tick, so the filter is evaluated on the write side and the answer is
        // persisted as a boolean per row.
        val allowedDestIds = selection.destinationIds.toSet()

        // 3. Format predictions for display. Capture the absolute arrival
        //    time (parsed from the FCM's ISO timestamp) alongside the
        //    formatted string so downstream consumers can re-derive
        //    minutes-remaining on their own clock between FCM pushes.
        val formattedPredictions = rawPreds.map { pred ->
            val etaString = StationlyFormatters.formatETA(pred.eta)
            // Platform is backend-owned (formatPlatform / getPresentablePlatform):
            // "Platform 8", "Platform not assigned", "Stop C", or "" for an
            // unassigned bus stop. Trust it verbatim — do NOT fill a blank from a
            // sibling prediction (that masked genuinely-unassigned stops). A stray
            // legacy "Unknown" is treated as unassigned ("").
            val displayPlatform = if (pred.platform.equals("Unknown", ignoreCase = true)) "" else pred.platform

            PredictionDisplay(
                destination = StationlyFormatters.formatDestination(pred.displayName),
                platform = displayPlatform,
                eta = etaString,
                isDue = etaString == "Due",
                stopLetter = pred.stopLetter,
                destId = pred.destId,
                matchesFilter = SqlStorage.matchesFilter(pred.destId, allowedDestIds),
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
        val processedPredictions = if (allowedDestIds.isEmpty()) {
            GlobalBoardProcessor.processPredictions(
                predictions = formattedPredictions,
                perPlatformCap = 8,
            )
        } else {
            // Cap matching and excluded rows SEPARATELY.
            //
            // Sharing one per-platform budget would let excluded trains crowd
            // out the ones the user actually asked for: on a busy platform,
            // eight Uxbridge departures would fill the cap and a board filtered
            // to Heathrow would render thin or empty despite Heathrow trains
            // being in the payload.
            //
            // Excluded rows are still persisted rather than dropped — they are
            // what the fail-open read falls back to, and what lets a filter
            // change be re-applied on device without waiting for a refetch.
            val matching = GlobalBoardProcessor.processPredictions(
                predictions = formattedPredictions.filter { it.matchesFilter },
                perPlatformCap = 8,
            )
            val excluded = GlobalBoardProcessor.processPredictions(
                predictions = formattedPredictions.filterNot { it.matchesFilter },
                perPlatformCap = 8,
            )
            matching + excluded
        }

        // 6. Save to SQL storage — same `syncMs` so the row timestamps match
        //    the sync stamp recorded above.
        sqlStorage.savePredictions(selection.station, selection.line, selection.direction, processedPredictions, syncMs)

        return processedPredictions
    }
}
