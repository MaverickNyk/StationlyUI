package com.stationly.core.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.auth.FirebaseAuth
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
        return WidgetState(
            stationName = "Loading...",
            lineName = "",
            predictions = emptyList(),
            status = null,
            lastUpdated = System.currentTimeMillis() / 1000
        )
    }

    override suspend fun clearWidgetData() {
        val intent = android.content.Intent("com.stationly.mobile.ACTION_UPDATE_WIDGET")
        intent.setComponent(android.content.ComponentName(context.packageName, "com.stationly.mobile.widget.DepartureWidgetProvider"))
        intent.putExtra("ACTION_TYPE", "CLEAR_WIDGET_DATA")
        context.sendBroadcast(intent)
    }
}

// Android NotificationManager implementation
class AndroidNotificationManager(
    private val context: Context
) : NotificationManager {
    
    override suspend fun subscribeToTopics(topics: List<String>) {
        val fcm = FirebaseMessaging.getInstance()
        topics.forEach { topic ->
            try {
                fcm.subscribeToTopic(topic).await()
                android.util.Log.d("NotificationManager", "Successfully subscribed to $topic")
            } catch (e: Exception) {
                android.util.Log.e("NotificationManager", "Failed to subscribe to $topic", e)
            }
        }
    }
    
    override suspend fun unsubscribeFromTopics(topics: List<String>) {
        val fcm = FirebaseMessaging.getInstance()
        topics.forEach { topic ->
            try {
                fcm.unsubscribeFromTopic(topic).await()
            } catch (e: Exception) {
                android.util.Log.e("NotificationManager", "Failed to unsubscribe from $topic", e)
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

    override suspend fun clearAllTopics() {
        val fcm = FirebaseMessaging.getInstance()
        // Android FCM doesn't expose a "get all subscriptions" API; tracked topics
        // are stored in shared prefs under "fcm_topics" — unsubscribe each.
        val prefs = context.getSharedPreferences("StationlyPrefs", android.content.Context.MODE_PRIVATE)
        val topics = prefs.getStringSet("fcm_topics", emptySet()) ?: emptySet()
        topics.forEach { runCatching { fcm.unsubscribeFromTopic(it).await() } }
        prefs.edit().remove("fcm_topics").apply()
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
        val editor = prefs.edit()
        prefs.all.keys.filter {
            it.startsWith("line_status_") || it.startsWith("predictions_") || it == "selections"
        }.forEach { editor.remove(it) }
        editor.apply()
    }

    override suspend fun clearAll() {
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
    private var apiKey: String = ""
    
    fun initialize(context: Context, apiKey: String) {
        appContext = context.applicationContext
        this.apiKey = apiKey
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
    actual fun getApiKey(): String = apiKey
    
    actual suspend fun getAuthToken(): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser
            user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            null
        }
    }
}