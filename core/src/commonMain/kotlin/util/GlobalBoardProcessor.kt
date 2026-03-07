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
     * Guaranteed to return top 3 earliest arrivals.
     */
    fun processPredictions(predictions: List<PredictionDisplay>): List<PredictionDisplay> {
        return StationlyFormatters.sortPredictions(predictions).take(3)
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
        // Ensure we work with the strictly sorted top 3
        val sortedPredictions = processPredictions(predictions)
        
        val boundComponents = template.components.map { component ->
            when (component) {
                is SduiWidgetComponent.Row -> {
                    val index = component.index.toIntOrNull() ?: 1
                    
                    // Filter matching predictions if destination is specified (e.g., for multi-directional templates)
                    val matchingPreds = if (component.destination.isNotBlank() && component.destination != "Any") {
                        sortedPredictions.filter { it.destination.contains(component.destination, true) }
                    } else {
                        sortedPredictions
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
        hasSelection: Boolean
    ): List<LegacyRow> {
        val sorted = processPredictions(predictions)
        val rows = mutableListOf<LegacyRow>()

        if (sorted.isEmpty()) {
             // 1. Message row
             rows.add(LegacyRow.Message(if (hasSelection) "All trains have departed!" else "Select a station inside the app"))
             // 2 & 3. Blank Departure rows for persistence (using space to preserve height)
             rows.add(LegacyRow.Departure(0, " ", " "))
             rows.add(LegacyRow.Departure(0, " ", " "))
             return rows
        }

        val grouped = sorted.groupBy { it.platform }
        grouped.forEach { (platform, platformPreds) ->
            val stopLetter = platformPreds.firstOrNull()?.stopLetter
            val platformLabel = if (!stopLetter.isNullOrBlank()) "Stop $stopLetter" else platform
            val title = if (lineName.isNotEmpty()) "${lineName.replaceFirstChar { it.uppercase() }} : $platformLabel" else platformLabel
            
            rows.add(LegacyRow.Header(title))
            
            // For each platform, ensure exactly 3 slots are shown (live or empty)
            for (i in 0 until 3) {
                if (i < platformPreds.size) {
                    val pred = platformPreds[i]
                    rows.add(LegacyRow.Departure(i + 1, pred.destination, pred.eta))
                } else {
                    rows.add(LegacyRow.Departure(0, " ", " "))
                }
            }
        }
        return rows
    }
}
