package com.stationly.core.repository

import com.stationly.core.model.*
import com.stationly.core.service.TflApiService
import com.stationly.core.platform.StorageManager
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val sqlStorage: SqlStorage,
    private val syncPredictionsUseCase: SyncPredictionsUseCase
) {
    
    // In-memory cache for real-time data
    private val _predictions = MutableStateFlow<List<PredictionItem>>(emptyList())
    val predictions: StateFlow<List<PredictionItem>> = _predictions.asStateFlow()
    
    private val _lineStatus = MutableStateFlow<LineStatus?>(null)
    val lineStatus: StateFlow<LineStatus?> = _lineStatus.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Per-station mutex so a single station never has two concurrent
     * `fetchInitialData` calls in flight. Different stations can still
     * fetch in parallel (independent mutex per key).
     *
     * Why: the FCM listener, the home pull-to-refresh, and the widget
     * refresh button can ALL trigger a fetch for the same station at
     * overlapping moments — they'd all write to SQL concurrently. SQLite
     * itself is serialised so there's no corruption, but the "winning"
     * write determines what surfaces see, and the in-flight `_lineStatus`
     * / `_isLoading` flow updates race each other. One-at-a-time per
     * station eliminates that flapping without blocking parallelism
     * across stations.
     */
    private val fetchMutexes = mutableMapOf<String, Mutex>()
    private val fetchMutexesLock = Mutex()

    private suspend fun mutexFor(stationId: String): Mutex =
        fetchMutexesLock.withLock {
            fetchMutexes.getOrPut(stationId.lowercase()) { Mutex() }
        }

    /**
     * Fetch initial data for a user selection
     * This is called when a user saves a new station
     */
    suspend fun fetchInitialData(selection: UserSelection) {
        mutexFor(selection.station).withLock {
            _isLoading.value = true

            try {
                // Fetch line status
                val statusList = apiService.getLineStatuses(selection.line.lowercase(), selection.mode.lowercase())
                statusList.firstOrNull()?.let { status ->
                    _lineStatus.value = status
                    sqlStorage.saveLineStatus(status)
                }


                // Eagerly fetch prediction data
                try {
                    val fcmPayload = apiService.getPredictions(selection.station)
                    syncPredictionsUseCase.execute(fcmPayload, selection)
                } catch (e: Exception) {
                    println("DepartureRepository: prediction fetch failed for ${selection.station} — ${e::class.simpleName}: ${e.message}")
                }

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
    }
    
    /**
     * Fetch predictions for a station
     */
    suspend fun fetchPredictions(selection: UserSelection): List<PredictionDisplay> {
        val fcmPayload = apiService.getPredictions(selection.station)
        return syncPredictionsUseCase.execute(fcmPayload, selection)
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