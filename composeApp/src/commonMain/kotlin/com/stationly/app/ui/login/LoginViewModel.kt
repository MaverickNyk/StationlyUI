package com.stationly.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.platform.DeviceIdentity
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.repository.UserSyncRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authProvider: PlatformAuthProvider
) : ViewModel() {

    private val sduiApi = NetworkModule.sduiApi

    private val userSyncRepository = UserSyncRepository(
        NetworkModule.sduiApi, Platform.sqlStorage, Platform.storageManager
    )
    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = SelectionRepository(Platform.storageManager, Platform.sqlStorage),
        departureRepository = DepartureRepository(
            NetworkModule.tflApi,
            Platform.storageManager,
            Platform.sqlStorage,
            SyncPredictionsUseCase(Platform.sqlStorage)
        ),
        notificationManager = Platform.notificationManager,
        widgetManager = Platform.widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager
    )

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

    fun onSubmit(
        screenType: String,
        onSuccess: () -> Unit,
        onNeedsEmailVerification: () -> Unit,
        resetOobCode: String? = null
    ) {
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
                    val isAuthFlow = screenType == "login" || screenType == "register"
                    if (isAuthFlow) {
                        if (authProvider.isEmailProvider() && !authProvider.isEmailVerified()) {
                            // First register path must send verification email automatically
                            if (screenType == "register") {
                                runCatching { sduiApi.sendVerificationEmail() }
                                    .onFailure { runCatching { authProvider.sendEmailVerification() } }
                            }
                            _uiState.value = _uiState.value.copy(isAuthenticating = false)
                            onNeedsEmailVerification()
                            return@fold
                        }
                        if (syncUserAndSetupData(provider = "email")) {
                            _uiState.value = _uiState.value.copy(isAuthenticating = false)
                            onSuccess()
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(isAuthenticating = false)
                        onSuccess()
                    }
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
                    if (syncUserAndSetupData(provider = "google")) {
                        _uiState.value = _uiState.value.copy(isAuthenticating = false)
                        onSuccess()
                    }
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
                    if (syncUserAndSetupData(provider = "google")) {
                        _uiState.value = _uiState.value.copy(isAuthenticating = false)
                        onSuccess()
                    }
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

    fun onAppleSignInInteractive(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            authProvider.signInWithAppleInteractive().fold(
                onSuccess = {
                    if (syncUserAndSetupData(provider = "apple")) {
                        _uiState.value = _uiState.value.copy(isAuthenticating = false)
                        onSuccess()
                    }
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

    /**
     * Post-auth backend session sync — the iOS counterpart of Android
     * `LoginViewModel.syncUserAndSyncData`:
     *  1. `/user/sync/profile` registers this device's session (deviceId +
     *     deviceInfo) and creates/updates the user record, returning the
     *     user's saved stations, which are restored into SQLite.
     *  2. The primary station is fully set up (predictions fetch + FCM topic
     *     subscribe + widget push).
     *  3. This device's FCM token is registered under the just-signed-in uid
     *     so user-targeted pushes (incl. cross-device force-logout) arrive.
     *
     * Unlike Android we must NOT run `stationLifecycleUseCase.cleanupAll()`
     * here: on iOS it wipes the standard NSUserDefaults domain, which is where
     * Swift AuthBridge just stored the user's identity (display name, photo,
     * token). The profile sync above already resets SQL + cached data.
     *
     * Returns false (after rolling back the Firebase session, mirroring
     * Android) when the backend can't be reached — the caller must NOT
     * navigate in that case.
     */
    private suspend fun syncUserAndSetupData(provider: String): Boolean {
        try {
            val uid = authProvider.currentUserUid()
                ?: throw IllegalStateException("No uid after sign-in")
            val stations = userSyncRepository.syncUserAndGetSavedStations(
                uid         = uid,
                email       = authProvider.currentUserEmail() ?: "",
                displayName = authProvider.currentUserDisplayName(),
                photoURL    = authProvider.currentUserPhotoUrl(),
                provider    = provider,
                deviceId    = DeviceIdentity.deviceId(),
                deviceInfo  = DeviceIdentity.deviceInfo()
            )

            stations.firstOrNull()?.let { primary ->
                stationLifecycleUseCase.setupStation(
                    UserSelection(
                        mode           = primary.mode,
                        line           = primary.line,
                        station        = primary.id,
                        stationName    = primary.name,
                        direction      = primary.direction,
                        destinations   = emptyList(),
                        destinationIds = emptyList()
                    ),
                    isFirstTime = true
                )
            }

            // Best-effort — a failed token registration shouldn't block login;
            // it is retried on the next foreground.
            //
            // Goes through FcmTokenRegistrar rather than POSTing inline so the
            // "last registered (token, uid)" cache is seeded here. Registering
            // directly left that cache empty, and SummaryViewModel's init call
            // then re-POSTed the identical token seconds later on EVERY login.
            com.stationly.app.util.FcmTokenRegistrar.ensureRegistered(uid = uid)

            return true
        } catch (e: Exception) {
            // ROLLBACK: Firebase auth succeeded but the backend sync didn't.
            // Proceeding would leave a session with no server record (broken
            // widget/sync/FCM topics) — sign back out and let the user retry.
            runCatching { authProvider.signOut() }
            _uiState.value = _uiState.value.copy(
                isAuthenticating = false,
                error = "We couldn't reach our servers to finish signing you in. " +
                        "Please check your connection and try again."
            )
            return false
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

    fun togglePasswordVisibility(field: String) {
        val current = _uiState.value.passwordVisible.toMutableMap()
        current[field] = !(current[field] ?: false)
        _uiState.value = _uiState.value.copy(passwordVisible = current)
    }

    fun confirmEmailVerified(onAuthSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            if (!authProvider.isLoggedIn()) {
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "Your session expired. Please sign in again."
                )
                return@launch
            }
            try {
                authProvider.reloadUser().getOrThrow()
            } catch (e: Exception) {
                // Ignore reload error, we check verified flag directly next
            }
            if (authProvider.isEmailVerified()) {
                if (syncUserAndSetupData(provider = "email")) {
                    _uiState.value = _uiState.value.copy(isAuthenticating = false)
                    onAuthSuccess()
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "Still not verified. Tap the link in the email and try again."
                )
            }
        }
    }

    fun silentlyCheckEmailVerified(onAuthSuccess: () -> Unit) {
        viewModelScope.launch {
            if (!authProvider.isLoggedIn()) return@launch
            try {
                authProvider.reloadUser().getOrThrow()
            } catch (e: Exception) {
                return@launch
            }
            if (authProvider.isEmailVerified()) {
                if (syncUserAndSetupData(provider = "email")) {
                    _uiState.value = _uiState.value.copy(isAuthenticating = false)
                    onAuthSuccess()
                }
            }
        }
    }

    fun resendVerificationEmail() {
        viewModelScope.launch {
            try {
                val sent = sduiApi.sendVerificationEmail()
                if (!sent) throw IllegalStateException("Backend returned non-200")
                _uiState.value = _uiState.value.copy(error = null)
            } catch (e: Exception) {
                authProvider.sendEmailVerification()
                    .onSuccess { _uiState.value = _uiState.value.copy(error = null) }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(
                            error = "Couldn't resend right now. Please try again in a minute."
                        )
                    }
            }
        }
    }

    fun signOutAfterVerificationFlow(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            authProvider.signOut()
            onSignedOut()
        }
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it, ignoreCase = true) }
}
