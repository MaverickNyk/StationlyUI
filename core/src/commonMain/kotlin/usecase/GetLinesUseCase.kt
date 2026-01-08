package com.stationly.core.usecase

import com.stationly.core.model.LineInfo
import com.stationly.core.service.TflApiService

/**
 * Get Lines Use Case
 * 
 * Fetches available lines for a given transport mode.
 * This is the second step in the selection flow.
 */
class GetLinesUseCase(
    private val apiService: TflApiService
) {
    
    suspend operator fun invoke(mode: String): List<LineInfo> {
        return apiService.getLines(mode)
    }
}