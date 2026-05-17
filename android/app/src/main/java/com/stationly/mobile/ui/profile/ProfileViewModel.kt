package com.stationly.mobile.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SubscribedStation
import com.stationly.core.platform.Platform
import com.stationly.core.platform.AndroidNotificationManager
import com.stationly.core.platform.AndroidStorageManager
import com.stationly.core.platform.AndroidWidgetManager
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.SduiApiServiceFactory
import com.stationly.core.service.TflApiServiceFactory
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val storageManager = AndroidStorageManager(context)
    private val notificationManager = AndroidNotificationManager(context)
    private val widgetManager = AndroidWidgetManager(context)
    private val apiService = TflApiServiceFactory.create()
    private val sduiService = SduiApiServiceFactory.create()
    private val syncPredictionsUseCase = SyncPredictionsUseCase(Platform.sqlStorage)
    private val selectionRepository = SelectionRepository(storageManager, Platform.sqlStorage)
    private val departureRepository = DepartureRepository(apiService, storageManager, Platform.sqlStorage, syncPredictionsUseCase)

    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = selectionRepository,
        departureRepository = departureRepository,
        notificationManager = notificationManager,
        widgetManager = widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = storageManager
    )

    private val _stations = MutableStateFlow<List<SubscribedStation>>(emptyList())
    val stations: StateFlow<List<SubscribedStation>> = _stations.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ID of the station currently being deleted, null when idle
    private val _deletingStationId = MutableStateFlow<String?>(null)
    val deletingStationId: StateFlow<String?> = _deletingStationId.asStateFlow()

    private val _aboutComponents = MutableStateFlow<List<SduiAppComponent>>(emptyList())
    val aboutComponents: StateFlow<List<SduiAppComponent>> = _aboutComponents.asStateFlow()

    private val _homeConfig = MutableStateFlow<Map<String, String>>(emptyMap())
    val homeConfig: StateFlow<Map<String, String>> = _homeConfig.asStateFlow()

    init {
        loadStations()
        loadAboutLayout()
        viewModelScope.launch { fetchHomeConfig() }
    }

    private fun loadAboutLayout() {
        viewModelScope.launch {
            try {
                val screen = sduiService.getAboutLayout()
                _aboutComponents.value = screen.components
            } catch (_: Exception) {
                // Keep empty — ProfileScreen falls back to hardcoded content
            }
        }
    }

    private suspend fun fetchHomeConfig() {
        try {
            _homeConfig.value = sduiService.getHomeConfig().strings
        } catch (_: Exception) {
            // Falls back to hardcoded strings already present in ProfileScreen
        }
    }

    fun loadStations() {
        val local = Platform.sqlStorage.getAllSelections()
        _stations.value = local.map { sel ->
            SubscribedStation(
                id = sel.station,
                name = sel.stationName,
                line = sel.line,
                mode = sel.mode,
                direction = sel.direction
            )
        }
        _isLoading.value = false
    }

    /**
     * Update the user's display name on Firebase Auth + sync to backend Firestore.
     * Returns Result.success(newName) on success, Result.failure(...) otherwise.
     * onComplete fires on the main dispatcher with the trimmed/normalised value
     * so the UI can update without re-querying Firebase.
     */
    fun updateDisplayName(
        rawName: String,
        onComplete: (Result<String>) -> Unit
    ) {
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
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                onComplete(Result.failure(IllegalStateException("Not signed in.")))
                return@launch
            }
            try {
                user.updateProfile(
                    com.google.firebase.auth.UserProfileChangeRequest.Builder()
                        .setDisplayName(trimmed)
                        .build()
                ).await()
                // Mirror to backend Firestore so the user doc, FCM payloads, and
                // future emails (welcome, reset, verify) see the new name.
                sduiService.syncProfile(
                    com.stationly.core.model.sdui.SyncProfileRequest(
                        uid           = user.uid,
                        email         = user.email ?: "",
                        displayName   = trimmed,
                        photoURL      = user.photoUrl?.toString(),
                        signInProvider = user.providerData.find { it.providerId != "firebase" }?.providerId
                            ?: "email"
                    )
                )
                onComplete(Result.success(trimmed))
            } catch (e: Exception) {
                onComplete(Result.failure(e))
            }
        }
    }

    fun deleteStation(station: SubscribedStation) {
        viewModelScope.launch {
            _deletingStationId.value = station.id
            // Full teardown — same as the main board delete
            val selection = UserSelection(
                mode = station.mode,
                line = station.line,
                station = station.id,
                stationName = station.name,
                direction = station.direction,
                destinations = emptyList(),
                destinationIds = emptyList()
            )
            stationLifecycleUseCase.discardStation(selection, clearSelectionInRepo = true)

            // Update widget from storage
            com.stationly.mobile.widget.DepartureWidgetProvider.updateFromStorage(context)

            // Refresh local list
            loadStations()

            // Sync to backend best-effort
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                try { sduiService.syncStations(uid, _stations.value) } catch (_: Exception) {}
            }
            _deletingStationId.value = null
        }
    }
}
