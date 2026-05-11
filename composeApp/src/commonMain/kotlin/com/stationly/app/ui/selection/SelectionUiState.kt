package com.stationly.app.ui.selection

import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.model.sdui.SduiDropdownOption

data class SelectionUiState(
    val layout: SduiAppScreen? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val showSuccessDialog: Boolean = false,
    val isLocating: Boolean = false,
    val isGpsUnavailable: Boolean = false,
    val isBackendOffline: Boolean = false,
    val isSaving: Boolean = false,
    val isSearchEmpty: Boolean = false,
    val failedFetches: Set<String> = emptySet(),
    val userLat: Double? = null,
    val userLon: Double? = null
)
