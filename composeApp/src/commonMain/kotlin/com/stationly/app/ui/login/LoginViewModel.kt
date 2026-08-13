package com.stationly.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.platform.DeviceIdentity
import com.stationly.app.sync.UserStateSync
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
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
    /** Named, so the login path can re-seed the shared cache — see below. */
    private val selectionRepository = SelectionRepository(Platform.storageManager, Platform.sqlStorage)
    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = selectionRepository,
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

    init {
        // Tell the user WHY they are looking at a sign-in screen.
        //
        // An account deleted on another device signs this one out on its own,
        // which without this is indistinguishable from being mysteriously logged
        // out — the single most alarming thing an app can do silently. The flag
        // is raised during the teardown and lives in DURABLE storage, because the
        // teardown wipes the app's own defaults.
        //
        // Read once and cleared immediately: it explains this sign-in, not every
        // future one.
        viewModelScope.launch {
            val flag = UserStateSync.ACCOUNT_REMOVED_FLAG
            val removed = runCatching { Platform.storageManager.loadDurable(flag) }.getOrNull()
            if (removed == "1") {
                runCatching { Platform.storageManager.removeDurable(flag) }
                _uiState.value = _uiState.value.copy(
                    accountRemovedNotice =
                        "Your account was deleted on another device, so you've been signed out here."
                )
            }
        }
    }

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
     * Post-auth restore — everything the user had, before the loader clears.
     *
     * The promise this keeps is that signing in on a phone you have never used
     * lands you in the app you left: your boards with their filters, your home
     * layout and order, your per-station settings. So all of it is AWAITED
     * here, in the order the home screen needs it:
     *
     *  1. `/user/sync/profile` registers this device's session (deviceId +
     *     deviceInfo) and creates/updates the user record. It also wipes local
     *     SQL and cache, which is safe only because we are about to repopulate
     *     from the cloud.
     *  2. Settings are applied FIRST. `UserSettings` gates the home
     *     screen on its `loaded` flag, and a board that arrives while settings
     *     are still at their defaults renders expanded and then collapses a
     *     frame later — which reads as a glitch, and throws away a full board
     *     build.
     *  3. Every board is set up from the v2 list, filters included. Not just
     *     the first: the one-board restore was Android's constraint, and
     *     restoring one of a user's five is indistinguishable from having lost
     *     the other four.
     *  4. This device's push token is registered under the just-signed-in uid
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
            userSyncRepository.syncUserAndGetSavedStations(
                uid         = uid,
                email       = authProvider.currentUserEmail() ?: "",
                displayName = authProvider.currentUserDisplayName(),
                photoURL    = authProvider.currentUserPhotoUrl(),
                provider    = provider,
                deviceId    = DeviceIdentity.deviceId(),
                deviceInfo  = DeviceIdentity.deviceInfo()
            )

            // Re-seed the in-memory selection cache from disk BEFORE restoring.
            //
            // `syncUserAndGetSavedStations` wipes SQLite and repopulates it
            // directly, without going through the repository — so the shared
            // cache still holds whatever the PREVIOUS session left in it. iOS
            // deliberately does not call `cleanupAll()` here (it would wipe the
            // identity keys Swift just wrote), and `cleanupAll` is what would
            // otherwise have cleared it.
            //
            // Without this, signing in as somebody else shows the previous user's
            // boards until something happens to rebuild the screen.
            runCatching { selectionRepository.initialize() }

            // Drop anything held for a previous session BEFORE restoring. The
            // settings stores are process-wide objects that survive a logout,
            // so signing in as somebody else without this leaves the previous
            // user's layout and order in memory — and the new user's first
            // settings change would then upload that arrangement to THEIR
            // account.
            UserStateSync.resetForNewSession()

            // A second read, and worth it. `syncProfile` returns the profile as
            // it was BEFORE this device's session was registered, and more to
            // the point it is the legacy-shaped response — the v2 board list and
            // the settings blob are what this login needs, and this is the call
            // that is guaranteed to carry them.
            val profile = UserStateSync.repository.fetch(uid)

            UserStateSync.restoreBoards(profile, stationLifecycleUseCase)

            // Best-effort — a failed token registration shouldn't block login;
            // it is retried on the next foreground.
            //
            // Goes through FcmTokenRegistrar rather than POSTing inline so the
            // "last registered (token, uid)" cache is seeded here. Registering
            // directly left that cache empty, and SummaryViewModel's init call
            // then re-POSTed the identical token seconds later on EVERY login.
            com.stationly.app.util.FcmTokenRegistrar.ensureRegistered(uid = uid)

            ActivityLog.record(ActivityEvents.AUTH_LOGGED_IN, "provider", provider)
            return true
        } catch (e: Exception) {
            ActivityLog.record(
                ActivityEvents.SYNC_FAILED,
                mapOf("stage" to "login", "reason" to (e.message ?: "unknown")),
            )
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
