package com.stationly.app.ui.summary

data class SummaryUiState(
    val isRefreshing: Boolean = false,
    val isBackendOffline: Boolean = false,
    val isOnline: Boolean = true,
    val lastUpdated: Long = 0L,
    val activeStationId: String? = null,
    val activeLineId: String? = null,
    val userInitial: String = "?",
    val photoUrl: String? = null,
    val showStationLimitDialog: Boolean = false
)
