package com.stationly.app.ui.profile

import com.stationly.core.model.sdui.SduiAppComponent

data class ProfileUiState(
    val email: String = "",
    val displayName: String = "",
    // True while we're logged in but the Swift AuthBridge hasn't written the
    // identity keys yet (keychain-restored session) — the header card shows a
    // name skeleton instead of the literal "User" flash.
    val isIdentityLoading: Boolean = false,
    val photoUrl: String? = null,
    val isSigningOut: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val error: String? = null,
    val aboutComponents: List<SduiAppComponent> = emptyList(),
    val homeConfig: Map<String, String> = emptyMap(),
    val signOutSuccess: Boolean = false,
    val deleteAccountSuccess: Boolean = false
)
