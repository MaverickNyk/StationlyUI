package com.stationly.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.platform.DeviceIdentity
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SubscribedStation
import com.stationly.core.model.sdui.SyncProfileRequest
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Identity-key poll: re-read every STEP ms up to TIMEOUT ms, so the profile
// card resolves the instant Swift AuthBridge writes the keys (a keychain
// restore usually lands within a few hundred ms) instead of a fixed 1.5 s wait.
private const val IDENTITY_POLL_STEP_MS = 120L
private const val IDENTITY_POLL_TIMEOUT_MS = 3000L

class ProfileViewModel(
    private val authProvider: PlatformAuthProvider
) : ViewModel() {

    private val sduiApi = NetworkModule.sduiApi
    private val storageManager = Platform.storageManager
    private val selectionRepository = SelectionRepository(Platform.storageManager, Platform.sqlStorage)
    private val departureRepository = DepartureRepository(
        NetworkModule.tflApi,
        Platform.storageManager,
        Platform.sqlStorage,
        SyncPredictionsUseCase(Platform.sqlStorage)
    )
    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = selectionRepository,
        departureRepository = departureRepository,
        notificationManager = Platform.notificationManager,
        widgetManager = Platform.widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager
    )

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /// Seed the card synchronously — the auth provider's reads are plain
    /// non-suspend getters, so the FIRST composition already has
    /// name/email/photo. Seeding from inside the loadProfile coroutine left
    /// the default-empty state (rendered as "User" + blank monogram via the
    /// screen's ifBlank fallback) visible for as long as the main dispatcher
    /// was contended — the intermittent ~1-in-10 "User" card.
    private fun initialState(): ProfileUiState {
        val name = authProvider.currentUserDisplayName()
            ?: authProvider.currentUserEmail()?.substringBefore("@")
        return ProfileUiState(
            email = authProvider.currentUserEmail() ?: "",
            displayName = name ?: "",
            photoUrl = authProvider.currentUserPhotoUrl(),
            // Logged in but the keychain-restored session beat AuthBridge's
            // identity-key write → render a name skeleton, never "User", until
            // loadProfile()'s poll resolves it.
            isIdentityLoading = name == null && authProvider.isLoggedIn(),
        )
    }

    init {
        loadProfile()
        loadStations()
        loadAboutLayout()
        loadHomeConfig()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            // Identity keys (name/email/photo) are written by Swift AuthBridge's
            // auth-state listener at launch; a keychain-restored session can
            // reach this screen before that write lands, which rendered the card
            // as "User" / "Stationly" / "Since Recently". Instead of a blunt
            // fixed wait, POLL briefly and resolve the instant the keys appear
            // (usually a few hundred ms) — no overshoot, and isIdentityLoading
            // keeps a skeleton on screen rather than "User" until then.
            var name = authProvider.currentUserDisplayName()
                ?: authProvider.currentUserEmail()?.substringBefore("@")
            if (name == null && authProvider.isLoggedIn()) {
                var waited = 0L
                while (name == null && waited < IDENTITY_POLL_TIMEOUT_MS) {
                    delay(IDENTITY_POLL_STEP_MS)
                    waited += IDENTITY_POLL_STEP_MS
                    name = authProvider.currentUserDisplayName()
                        ?: authProvider.currentUserEmail()?.substringBefore("@")
                }
            }
            val provider    = storageManager.loadString("signin_provider") ?: "Stationly"
            val memberSince = storageManager.loadString("member_since") ?: ""
            _uiState.value = _uiState.value.copy(
                email = authProvider.currentUserEmail() ?: "",
                displayName = name ?: "User",
                photoUrl = authProvider.currentUserPhotoUrl(),
                signInProvider = provider,
                memberSince = memberSince,
                isIdentityLoading = false,
            )
        }
    }

    private fun loadStations() {
        val local = Platform.sqlStorage.getAllSelections()
        _uiState.value = _uiState.value.copy(
            stations = local.map { sel ->
                SubscribedStation(
                    id = sel.station,
                    name = sel.stationName,
                    line = sel.line,
                    mode = sel.mode,
                    direction = sel.direction
                )
            },
            isLoading = false
        )
    }

    private fun loadAboutLayout() {
        viewModelScope.launch {
            try {
                val screen = sduiApi.getAboutLayout()
                _uiState.value = _uiState.value.copy(aboutComponents = screen.components)
            } catch (_: Exception) {}
        }
    }

    private fun loadHomeConfig() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    homeConfig = sduiApi.getHomeConfig().strings
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Update the display name on Firebase Auth (via the platform provider —
     * Swift AuthBridge on iOS) and mirror it to the backend user doc so FCM
     * payloads and emails see the new name. Mirrors Android ProfileViewModel.
     */
    fun updateDisplayName(rawName: String, onComplete: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val trimmed = rawName.trim()
            if (trimmed.length < 2) {
                onComplete(Result.failure(IllegalArgumentException("Name must be at least 2 characters.")))
                return@launch
            }
            if (trimmed.length > 60) {
                onComplete(Result.failure(IllegalArgumentException("Name is too long.")))
                return@launch
            }
            authProvider.updateDisplayName(trimmed).fold(
                onSuccess = {
                    // Best-effort backend mirror — the Firebase name is already
                    // saved, so a sync failure shouldn't fail the edit.
                    try {
                        val uid = authProvider.currentUserUid()
                        if (uid != null) {
                            sduiApi.syncProfile(
                                SyncProfileRequest(
                                    uid            = uid,
                                    email          = authProvider.currentUserEmail() ?: "",
                                    displayName    = trimmed,
                                    photoURL       = authProvider.currentUserPhotoUrl(),
                                    signInProvider = when (storageManager.loadString("signin_provider")) {
                                        "Google" -> "google.com"
                                        "Apple"  -> "apple.com"
                                        else     -> "email"
                                    },
                                    deviceId       = DeviceIdentity.deviceId(),
                                    deviceInfo     = DeviceIdentity.deviceInfo()
                                )
                            )
                        }
                    } catch (_: Exception) {}
                    _uiState.value = _uiState.value.copy(displayName = trimmed)
                    onComplete(Result.success(trimmed))
                },
                onFailure = { e -> onComplete(Result.failure(e)) }
            )
        }
    }

    fun deleteStation(station: SubscribedStation) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(deletingStationId = station.id)
            val selection = UserSelection(
                mode = station.mode, line = station.line,
                station = station.id, stationName = station.name,
                direction = station.direction,
                destinations = emptyList(), destinationIds = emptyList()
            )
            stationLifecycleUseCase.discardStation(selection, clearSelectionInRepo = true)

            // Refresh SQLite list
            loadStations()

            // Best-effort backend sync
            syncStationsToBackend()

            _uiState.value = _uiState.value.copy(deletingStationId = null)
        }
    }

    fun signOut(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSigningOut = true, error = null)
            try {
                // ── Backend teardown FIRST — both calls are auth-gated and the
                // bearer token is read from storage that signOut() clears, so
                // running them afterwards would 401 silently (the exact bug
                // Android's FirebaseAuthManager documents). Both are capped so a
                // slow backend can't strand the user "half signed out".
                val uid = storageManager.loadString("firebase_user_uid")
                if (uid != null) {
                    // a) /user/logout → backend endSession: decrements this
                    //    station's subscription count and flips loggedIn=false.
                    //    Without it the Syncer keeps polling TfL for a user who
                    //    has gone, and the station stays in the subscribed set.
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(4000) {
                            sduiApi.logOut(uid, com.stationly.app.platform.DeviceIdentity.deviceId())
                        }
                    } catch (_: Exception) {}

                    // b) Unregister this device's FCM token so a push aimed at
                    //    the now-signed-out user can't land here after someone
                    //    else signs in on this device.
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(3000) {
                            val token = com.stationly.core.platform.Platform.notificationManager.registerDevice()
                            if (token.isNotBlank()) sduiApi.unregisterFcmToken(token)
                        }
                    } catch (_: Exception) {}
                }

                // Firebase sign-out (iOS: triggers Swift AuthBridge.logout(); Android: direct)
                authProvider.signOut()
                // Unsubscribe FCM, clear widget, clear all storage
                stationLifecycleUseCase.cleanupAll()
                // Forget the cached (token, uid) pair so the NEXT user on this
                // device re-registers instead of being skipped as "unchanged".
                com.stationly.app.util.FcmTokenRegistrar.clearCache()
                _uiState.value = _uiState.value.copy(isSigningOut = false, signOutSuccess = true)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSigningOut = false,
                    error = "Sign out failed. Please try again."
                )
            }
        }
    }

    fun deleteAccount(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeletingAccount = true, error = null)
            try {
                val uid = storageManager.loadString("firebase_user_uid") ?: run {
                    _uiState.value = _uiState.value.copy(isDeletingAccount = false, error = "Not authenticated")
                    return@launch
                }
                // Drop this device's token BEFORE the account goes: deleting the
                // Firestore user doc does NOT delete its `fcm_tokens`
                // subcollection (Firestore keeps subcollections when a parent
                // doc is removed), so an un-unregistered token would linger and
                // could still resolve for uid-targeted sends.
                try {
                    kotlinx.coroutines.withTimeoutOrNull(3000) {
                        val token = com.stationly.core.platform.Platform.notificationManager.registerDevice()
                        if (token.isNotBlank()) sduiApi.unregisterFcmToken(token)
                    }
                } catch (_: Exception) {}

                // Backend handles the rest atomically: endSession decrements the
                // station subscriptions exactly once, then the doc + auth user go.
                sduiApi.deleteAccount(uid)
                authProvider.signOut()
                stationLifecycleUseCase.cleanupAll()
                com.stationly.app.util.FcmTokenRegistrar.clearCache()
                _uiState.value = _uiState.value.copy(isDeletingAccount = false, deleteAccountSuccess = true)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDeletingAccount = false,
                    error = "Could not delete account. Please try again."
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private suspend fun syncStationsToBackend() {
        try {
            val uid = storageManager.loadString("firebase_user_uid") ?: return
            sduiApi.syncStations(uid, _uiState.value.stations)
        } catch (_: Exception) {}
    }
}
