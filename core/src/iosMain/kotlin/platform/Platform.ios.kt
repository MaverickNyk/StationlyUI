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
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
    const val WIDGET_MODE             = "widget_mode"
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
    const val FIREBASE_USER_EMAIL_VERIFIED = "firebase_user_email_verified"
    const val FIREBASE_USER_IS_EMAIL_PROVIDER = "firebase_user_is_email_provider"

    // Deep link — written by Swift AppDelegate, consumed once by KMP on app start
    const val PENDING_RESET_OOB_CODE  = "pending_reset_oob_code"
}

// ─────────────────────────────────────────────────────────
// Widget Manager
// ─────────────────────────────────────────────────────────

class IosWidgetManager : WidgetManager {

    private val appGroupDefaults: NSUserDefaults?
        get() = NSUserDefaults(suiteName = APP_GROUP_ID)

    // Android parity (pull model): AndroidWidgetManager ignores the state it
    // is handed — every method just broadcasts ACTION_UPDATE_WIDGET and the
    // provider re-reads the PRIMARY (first) selection from SQL at render
    // time. iOS has no provider process, so the re-read happens here: every
    // trigger (FCM write, the home VM reloading ANY board, station add or
    // remove) rewrites the App Group from the primary selection's SQL state.
    // Writing the caller's state verbatim made the widget "last writer
    // wins" — the home VM reloading a second board put a non-primary station
    // on the widget, and deleting one of two boards blanked it.
    override suspend fun updateWidget(state: WidgetState) = refreshFromPrimary()

    override suspend fun showWaitingState(station: String, line: String) = refreshFromPrimary()

    override suspend fun clearWidgetData() = withContext(Dispatchers.IO) {
        val d = appGroupDefaults ?: return@withContext
        wipe(d)
    }

    private suspend fun refreshFromPrimary() = withContext(Dispatchers.IO) {
        val d = appGroupDefaults ?: return@withContext
        val sql = Platform.sqlStorage
        val primary = sql.getAllSelections().firstOrNull()
        if (primary == null) {
            // Nothing left to show (last board deleted / logged out): wipe to
            // the widget's designed empty state ("No station set") — the iOS
            // analog of Android's "No boards yet" fallback rows.
            wipe(d)
            return@withContext
        }

        // Same shape ProcessFcmPayloadUseCase.refreshWidgetFromStorage and the
        // SummaryViewModel poll produce, so the widget never flips format
        // depending on which trigger refreshed it.
        val preds = GlobalBoardProcessor.processPredictions(
            sql.getPredictions(primary.station, primary.line)
        )
        val tsMs = sql.getLastUpdatedTimestamp(primary.station, primary.line)
            ?: (NSDate().timeIntervalSince1970 * 1000).toLong()
        val status = sql.getLineStatus(primary.mode, primary.line)?.let { s ->
            val reason = StationlyFormatters.formatStatusReason(s.reason ?: "").trim()
            if (reason.isNotEmpty()) "${s.statusSeverityDescription}: $reason"
            else s.statusSeverityDescription
        }
        write(
            d,
            WidgetState(
                stationName = primary.stationName,
                lineName = primary.line,
                predictions = preds,
                status = status,
                lastUpdated = tsMs / 1000,
                direction = primary.direction,
                mode = primary.mode,
            )
        )
    }

    private fun write(d: NSUserDefaults, state: WidgetState) {
        val predictionsJson = try {
            json.encodeToString(ListSerializer(PredictionDisplay.serializer()), state.predictions)
        } catch (_: Exception) { "[]" }

        d.setObject(state.stationName,          forKey = AppGroupKeys.WIDGET_STATION_NAME)
        d.setObject(state.lineName,              forKey = AppGroupKeys.WIDGET_LINE_NAME)
        d.setObject(predictionsJson,             forKey = AppGroupKeys.WIDGET_PREDICTIONS)
        d.setObject(state.status ?: "",          forKey = AppGroupKeys.WIDGET_STATUS)
        d.setObject(state.direction,             forKey = AppGroupKeys.WIDGET_DIRECTION)
        d.setObject(state.mode,                  forKey = AppGroupKeys.WIDGET_MODE)
        d.setDouble(state.lastUpdated.toDouble(), forKey = AppGroupKeys.WIDGET_LAST_UPDATED)
        bumpReloadSignal(d)
        d.synchronize()
    }

    private fun wipe(d: NSUserDefaults) {
        d.removeObjectForKey(AppGroupKeys.WIDGET_STATION_NAME)
        d.removeObjectForKey(AppGroupKeys.WIDGET_LINE_NAME)
        d.removeObjectForKey(AppGroupKeys.WIDGET_PREDICTIONS)
        d.removeObjectForKey(AppGroupKeys.WIDGET_STATUS)
        d.removeObjectForKey(AppGroupKeys.WIDGET_DIRECTION)
        d.removeObjectForKey(AppGroupKeys.WIDGET_MODE)
        d.removeObjectForKey(AppGroupKeys.WIDGET_LAST_UPDATED)
        bumpReloadSignal(d)
        d.synchronize()
    }

    // Bumping the signal tells Swift WidgetReloadObserver to call
    // WidgetCenter.reloadAllTimelines()
    private fun bumpReloadSignal(d: NSUserDefaults) {
        val sig = d.integerForKey(AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        d.setInteger(sig + 1, forKey = AppGroupKeys.WIDGET_RELOAD_SIGNAL)
    }

    override suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState {
        return WidgetState(
            stationName = predictions.firstOrNull()?.stationName ?: "",
            lineName    = predictions.firstOrNull()?.line ?: "",
            predictions = emptyList(),
            status      = null,
            lastUpdated = NSDate().timeIntervalSince1970.toLong(),
            direction   = predictions.firstOrNull()?.direction ?: "",
            mode        = predictions.firstOrNull()?.mode ?: ""
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
     * Fire-and-forget variant for foreground deliveries where Swift doesn't
     * need to await the write (the WidgetReloadObserver picks up the signal).
     */
    fun processPayload(jsonString: String) {
        GlobalScope.launch(Dispatchers.IO) {
            processPayloadAndWait(jsonString)
        }
    }

    /**
     * Process an FCM message and return only when SQLite + the App Group have
     * been written. Swift sees this as `processPayloadAndWait(jsonString:completionHandler:)`
     * — AppDelegate awaits it in didReceiveRemoteNotification before calling
     * the background-fetch completion handler, so iOS doesn't suspend the
     * process mid-write and the widget reload sees fresh data.
     *
     * The argument is the WHOLE APNs userInfo dict as JSON. A real Syncer
     * topic push looks like:
     *   { "from": "/topics/Station_940GZZLUASL",   ← or LineStatus_tube_victoria
     *     "payload": "{…inner JSON string…}",       ← FcmPayload or LineStatus
     *     "aps": { "content-available": 1 }, … }
     * which is why decoding the top level directly as FcmPayload (the old
     * code) dropped every real push — the data lives one level down, exactly
     * like Android's remoteMessage.data["payload"].
     */
    suspend fun processPayloadAndWait(jsonString: String) {
        try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val topic = (root["from"] as? JsonPrimitive)?.contentOrNull
            val inner = (root["payload"] as? JsonPrimitive)?.contentOrNull

            when {
                topic?.contains("LineStatus_") == true && inner != null ->
                    processUseCase.processLineStatusUpdate(json.decodeFromString(inner))

                topic?.contains("Station_") == true && inner != null ->
                    processUseCase.processStationUpdate(
                        topicStationId = topic.substringAfter("Station_"),
                        payload = json.decodeFromString(inner)
                    )

                // No topic (direct/test push) — sniff the payload shape.
                inner != null -> {
                    val parsed = json.parseToJsonElement(inner).jsonObject
                    if ("lines" in parsed) {
                        processUseCase.processStationUpdate(null, json.decodeFromString(inner))
                    } else if ("statusSeverityDescription" in parsed) {
                        processUseCase.processLineStatusUpdate(json.decodeFromString(inner))
                    }
                }

                // Legacy/manual pushes that put the FcmPayload at the top level.
                "lines" in root ->
                    processUseCase.processStationUpdate(null, json.decodeFromString(jsonString))
            }
        } catch (e: Exception) {
            println("[FcmPayloadBridge] Failed to process payload: ${e.message}")
        }
    }
}
