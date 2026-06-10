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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
        loadStations()
        loadAboutLayout()
        loadHomeConfig()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            val provider    = storageManager.loadString("signin_provider") ?: "Stationly"
            val memberSince = storageManager.loadString("member_since") ?: ""
            _uiState.value = _uiState.value.copy(
                email = authProvider.currentUserEmail() ?: "",
                displayName = authProvider.currentUserDisplayName()
                    ?: authProvider.currentUserEmail()?.substringBefore("@") ?: "User",
                photoUrl = authProvider.currentUserPhotoUrl(),
                signInProvider = provider,
                memberSince = memberSince
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
                // Firebase sign-out first (iOS: triggers Swift AuthBridge.logout(); Android: direct)
                authProvider.signOut()
                // Unsubscribe FCM, clear widget, clear all storage
                stationLifecycleUseCase.cleanupAll()
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
                sduiApi.deleteAccount(uid)
                authProvider.signOut()
                stationLifecycleUseCase.cleanupAll()
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
