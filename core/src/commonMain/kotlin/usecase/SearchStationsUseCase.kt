package com.stationly.core.usecase

import com.stationly.core.model.StationBrief
import com.stationly.core.service.TflApiService

/**
 * Search Stations Use Case
 * 
 * Searches for stations by name/keyword.
 * This is the fourth step in the selection flow.
 */
class SearchStationsUseCase(
    private val apiService: TflApiService
) {
    
    suspend operator fun invoke(searchKey: String): List<StationBrief> {
        return apiService.searchStations(searchKey)
    }
}