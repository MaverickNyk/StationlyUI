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
    actual fun getEnvironment(): AppEnvironment = AppEnvironment.PRODUCTION
    actual fun getBaseUrl(): String = com.stationly.core.config.AppConfig.apiBaseUrl
    actual suspend fun getAuthToken(): String? = null
    actual suspend fun signOutFromAuthExpiry() {} // Web target: no auth bridge yet.
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
        clearExceptDurable()
    }

    override suspend fun clearAll() {
        clearExceptDurable()
    }

    /**
     * Wipe the session, keeping the durable prefix.
     *
     * `localStorage.clear()` was wrong for both callers: it is one namespace for
     * the session store AND the durable one, so a logout took the per-account
     * settings with it and this platform silently failed the contract the other
     * two keep. Enumerated rather than clear-then-restore, because a restore
     * loses anything written by another tab in between.
     */
    private fun clearExceptDurable() {
        val doomed = buildList {
            for (i in 0 until localStorage.length) {
                val key = localStorage.key(i) ?: continue
                if (!key.startsWith(DURABLE_PREFIX)) add(key)
            }
        }
        doomed.forEach { localStorage.removeItem(it) }
    }
    
    override suspend fun saveString(key: String, value: String) {
        localStorage.setItem(key, value)
    }
    
    override suspend fun loadString(key: String): String? {
        return localStorage.getItem(key)
    }

    // Same localStorage, under a prefix the session wipe does not clear.
    override suspend fun saveDurable(key: String, value: String) {
        localStorage.setItem("$DURABLE_PREFIX$key", value)
    }

    override suspend fun loadDurable(key: String): String? = localStorage.getItem("$DURABLE_PREFIX$key")

    override suspend fun removeDurable(key: String) {
        localStorage.removeItem("$DURABLE_PREFIX$key")
    }

    private val DURABLE_PREFIX = "durable_"
}
