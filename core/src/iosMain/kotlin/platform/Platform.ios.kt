package com.stationly.core.platform

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import com.stationly.core.repository.SqlStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.*

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// ── App Group identifier shared between main app and WidgetKit extension ──
private const val APP_GROUP_ID = "group.com.stationly.mobile"

// ── NSUserDefaults keys ──
object AppGroupKeys {
    const val WIDGET_STATION_NAME    = "widget_station_name"
    const val WIDGET_LINE_NAME       = "widget_line_name"
    const val WIDGET_PREDICTIONS     = "widget_predictions"
    const val WIDGET_STATUS          = "widget_status"
    const val WIDGET_LAST_UPDATED    = "widget_last_updated"
    const val WIDGET_RELOAD_SIGNAL   = "widget_reload_signal"
    const val FCM_TOPICS             = "fcm_topics"
    const val FCM_SUBSCRIBE_PENDING  = "fcm_subscribe_pending"
    const val FCM_UNSUBSCRIBE_PENDING = "fcm_unsubscribe_pending"
    const val FIREBASE_AUTH_TOKEN    = "firebase_auth_token"
    const val APNS_TOKEN             = "apns_token"
}

class IosWidgetManager : WidgetManager {

    private val appGroupDefaults: NSUserDefaults?
        get() = NSUserDefaults(suiteName = APP_GROUP_ID)

    override suspend fun updateWidget(state: WidgetState) = withContext(Dispatchers.IO) {
        val defaults = appGroupDefaults ?: return@withContext

        val predictionsJson = try {
            json.encodeToString(ListSerializer(PredictionDisplay.serializer()), state.predictions)
        } catch (e: Exception) { "[]" }

        defaults.setObject(state.stationName, forKey = AppGroupKeys.WIDGET_STATION_NAME)
        defaults.setObject(state.lineName, forKey = AppGroupKeys.WIDGET_LINE_NAME)
        defaults.setObject(predictionsJson, forKey = AppGroupKeys.WIDGET_PREDICTIONS)
        defaults.setObject(state.status ?: "", forKey = AppGroupKeys.WIDGET_STATUS)
        defaults.setDouble(state.lastUpdated.toDouble(), forKey = AppGroupKeys.WIDGET_LAST_UPDATED)
        // Bump the reload signal — Swift WidgetKit observes this value to trigger timeline refresh
        val current = defaults.integerForKey(AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        defaults.setInteger(current + 1, forKey = AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        defaults.synchronize()
    }

    override suspend fun showWaitingState(station: String, line: String) = withContext(Dispatchers.IO) {
        val defaults = appGroupDefaults ?: return@withContext
        defaults.setObject(station, forKey = AppGroupKeys.WIDGET_STATION_NAME)
        defaults.setObject(line, forKey = AppGroupKeys.WIDGET_LINE_NAME)
        defaults.setObject("[]", forKey = AppGroupKeys.WIDGET_PREDICTIONS)
        defaults.setObject("", forKey = AppGroupKeys.WIDGET_STATUS)
        defaults.synchronize()
    }

    override suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState {
        return WidgetState(
            stationName = predictions.firstOrNull()?.stationName ?: "",
            lineName = predictions.firstOrNull()?.line ?: "",
            predictions = emptyList(),
            status = null,
            lastUpdated = (NSDate().timeIntervalSince1970).toLong()
        )
    }
}

class IosNotificationManager : NotificationManager {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun subscribeToTopics(topics: List<String>) = withContext(Dispatchers.IO) {
        val existing = (defaults.arrayForKey(AppGroupKeys.FCM_TOPICS) as? List<*>)
            ?.filterIsInstance<String>() ?: emptyList()
        val updated = (existing + topics).distinct()
        defaults.setObject(updated, forKey = AppGroupKeys.FCM_TOPICS)
        // Queue for Swift FCM subscription on next app foreground
        val pending = (defaults.arrayForKey(AppGroupKeys.FCM_SUBSCRIBE_PENDING) as? List<*>)
            ?.filterIsInstance<String>() ?: emptyList()
        defaults.setObject((pending + topics).distinct(), forKey = AppGroupKeys.FCM_SUBSCRIBE_PENDING)
        defaults.synchronize()
    }

    override suspend fun unsubscribeFromTopics(topics: List<String>) = withContext(Dispatchers.IO) {
        val existing = (defaults.arrayForKey(AppGroupKeys.FCM_TOPICS) as? List<*>)
            ?.filterIsInstance<String>() ?: emptyList()
        defaults.setObject(existing - topics.toSet(), forKey = AppGroupKeys.FCM_TOPICS)
        val pending = (defaults.arrayForKey(AppGroupKeys.FCM_UNSUBSCRIBE_PENDING) as? List<*>)
            ?.filterIsInstance<String>() ?: emptyList()
        defaults.setObject((pending + topics).distinct(), forKey = AppGroupKeys.FCM_UNSUBSCRIBE_PENDING)
        defaults.synchronize()
    }

    override suspend fun handleNotification(payload: Map<String, String>) {
        // Handled by AppDelegate / UNUserNotificationCenterDelegate on the Swift side
    }

    override suspend fun registerDevice(): String {
        return defaults.stringForKey(AppGroupKeys.APNS_TOKEN) ?: ""
    }
}

class IosStorageManager : StorageManager {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun saveSelections(selections: List<UserSelection>) = withContext(Dispatchers.IO) {
        val str = json.encodeToString(ListSerializer(UserSelection.serializer()), selections)
        defaults.setObject(str, forKey = "selections")
        defaults.synchronize()
    }

    override suspend fun loadSelections(): List<UserSelection> = withContext(Dispatchers.IO) {
        val str = defaults.stringForKey("selections") ?: return@withContext emptyList()
        return@withContext try {
            json.decodeFromString(ListSerializer(UserSelection.serializer()), str)
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun saveLineStatus(lineId: String, statusJson: String) = withContext(Dispatchers.IO) {
        defaults.setObject(statusJson, forKey = "line_status_$lineId")
        defaults.synchronize()
    }

    override suspend fun loadLineStatus(lineId: String): String? = withContext(Dispatchers.IO) {
        defaults.stringForKey("line_status_$lineId")
    }

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        val allKeys = (defaults.dictionaryRepresentation().keys as? List<*>)
            ?.filterIsInstance<String>() ?: emptyList()
        allKeys.filter {
            it.startsWith("line_status_") || it.startsWith("predictions_") || it == "selections"
        }.forEach { defaults.removeObjectForKey(it) }
        defaults.synchronize()
    }

    override suspend fun saveString(key: String, value: String) = withContext(Dispatchers.IO) {
        defaults.setObject(value, forKey = key)
        defaults.synchronize()
    }

    override suspend fun loadString(key: String): String? = withContext(Dispatchers.IO) {
        defaults.stringForKey(key)
    }
}

// ── iOS API key (same as Android — move to xcconfig in production) ──
private const val IOS_API_KEY = "bff7e80b234d440d9fea4a1b3b96fae4"

actual object Platform {
    private var _sqlStorage: SqlStorage? = null

    actual val widgetManager: WidgetManager = IosWidgetManager()
    actual val notificationManager: NotificationManager = IosNotificationManager()
    actual val storageManager: StorageManager = IosStorageManager()

    actual val sqlStorage: SqlStorage
        get() = _sqlStorage ?: SqlStorage(createDatabase(DriverFactory())).also { _sqlStorage = it }

    actual fun getPlatformName(): String = "iOS"
    actual fun getApiKey(): String = IOS_API_KEY

    actual suspend fun getAuthToken(): String? = withContext(Dispatchers.IO) {
        // The Swift side refreshes the token and stores it in NSUserDefaults.
        // See AuthBridge.swift in iosApp.
        NSUserDefaults.standardUserDefaults.stringForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN)
    }
}
