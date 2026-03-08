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
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.platform.Platform
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.usecase.StationLifecycleUseCase

data class LoginUiState(
    val layout: SduiAppScreen? = null,
    val isLoading: Boolean = true,
    val isAuthenticating: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val inputs: Map<String, String> = emptyMap()
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val apiService = SduiApiServiceFactory.create()
    val authManager = FirebaseAuthManager(application)
    private val userSyncRepository = UserSyncRepository(apiService, Platform.sqlStorage, Platform.storageManager)
    private val firebaseAuth = com.google.firebase.auth.FirebaseAuth.getInstance()
    
    private val departureRepository = DepartureRepository(
        com.stationly.core.service.TflApiServiceFactory.create(),
        Platform.storageManager,
        Platform.sqlStorage
    )
    
    private val selectionRepository = SelectionRepository(Platform.storageManager, Platform.sqlStorage)
    private val stationLifecycleUseCase = StationLifecycleUseCase(
        selectionRepository = selectionRepository,
        departureRepository = departureRepository,
        notificationManager = Platform.notificationManager,
        widgetManager = Platform.widgetManager,
        sqlStorage = Platform.sqlStorage,
        storageManager = Platform.storageManager
    )
    
    init {
        // Initial layout set by screenType from NavHost via setScreenType
    }

    fun setScreenType(type: String) {
        if (_uiState.value.layout?.id?.startsWith(type) == true) return
        loadLayout(type)
    }

    private fun loadLayout(type: String) {
        Log.d("LoginViewModel", "Fetching SDUI layout for: $type")
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isLoading = true, 
                    error = null, 
                    info = null, 
                    inputs = emptyMap()
                )
                val layout = when (type) {
                    "login" -> apiService.getLoginLayout()
                    "register" -> apiService.getRegisterLayout()
                    "forgot-password" -> apiService.getForgotPasswordLayout()
                    else -> apiService.getLoginLayout()
                }
                Log.d("LoginViewModel", "Successfully loaded $type layout")
                _uiState.value = _uiState.value.copy(layout = layout, isLoading = false)
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Failed to load $type screen.", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Check your connection and try again."
                )
            }
        }
    }

    fun onInputChanged(id: String, value: String) {
        val currentInputs = _uiState.value.inputs.toMutableMap()
        currentInputs[id] = value
        _uiState.value = _uiState.value.copy(inputs = currentInputs)
    }

    fun handleAction(
        action: String, 
        onAuthSuccess: () -> Unit = {},
        onNavigateToRegister: () -> Unit = {},
        onNavigateToLogin: () -> Unit = {},
        onNavigateToForgotPassword: () -> Unit = {},
        onGoogleSignInRequested: () -> Unit = {}
    ) {
        Log.d("LoginViewModel", "Handling UI Action: $action")
        when (action) {
            "LOGIN_ACTION" -> performEmailAuth(onAuthSuccess)
            "REGISTER_ACTION" -> performRegister(onAuthSuccess)
            "RESET_PASSWORD_ACTION" -> performReset()
            "NAVIGATE_TO_REGISTER" -> onNavigateToRegister()
            "NAVIGATE_TO_LOGIN" -> onNavigateToLogin()
            "NAVIGATE_TO_FORGOT_PASSWORD" -> onNavigateToForgotPassword()
            "GOOGLE_LOGIN_ACTION" -> onGoogleSignInRequested()
        }
    }

    fun signInWithGoogle(task: com.google.android.gms.tasks.Task<com.google.android.gms.auth.api.signin.GoogleSignInAccount>, onAuthSuccess: () -> Unit) {
        Log.d("LoginViewModel", "Launching Google Auth Process")
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            when (val result = authManager.signInWithGoogle(task)) {
                is com.stationly.mobile.service.AuthResult.Success -> {
                    Log.d("LoginViewModel", "Google Sign-In Success: ${result.user.email}")
                    syncUserAndSyncData(result.user, onAuthSuccess)
                }
                is com.stationly.mobile.service.AuthResult.Error -> {
                    Log.e("LoginViewModel", "Google Sign-In Failed: ${result.message}")
                    val displayError = if (result.message.contains("Code: 10")) {
                        "Google App Misconfigured (Code 10). Please ensure SHA-1 is correct in Firebase."
                    } else result.message
                    _uiState.value = _uiState.value.copy(isAuthenticating = false, error = displayError)
                }
            }
        }
    }

    private fun performEmailAuth(onAuthSuccess: () -> Unit) {
        val inputs = _uiState.value.inputs
        val email = inputs["email"]?.trim() ?: ""
        val password = inputs["password"] ?: ""
        
        Log.d("LoginViewModel", "Email Auth Attempt for $email")

        if (email.isEmpty() || password.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Please enter your stationly credentials.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            when (val result = authManager.signInWithEmail(email, password)) {
                is com.stationly.mobile.service.AuthResult.Success -> {
                    Log.d("LoginViewModel", "Email Login Successful")
                    syncUserAndSyncData(result.user, onAuthSuccess)
                }
                is com.stationly.mobile.service.AuthResult.Error -> {
                    Log.e("LoginViewModel", "Email Login Failed: ${result.message}")
                    _uiState.value = _uiState.value.copy(isAuthenticating = false, error = result.message)
                }
            }
        }
    }

    private fun performRegister(onAuthSuccess: () -> Unit) {
        val inputs = _uiState.value.inputs
        val email = inputs["email"]?.trim() ?: ""
        val password = inputs["password"] ?: ""
        val displayName = inputs["displayName"]?.trim() ?: ""

        Log.d("LoginViewModel", "New Account Request: $email")

        if (email.isEmpty() || password.length < 6) {
            _uiState.value = _uiState.value.copy(error = "Registration Info: Use a valid email and 6+ character password.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            when (val result = authManager.createEmailAccount(email, password)) {
                is com.stationly.mobile.service.AuthResult.Success -> {
                    Log.d("LoginViewModel", "Account Created Successfully")
                    if (displayName.isNotEmpty()) {
                        try {
                            val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                .setDisplayName(displayName)
                                .build()
                            result.user.updateProfile(profileUpdates).await()
                        } catch (e: Exception) {
                            Log.e("LoginViewModel", "Failed to update profile", e)
                        }
                    }
                    syncUserAndSyncData(result.user, onAuthSuccess)
                }
                is com.stationly.mobile.service.AuthResult.Error -> {
                    Log.e("LoginViewModel", "Registration Failed: ${result.message}")
                    _uiState.value = _uiState.value.copy(isAuthenticating = false, error = result.message)
                }
            }
        }
    }

    private fun performReset() {
        val email = _uiState.value.inputs["email"]?.trim() ?: ""
        Log.d("LoginViewModel", "Requesting password reset for $email")
        
        if (email.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Enter your email to receive a recovery link.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
            try {
                authManager.auth.sendPasswordResetEmail(email).await()
                Log.d("LoginViewModel", "Reset link sent successfully")
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false, 
                    info = "Great! A recovery link has been sent to $email", 
                    error = null
                )
            } catch (e: Exception) {
                Log.e("LoginViewModel", "Reset flow failed", e)
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false, 
                    error = "Failed to send link: ${e.localizedMessage}"
                )
            }
        }
    }
    private fun syncUserAndSyncData(user: com.google.firebase.auth.FirebaseUser, onAuthSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isAuthenticating = true, info = "Syncing your boards...")
                
                val provider = user.providerData.getOrNull(1)?.providerId ?: "email"
                val stations = userSyncRepository.syncUserAndGetSavedStations(
                    uid = user.uid,
                    email = user.email ?: "",
                    displayName = user.displayName,
                    photoURL = user.photoUrl?.toString(),
                    provider = provider
                )
                
                // 1. Process each station using the central lifecycle logic
                stations.forEachIndexed { index, station ->
                    Log.d("LoginViewModel", ">>> [LOGIN_SYNC] Refactoring Setup for: ${station.name}")
                    val selection = com.stationly.core.model.UserSelection(
                        mode = station.mode,
                        line = station.line,
                        station = station.id,
                        stationName = station.name,
                        direction = station.direction,
                        destinations = emptyList(),
                        destinationIds = emptyList()
                    )
                    
                    // Call the NEW centralized setup flow
                    stationLifecycleUseCase.setupStation(selection, isFirstTime = true)
                }
                
                _uiState.value = _uiState.value.copy(isAuthenticating = false, info = "Welcome back!")
                
                Log.d("LoginViewModel", ">>> [LOGIN_SYNC] ALL STEPS COMPLETE. Navigating to Summary.")
                // Navigate only AFTER everything is finished to avoid coroutine cancellation
                onAuthSuccess()
            } catch (e: Exception) {
                Log.e("LoginViewModel", "!!! [LOGIN_SYNC] FATAL SYNC ERROR", e)
                authManager.logout() // Revert login locally if backend sync fails
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "Failed to sync your profile: ${e.message}"
                )
            }
        }
    }
}
