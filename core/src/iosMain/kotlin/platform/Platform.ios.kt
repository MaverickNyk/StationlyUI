package com.stationly.core.platform

import com.stationly.core.model.FcmPayload
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.repository.SqlStorage
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.FormatDeparturesUseCase
import com.stationly.core.usecase.ProcessFcmPayloadUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private const val APP_GROUP_ID = "group.com.stationly.mobile"

// ── All NSUserDefaults keys used across KMP and Swift ──
object AppGroupKeys {
    // Widget data written by KMP, read by WidgetKit extension
    const val WIDGET_STATION_NAME     = "widget_station_name"
    const val WIDGET_LINE_NAME        = "widget_line_name"
    const val WIDGET_PREDICTIONS      = "widget_predictions"
    const val WIDGET_STATUS           = "widget_status"
    const val WIDGET_DIRECTION        = "widget_direction"
    const val WIDGET_LAST_UPDATED     = "widget_last_updated"
    const val WIDGET_RELOAD_SIGNAL    = "widget_reload_signal"

    // FCM topic management — written by KMP, processed by Swift FCMBridge
    const val FCM_TOPICS              = "fcm_topics"
    const val FCM_SUBSCRIBE_PENDING   = "fcm_subscribe_pending"
    const val FCM_UNSUBSCRIBE_PENDING = "fcm_unsubscribe_pending"
    const val FCM_TOKEN               = "fcm_token"    // FCM registration token stored by AppDelegate

    // Auth state — written by Swift AuthBridge, read by KMP IosPlatformAuthProvider
    const val FIREBASE_AUTH_TOKEN     = "firebase_auth_token"
    const val FIREBASE_USER_EMAIL     = "firebase_user_email"
    const val FIREBASE_USER_NAME      = "firebase_user_display_name"
    const val FIREBASE_USER_PHOTO     = "firebase_user_photo_url"
    const val FIREBASE_USER_UID       = "firebase_user_uid"

    // Auth command protocol — KMP writes, Swift reads and executes, then clears
    const val AUTH_PENDING_COMMAND    = "auth_pending_command"
    const val AUTH_PENDING_ERROR      = "auth_pending_error"
    const val AUTH_OPERATION_SUCCESS  = "auth_operation_success"  // for non-token operations (e.g. resetConfirm)
    // Written by Swift ONLY after the Firebase call fully completes. KMP must
    // wait on THIS (not on the command key vanishing — Swift clears that
    // immediately on receipt, long before an interactive Google flow finishes).
    const val AUTH_COMMAND_DONE       = "auth_command_done"

    // Profile metadata
    const val SIGNIN_PROVIDER         = "signin_provider"
    const val MEMBER_SINCE            = "member_since"

    // Deep link — written by Swift AppDelegate, consumed once by KMP on app start
    const val PENDING_RESET_OOB_CODE  = "pending_reset_oob_code"
}

// ─────────────────────────────────────────────────────────
// Widget Manager
// ─────────────────────────────────────────────────────────

class IosWidgetManager : WidgetManager {

    private val appGroupDefaults: NSUserDefaults?
        get() = NSUserDefaults(suiteName = APP_GROUP_ID)

    override suspend fun updateWidget(state: WidgetState) = withContext(Dispatchers.IO) {
        val d = appGroupDefaults ?: return@withContext
        val predictionsJson = try {
            json.encodeToString(ListSerializer(PredictionDisplay.serializer()), state.predictions)
        } catch (_: Exception) { "[]" }

        d.setObject(state.stationName,          forKey = AppGroupKeys.WIDGET_STATION_NAME)
        d.setObject(state.lineName,              forKey = AppGroupKeys.WIDGET_LINE_NAME)
        d.setObject(predictionsJson,             forKey = AppGroupKeys.WIDGET_PREDICTIONS)
        d.setObject(state.status ?: "",          forKey = AppGroupKeys.WIDGET_STATUS)
        d.setObject(state.direction,             forKey = AppGroupKeys.WIDGET_DIRECTION)
        d.setDouble(state.lastUpdated.toDouble(), forKey = AppGroupKeys.WIDGET_LAST_UPDATED)
        // Bumping the signal tells Swift WidgetReloadObserver to call WidgetCenter.reloadAllTimelines()
        val sig = d.integerForKey(AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        d.setInteger(sig + 1, forKey = AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        d.synchronize()
    }

    override suspend fun showWaitingState(station: String, line: String) = withContext(Dispatchers.IO) {
        val d = appGroupDefaults ?: return@withContext
        d.setObject(station, forKey = AppGroupKeys.WIDGET_STATION_NAME)
        d.setObject(line,    forKey = AppGroupKeys.WIDGET_LINE_NAME)
        d.setObject("[]",    forKey = AppGroupKeys.WIDGET_PREDICTIONS)
        d.setObject("",      forKey = AppGroupKeys.WIDGET_STATUS)
        val sig = d.integerForKey(AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        d.setInteger(sig + 1, forKey = AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        d.synchronize()
    }

    override suspend fun clearWidgetData() = withContext(Dispatchers.IO) {
        val d = appGroupDefaults ?: return@withContext
        d.removeObjectForKey(AppGroupKeys.WIDGET_STATION_NAME)
        d.removeObjectForKey(AppGroupKeys.WIDGET_LINE_NAME)
        d.removeObjectForKey(AppGroupKeys.WIDGET_PREDICTIONS)
        d.removeObjectForKey(AppGroupKeys.WIDGET_STATUS)
        d.removeObjectForKey(AppGroupKeys.WIDGET_LAST_UPDATED)
        // Bump signal so widget reloads to empty state
        val sig = d.integerForKey(AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        d.setInteger(sig + 1, forKey = AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        d.synchronize()
    }

    override suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState {
        return WidgetState(
            stationName = predictions.firstOrNull()?.stationName ?: "",
            lineName    = predictions.firstOrNull()?.line ?: "",
            predictions = emptyList(),
            status      = null,
            lastUpdated = NSDate().timeIntervalSince1970.toLong()
        )
    }
}

// ─────────────────────────────────────────────────────────
// Notification Manager
// ─────────────────────────────────────────────────────────

class IosNotificationManager : NotificationManager {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun subscribeToTopics(topics: List<String>) = withContext(Dispatchers.IO) {
        val existing = pendingList(AppGroupKeys.FCM_TOPICS)
        defaults.setObject((existing + topics).distinct(), forKey = AppGroupKeys.FCM_TOPICS)
        val pending = pendingList(AppGroupKeys.FCM_SUBSCRIBE_PENDING)
        defaults.setObject((pending + topics).distinct(), forKey = AppGroupKeys.FCM_SUBSCRIBE_PENDING)
        defaults.synchronize()
        Unit
    }

    override suspend fun unsubscribeFromTopics(topics: List<String>) = withContext(Dispatchers.IO) {
        val existing = pendingList(AppGroupKeys.FCM_TOPICS)
        defaults.setObject(existing - topics.toSet(), forKey = AppGroupKeys.FCM_TOPICS)
        val pending = pendingList(AppGroupKeys.FCM_UNSUBSCRIBE_PENDING)
        defaults.setObject((pending + topics).distinct(), forKey = AppGroupKeys.FCM_UNSUBSCRIBE_PENDING)
        defaults.synchronize()
        Unit
    }

    override suspend fun clearAllTopics() = withContext(Dispatchers.IO) {
        val all = pendingList(AppGroupKeys.FCM_TOPICS)
        if (all.isNotEmpty()) {
            val pending = pendingList(AppGroupKeys.FCM_UNSUBSCRIBE_PENDING)
            defaults.setObject((pending + all).distinct(), forKey = AppGroupKeys.FCM_UNSUBSCRIBE_PENDING)
        }
        defaults.removeObjectForKey(AppGroupKeys.FCM_TOPICS)
        defaults.removeObjectForKey(AppGroupKeys.FCM_SUBSCRIBE_PENDING)
        defaults.synchronize()
        Unit
    }

    override suspend fun handleNotification(payload: Map<String, String>) {
        // Handled by Swift AppDelegate / UNUserNotificationCenterDelegate
        // FCM payload processing is done by FcmPayloadBridge.processPayload()
    }

    override suspend fun registerDevice(): String =
        defaults.stringForKey(AppGroupKeys.FCM_TOKEN) ?: ""

    private fun pendingList(key: String): List<String> =
        (defaults.arrayForKey(key) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
}

// ─────────────────────────────────────────────────────────
// Storage Manager
// ─────────────────────────────────────────────────────────

class IosStorageManager : StorageManager {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun saveSelections(selections: List<UserSelection>) = withContext(Dispatchers.IO) {
        defaults.setObject(
            json.encodeToString(ListSerializer(UserSelection.serializer()), selections),
            forKey = "selections"
        )
        defaults.synchronize()
        Unit
    }

    override suspend fun loadSelections(): List<UserSelection> = withContext(Dispatchers.IO) {
        val str = defaults.stringForKey("selections") ?: return@withContext emptyList()
        try { json.decodeFromString(ListSerializer(UserSelection.serializer()), str) }
        catch (_: Exception) { emptyList() }
    }

    override suspend fun saveLineStatus(lineId: String, statusJson: String) = withContext(Dispatchers.IO) {
        defaults.setObject(statusJson, forKey = "line_status_$lineId")
        defaults.synchronize()
        Unit
    }

    override suspend fun loadLineStatus(lineId: String): String? = withContext(Dispatchers.IO) {
        defaults.stringForKey("line_status_$lineId")
    }

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        allKeys().filter {
            it.startsWith("line_status_") || it.startsWith("predictions_") ||
            it.startsWith("cached_") || it == "selections"
        }.forEach { defaults.removeObjectForKey(it) }
        defaults.synchronize()
        Unit
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        // removePersistentDomainForName is the safe, API-sanctioned way to wipe all app UserDefaults
        val bundleId = NSBundle.mainBundle.bundleIdentifier
        if (bundleId != null) {
            defaults.removePersistentDomainForName(bundleId)
        } else {
            allKeys().forEach { defaults.removeObjectForKey(it) }
        }
        defaults.synchronize()
        Unit
    }

    override suspend fun saveString(key: String, value: String) = withContext(Dispatchers.IO) {
        defaults.setObject(value, forKey = key)
        defaults.synchronize()
        Unit
    }

    override suspend fun loadString(key: String): String? = withContext(Dispatchers.IO) {
        defaults.stringForKey(key)
    }

    // dictionaryRepresentation().keys is Set<Any?> — filterIsInstance avoids the broken List cast
    private fun allKeys(): List<String> =
        defaults.dictionaryRepresentation().keys.filterIsInstance<String>()
}

// ─────────────────────────────────────────────────────────
// Platform singleton
// ─────────────────────────────────────────────────────────

private const val IOS_API_KEY = "f7d6c5b4-3a2b-1c0d-e9f8-a7b6c5d4e3f2"

actual object Platform {
    private var _sqlStorage: SqlStorage? = null

    actual val widgetManager: WidgetManager       = IosWidgetManager()
    actual val notificationManager: NotificationManager = IosNotificationManager()
    actual val storageManager: StorageManager     = IosStorageManager()

    actual val sqlStorage: SqlStorage
        get() = _sqlStorage ?: SqlStorage(createDatabase(DriverFactory())).also { _sqlStorage = it }

    actual fun getPlatformName(): String = "iOS"
    actual fun getApiKey(): String       = IOS_API_KEY
    actual fun getEnvironment(): AppEnvironment {
        val env = NSBundle.mainBundle.objectForInfoDictionaryKey("StationlyEnvironment") as? String
        return if (env == "staging") AppEnvironment.STAGING else AppEnvironment.PRODUCTION
    }
    actual fun getBaseUrl(): String = com.stationly.core.config.AppConfig.apiBaseUrl

    actual suspend fun getAuthToken(): String? = withContext(Dispatchers.IO) {
        NSUserDefaults.standardUserDefaults.stringForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN)
    }

    actual suspend fun signOutFromAuthExpiry() {
        // iOS Firebase sign-out runs in Swift; enqueue the "signOut" command for the
        // AuthBridge to pick up. Token slot is cleared so any in-flight request that
        // re-reads it sees no token rather than the expired one.
        val ud = NSUserDefaults.standardUserDefaults
        if (ud.stringForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN) == null) return
        ud.removeObjectForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN)
        ud.setObject("signOut", forKey = "auth_pending_command")
    }
}

// ─────────────────────────────────────────────────────────
// FCM Payload Bridge
// Called by Swift AppDelegate when an FCM push notification arrives.
// Swift serialises the push userInfo dict to JSON and calls processPayload().
// ─────────────────────────────────────────────────────────

object FcmPayloadBridge {

    private val processUseCase: ProcessFcmPayloadUseCase by lazy {
        ProcessFcmPayloadUseCase(
            departureRepository = DepartureRepository(
                NetworkModule.tflApi,
                Platform.storageManager,
                Platform.sqlStorage,
                SyncPredictionsUseCase(Platform.sqlStorage)
            ),
            widgetManager          = Platform.widgetManager,
            storageManager         = Platform.storageManager,
            formatDeparturesUseCase = FormatDeparturesUseCase(),
            // iOS persists selections only to SQLite — pass it so FCM can find
            // the primary board and actually update the widget.
            sqlStorage             = Platform.sqlStorage
        )
    }

    /**
     * Fire-and-forget: parse JSON FCM payload, update SQLite cache, refresh widget.
     * Called from Swift on a background queue; uses GlobalScope since this is a
     * platform-event callback with no associated lifecycle.
     */
    fun processPayload(jsonString: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val payload = json.decodeFromString<FcmPayload>(jsonString)
                processUseCase(payload)
            } catch (e: Exception) {
                println("[FcmPayloadBridge] Failed to process payload: ${e.message}")
            }
        }
    }
}
