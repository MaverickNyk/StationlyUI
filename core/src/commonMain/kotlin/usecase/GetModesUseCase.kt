package com.stationly.core.usecase

import com.stationly.core.model.TransportMode
import com.stationly.core.service.TflApiService

/**
 * Get Modes Use Case
 * 
 * Fetches available transport modes (Tube, DLR, Overground, etc.)
 * This is the first step in the selection flow.
 */
class GetModesUseCase(
    private val apiService: TflApiService
) {
    
    suspend operator fun invoke(): List<TransportMode> {
        return apiService.getModes()
    }
}