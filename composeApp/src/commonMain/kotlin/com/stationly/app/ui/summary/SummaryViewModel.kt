package com.stationly.app.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SduiAppComponent
import com.stationly.core.model.sdui.SduiWidgetPayload
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.FormatDeparturesUseCase
import com.stationly.core.usecase.StationLifecycleUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class SummaryViewModel(
    private val selectionRepository: SelectionRepository = SelectionRepository(
        Platform.storageManager,
        Platform.sqlStorage
    ),
    private val departureRepository: DepartureRepository = DepartureRepository(
        NetworkModule.tflApi,
        Platform.storageManager,
        Platform.sqlStorage,
        SyncPredictionsUseCase(Platform.sqlStorage)
    )
) : ViewModel() {

    private val sduiApi = NetworkModule.sduiApi

    private val formatDeparturesUseCase = FormatDeparturesUseCase()
    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = selectionRepository,
        departureRepository = departureRepository,
        notificationManager = Platform.notificationManager,
        widgetManager = Platform.widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager
    )

    private val _uiState = MutableStateFlow(SummaryUiState())
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()

    private val _selections = MutableStateFlow<List<UserSelection>>(emptyList())
    val selections: StateFlow<List<UserSelection>> = _selections.asStateFlow()

    private val _predictions = MutableStateFlow<Map<String, List<PredictionDisplay>>>(emptyMap())
    val predictions: StateFlow<Map<String, List<PredictionDisplay>>> = _predictions.asStateFlow()

    private val _lineStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val lineStatuses: StateFlow<Map<String, String>> = _lineStatuses.asStateFlow()

    private val _failedLineStatusKeys = MutableStateFlow<Set<String>>(emptySet())
    val failedLineStatusKeys: StateFlow<Set<String>> = _failedLineStatusKeys.asStateFlow()

    private val _stationUpdates = MutableStateFlow<Map<String, Long>>(emptyMap())
    val stationUpdates: StateFlow<Map<String, Long>> = _stationUpdates.asStateFlow()

    private val _sduiPayloads = MutableStateFlow<Map<String, SduiWidgetPayload?>>(emptyMap())
    val sduiPayloads: StateFlow<Map<String, SduiWidgetPayload?>> = _sduiPayloads.asStateFlow()

    private val _announcement = MutableStateFlow<SduiAppComponent.Announcement?>(null)
    val announcement: StateFlow<SduiAppComponent.Announcement?> = _announcement.asStateFlow()

    private val _homeConfig = MutableStateFlow<Map<String, String>>(emptyMap())
    val homeConfig: StateFlow<Map<String, String>> = _homeConfig.asStateFlow()

    private val _forceUpdate = MutableStateFlow(false)
    val forceUpdate: StateFlow<Boolean> = _forceUpdate.asStateFlow()

    private val _isDeletingBoard = MutableStateFlow<String?>(null)
    val isDeletingBoard: StateFlow<String?> = _isDeletingBoard.asStateFlow()

    // Always false on iOS — no Android widget infrastructure
    private val _showWidgetPromo = MutableStateFlow(false)
    val showWidgetPromo: StateFlow<Boolean> = _showWidgetPromo.asStateFlow()

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    init {
        viewModelScope.launch { fetchAnnouncement() }
        viewModelScope.launch { fetchHomeConfig() }
        viewModelScope.launch { loadUserInitial() }
        viewModelScope.launch {
            selectionRepository.initialize()
            selectionRepository.selections.value.firstOrNull()?.let { refreshDataIfStale(it) }
        }
        viewModelScope.launch {
            selectionRepository.selections.collect { newSelections ->
                _selections.value = newSelections
                newSelections.forEach { selection ->
                    loadPredictions(selection)
                    loadLineStatus(selection)
                }
                if (newSelections.isNotEmpty() && _uiState.value.activeStationId == null) {
                    val primary = newSelections.first()
                    _uiState.value = _uiState.value.copy(
                        activeStationId = primary.station,
                        activeLineId = primary.line
                    )
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                _selections.value.forEach { loadPredictions(it) }
            }
        }
    }

    private fun loadPredictions(selection: UserSelection) {
        viewModelScope.launch {
            try {
                val rawPreds = Platform.sqlStorage.getPredictions(selection.station, selection.line)
                val dbPreds = GlobalBoardProcessor.processPredictions(rawPreds)
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                val predsTimestamp = if (dbPreds.isNotEmpty()) now else 0L

                val currentMap = _predictions.value.toMutableMap()
                currentMap[selection.station] = dbPreds
                _predictions.value = currentMap

                loadSduiTemplateForSelection(selection, dbPreds)

                if (predsTimestamp > 0) {
                    val updates = _stationUpdates.value.toMutableMap()
                    updates[selection.station] = predsTimestamp
                    _stationUpdates.value = updates

                    if (predsTimestamp > _uiState.value.lastUpdated) {
                        _uiState.value = _uiState.value.copy(lastUpdated = predsTimestamp)
                    }

                    // Real line status (severity : reason) so the widget footer
                    // matches the in-app board / Android widget instead of always
                    // defaulting to "Good Service".
                    val dbStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
                    val widgetStatus = dbStatus?.let { s ->
                        val reason = StationlyFormatters.formatStatusReason(s.reason ?: "").trim()
                        if (reason.isNotEmpty()) "${s.statusSeverityDescription}: $reason"
                        else s.statusSeverityDescription
                    }
                    Platform.widgetManager.updateWidget(
                        com.stationly.core.model.WidgetState(
                            stationName = selection.stationName,
                            lineName = selection.line,
                            predictions = dbPreds,
                            status = widgetStatus,
                            lastUpdated = predsTimestamp / 1000,
                            direction = selection.direction
                        )
                    )
                }
            } catch (_: Exception) {
                // Silently ignore — stale data remains visible
            }
        }
    }

    private suspend fun loadSduiTemplateForSelection(
        selection: UserSelection,
        predictions: List<PredictionDisplay>
    ) {
        val sduiJson = Platform.storageManager.loadString("sdui_layout_${selection.station}")
        if (sduiJson != null) {
            try {
                val template = jsonFormat.decodeFromString<SduiWidgetPayload>(sduiJson)
                val status = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
                val boundPayload = GlobalBoardProcessor.bindSduiTemplate(
                    template,
                    predictions,
                    status?.statusSeverityDescription,
                    status?.reason
                )
                val current = _sduiPayloads.value.toMutableMap()
                current[selection.station] = boundPayload
                _sduiPayloads.value = current
            } catch (_: Exception) {
                val current = _sduiPayloads.value.toMutableMap()
                current.remove(selection.station)
                _sduiPayloads.value = current
            }
        } else {
            val current = _sduiPayloads.value.toMutableMap()
            current.remove(selection.station)
            _sduiPayloads.value = current
        }
    }

    private fun loadLineStatus(selection: UserSelection) {
        val key = "${selection.mode}_${selection.line}".lowercase()
        viewModelScope.launch {
            try {
                val dbStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
                if (dbStatus != null) {
                    var cleanReason = StationlyFormatters.formatStatusReason(dbStatus.reason ?: "").trim()
                    if (cleanReason.isNotEmpty()) cleanReason = ": $cleanReason"
                    val formattedStatus = "${dbStatus.statusSeverityDescription}$cleanReason"
                    _lineStatuses.value = _lineStatuses.value.toMutableMap().also { it[key] = formattedStatus }
                    _failedLineStatusKeys.value = _failedLineStatusKeys.value - key
                }
            } catch (_: Exception) {
                _failedLineStatusKeys.value = _failedLineStatusKeys.value + key
            }
        }
    }

    private suspend fun refreshDataIfStale(selection: UserSelection) {
        val existingPreds = Platform.sqlStorage.getPredictions(selection.station, selection.line)
        val existingStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
        val lastUpdatedByStation = _stationUpdates.value[selection.station] ?: 0L
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val isStale = now - lastUpdatedByStation > 60_000

        if (existingPreds.isEmpty() || existingStatus == null || isStale) {
            departureRepository.fetchInitialData(selection)
            loadPredictions(selection)
            loadLineStatus(selection)
        }
    }

    fun refreshAll() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            try {
                _selections.value.forEach { selection ->
                    departureRepository.fetchInitialData(selection)
                    loadPredictions(selection)
                    loadLineStatus(selection)
                }
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                _uiState.value = _uiState.value.copy(isRefreshing = false, lastUpdated = now)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    isBackendOffline = true
                )
                scheduleAutoRetry()
            }
        }
    }

    fun deleteSelection(selection: UserSelection) {
        viewModelScope.launch {
            _isDeletingBoard.value = selection.station
            try {
                stationLifecycleUseCase.discardStation(selection, clearSelectionInRepo = true)

                val currentMap = _predictions.value.toMutableMap()
                currentMap.remove(selection.station)
                _predictions.value = currentMap

                val currentLineStatuses = _lineStatuses.value.toMutableMap()
                currentLineStatuses.remove("${selection.mode}_${selection.line}".lowercase())
                _lineStatuses.value = currentLineStatuses

                val uid = Platform.storageManager.loadString("firebase_user_uid")
                if (uid != null) {
                    try {
                        val remainingSelections = _selections.value.filter { it.station != selection.station }
                        val mapped = remainingSelections.map {
                            com.stationly.core.model.sdui.SubscribedStation(
                                id = it.station, name = it.stationName,
                                line = it.line, mode = it.mode, direction = it.direction
                            )
                        }
                        sduiApi.syncStations(uid, mapped)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                _isDeletingBoard.value = null
            }
        }
    }

    private suspend fun loadUserInitial() {
        val name = Platform.storageManager.loadString("firebase_user_display_name")
            ?: Platform.storageManager.loadString("firebase_user_email")
        val initial = name?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "?"
        // Firebase profile photo (Google sign-in) — written by AuthBridge.swift
        // to NSUserDefaults under "firebase_user_photo_url". Rendered as the
        // top-bar avatar; falls back to the monogram when absent.
        val photoUrl = Platform.storageManager.loadString("firebase_user_photo_url")
        _uiState.value = _uiState.value.copy(userInitial = initial, photoUrl = photoUrl)
    }

    private suspend fun fetchAnnouncement() {
        try {
            val screen = NetworkModule.sduiApi.getHomeAnnouncement()
            val component = screen.components.filterIsInstance<SduiAppComponent.Announcement>().firstOrNull()
            if (component != null) {
                val dismissKey = component.dismissKey ?: component.id
                val dismissed = Platform.storageManager.loadString("dismissed_announcement_$dismissKey") == "true"
                if (!dismissed) _announcement.value = component
            }
        } catch (_: Exception) {}
    }

    private suspend fun fetchHomeConfig() {
        try {
            val config = NetworkModule.sduiApi.getHomeConfig().strings
            _homeConfig.value = config
        } catch (_: Exception) {}
    }

    fun dismissAnnouncement() {
        val current = _announcement.value ?: return
        val key = current.dismissKey ?: current.id
        viewModelScope.launch {
            Platform.storageManager.saveString("dismissed_announcement_$key", "true")
            _announcement.value = null
        }
    }

    fun reloadSelectionsFromDb() {
        viewModelScope.launch {
            selectionRepository.initialize()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(isBackendOffline = false)
    }

    fun retryLoad() {
        _uiState.value = _uiState.value.copy(isBackendOffline = false)
        refreshAll()
    }

    fun dismissWidgetPromo() {
        _showWidgetPromo.value = false
    }

    fun hideWidgetPromoForSession() {
        _showWidgetPromo.value = false
    }

    private fun scheduleAutoRetry() {
        viewModelScope.launch {
            delay(30_000)
            if (_uiState.value.isBackendOffline) retryLoad()
        }
    }
}
