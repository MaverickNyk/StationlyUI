package com.stationly.core.platform

import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState

/**
 * Platform abstraction for widget management
 */
interface WidgetManager {
    suspend fun updateWidget(state: WidgetState)
    suspend fun showWaitingState(station: String, line: String)
    suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState
}

/**
 * Platform abstraction for notification management
 */
interface NotificationManager {
    suspend fun subscribeToTopics(topics: List<String>)
    suspend fun unsubscribeFromTopics(topics: List<String>)
    suspend fun handleNotification(payload: Map<String, String>)
    suspend fun registerDevice(): String
}

/**
 * Platform abstraction for local storage
 */
interface StorageManager {
    suspend fun saveSelections(selections: List<UserSelection>)
    suspend fun loadSelections(): List<UserSelection>
    suspend fun saveLineStatus(lineId: String, statusJson: String)
    suspend fun loadLineStatus(lineId: String): String?
    suspend fun clearCache()
    suspend fun saveString(key: String, value: String)
    suspend fun loadString(key: String): String?
}

/**
 * Platform abstraction - provides platform-specific implementations
 */
expect object Platform {
    val widgetManager: WidgetManager
    val notificationManager: NotificationManager
    val storageManager: StorageManager
    val sqlStorage: com.stationly.core.repository.SqlStorage
    
    fun getPlatformName(): String
}