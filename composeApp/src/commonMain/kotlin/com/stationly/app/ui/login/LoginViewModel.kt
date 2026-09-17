package com.stationly.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.platform.DeviceIdentity
import com.stationly.app.sync.UserStateSync
import com.stationly.app.ui.util.HomeConfigCache
import com.stationly.core.config.SduiConfig
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.session.PendingOps
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

    /**
     * The words this flow uses, from the SDUI config map — see [AuthStrings].
     *
     * Read through a property rather than captured once, because it is replaced
     * when the config arrives and every message below is composed at the moment
     * it is shown. Starts at the compiled defaults, which is the correct answer
     * on a cold install and an offline launch alike.
     */
    private var strings: AuthStrings = AuthStrings.DEFAULT

    /**
     * The raw config map behind [strings], for the auth screens that render SDUI
     * copy of their own rather than error text.
     *
     * A StateFlow because a screen has to REDRAW when the config lands, and a
     * plain field cannot tell it to. `VerifyEmailScreen` used to fetch
     * `getHomeConfig()` itself, which meant the auth flow made the same request
     * twice within a second of itself — once here for the error wording and once
     * there for the screen's labels — and each half could be looking at a
     * different payload.
     */
    private val _configStrings = MutableStateFlow<Map<String, String>>(emptyMap())
    val configStrings: StateFlow<Map<String, String>> = _configStrings.asStateFlow()

    init {
        // Wording first, so anything raised below is already using the served
        // copy where there is any. Both are launched rather than awaited — the
        // notice and the config are independent, and a slow config fetch must
        // not hold up an explanation the user is owed immediately.
        loadStrings()

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
                // `strings` is read HERE, not captured above: this coroutine and
                // loadStrings' race, and whichever wins, the notice should show
                // the best wording available at the moment it is raised.
                _uiState.value = _uiState.value.copy(
                    accountRemovedNotice = strings.accountRemoved
                )
            }
        }
    }

    /**
     * Load the config map this screen's wording comes from.
     *
     * Cache first, network second, and the order matters here more than it does
     * on the home screen: this is the FIRST screen a new install shows, so
     * waiting for a fetch would mean every new user sees the compiled copy no
     * matter what the backend says — which is most of the reason for serving it.
     *
     * Failures are silent by design. A login screen that cannot reach the config
     * endpoint has strictly more useful things to tell the user than that, and
     * [AuthStrings] falls back to the compiled words on its own.
     */
    private fun loadStrings() {
        viewModelScope.launch {
            runCatching { HomeConfigCache.load() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    strings = AuthStrings(it)
                    _configStrings.value = it
                    // The auth screen is often the FIRST fetch on a cold install,
                    // so the board is painted from whatever it adopts here long
                    // before the home screen runs its own.
                    SduiConfig.refresh(it)
                }
            runCatching { sduiApi.getHomeConfig().strings }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    strings = AuthStrings(it)
                    _configStrings.value = it
                    SduiConfig.refresh(it)
                    // Seed the shared cache too. The login screen is often the
                    // first thing to fetch this on a new install, and leaving it
                    // unsaved would make the home screen fetch the identical
                    // payload again seconds later.
                    HomeConfigCache.save(it)
                }
        }
    }

    /**
     * The password rule, for the screens that validate inline before submitting.
     *
     * Exposed rather than duplicated: `LoginScreen` used to carry its own
     * `password.length < 6` and its own message, so a served length would have
     * moved the check in this file and left the form still refusing at six.
     */
    val passwordMinLength: Int get() = strings.passwordMinLength
    val passwordTooShortMessage: String get() = strings.passwordTooShort

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
                    error = strings.backendUnreachable
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
                        _uiState.value = _uiState.value.copy(isAuthenticating = false, error = strings.resetLinkInvalid)
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
            // ── The whole restore, declared as one ──
            //
            // Everything inside this block runs with SQLite either empty or
            // half-filled, and the widget write reads SQLite. Told nothing, it
            // reads an empty table as "the user deleted their last board" and
            // wipes the App Group, so every placed widget says "Open the app to
            // add a station" to a user who is in the middle of signing in.
            //
            // Only this side knows the emptiness is deliberate and temporary, so
            // only this side can say so. See [WidgetRestore] — the flag suppresses
            // exactly one branch, the empty-state wipe, and boards written as each
            // one is set up still publish normally.
            //
            // Scoped to the block rather than set and cleared by hand: the restore
            // has four failure points inside it and a `finally` is the only shape
            // that cannot leave the flag raised on the way out.
            com.stationly.core.platform.WidgetRestore.during {
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
            }

            // `FcmTokenRegistrar` was deleted here (P3).
            //
            // It read as live wiring and was dead on iOS from its second line:
            // `IosNotificationManager.registerDevice()` returns "" by design, so
            // `ensureRegistered` returned immediately, every time, and had never
            // registered anything. The guard that hid it (`if (token.isNotBlank())`)
            // read like a safety check.
            //
            // It was also not the registrar Android uses — `android/…/service/
            // FcmTokenRegistrar` is a DIFFERENT object with the same name. So the
            // old audit finding "iOS never unregisters its push tokens at logout"
            // really meant "iOS calls a function that was never capable of doing
            // anything". iOS push registration is `DevicePushCoordinator`.

            // Drain anything a previous session could not tell the server.
            //
            // THIS is the moment it can work, and the only one. `/user/logout`
            // is bearer-gated and rejects a uid that does not match the token,
            // so a queued logout for this account is replayable only while
            // signed in AS this account — at launch the user is usually still
            // signed out and every attempt would be a 401.
            //
            // The case this exists for: a forced auth-expiry ended the session
            // without ever telling the backend (there was no live token to tell
            // it with), the user signs in again here, and the queue drains.
            //
            // Best-effort and non-blocking on the login result: a failed replay
            // leaves the op queued for next time, and must never turn a
            // successful sign-in into a failed one.
            runCatching {
                PendingOps.replay(sduiApi, uid, DeviceIdentity.deviceId())
            }

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
                error = strings.syncRollback
            )
            return false
        }
    }

    fun onForgotPasswordSubmit(email: String, onSent: () -> Unit) {
        if (email.isBlank()) {
            _uiState.value = _uiState.value.copy(error = strings.resetEmailRequired)
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
                    error = strings.resetSendFailed
                )
            }
        }
    }

    fun confirmPasswordReset(onSuccess: () -> Unit) {
        val oobCode  = _uiState.value.resetOobCode ?: return
        val password = _uiState.value.inputs["newPassword"] ?: ""
        if (password.length < strings.passwordMinLength) {
            _uiState.value = _uiState.value.copy(error = strings.passwordTooShort)
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
                            strings.resetLinkExpired
                        else -> strings.resetFailed
                    }
                    _uiState.value = _uiState.value.copy(isAuthenticating = false, error = msg)
                }
            )
        }
    }

    /**
     * A raw provider error turned into something a person can act on.
     *
     * The MATCHING stays here and is not configurable, deliberately: which
     * Firebase code means "wrong password" is a fact about Firebase, and a
     * server able to re-point that at the "no such account" text could only make
     * the app lie about what happened. The server owns the wording — see
     * [AuthStrings] — and this owns which wording applies.
     *
     * The `else` branch still prefers the raw provider text over the generic
     * line when there is any: an unrecognised error we pass through is at least
     * true, while "Something went wrong" tells a user nothing they had not
     * already worked out.
     */
    private fun friendlyAuthError(raw: String): String = when {
        raw.containsAny("wrong-password", "invalid-credential", "INVALID_LOGIN_CREDENTIALS") ->
            strings.wrongPassword
        raw.containsAny("user-not-found", "no user record") -> strings.userNotFound
        raw.containsAny("email-already-in-use")             -> strings.emailInUse
        raw.containsAny("weak-password")                    -> strings.weakPassword
        raw.containsAny("too-many-requests")                -> strings.tooManyRequests
        raw.containsAny("network")                          -> strings.noNetwork
        else -> raw.ifBlank { strings.generic }
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
                    error = strings.sessionExpired
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
                    error = strings.stillUnverified
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
                            error = strings.resendFailed
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
