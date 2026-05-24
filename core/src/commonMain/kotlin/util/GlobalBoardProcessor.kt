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
     * Groups by platform and takes the top [perPlatformCap] earliest arrivals
     * PER platform. Orders platforms by whichever platform has the earliest
     * overall train.
     *
     * - Display layer calls with the default cap of 3 (matches the
     *   dot-matrix board's 3-rows-per-platform layout).
     * - Storage layer ([SyncPredictionsUseCase]) calls with a larger cap
     *   (e.g. 8) so the in-memory tick layer has buffer rows to shift into
     *   the visible 3-row window once the top row's train has departed.
     */
    fun processPredictions(
        predictions: List<PredictionDisplay>,
        perPlatformCap: Int = 3,
    ): List<PredictionDisplay> =
        groupByPlatformSorted(predictions).flatMap { (_, platformPreds) ->
            platformPreds.take(perPlatformCap)
        }

    private fun groupByPlatformSorted(predictions: List<PredictionDisplay>): List<Map.Entry<String, List<PredictionDisplay>>> {
        val sorted = StationlyFormatters.sortPredictions(predictions)
        return sorted.groupBy { it.platform }.entries.sortedBy { (_, platformPreds) ->
            val firstEta = platformPreds.firstOrNull()?.eta?.lowercase()?.trim() ?: ""
            when {
                firstEta.contains("due") -> 0
                firstEta.contains("min") -> firstEta.replace(" min", "").toIntOrNull() ?: 999
                else -> firstEta.toIntOrNull() ?: 999
            }
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
        hasEverUpdated: Boolean = false,
        lineStatusSeverity: String? = null,
        lineStatusReason: String? = null,
        currentHour: Int = -1
    ): List<LegacyRow> {
        val rows = mutableListOf<LegacyRow>()

        if (predictions.isEmpty()) {
            buildPlaceholderRows(
                lineName, hasSelection, isLoggedIn, hasEverUpdated,
                lineStatusSeverity, lineStatusReason, currentHour
            ).forEach { rows.add(it) }
            return rows
        }

        val platformGroups = groupByPlatformSorted(predictions)
        platformGroups.forEach { (platform, platformPreds) ->
            val stopLetter = platformPreds.firstOrNull()?.stopLetter
            val platformLabel = if (!stopLetter.isNullOrBlank()) "Stop $stopLetter" else platform
            
            // Add Platform Header
            rows.add(LegacyRow.Header(platformLabel))
            
            // Take up to 3 predictions per platform
            val platformTop3 = platformPreds.take(3)
            platformTop3.forEachIndexed { index, pred ->
                rows.add(LegacyRow.Departure(index + 1, pred.destination, pred.eta))
            }
            
            if (platformGroups.size == 1 && platformTop3.size < 3) {
                for (i in platformTop3.size until 3) {
                    rows.add(LegacyRow.Departure(0, " ", " "))
                }
            }
        }

        return rows
    }

    private fun buildPlaceholderRows(
        lineName: String,
        hasSelection: Boolean,
        isLoggedIn: Boolean,
        hasEverUpdated: Boolean,
        lineStatusSeverity: String?,
        lineStatusReason: String?,
        currentHour: Int
    ): List<LegacyRow> {
        val line = lineName.trim()
        val isNightRoute = line.matches(Regex("(?i)^N\\d+$"))
        val isDay = currentHour in 6..22
        val isPostNight = currentHour in 5..6
        val isEarlyMorning = currentHour in 4..6
        val severity = lineStatusSeverity?.trim() ?: ""
        val reason = lineStatusReason?.trim()?.takeIf { it.isNotBlank() }

        val lines: List<String> = when {
            !isLoggedIn -> listOf(
                "👋 Hey, welcome!",
                "Sign in to see your live board",
                "Real-time. Every minute. Free.",
                "Tap to open Stationly →"
            )
            !hasSelection -> listOf(
                "🚉 You're one stop away",
                "Pick a station, we'll do the rest",
                "Tube · Bus · DLR · Overground",
                "Tap to set up your board →"
            )
            isNightRoute && currentHour != -1 && isDay -> listOf(
                "🌙 Night owls only",
                "$line runs after midnight",
                "Come back tonight for live times",
                ""
            )
            isNightRoute && currentHour != -1 && isPostNight -> listOf(
                "🌙 That's a wrap for tonight",
                "$line stopped running around 5am",
                "Back again after midnight",
                ""
            )
            severity.contains("strike", ignoreCase = true) ||
            severity.contains("industrial action", ignoreCase = true) -> listOf(
                "😤 Strike action on $line",
                "No service running today",
                reason ?: "Staff are on strike",
                "Check TfL for alternatives"
            )
            severity.equals("Suspended", ignoreCase = true) -> listOf(
                "⚠️ $line is suspended",
                reason ?: "No service right now",
                "Engineers are working on it",
                "Tap ↻ to check for updates"
            )
            severity.equals("Bus Service", ignoreCase = true) -> listOf(
                "🚌 Running on buses",
                "$line replaced by buses today",
                reason ?: "Check TfL for bus stop info",
                ""
            )
            severity.equals("Planned Closure", ignoreCase = true) -> listOf(
                "🔧 Planned closure today",
                reason ?: "$line closed for engineering",
                "Normal service resumes after",
                ""
            )
            isEarlyMorning && currentHour != -1 && !isNightRoute -> listOf(
                "☀️ Early start?",
                "First trains are warming up",
                "Service begins shortly",
                ""
            )
            !hasEverUpdated -> listOf(
                "📡 Tuning in to TfL...",
                "Your live board is almost ready",
                "Signals incoming — hang tight",
                ""
            )
            severity.equals("Good Service", ignoreCase = true) -> listOf(
                "🚇 All clear on the line",
                "No trains approaching right now",
                "Should update any moment",
                "Tap ↻ to refresh"
            )
            else -> listOf(
                "All quiet at the platform",
                reason ?: "No trains due right now",
                "Tap ↻ to check again",
                ""
            )
        }

        val header = lines.getOrElse(0) { "" }
        val row1   = lines.getOrElse(1) { "" }
        val row2   = lines.getOrElse(2) { "" }
        val row3   = lines.getOrElse(3) { "" }
        return listOf(
            LegacyRow.Header(header),
            LegacyRow.Departure(0, row1, ""),
            LegacyRow.Departure(0, row2, ""),
            LegacyRow.Departure(0, row3, "")
        )
    }
}
