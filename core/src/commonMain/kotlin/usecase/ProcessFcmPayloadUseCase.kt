package com.stationly.core.usecase

import com.stationly.core.model.FcmPayload
import com.stationly.core.model.PredictionItem
import com.stationly.core.model.WidgetState
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.platform.WidgetManager
import com.stationly.core.platform.StorageManager
import kotlinx.datetime.Clock

/**
 * Process FCM Payload Use Case
 * 
 * This is the core of the real-time widget updates. It processes incoming
 * FCM messages and updates the widget with new prediction data.
 * 
 * Mirrors the FcmMessagingService logic from MindTheTimeAndroid but
 * extracted as a reusable use case.
 */
class ProcessFcmPayloadUseCase(
    private val departureRepository: DepartureRepository,
    private val widgetManager: WidgetManager,
    private val storageManager: StorageManager,
    private val formatDeparturesUseCase: FormatDeparturesUseCase
) {
    
    /**
     * Execute the FCM payload processing
     * 
     * @param payload The incoming FCM payload
     */
    suspend operator fun invoke(payload: FcmPayload) {
        // Step 1: Process and cache predictions
        departureRepository.processFcmPayload(payload)
        
        // Step 2: Get primary selection for context
        val primarySelection = storageManager.loadSelections().firstOrNull()
        
        if (primarySelection != null) {
            // Step 3: Format predictions for widget
            val predictions = extractRelevantPredictions(payload, primarySelection.line)
            val widgetState = formatDeparturesUseCase(predictions, primarySelection)
            
            // Step 4: Update widget
            widgetManager.updateWidget(widgetState)
        }
    }
    
    /**
     * Extract predictions relevant to the primary selection
     */
    private fun extractRelevantPredictions(
        payload: FcmPayload,
        lineId: String
    ): List<PredictionItem> {
        val lineData = payload.lines[lineId] ?: return emptyList()
        
        // Get all predictions for the line
        return lineData.dirs.flatMap { it.value.preds }
    }
}