package com.stationly.core.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Android WidgetManager implementation
class AndroidWidgetManager(
    private val context: Context
) : WidgetManager {
    
    override suspend fun updateWidget(state: WidgetState) {
        // Trigger widget update through WorkManager
        // This will call the DepartureWidgetProvider
        val workManager = WorkManager.getInstance(context)
        // WorkManager implementation would go here
    }
    
    override suspend fun showWaitingState(station: String, line: String) {
        // Update widget with waiting state
        // Implementation would update RemoteViews
    }
    
    override suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState {
        // This would use the FormatDeparturesUseCase
        // For now, return a placeholder
        return WidgetState(
            stationName = "Loading...",
            lineName = "",
            predictions = emptyList(),
            status = null,
            lastUpdated = System.currentTimeMillis() / 1000
        )
    }
}

// Android NotificationManager implementation
class AndroidNotificationManager(
    private val context: Context
) : NotificationManager {
    
    override suspend fun subscribeToTopics(topics: List<String>) {
        topics.forEach { topic ->
            try {
                FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
            } catch (e: Exception) {
                // Log error but don't crash
            }
        }
    }
    
    override suspend fun unsubscribeFromTopics(topics: List<String>) {
        topics.forEach { topic ->
            try {
                FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).await()
            } catch (e: Exception) {
                // Log error but don't crash
            }
        }
    }
    
    override suspend fun handleNotification(payload: Map<String, String>) {
        // This would be called from FcmMessagingService
        // Would trigger ProcessFcmPayloadUseCase
    }
    
    override suspend fun registerDevice(): String {
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            ""
        }
    }
}

// Android StorageManager implementation
class AndroidStorageManager(
    private val context: Context
) : StorageManager {
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
    }
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun saveSelections(selections: List<UserSelection>) {
        val jsonStr = json.encodeToString(ListSerializer(UserSelection.serializer()), selections)
        prefs.edit().putString("selections", jsonStr).apply()
    }
    
    override suspend fun loadSelections(): List<UserSelection> {
        val jsonStr = prefs.getString("selections", null) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(UserSelection.serializer()), jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    override suspend fun saveLineStatus(lineId: String, statusJson: String) {
        prefs.edit().putString("line_status_$lineId", statusJson).apply()
    }
    
    override suspend fun loadLineStatus(lineId: String): String? {
        return prefs.getString("line_status_$lineId", null)
    }
    
    override suspend fun clearCache() {
        prefs.edit().clear().apply()
    }
    
    override suspend fun saveString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    
    override suspend fun loadString(key: String): String? {
        return prefs.getString(key, null)
    }
}

// Android Platform implementation
actual object Platform {
    private lateinit var appContext: Context
    
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
    
    actual val widgetManager: WidgetManager by lazy { AndroidWidgetManager(appContext) }
    actual val notificationManager: NotificationManager by lazy { AndroidNotificationManager(appContext) }
    actual val storageManager: StorageManager by lazy { AndroidStorageManager(appContext) }
    
    actual fun getPlatformName(): String = "Android"
}