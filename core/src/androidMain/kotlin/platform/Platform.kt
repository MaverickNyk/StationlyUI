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
        val intent = android.content.Intent("com.stationly.mobile.ACTION_UPDATE_WIDGET")
        intent.setComponent(android.content.ComponentName(context.packageName, "com.stationly.mobile.widget.DepartureWidgetProvider"))
        intent.putExtra("ACTION_TYPE", "UPDATE_WIDGET")
        context.sendBroadcast(intent)
    }

    override suspend fun showWaitingState(station: String, line: String) {
        val intent = android.content.Intent("com.stationly.mobile.ACTION_UPDATE_WIDGET")
        intent.setComponent(android.content.ComponentName(context.packageName, "com.stationly.mobile.widget.DepartureWidgetProvider"))
        intent.putExtra("ACTION_TYPE", "SHOW_WAITING_STATE")
        intent.putExtra("stationName", station)
        intent.putExtra("lineName", line)
        context.sendBroadcast(intent)
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
        val fcm = FirebaseMessaging.getInstance()
        try {
            val token = fcm.token.await()
            android.util.Log.d("FCM_SUBS", ">>> Current Device Token: $token")
        } catch (e: Exception) {
            android.util.Log.e("FCM_SUBS", "!!! Failed to get token", e)
        }

        topics.forEach { topic ->
            try {
                android.util.Log.d("FCM_SUBS", ">>> Subscribing to topic: $topic")
                fcm.subscribeToTopic(topic).await()
                android.util.Log.d("FCM_SUBS", ">>> Successfully subscribed to: $topic")
            } catch (e: Exception) {
                android.util.Log.e("FCM_SUBS", "!!! Failed to subscribe to $topic", e)
            }
        }
    }
    
    override suspend fun unsubscribeFromTopics(topics: List<String>) {
        val fcm = FirebaseMessaging.getInstance()
        topics.forEach { topic ->
            try {
                android.util.Log.d("FCM_SUBS", ">>> Unsubscribing from topic: $topic")
                fcm.unsubscribeFromTopic(topic).await()
                android.util.Log.d("FCM_SUBS", ">>> Successfully unsubscribed from: $topic")
            } catch (e: Exception) {
                android.util.Log.e("FCM_SUBS", "!!! Failed to unsubscribe from $topic", e)
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
    
    actual val sqlStorage: com.stationly.core.repository.SqlStorage by lazy {
        val driverFactory = DriverFactory(appContext)
        val database = createDatabase(driverFactory)
        com.stationly.core.repository.SqlStorage(database)
    }
    
    actual fun getPlatformName(): String = "Android"
}