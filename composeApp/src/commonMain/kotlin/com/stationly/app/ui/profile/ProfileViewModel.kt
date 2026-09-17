package com.stationly.app.ui.profile
import com.stationly.core.session.SessionStore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.platform.DeviceIdentity
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.session.PendingOps
import com.stationly.core.model.sdui.SyncProfileRequest
import com.stationly.app.ui.summary.BoardExpansion
import com.stationly.app.ui.summary.BoardFocus
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
        loadAboutLayout()
        loadHomeConfig()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            // Identity keys (name/email/photo) are written by Swift AuthBridge's
            // auth-state listener at launch; a keychain-restored session can
            // reach this screen before that write lands, which rendered the card
            // as a literal "User". Instead of a blunt
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
            _uiState.value = _uiState.value.copy(
                email = authProvider.currentUserEmail() ?: "",
                displayName = name ?: "User",
                photoUrl = authProvider.currentUserPhotoUrl(),
                isIdentityLoading = false,
            )
        }
    }

    // No station loading, no station deleting, no station sync here any more.
    // The profile listed every board a second time purely so it could offer a
    // delete button, which duplicated both the list and the teardown rules — and
    // a second implementation of "discard a board" is a second place for the
    // survivor logic to be got wrong. Stations are owned by the home screen and
    // its settings; see StationSettingsViewModel.

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

    fun signOut(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSigningOut = true, error = null)
            try {
                // ── Backend teardown FIRST — both calls are auth-gated and the
                // bearer token is read from storage that signOut() clears, so
                // running them afterwards would 401 silently (the exact bug
                // Android's FirebaseAuthManager documents). Both are capped so a
                // slow backend can't strand the user "half signed out".
                val uid = SessionStore.uid()
                if (uid != null) {
                    // ── Flush pending state BEFORE anything tears down ──
                    //
                    // Settings and board changes are debounced by a couple of
                    // seconds, and signing out immediately after changing one is
                    // an ordinary thing to do. Without this the change is lost:
                    // `cleanupAll()` below wipes the local copy, so the next
                    // login restores the state as it was BEFORE the edit and the
                    // user's last action is silently undone.
                    //
                    // Capped like the other teardown calls — a slow backend must
                    // not strand the user "half signed out".
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(3000) {
                            com.stationly.app.sync.UserStateSync.flushNow()
                        }
                    } catch (_: Exception) {}

                    // Recorded before the sign-out, while the queue still stamps
                    // events with this uid — the whole reason the activity table
                    // survives `clearAllData`. Awaited for the same reason: a
                    // detached write would race the wipe.
                    runCatching {
                        ActivityLog.recordBlocking(ActivityEvents.AUTH_LOGGED_OUT)
                    }

                    // a) /user/logout → backend endSession: decrements this
                    //    station's subscription count and flips loggedIn=false.
                    //    Without it the Syncer keeps polling TfL for a user who
                    //    has gone, and the station stays in the subscribed set.
                    val deviceId = com.stationly.app.platform.DeviceIdentity.deviceId()
                    val toldTheServer = try {
                        kotlinx.coroutines.withTimeoutOrNull(4000) {
                            sduiApi.logOut(uid, deviceId)
                        } == true
                    } catch (_: Exception) { false }

                    // QUEUE IT IF IT DID NOT LAND.
                    //
                    // The call above is capped at four seconds and swallows its
                    // failure, which is right — the user asked to sign out and
                    // must not be held hostage to a network. But until now that
                    // meant an offline sign-out was simply never told to the
                    // server: the account kept its subscription hold, the device
                    // stayed in the push audience of an account nobody was signed
                    // into, and only the 90-day sweep eventually cleared it.
                    //
                    // Queued in DURABLE storage, because the teardown a few lines
                    // below wipes the ordinary defaults domain — a queue written
                    // there would be erased by the very sequence it exists to
                    // outlive.
                    //
                    // Replay is safe with no conditional: `/user/logout`
                    // addresses `users/{uid}/devices/{deviceId}`, a path that
                    // NAMES the account, so if somebody else has since signed in
                    // on this device their row is under a different parent and
                    // this cannot touch it.
                    if (!toldTheServer) {
                        runCatching { PendingOps.enqueueLogout(uid, deviceId) }
                    }

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
                // A pending Expanded/Collapsed request names a station id, and
                // ids are TfL naptans — shared across accounts. Left behind, it
                // would apply to whoever signs in next if they happen to track
                // the same station.
                BoardExpansion.clear()
                BoardFocus.clear()
                // The push-registration cache is cleared on the Swift side, in
                // `AuthBridge.logout()` — `DevicePushCoordinator.release()`. It
                // has to happen there rather than here: the signature it holds
                // is in-process Swift state, and `authProvider.signOut()` above
                // is what reaches it. Without that clear the next sign-in on
                // this device computes an identical body and skips the POST,
                // leaving the device with no push address.
                //
                // Same reasoning for the settings stores: they are process-wide
                // objects, so `cleanupAll()` wiping the DISK is invisible to a
                // repository that has already loaded. Without this the next user
                // to sign in on this device inherits this one's arrangement.
                com.stationly.app.sync.UserStateSync.resetForNewSession()
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
                val uid = SessionStore.uid() ?: run {
                    _uiState.value = _uiState.value.copy(isDeletingAccount = false, error = "Not authenticated")
                    return@launch
                }
                runCatching {
                    ActivityLog.recordBlocking(ActivityEvents.AUTH_ACCOUNT_DELETED)
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
                // A pending Expanded/Collapsed request names a station id, and
                // ids are TfL naptans — shared across accounts. Left behind, it
                // would apply to whoever signs in next if they happen to track
                // the same station.
                BoardExpansion.clear()
                BoardFocus.clear()
                // The teardown `signOut()` does, plus the disk. This was missing
                // entirely, and each half of it mattered: a debounced push queued
                // by the deleted account could still fire, the settings stores are
                // process-wide objects so the next user to sign in on this device
                // inherited the deleted user's arrangement, and their per-uid rows
                // in durable storage — which `cleanupAll()` deliberately cannot
                // reach — would have sat on the device forever with no account
                // left to claim them.
                com.stationly.app.sync.UserStateSync.forgetAccount(uid)
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
}
