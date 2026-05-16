package com.stationly.app.ui.login

import com.stationly.core.model.sdui.SduiAppScreen

data class LoginUiState(
    val layout: SduiAppScreen? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val inputs: Map<String, String> = emptyMap(),
    val passwordVisible: Map<String, Boolean> = emptyMap(),
    val resetEmailSent: Boolean = false,
    val resetEmail: String = "",
    val isAuthenticating: Boolean = false,
    val isBackendOffline: Boolean = false,
    val resetOobCode: String? = null,
    val passwordResetConfirmed: Boolean = false
)
