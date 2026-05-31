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
        val cacheKey = "auth_layout_$type"
        // Instant first paint from the last good layout (stale-while-revalidate);
        // the network refresh below always runs and replaces it. Auth is the
        // first screen for logged-out users, so this kills the cold-launch blank.
        val cached = com.stationly.mobile.util.SduiCache.read<com.stationly.core.model.sdui.SduiAppScreen>(
            getApplication(), cacheKey
        )
        _uiState.value = _uiState.value.copy(
            layout = cached ?: _uiState.value.layout,
            // Only show the full-screen loader when we have nothing to paint yet.
            isLoading = cached == null,
            // We have content to show — clear any stale offline state.
            isBackendOffline = if (cached != null) false else _uiState.value.isBackendOffline,
            error = null,
            inputs = emptyMap()
        )
        viewModelScope.launch {
            try {
                val layout = when (type) {
                    "login"            -> apiService.getLoginLayout()
                    "register"         -> apiService.getRegisterLayout()
                    "forgot-password"  -> apiService.getForgotPasswordLayout()
                    else               -> apiService.getLoginLayout()
                }
                _uiState.value = _uiState.value.copy(layout = layout, isLoading = false)
                com.stationly.mobile.util.SduiCache.write(getApplication(), cacheKey, layout)
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Failed to load $type screen.", e)
                // If we already painted a cached layout, stay on it silently;
                // only surface the offline error when there's nothing to show.
                if (_uiState.value.layout == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isBackendOffline = true,
                        error = com.stationly.mobile.util.BackendErrorUtil.getFriendlyMessage(e)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
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
        onNeedsEmailVerification: () -> Unit = {},
        onNavigateToRegister: () -> Unit = {},
        onNavigateToLogin: () -> Unit = {},
        onNavigateToForgotPassword: () -> Unit = {},
        onGoogleSignInRequested: () -> Unit = {}
    ) {
        Log.d("LoginViewModel", "Action: $action")
        when (action) {
            "LOGIN_ACTION"               -> performEmailAuth(onAuthSuccess, onNeedsEmailVerification)
            "REGISTER_ACTION"            -> performRegister(onNeedsEmailVerification)
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
            com.stationly.mobile.service.AuthLog.googleLoginStarted()
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            when (val result = authManager.signInWithGoogle(task)) {
                is com.stationly.mobile.service.AuthResult.Success -> {
                    com.stationly.mobile.service.AuthLog.loginSuccess(result.user.uid, "google")
                    syncUserAndSyncData(result.user, "google", onAuthSuccess)
                }
                is com.stationly.mobile.service.AuthResult.Error -> {
                    // Code 12501 = user cancelled the chooser — not an error to surface
                    if (result.message.contains("12501")) {
                        com.stationly.mobile.service.AuthLog.googleLoginCancelled()
                        _uiState.value = _uiState.value.copy(isAuthenticating = false)
                        return@launch
                    }
                    com.stationly.mobile.service.AuthLog.loginFailure(result.message, "google")
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
     * Evaluates SduiValidation rules from the loaded layout against current inputs,
     * THEN falls back to client-side safety-net checks for email format / password
     * strength / display name. SDUI takes priority — the safety net only fires when
     * the backend didn't send rules for that field. Returns the first failing
     * message, or null if all fields are valid.
     */
    private fun validateInputs(): String? {
        val layout = _uiState.value.layout ?: return null
        val inputs = _uiState.value.inputs
        val components = layout.components.filterIsInstance<com.stationly.core.model.sdui.SduiAppComponent.Input>()

        // 1. Backend-driven SDUI validation (the backend's word is final when it speaks)
        for (input in components) {
            val v = input.validation ?: continue
            val value = inputs[input.id]?.trim() ?: ""
            if (v.required && value.isEmpty()) return v.errorMessage ?: "Please fill in ${input.label}."
            if (value.isNotEmpty()) {
                v.minLength?.let { if (value.length < it) return v.errorMessage ?: "${input.label} must be at least $it characters." }
                v.maxLength?.let { if (value.length > it) return v.errorMessage ?: "${input.label} must be at most $it characters." }
                v.pattern?.let  { if (!Regex(it).containsMatchIn(value)) return v.errorMessage ?: "${input.label} is invalid." }
            }
        }

        // 2. Client-side safety net — catches the obvious stuff if SDUI is missing
        //    rules. Avoids round-tripping bad data to Firebase for a generic error.
        for (input in components) {
            val raw = inputs[input.id] ?: ""
            val trimmed = raw.trim()
            val isEmail    = input.id.equals("email", ignoreCase = true) || input.style == "email"
            val isPassword = input.style == "password"
            val isName     = input.id.equals("displayName", ignoreCase = true)
                          || input.id.equals("name", ignoreCase = true)
            when {
                isEmail && trimmed.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmed).matches() ->
                    return "That doesn't look like a valid email address."
                // We trim the password before submit, but here we want raw because
                // leading-whitespace stripping is a "submit-time fix," not "input-time."
                isPassword && raw.isNotEmpty() && raw.trim().length < MIN_PASSWORD_LENGTH ->
                    return "Password must be at least $MIN_PASSWORD_LENGTH characters."
                isName && trimmed.isNotEmpty() && trimmed.length < 2 ->
                    return "Please enter a name with at least 2 characters."
            }
        }
        return null
    }

    private companion object {
        // Firebase enforces 6+; we match that so users never see Firebase's own message.
        const val MIN_PASSWORD_LENGTH = 6
    }

    private fun performEmailAuth(
        onAuthSuccess: () -> Unit,
        onNeedsEmailVerification: () -> Unit
    ) {
        val email    = _uiState.value.inputs["email"]?.trim() ?: ""
        // Password is trimmed on submit only — not on input, so the user can still type
        // a literal space mid-password if they want, but copy-pasted leading/trailing
        // whitespace (the common silent-fail) is dropped before we hit Firebase.
        val password = _uiState.value.inputs["password"]?.trim() ?: ""
        validateInputs()?.let { error ->
            _uiState.value = _uiState.value.copy(error = error)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            when (val result = authManager.signInWithEmail(email, password)) {
                is com.stationly.mobile.service.AuthResult.Success -> {
                    com.stationly.mobile.service.AuthLog.loginSuccess(result.user.uid, "email")
                    // Gate: email login routes to verify-email if Firebase says
                    // the address is not yet confirmed. Google bypasses this entirely.
                    if (!result.user.isEmailVerified) {
                        _uiState.value = _uiState.value.copy(isAuthenticating = false)
                        onNeedsEmailVerification()
                    } else {
                        syncUserAndSyncData(result.user, "email", onAuthSuccess)
                    }
                }
                is com.stationly.mobile.service.AuthResult.Error -> {
                    com.stationly.mobile.service.AuthLog.loginFailure(result.message, "email")
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        error = friendlySignInError(result.message)
                    )
                }
            }
        }
    }

    private fun performRegister(onNeedsEmailVerification: () -> Unit) {
        val email       = _uiState.value.inputs["email"]?.trim() ?: ""
        val password    = _uiState.value.inputs["password"]?.trim() ?: ""
        val displayName = _uiState.value.inputs["displayName"]?.trim() ?: ""

        validateInputs()?.let { error ->
            _uiState.value = _uiState.value.copy(error = error)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            when (val result = authManager.createEmailAccount(email, password)) {
                is com.stationly.mobile.service.AuthResult.Success -> {
                    com.stationly.mobile.service.AuthLog.registerSuccess(result.user.uid, "email")
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

                    // CREATE-THEN-VERIFY: write the Firestore user doc IMMEDIATELY so
                    // we have a backend record for every Auth user, even unverified
                    // ones. The doc carries emailVerified = false until the user
                    // confirms. Funnel visibility + recovery path comes for free.
                    val provider = "email"
                    val syncOk = runCatching {
                        userSyncRepository.syncUserAndGetSavedStations(
                            uid         = result.user.uid,
                            email       = result.user.email ?: email,
                            displayName = result.user.displayName ?: displayName.ifBlank { null },
                            photoURL    = result.user.photoUrl?.toString(),
                            provider    = provider,
                            deviceId    = com.stationly.mobile.service.DeviceIdProvider.get(getApplication()),
                            deviceInfo  = com.stationly.mobile.service.DeviceIdProvider.info(getApplication())
                        )
                    }.isSuccess
                    if (!syncOk) {
                        com.stationly.mobile.service.AuthLog.backendSyncFailed(
                            stage  = "register",
                            reason = "sync_failed_pre_verify"
                        )
                    }

                    // Trigger the Stationly-branded verification email via the
                    // backend (Resend). Runs under NonCancellable so a stray
                    // ViewModel-scope cancellation (the bug that surfaced when the
                    // global verify-gate raced this coroutine) can't drop the email
                    // send half-way through and silently fall back to Firebase's
                    // plain template.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        try {
                            val sent = apiService.sendVerificationEmail()
                            if (!sent) throw IllegalStateException("backend returned non-200")
                        } catch (e: Exception) {
                            Log.w("LoginViewModel", "Branded verification email failed; falling back to Firebase default", e)
                            runCatching { result.user.sendEmailVerification().await() }
                        }
                    }

                    _uiState.value = _uiState.value.copy(isAuthenticating = false)
                    onNeedsEmailVerification()
                }
                is com.stationly.mobile.service.AuthResult.Error -> {
                    com.stationly.mobile.service.AuthLog.registerFailure(result.message, "email")
                    _uiState.value = _uiState.value.copy(
                        isAuthenticating = false,
                        error = friendlyRegisterError(result.message)
                    )
                }
            }
        }
    }

    /**
     * Called from VerifyEmailScreen when the user taps "I've verified". Reloads the
     * Firebase user to get a fresh isEmailVerified flag, then proceeds to syncing
     * and the summary if true. Stays put with an error if still unverified.
     */
    fun confirmEmailVerified(onAuthSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            val user = authManager.currentUser
            if (user == null) {
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "Your session expired. Please sign in again."
                )
                return@launch
            }
            try {
                user.reload().await()
                // Force-refresh the ID token so the backend sees email_verified=true on
                // the very next /user/sync/profile call — otherwise the cached token
                // still says false and welcome doesn't fire.
                user.getIdToken(true).await()
            } catch (e: Exception) {
                Log.w("LoginViewModel", "user.reload() / token refresh failed", e)
            }
            val refreshed = authManager.currentUser
            if (refreshed?.isEmailVerified == true) {
                syncUserAndSyncData(refreshed, "email", onAuthSuccess)
            } else {
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "Still not verified — tap the link in the email and try again."
                )
            }
        }
    }

    /**
     * Handle the stationly://verified?oobCode=... deep link. Applies the one-time
     * verification code via Firebase client SDK, refreshes the user, runs the post-
     * verify sync (which fires the welcome email backend-side), then proceeds.
     *
     * If there's no signed-in Firebase user (rare — they killed the app between
     * signup and the email click), we route them to login with a friendly message
     * so they can re-authenticate; their account will already be verified by then.
     */
    fun applyVerificationCode(oobCode: String, onAuthSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            val applyResult = runCatching {
                authManager.auth.applyActionCode(oobCode).await()
            }
            if (applyResult.isFailure) {
                Log.w("LoginViewModel", "applyActionCode failed", applyResult.exceptionOrNull())
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "This verification link is no longer valid. Tap \"Resend email\" to get a new one."
                )
                return@launch
            }
            val user = authManager.currentUser
            if (user == null) {
                // App was killed between signup and email click. Account IS now
                // verified (apply succeeded). Just route them to login.
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "Email verified — please sign in to finish setting up."
                )
                return@launch
            }
            // Refresh local cache + ID token so backend sees emailVerified=true on
            // the next sync (which fires the welcome email).
            runCatching {
                user.reload().await()
                user.getIdToken(true).await()
            }
            syncUserAndSyncData(authManager.currentUser ?: user, "email", onAuthSuccess)
        }
    }

    /**
     * Silent ON_RESUME poll variant of [confirmEmailVerified]. Called every time the
     * VerifyEmailScreen becomes visible again (e.g. user returns from their email
     * app after tapping the link). If isEmailVerified is now true we proceed; if
     * not we say nothing — the user might not have verified yet, no need to nag.
     */
    fun silentlyCheckEmailVerified(onAuthSuccess: () -> Unit) {
        viewModelScope.launch {
            val user = authManager.currentUser ?: return@launch
            runCatching {
                user.reload().await()
                user.getIdToken(true).await()
            }
            val refreshed = authManager.currentUser
            if (refreshed?.isEmailVerified == true) {
                syncUserAndSyncData(refreshed, "email", onAuthSuccess)
            }
        }
    }

    /**
     * Resend the verification email. Rate-limited at the UI layer (60s cooldown);
     * Firebase enforces its own rate limit on top.
     */
    fun resendVerificationEmail() {
        viewModelScope.launch {
            val user = authManager.currentUser ?: return@launch
            try {
                val sent = apiService.sendVerificationEmail()
                if (!sent) throw IllegalStateException("backend returned non-200")
                _uiState.value = _uiState.value.copy(error = null)
            } catch (e: Exception) {
                Log.w("LoginViewModel", "Branded resend failed; falling back to Firebase default", e)
                runCatching { user.sendEmailVerification().await() }
                    .onSuccess { _uiState.value = _uiState.value.copy(error = null) }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(
                            error = "Couldn't resend right now. Please try again in a minute."
                        )
                    }
            }
        }
    }

    /**
     * "Use a different email" from VerifyEmailScreen — sign out fully so the user
     * lands back on the landing screen and can register with a different address.
     */
    fun signOutAfterVerificationFlow(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            authManager.logout()
            onSignedOut()
        }
    }

    private fun friendlySignInError(raw: String): String = when {
        raw.containsAny("user-not-found", "no user record", "There is no user record") ->
            "No account found with this email. Create an account to get started."
        raw.containsAny("wrong-password", "invalid-credential", "INVALID_LOGIN_CREDENTIALS",
            "invalid credential") ->
            "Incorrect email or password. Try again or reset your password."
        raw.containsAny("account-exists-with-different-credential") ->
            "This email is already registered with a different sign-in method. " +
            "Try \"Continue with Google\" or reset your password."
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
            "This email is already registered with a different sign-in method. " +
            "Sign in the way you signed up the first time."
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
                com.stationly.mobile.service.AuthLog.passwordResetRequested(
                    com.stationly.mobile.service.AuthLog.maskPii(email)
                )
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

    private fun syncUserAndSyncData(
        user: com.google.firebase.auth.FirebaseUser,
        provider: String,
        onAuthSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isAuthenticating = true)
                val stations = userSyncRepository.syncUserAndGetSavedStations(
                    uid         = user.uid,
                    email       = user.email ?: "",
                    displayName = user.displayName,
                    photoURL    = user.photoUrl?.toString(),
                    provider    = provider,
                    deviceId    = com.stationly.mobile.service.DeviceIdProvider.get(getApplication()),
                    deviceInfo  = com.stationly.mobile.service.DeviceIdProvider.info(getApplication())
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
                    // Fan out to every surface so the dream's chronometer
                    // resets too (setupStation already updates the home
                    // VM via SharedPrefs ping + the widget via
                    // widgetManager.updateWidget, but skips the dream
                    // broadcast).
                    com.stationly.mobile.util.FreshDataNotifier.notify(
                        getApplication(),
                        stationId = primary.id,
                        lineId = primary.line,
                    )
                } else {
                    // No saved stations — redraw widget so it exits the login placeholder
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.stationly.mobile.widget.DepartureWidgetProvider.updateFromStorage(getApplication())
                    }
                }
                // Register this device's FCM token under the just-signed-in
                // account NOW. FcmTokenRegistrar otherwise only runs on app
                // launch and token rotation, so logging in within an already-
                // running process left the token unregistered for the new uid —
                // which meant every `user_sync` push (incl. the account-deleted
                // force-logout) reached 0 devices. This is what made cross-device
                // logout fall back to the foreground/lock-unlock path instead of
                // being instant.
                com.stationly.mobile.service.FcmTokenRegistrar.ensureRegistered(getApplication())

                _uiState.value = _uiState.value.copy(isAuthenticating = false)
                onAuthSuccess()
            } catch (e: Exception) {
                // ROLLBACK: Firebase auth succeeded but backend sync didn't. If we
                // proceed, the user has a Firebase session with no server record —
                // widget breaks, sync is broken, FCM topics will never reconcile.
                // Sign back out and surface a clear error so they can retry.
                com.stationly.mobile.service.AuthLog.backendSyncFailed(
                    stage  = provider,
                    reason = e.message ?: e::class.simpleName.orEmpty()
                )
                runCatching { authManager.logout() }
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "We couldn't reach our servers to finish signing you in. " +
                            "Please check your connection and try again."
                )
                // Do NOT call onAuthSuccess() — the user stays on the auth screen.
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
                com.stationly.mobile.service.AuthLog.passwordResetConfirmed()
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
