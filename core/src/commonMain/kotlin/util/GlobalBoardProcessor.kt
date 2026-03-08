package com.stationly.core.util

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.sdui.SduiWidgetPayload
import com.stationly.core.model.sdui.SduiWidgetComponent

sealed class LegacyRow {
    data class Header(val title: String) : LegacyRow()
    data class Departure(val index: Int, val destination: String, val eta: String) : LegacyRow()
    data class Message(val text: String) : LegacyRow()
}

/**
 * GlobalBoardProcessor - Unified logic for App and Widget
 * 
 * Ensures that sorting, top-3 filtering, and SDUI binding are identical 
 * across both the Android App's internal board and the Home Screen Widget.
 */
object GlobalBoardProcessor {
    
    /**
     * Common sorting and filtering for all departure lists.
     * Groups by platform and takes the top 3 earliest arrivals PER platform.
     * Orders platforms by whichever platform has the earliest overall train.
     */
    fun processPredictions(predictions: List<PredictionDisplay>): List<PredictionDisplay> {
        val sorted = StationlyFormatters.sortPredictions(predictions)
        // Group by platform to ensure we take top 3 per platform as requested
        val grouped = sorted.groupBy { it.platform }
        
        // Sort the platforms so the one with the earliest train appears first
        val sortedPlatforms = grouped.entries.sortedBy { (_, platformPreds) ->
            val firstPred = platformPreds.firstOrNull()?.eta?.lowercase()?.trim() ?: ""
            when {
                firstPred.contains("due") -> 0
                firstPred.contains("min") -> firstPred.replace(" min", "").toIntOrNull() ?: 999
                else -> firstPred.toIntOrNull() ?: 999
            }
        }
        
        // Flatten back to a single list
        return sortedPlatforms.flatMap { (_, platformPreds) -> 
            platformPreds.take(3) 
        }
    }

    /**
     * Binds raw data to an SDUI template.
     * Centralizes the logic for index-based row filling and status injection.
     */
    fun bindSduiTemplate(
        template: SduiWidgetPayload,
        predictions: List<PredictionDisplay>,
        lineStatusSeverity: String?,
        lineStatusReason: String?
    ): SduiWidgetPayload {
        // Get the processed predictions (top 3 per platform, correctly ordered)
        val processedPredictions = processPredictions(predictions)
        
        val boundComponents = template.components.map { component ->
            when (component) {
                is SduiWidgetComponent.Row -> {
                    val index = component.index.toIntOrNull() ?: 1
                    
                    // Filter matching predictions if destination is specified (e.g., for multi-directional templates)
                    val matchingPreds = if (component.destination.isNotBlank() && component.destination != "Any") {
                        processedPredictions.filter { it.destination.contains(component.destination, true) }
                    } else {
                        processedPredictions
                    }
                    
                    val actualPred = matchingPreds.getOrNull(index - 1)
                    if (actualPred != null) {
                        component.copy(
                            destination = actualPred.destination,
                            eta = actualPred.eta
                        )
                    } else {
                        // Empty slot if no more trains match the requirements
                        component.copy(destination = " ", eta = " ")
                    }
                }
                is SduiWidgetComponent.Status -> {
                    if (lineStatusSeverity != null) {
                        component.copy(
                            severity = lineStatusSeverity, 
                            reason = lineStatusReason ?: ""
                        )
                    } else {
                        component
                    }
                }
                else -> component
            }
        }
        return template.copy(components = boundComponents)
    }

    /**
     * Unified logic for the "Legacy" departure board rows (non-SDUI path).
     * Ensures both App and Widget display headers and groupings IDENTICALLY.
     */
    fun prepareLegacyRows(
        predictions: List<PredictionDisplay>,
        lineName: String,
        hasSelection: Boolean,
        isLoggedIn: Boolean = true,
        hasEverUpdated: Boolean = false
    ): List<LegacyRow> {
        val rows = mutableListOf<LegacyRow>()

        if (predictions.isEmpty()) {
             val statusMsg = when {
                 !isLoggedIn -> "Please Login"
                 !hasSelection -> "No Station"
                 !hasEverUpdated -> "Fetching live signals..."
                 else -> "All trains have departed!"
             }
             rows.add(LegacyRow.Header(statusMsg))
             
             // Fixed 3 empty rows for placeholder
             rows.add(LegacyRow.Departure(0, " ", " "))
             rows.add(LegacyRow.Departure(0, " ", " "))
             rows.add(LegacyRow.Departure(0, " ", " "))

             if (!isLoggedIn) rows.add(LegacyRow.Message("Please Login and setup your first board"))
             else if (!hasSelection) rows.add(LegacyRow.Message("Please setup your first departure board"))
             
             return rows
        }

        // 1. Sort all predictions first
        val sorted = StationlyFormatters.sortPredictions(predictions)
        
        // 2. Group by platform
        val grouped = sorted.groupBy { it.platform }
        
        // 3. Sort platforms by the earliest ETA in each platform
        val sortedPlatforms = grouped.entries.sortedBy { (_, platformPreds) ->
            val firstPred = platformPreds.firstOrNull()?.eta?.lowercase()?.trim() ?: ""
            when {
                firstPred.contains("due") -> 0
                firstPred.contains("min") -> firstPred.replace(" min", "").toIntOrNull() ?: 999
                else -> firstPred.toIntOrNull() ?: 999
            }
        }
        
        sortedPlatforms.forEach { (platform, platformPreds) ->
            val stopLetter = platformPreds.firstOrNull()?.stopLetter
            val platformLabel = if (!stopLetter.isNullOrBlank()) "Stop $stopLetter" else platform
            
            // Add Platform Header
            rows.add(LegacyRow.Header(platformLabel))
            
            // Take up to 3 predictions per platform
            val platformTop3 = platformPreds.take(3)
            platformTop3.forEachIndexed { index, pred ->
                rows.add(LegacyRow.Departure(index + 1, pred.destination, pred.eta))
            }
            
            // If it's a single platform, ensure we show at least 3 rows (even if placeholders)
            if (sortedPlatforms.size == 1 && platformTop3.size < 3) {
                for (i in platformTop3.size until 3) {
                    rows.add(LegacyRow.Departure(0, " ", " "))
                }
            }
        }

        return rows
    }
}
