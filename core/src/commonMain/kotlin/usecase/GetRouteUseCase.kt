package com.stationly.core.usecase

import com.stationly.core.model.LineRouteResponse
import com.stationly.core.service.TflApiService

/**
 * Get Route Use Case
 * 
 * Fetches route information including directions and destinations.
 * This is the third step in the selection flow.
 */
class GetRouteUseCase(
    private val apiService: TflApiService
) {
    
    suspend operator fun invoke(lineId: String): LineRouteResponse {
        return apiService.getRoute(lineId)
    }
}