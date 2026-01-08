package com.stationly.core.usecase

import com.stationly.core.model.UserSelection
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.platform.NotificationManager
import com.stationly.core.platform.WidgetManager

/**
 * Save Selection Use Case
 * 
 * Orchestrates the complete flow of saving a user selection:
 * 1. Save to repository (local storage)
 * 2. Subscribe to FCM topics for real-time updates
 * 3. Update widget with waiting state
 * 4. Fetch initial data
 * 
 * Mirrors the saveSelection logic from MindTheTimeAndroid's SummaryViewModel
 * but extracted as a reusable, testable use case.
 */
class SaveSelectionUseCase(
    private val selectionRepository: SelectionRepository,
    private val notificationManager: NotificationManager,
    private val widgetManager: WidgetManager,
    private val fetchInitialDataUseCase: FetchInitialDataUseCase
) {
    
    /**
     * Execute the save selection flow
     * 
     * @param newSelection The new selection to save
     * @param oldSelection Optional old selection (for edits)
     */
    suspend operator fun invoke(
        newSelection: UserSelection,
        oldSelection: UserSelection? = null
    ) {
        // Step 1: Save to repository (local storage)
        selectionRepository.saveSelection(newSelection, oldSelection)
        
        // Step 2: Handle FCM topic subscriptions
        handleSubscriptions(newSelection, oldSelection)
        
        // Step 3: Update widget with waiting state
        widgetManager.showWaitingState(
            station = newSelection.stationName,
            line = newSelection.line
        )
        
        // Step 4: Fetch initial data (line status)
        fetchInitialDataUseCase(newSelection)
    }
    
    private suspend fun handleSubscriptions(
        newSelection: UserSelection,
        oldSelection: UserSelection?
    ) {
        // Unsubscribe from old topics if editing
        oldSelection?.let {
            val oldTopics = listOf(
                "Station_${it.station}",
                "LineStatus_${it.mode}_${it.line}"
            )
            notificationManager.unsubscribeFromTopics(oldTopics)
        }
        
        // Subscribe to new topics
        val newTopics = listOf(
            "Station_${newSelection.station}",
            "LineStatus_${newSelection.mode}_${newSelection.line}"
        )
        notificationManager.subscribeToTopics(newTopics)
    }
}