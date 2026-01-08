package com.stationly.mobile.ui.summary

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class SummaryUiState(
    val isRefreshing: Boolean = false,
    val error: String? = null
)

class SummaryViewModel {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _uiState = MutableStateFlow(SummaryUiState())
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()

    private val _selections = MutableStateFlow<List<UserSelection>>(emptyList())
    val selections: StateFlow<List<UserSelection>> = _selections.asStateFlow()

    private val _predictions = MutableStateFlow<Map<String, List<PredictionDisplay>>>(emptyMap())
    val predictions: StateFlow<Map<String, List<PredictionDisplay>>> = _predictions.asStateFlow()

    init {
        // Load some dummy data for now
        scope.launch {
            _selections.value = listOf(
                UserSelection(
                    id = "1",
                    stationId = "940GZZLUKSX",
                    stationName = "King's Cross St. Pancras",
                    line = "Victoria",
                    direction = "Southbound",
                    platform = "Southbound Platform 3"
                )
            )
            refreshAll()
        }
    }

    fun refreshAll() {
        // Todo: Implement real fetching using Core
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        scope.launch {
            // Simulate delay
            kotlinx.coroutines.delay(1000)
            _uiState.value = _uiState.value.copy(isRefreshing = false)
            
            // Dummy predictions
             _predictions.value = mapOf(
                "1" to listOf(
                    PredictionDisplay(
                        lineId = "victoria",
                        destination = "Brixton",
                        eta = "2 min",
                        platform = "Southbound Platform 3",
                        isDue = false
                    ),
                    PredictionDisplay(
                        lineId = "victoria",
                        destination = "Brixton",
                        eta = "Due",
                        platform = "Southbound Platform 3",
                        isDue = true
                    )
                )
             )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun getLastUpdatedString(): String {
        return "Just now"
    }

    fun getPredictionsForSelection(selection: UserSelection): List<PredictionDisplay> {
        return _predictions.value[selection.id] ?: emptyList()
    }
    
    fun hasPredictions(selection: UserSelection): Boolean {
         return _predictions.value.containsKey(selection.id)
    }

    fun deleteSelection(selection: UserSelection) {
        // Implement delete
    }
}
