package com.stationly.core.platform

import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import kotlinx.browser.window
import kotlinx.browser.localStorage

actual object Platform {
    actual val widgetManager: WidgetManager = WebWidgetManager()
    actual val notificationManager: NotificationManager = WebNotificationManager()
    actual val storageManager: StorageManager = WebStorageManager()
    
    actual fun getPlatformName(): String = "Web"
}

class WebWidgetManager : WidgetManager {
    override suspend fun updateWidget(state: WidgetState) {
        // No-op on web
        console.log("Widget update requested: $state")
    }
    
    override suspend fun showWaitingState(station: String, line: String) {
        // No-op
    }
    
    override suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState {
        return WidgetState.Empty
    }
}

class WebNotificationManager : NotificationManager {
    override suspend fun subscribeToTopics(topics: List<String>) {
        console.log("Subscribing to topics: $topics")
    }
    
    override suspend fun unsubscribeFromTopics(topics: List<String>) {
        console.log("Unsubscribing from topics: $topics")
    }
    
    override suspend fun handleNotification(payload: Map<String, String>) {
        console.log("Notification received: $payload")
    }
    
    override suspend fun registerDevice(): String {
        return "web-device-id-placeholder"
    }
}

class WebStorageManager : StorageManager {
    override suspend fun saveSelections(selections: List<UserSelection>) {
        // In a real app, serialize to JSON
        console.log("Saving selections")
    }
    
    override suspend fun loadSelections(): List<UserSelection> {
        return emptyList()
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
