package com.stationly.core.repository

import com.stationly.core.model.*
import com.stationly.core.service.TflApiService
import com.stationly.core.platform.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Departure Repository
 * 
 * Manages real-time departure data, line status, and predictions.
 * This is the core of the widget functionality.
 * 
 * Mirrors the real-time functionality from MindTheTimeAndroid but
 * extracted as a reusable, platform-agnostic repository.
 */
class DepartureRepository(
    private val apiService: TflApiService,
    private val storageManager: StorageManager,
    private val sqlStorage: SqlStorage
) {
    
    // In-memory cache for real-time data
    private val _predictions = MutableStateFlow<List<PredictionItem>>(emptyList())
    val predictions: StateFlow<List<PredictionItem>> = _predictions.asStateFlow()
    
    private val _lineStatus = MutableStateFlow<LineStatus?>(null)
    val lineStatus: StateFlow<LineStatus?> = _lineStatus.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    /**
     * Fetch initial data for a user selection
     * This is called when a user saves a new station
     */
    suspend fun fetchInitialData(selection: UserSelection) {
        _isLoading.value = true
        
        try {
            // Fetch line status
            val statusList = apiService.getLineStatuses(selection.line)
            statusList.firstOrNull()?.let { status ->
                _lineStatus.value = status
                sqlStorage.saveLineStatus(status)
            }
            
            // Note: Predictions come from FCM/WebSocket, not API
            // We just cache the line status here
            
        } catch (e: Exception) {
            // Try to load from cache
            val cachedStatus = sqlStorage.getLineStatus(selection.mode, selection.line)
            if (cachedStatus != null) {
                _lineStatus.value = cachedStatus
            }
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * Process incoming FCM payload
     * This is called when FCM message arrives
     */
    suspend fun processFcmPayload(payload: FcmPayload) {
        // Extract predictions from payload
        val allPredictions = payload.lines.values.flatMap { lineData ->
            lineData.dirs.values.flatMap { dirData ->
                dirData.preds
            }
        }
        
        // Update predictions
        _predictions.value = allPredictions
        
        // Note: In real app we would map PredictionItem to PredictionDisplay here
        // For simplicity, we'll implement that mapping when we do the unified formatters
    }
    
    /**
     * Clear departure data
     */
    suspend fun clear() {
        _predictions.value = emptyList()
        _lineStatus.value = null
        sqlStorage.clearAllPredictions()
        sqlStorage.clearLineStatuses()
    }
}