package com.stationly.mobile.ui.login

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.service.SduiApiServiceFactory
import com.stationly.mobile.service.FirebaseAuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.stationly.core.repository.UserSyncRepository
import com.stationly.core.platform.Platform
import com.stationly.core.usecase.StationLifecycleUseCase

data class LoginUiState(
    val layout: SduiAppScreen? = null,
    val isLoading: Boolean = false,
    val isAuthenticating: Boolean = false,
    val isBackendOffline: Boolean = false,
    val error: String? = null,
    val inputs: Map<String, String> = emptyMap(),
    val resetEmailSent: Boolean = false,
    val resetEmail: String = "",
    val resetOobCode: String? = null,
    val passwordResetConfirmed: Boolean = false
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val apiService = SduiApiServiceFactory.create()
    val authManager = FirebaseAuthManager(application)
    private val userSyncRepository = UserSyncRepository(apiService, Platform.sqlStorage, Platform.storageManager)

    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = com.stationly.core.repository.SelectionRepository(Platform.storageManager, Platform.sqlStorage),
        departureRepository = com.stationly.core.repository.DepartureRepository(
            com.stationly.core.service.TflApiServiceFactory.create(),
            Platform.storageManager,
            Platform.sqlStorage,
            com.stationly.core.usecase.SyncPredictionsUseCase(Platform.sqlStorage)
        ),
        notificationManager = Platform.notificationManager,
        widgetManager = Platform.widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager
    )

    fun setScreenType(type: String) {
        // If the layout for this screen type is already loaded, reset form state without a network call
        if (_uiState.value.layout?.id?.startsWith(type) == true) {
            _uiState.value = _uiState.value.copy(
                inputs = emptyMap(),
                error = null,
                resetEmailSent = false,
                resetEmail = ""
            )
            return
        }
        loadLayout(type)
    }

    fun clearFormState() {
        _uiState.value = _uiState.value.copy(
            inputs = emptyMap(),
            error = null,
            resetEmailSent = false,
            resetEmail = ""
        )
    }

    private fun loadLayout(type: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, inputs = emptyMap())
            try {
                val layout = when (type) {
                    "login"            -> apiService.getLoginLayout()
                    "register"         -> apiService.getRegisterLayout()
                    "forgot-password"  -> apiService.getForgotPasswordLayout()
                    else               -> apiService.getLoginLayout()
                }
                _uiState.value = _uiState.value.copy(layout = layout, isLoading = false)
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Failed to load $type screen.", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isBackendOffline = true,
                    error = com.stationly.mobile.util.BackendErrorUtil.getFriendlyMessage(e)
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun onInputChanged(id: String, value: String) {
        _uiState.value = _uiState.value.copy(
            inputs = _uiState.value.inputs + (id to value)
        )
    }

    fun handleAction(
        action: String,
        onAuthSuccess: () -> Unit = {},
        onNavigateToRegister: () -> Unit = {},
        onNavigateToLogin: () -> Unit = {},
        onNavigateToForgotPassword: () -> Unit = {},
        onGoogleSignInRequested: () -> Unit = {}
    ) {
        Log.d("LoginViewModel", "Action: $action")
        when (action) {
            "LOGIN_ACTION"               -> performEmailAuth(onAuthSuccess)
            "REGISTER_ACTION"            -> performRegister(onAuthSuccess)
            "RESET_PASSWORD_ACTION"      -> performReset()
            "NAVIGATE_TO_REGISTER"       -> onNavigateToRegister()
            "NAVIGATE_TO_LOGIN"          -> onNavigateToLogin()
            "NAVIGATE_TO_FORGOT_PASSWORD" -> onNavigateToForgotPassword()
            "GOOGLE_LOGIN_ACTION"        -> onGoogleSignInRequested()
        }
    }

    fun signInWithGoogle(
        task: com.google.android.gms.tasks.Task<com.google.android.gms.auth.api.signin.GoogleSignInAccount>,
        onAuthSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            when (val result = authManager.signInWithGoogle(task)) {
                is com.stationly.mobile.service.AuthResult.Success -> {
                    Log.d("LoginViewModel", "Google Sign-In OK: ${result.user.email}")
                    syncUserAndSyncData(result.user, onAuthSuccess)
                }
                is com.stationly.mobile.service.AuthResult.Error -> {
                    Log.w("LoginViewModel", "Google Sign-In result: ${result.message}")
                    // Code 12501 = user cancelled the chooser — not an error to surface
                    if (result.message.contains("12501")) {
                        _uiState.value = _uiState.value.copy(isAuthenticating = false)
                        return@launch
                    }
                    val displayError = when {
                        result.message.contains("Code: 10") ->
                            "Google sign-in misconfigured. Please contact support."
                        else -> result.message
                    }
                    _uiState.value = _uiState.value.copy(isAuthenticating = false, error = displayError)
                }
            }
        }
    }

    /**
     * Evaluates SduiValidation rules from the loaded layout against current inputs.
     * Returns the first failing errorMessage, or null if all fields are valid.
     * This replaces per-action hardcoded validation so the backend owns the rules.
     */
    private fun validateInputs(): String? {
        val layout = _uiState.value.layout ?: return null
        val inputs = _uiState.value.inputs
        layout.components.filterIsInstance<com.stationly.core.model.sdui.SduiAppComponent.Input>()
            .forEach { input ->
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

    private fun performEmailAuth(onAuthSuccess: () -> Unit) {
        val email    = _uiState.value.inputs["email"]?.trim() ?: ""
        val password = _uiState.value.inputs["password"] ?: ""
        validateInputs()?.let { error ->
            _uiState.value = _uiState.value.copy(error = error)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            when (val result = authManager.signInWithEmail(email, password)) {
                is com.stationly.mobile.service.AuthResult.Success ->
                    syncUserAndSyncData(result.user, onAuthSuccess)
                is com.stationly.mobile.service.AuthResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        error = friendlySignInError(result.message)
                    )
            }
        }
    }

    private fun performRegister(onAuthSuccess: () -> Unit) {
        val email       = _uiState.value.inputs["email"]?.trim() ?: ""
        val password    = _uiState.value.inputs["password"] ?: ""
        val displayName = _uiState.value.inputs["displayName"]?.trim() ?: ""

        validateInputs()?.let { error ->
            _uiState.value = _uiState.value.copy(error = error)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            when (val result = authManager.createEmailAccount(email, password)) {
                is com.stationly.mobile.service.AuthResult.Success -> {
                    if (displayName.isNotEmpty()) {
                        try {
                            result.user.updateProfile(
                                com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                    .setDisplayName(displayName).build()
                            ).await()
                        } catch (e: Exception) {
                            Log.w("LoginViewModel", "Display name update failed", e)
                        }
                    }
                    // Send welcome / verification email via Firebase (non-fatal)
                    try {
                        result.user.sendEmailVerification().await()
                        Log.d("LoginViewModel", "Verification email sent to $email")
                    } catch (e: Exception) {
                        Log.w("LoginViewModel", "Verification email failed", e)
                    }
                    syncUserAndSyncData(result.user, onAuthSuccess)
                }
                is com.stationly.mobile.service.AuthResult.Error ->
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        error = friendlyRegisterError(result.message)
                    )
            }
        }
    }

    private fun friendlySignInError(raw: String): String = when {
        raw.containsAny("user-not-found", "no user record", "There is no user record") ->
            "No account found with this email. Create an account to get started."
        raw.containsAny("wrong-password", "invalid-credential", "INVALID_LOGIN_CREDENTIALS",
            "invalid credential") ->
            "Incorrect email or password. Try again or reset your password."
        raw.containsAny("account-exists-with-different-credential") ->
            "This email is linked to a Google account. Use 'Continue with Google' to sign in."
        raw.containsAny("user-disabled") ->
            "This account has been disabled. Please contact support."
        raw.containsAny("too-many-requests") ->
            "Too many failed attempts. Please wait a moment and try again."
        raw.containsAny("network") ->
            "No internet connection. Check your connection and try again."
        else -> raw
    }

    private fun friendlyRegisterError(raw: String): String = when {
        raw.containsAny("email-already-in-use", "email address is already in use") ->
            "This email is already registered. Sign in instead, or reset your password if you've forgotten it."
        raw.containsAny("account-exists-with-different-credential") ->
            "This email is linked to a Google account. Use 'Continue with Google' to sign in."
        raw.containsAny("invalid-email") ->
            "That doesn't look like a valid email address."
        raw.containsAny("weak-password", "password is too weak") ->
            "Please use a stronger password (at least 6 characters)."
        raw.containsAny("network") ->
            "No internet connection. Check your connection and try again."
        else -> raw
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it, ignoreCase = true) }

    private fun performReset() {
        val email = _uiState.value.inputs["email"]?.trim() ?: ""
        if (email.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Enter your email to receive a reset link.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            try {
                apiService.sendPasswordResetEmail(email)
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    resetEmailSent   = true,
                    resetEmail       = email
                )
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Reset failed", e)
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "Could not send reset link. Please try again."
                )
            }
        }
    }

    private fun syncUserAndSyncData(user: com.google.firebase.auth.FirebaseUser, onAuthSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isAuthenticating = true)
                val provider = user.providerData.find { it.providerId != "firebase" }?.providerId ?: "email"
                val stations = userSyncRepository.syncUserAndGetSavedStations(
                    uid         = user.uid,
                    email       = user.email ?: "",
                    displayName = user.displayName,
                    photoURL    = user.photoUrl?.toString(),
                    provider    = provider
                )
                stationLifecycleUseCase.cleanupAll()
                val primary = stations.firstOrNull()
                if (primary != null) {
                    stationLifecycleUseCase.setupStation(
                        com.stationly.core.model.UserSelection(
                            mode        = primary.mode,
                            line        = primary.line,
                            station     = primary.id,
                            stationName = primary.name,
                            direction   = primary.direction,
                            destinations   = emptyList(),
                            destinationIds = emptyList()
                        ),
                        isFirstTime = true
                    )
                } else {
                    // No saved stations — redraw widget so it exits the login placeholder
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.stationly.mobile.widget.DepartureWidgetProvider.updateFromStorage(getApplication())
                    }
                }
                _uiState.value = _uiState.value.copy(isAuthenticating = false)
                onAuthSuccess()
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Backend sync failed, proceeding anyway: ${e.message}")
                _uiState.value = _uiState.value.copy(isAuthenticating = false)
                onAuthSuccess()
            }
        }
    }

    fun clearOfflineState() {
        _uiState.value = _uiState.value.copy(isBackendOffline = false, error = null)
    }

    fun setResetOobCode(oobCode: String) {
        _uiState.value = _uiState.value.copy(resetOobCode = oobCode, error = null, inputs = emptyMap())
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
            try {
                authManager.auth.confirmPasswordReset(oobCode, password).await()
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    passwordResetConfirmed = true,
                    resetOobCode = null,
                    inputs = emptyMap()
                )
                onSuccess()
            } catch (e: Exception) {
                Log.e("LoginViewModel", "confirmPasswordReset failed", e)
                val msg = when {
                    e.message?.contains("expired") == true || e.message?.contains("invalid") == true ->
                        "This reset link has expired. Please request a new one."
                    else -> "Could not reset password. Please try again."
                }
                _uiState.value = _uiState.value.copy(isAuthenticating = false, error = msg)
            }
        }
    }
}
