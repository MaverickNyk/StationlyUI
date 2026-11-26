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
    val showStationLimitDialog: Boolean = false,
    val showLineLimitDialog: Boolean = false,
    val userLat: Double? = null,
    val userLon: Double? = null,
    /**
     * WHY there is no nearby list, when there is none. `isGpsUnavailable` only
     * says that there is none, and the screen used to print "Location off" for
     * every reason, including a phone with location on that is simply not in
     * London (QA 2026-11-23, finding #1). Null while a list is showing.
     */
    val nearbyProblem: NearbyProblem? = null,
)

/** Why the station step has no nearby list. See [SelectionUiState.nearbyProblem]. */
enum class NearbyProblem {
    /** No fix: location off, permission refused, or no fix within the wait. */
    NO_LOCATION,
    /** A fix, and the server found nothing of this mode near it. */
    NONE_NEARBY,
    /** The nearby request itself failed: offline or server trouble. */
    UNREACHABLE,
}
