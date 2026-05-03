package com.stationly.core.platform

import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

// Import browser APIs with correct types for Kotlin/Wasm
@JsName("console")
external object console {
    fun log(message: String)
    fun error(message: String)
}

@JsName("localStorage")
external object localStorage {
    fun setItem(key: String, value: String)
    fun getItem(key: String): String?
    fun clear()
}

actual object Platform {
    actual val widgetManager: WidgetManager = WebWidgetManager()
    actual val notificationManager: NotificationManager = WebNotificationManager()
    actual val storageManager: StorageManager = WebStorageManager()
    
    actual fun getPlatformName(): String = "Web"
    actual fun getApiKey(): String = ""
    actual suspend fun getAuthToken(): String? = null
}

class WebWidgetManager : WidgetManager {
    override suspend fun updateWidget(state: WidgetState) {
        console.log("Widget update: ${state.stationName}")
    }

    override suspend fun showWaitingState(station: String, line: String) {}

    override suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState =
        WidgetState("", "", emptyList(), null, 0L)

    override suspend fun clearWidgetData() {}
}

class WebNotificationManager : NotificationManager {
    override suspend fun subscribeToTopics(topics: List<String>) {}
    override suspend fun unsubscribeFromTopics(topics: List<String>) {}
    override suspend fun handleNotification(payload: Map<String, String>) {}
    override suspend fun registerDevice(): String = ""
    override suspend fun clearAllTopics() {}
}

class WebStorageManager : StorageManager {
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun saveSelections(selections: List<UserSelection>) {
        try {
            val jsonStr = json.encodeToString(selections)
            localStorage.setItem("stationly_selections", jsonStr)
            console.log("Saved ${selections.size} selections to localStorage")
        } catch (e: Exception) {
            console.error("Error saving selections: ${e.message ?: "Unknown error"}")
        }
    }
    
    override suspend fun loadSelections(): List<UserSelection> {
        return try {
            val jsonStr = localStorage.getItem("stationly_selections")
            if (jsonStr != null) {
                val selections = json.decodeFromString<List<UserSelection>>(jsonStr)
                console.log("Loaded ${selections.size} selections from localStorage")
                selections
            } else {
                console.log("No selections found in localStorage")
                emptyList()
            }
        } catch (e: Exception) {
            console.error("Error loading selections: ${e.message ?: "Unknown error"}")
            emptyList()
        }
    }
    
    override suspend fun saveLineStatus(lineId: String, statusJson: String) {
        localStorage.setItem("line_status_$lineId", statusJson)
    }
    
    override suspend fun loadLineStatus(lineId: String): String? {
        return localStorage.getItem("line_status_$lineId")
    }
    
    override suspend fun clearCache() {
        localStorage.clear()
    }

    override suspend fun clearAll() {
        localStorage.clear()
    }
    
    override suspend fun saveString(key: String, value: String) {
        localStorage.setItem(key, value)
    }
    
    override suspend fun loadString(key: String): String? {
        return localStorage.getItem(key)
    }
}
