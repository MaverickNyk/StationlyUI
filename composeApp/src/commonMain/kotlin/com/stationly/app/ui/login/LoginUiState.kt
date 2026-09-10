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
    val passwordResetConfirmed: Boolean = false,
    /**
     * "Your account was deleted elsewhere" — shown once, on the sign-in screen
     * that deletion sent the user to.
     *
     * Separate from [error] rather than reusing it, because `loadLayout` clears
     * `error` on entry (correctly — a stale validation message must not survive
     * a screen change), and this notice arrives BEFORE the layout loads. Put in
     * `error` it would be wiped a frame later and the user would be signed out
     * with no explanation, which is the whole thing it exists to prevent.
     */
    val accountRemovedNotice: String? = null
)
