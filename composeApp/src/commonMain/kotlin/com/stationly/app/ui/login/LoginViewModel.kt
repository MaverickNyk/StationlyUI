package com.stationly.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.service.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authProvider: PlatformAuthProvider
) : ViewModel() {

    private val sduiApi = NetworkModule.sduiApi

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun loadLayout(screenType: String) {
        // If layout already loaded for same screen type, just reset form state
        if (_uiState.value.layout?.id?.startsWith(screenType) == true) {
            _uiState.value = _uiState.value.copy(
                inputs = emptyMap(), error = null,
                resetEmailSent = false, resetEmail = ""
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, inputs = emptyMap())
            try {
                val layout = when (screenType) {
                    "login"           -> sduiApi.getLoginLayout()
                    "register"        -> sduiApi.getRegisterLayout()
                    "forgot-password" -> sduiApi.getForgotPasswordLayout()
                    else              -> sduiApi.getLoginLayout()
                }
                _uiState.value = _uiState.value.copy(layout = layout, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, isBackendOffline = true,
                    error = "Could not connect to Stationly servers."
                )
            }
        }
    }

    fun setScreenType(type: String) = loadLayout(type)

    fun setResetOobCode(oobCode: String) {
        _uiState.value = _uiState.value.copy(resetOobCode = oobCode, error = null, inputs = emptyMap())
    }

    fun onInputChanged(fieldId: String, value: String) {
        val updated = _uiState.value.inputs.toMutableMap().also { it[fieldId] = value }
        _uiState.value = _uiState.value.copy(inputs = updated)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearFormState() {
        _uiState.value = _uiState.value.copy(
            inputs = emptyMap(), error = null, resetEmailSent = false, resetEmail = ""
        )
    }

    fun clearOfflineState() {
        _uiState.value = _uiState.value.copy(isBackendOffline = false, error = null)
    }

    private fun validateInputs(): String? {
        val layout = _uiState.value.layout ?: return null
        val inputs = _uiState.value.inputs
        layout.components.filterIsInstance<SduiAppComponent.Input>().forEach { input ->
            val v = input.validation ?: return@forEach
            val value = inputs[input.id]?.trim() ?: ""
            if (v.required && value.isEmpty()) return v.errorMessage ?: "Please fill in ${input.label}."
            if (value.isNotEmpty()) {
                v.minLength?.let { if (value.length < it) return v.errorMessage ?: "${input.label} must be at least $it characters." }
                v.maxLength?.let { if (value.length > it) return v.errorMessage ?: "${input.label} must be at most $it characters." }
                v.pattern?.let  { if (!Regex(it).containsMatchIn(value)) return v.errorMessage ?: "${input.label} is invalid." }
            }
        }
        return null
    }

    fun onSubmit(screenType: String, onSuccess: () -> Unit, resetOobCode: String? = null) {
        val inputs = _uiState.value.inputs
        val email    = inputs["email"]?.trim() ?: ""
        val password = inputs["password"] ?: ""

        validateInputs()?.let { error ->
            _uiState.value = _uiState.value.copy(error = error)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            val result: Result<*> = when (screenType) {
                "register"     -> authProvider.registerWithEmail(email, password)
                "reset-confirm" -> {
                    val code = resetOobCode ?: _uiState.value.resetOobCode ?: ""
                    if (code.isBlank()) {
                        _uiState.value = _uiState.value.copy(isAuthenticating = false, error = "Invalid reset link")
                        return@launch
                    }
                    authProvider.confirmPasswordReset(code, password)
                }
                else           -> authProvider.signInWithEmail(email, password)
            }
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isAuthenticating = false)
                    onSuccess()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        error = friendlyAuthError(e.message ?: "")
                    )
                }
            )
        }
    }

    fun onGoogleSignIn(idToken: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            authProvider.signInWithGoogle(idToken).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isAuthenticating = false)
                    onSuccess()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        error = friendlyAuthError(e.message ?: "")
                    )
                }
            )
        }
    }

    fun onGoogleSignInInteractive(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            authProvider.signInWithGoogleInteractive().fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isAuthenticating = false)
                    onSuccess()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        error = friendlyAuthError(e.message ?: "")
                    )
                }
            )
        }
    }

    fun onForgotPasswordSubmit(email: String, onSent: () -> Unit) {
        if (email.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter your email to receive a reset link.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            try {
                sduiApi.sendPasswordResetEmail(email)
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false, resetEmailSent = true, resetEmail = email
                )
                onSent()
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "Could not send reset link. Please try again."
                )
            }
        }
    }

    fun confirmPasswordReset(onSuccess: () -> Unit) {
        val oobCode  = _uiState.value.resetOobCode ?: return
        val password = _uiState.value.inputs["newPassword"] ?: ""
        if (password.length < 6) {
            _uiState.value = _uiState.value.copy(error = "Password must be at least 6 characters.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            authProvider.confirmPasswordReset(oobCode, password).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false, passwordResetConfirmed = true,
                        resetOobCode = null, inputs = emptyMap()
                    )
                    onSuccess()
                },
                onFailure = { e ->
                    val msg = when {
                        e.message?.contains("expired") == true ||
                        e.message?.contains("invalid") == true ->
                            "This reset link has expired. Please request a new one."
                        else -> "Could not reset password. Please try again."
                    }
                    _uiState.value = _uiState.value.copy(isAuthenticating = false, error = msg)
                }
            )
        }
    }

    private fun friendlyAuthError(raw: String): String = when {
        raw.containsAny("wrong-password", "invalid-credential", "INVALID_LOGIN_CREDENTIALS") ->
            "Incorrect email or password. Try again or reset your password."
        raw.containsAny("user-not-found", "no user record") ->
            "No account found with this email. Create an account to get started."
        raw.containsAny("email-already-in-use") ->
            "This email is already registered. Sign in instead."
        raw.containsAny("weak-password") ->
            "Please use a stronger password (at least 6 characters)."
        raw.containsAny("too-many-requests") ->
            "Too many failed attempts. Please wait a moment and try again."
        raw.containsAny("network") ->
            "No internet connection. Check your connection and try again."
        else -> raw.ifBlank { "Something went wrong. Please try again." }
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it, ignoreCase = true) }
}
