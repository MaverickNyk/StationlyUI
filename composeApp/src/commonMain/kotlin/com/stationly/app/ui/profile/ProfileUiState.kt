package com.stationly.app.ui.profile

import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SubscribedStation

data class ProfileUiState(
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val signInProvider: String = "Stationly",
    val memberSince: String = "",
    val stations: List<SubscribedStation> = emptyList(),
    val isLoading: Boolean = true,
    val deletingStationId: String? = null,
    val isSigningOut: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val error: String? = null,
    val aboutComponents: List<SduiAppComponent> = emptyList(),
    val homeConfig: Map<String, String> = emptyMap(),
    val signOutSuccess: Boolean = false,
    val deleteAccountSuccess: Boolean = false
)
