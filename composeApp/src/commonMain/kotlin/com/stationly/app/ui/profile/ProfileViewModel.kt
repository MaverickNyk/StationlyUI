package com.stationly.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.app.ui.login.PlatformAuthProvider
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SubscribedStation
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
        _uiState.value = _uiState.value.copy(
            email = authProvider.currentUserEmail() ?: "",
            displayName = authProvider.currentUserDisplayName()
                ?: authProvider.currentUserEmail()?.substringBefore("@") ?: "User",
            photoUrl = authProvider.currentUserPhotoUrl(),
            signInProvider = storageManager.loadString("signin_provider") ?: "Stationly",
            memberSince = storageManager.loadString("member_since") ?: ""
        )
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
                // Unsubscribe all FCM topics before logout
                Platform.sqlStorage.getAllSelections().forEach { sel ->
                    Platform.notificationManager.unsubscribeFromTopic("Station_${sel.station}")
                    Platform.notificationManager.unsubscribeFromTopic("LineStatus_${sel.mode}_${sel.line}")
                }
                // Clear all local data
                stationLifecycleUseCase.cleanupAll()
                Platform.widgetManager.clearWidgetData()
                // Clear auth state via authProvider (bridge triggers Swift logout on iOS)
                storageManager.clearAll()
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
                val uid = storageManager.loadString("firebase_uid") ?: run {
                    _uiState.value = _uiState.value.copy(isDeletingAccount = false, error = "Not authenticated")
                    return@launch
                }

                // Delete backend data
                sduiApi.deleteAccount(uid)

                // Unsubscribe all FCM topics
                Platform.sqlStorage.getAllSelections().forEach { sel ->
                    Platform.notificationManager.unsubscribeFromTopic("Station_${sel.station}")
                    Platform.notificationManager.unsubscribeFromTopic("LineStatus_${sel.mode}_${sel.line}")
                }

                // Clear all local data
                stationLifecycleUseCase.cleanupAll()
                Platform.widgetManager.clearWidgetData()
                storageManager.clearAll()

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
            val uid = storageManager.loadString("firebase_uid") ?: return
            sduiApi.syncStations(uid, _uiState.value.stations)
        } catch (_: Exception) {}
    }
}
