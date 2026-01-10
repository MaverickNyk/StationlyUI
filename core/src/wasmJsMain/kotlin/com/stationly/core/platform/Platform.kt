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
}

class WebWidgetManager : WidgetManager {
    override suspend fun updateWidget(state: WidgetState) {
        // No-op on web
        console.log("Widget update requested: ${state.stationName}")
    }
    
    override suspend fun showWaitingState(station: String, line: String) {
        // No-op
        console.log("Waiting state for $station - $line")
    }
    
    override suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState {
        return WidgetState(
            stationName = "",
            lineName = "",
            predictions = emptyList(),
            status = null,
            lastUpdated = 0L
        )
    }
}

class WebNotificationManager : NotificationManager {
    override suspend fun subscribeToTopics(topics: List<String>) {
        console.log("Subscribing to topics: ${topics.joinToString()}")
    }
    
    override suspend fun unsubscribeFromTopics(topics: List<String>) {
        console.log("Unsubscribing from topics: ${topics.joinToString()}")
    }
    
    override suspend fun handleNotification(payload: Map<String, String>) {
        console.log("Notification received")
    }
    
    override suspend fun registerDevice(): String {
        return "web-device-id-placeholder"
    }
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
    
    override suspend fun saveString(key: String, value: String) {
        localStorage.setItem(key, value)
    }
    
    override suspend fun loadString(key: String): String? {
        return localStorage.getItem(key)
    }
}
