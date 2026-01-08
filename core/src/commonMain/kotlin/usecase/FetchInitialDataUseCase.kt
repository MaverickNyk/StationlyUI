package com.stationly.core.usecase

import com.stationly.core.model.UserSelection
import com.stationly.core.repository.DepartureRepository

/**
 * Fetch Initial Data Use Case
 * 
 * Fetches initial line status data when a user saves a new station.
 * This is called after subscription setup to get the current line status.
 * 
 * Mirrors the initial data loading from MindTheTimeAndroid's SelectionViewModel
 */
class FetchInitialDataUseCase(
    private val departureRepository: DepartureRepository
) {
    
    /**
     * Execute the fetch operation
     * 
     * @param selection The user selection to fetch data for
     */
    suspend operator fun invoke(selection: UserSelection) {
        departureRepository.fetchInitialData(selection)
    }
}