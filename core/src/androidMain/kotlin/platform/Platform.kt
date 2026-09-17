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
        val prefs = context.getSharedPreferences("StationlyPrefs", android.content.Context.MODE_PRIVATE)
        val stored = (prefs.getStringSet("fcm_topics", emptySet()) ?: emptySet()).toMutableSet()
        topics.forEach { topic ->
            try {
                fcm.subscribeToTopic(topic).await()
                stored.add(topic)
                android.util.Log.d("NotificationManager", "Successfully subscribed to $topic")
            } catch (e: Exception) {
                android.util.Log.e("NotificationManager", "Failed to subscribe to $topic", e)
            }
        }
        prefs.edit().putStringSet("fcm_topics", stored).apply()
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
        // Would trigger ProcessPredictionsUseCase
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

    /**
     * A SEPARATE prefs file, so the logout wipe of the session store misses it.
     * The direct mirror of iOS keeping these in the App Group suite.
     */
    private val durablePrefs by lazy {
        context.getSharedPreferences("stationly_durable_prefs", android.content.Context.MODE_PRIVATE)
    }

    override suspend fun saveDurable(key: String, value: String) {
        durablePrefs.edit().putString(key, value).apply()
    }

    override suspend fun loadDurable(key: String): String? = durablePrefs.getString(key, null)

    override suspend fun removeDurable(key: String) {
        durablePrefs.edit().remove(key).apply()
    }
}

// Android Platform implementation
actual object Platform {
    private lateinit var appContext: Context
    private var apiKey: String = ""
    private var environment: AppEnvironment = AppEnvironment.PRODUCTION

    fun initialize(context: Context, apiKey: String, environment: AppEnvironment) {
        appContext = context.applicationContext
        this.apiKey = apiKey
        this.environment = environment
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

    /**
     * Read from the installed package rather than `BuildConfig`, because `core`
     * is a shared module and has no `BuildConfig` of the app that includes it.
     * `PackageManager` is the same value the Play Store compares against.
     */
    actual fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0"

    actual fun appBuild(): String = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode.toString()
    }.getOrNull() ?: "0"

    actual fun getApiKey(): String = apiKey
    actual fun getEnvironment(): AppEnvironment = environment
    actual fun getBaseUrl(): String = com.stationly.core.config.AppConfig.apiBaseUrl
    
    /**
     * `forceRefresh = false` is the whole implementation, and it is not laziness.
     *
     * The SDK answers from its cache while the token has more than ~5 minutes
     * left and goes to Google only when it does not, which is exactly the
     * freshness contract [com.stationly.core.platform.Platform.getAuthToken]
     * now states. iOS had to be taught this; Android has always had it for free,
     * which is why the hour-long auto-logout was an iOS-only symptom.
     */
    actual suspend fun getAuthToken(): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser
            user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            null
        }
    }

    /** The same call with the cache bypassed. See the expect declaration for
     *  why this is reserved for the 401 retry and never used per-request. */
    actual suspend fun refreshAuthToken(): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser
            user?.getIdToken(true)?.await()?.token
        } catch (e: Exception) {
            null
        }
    }

    actual suspend fun signOutFromAuthExpiry(path: String, status: Int, accountGone: Boolean) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            // Logged with the three facts that identify WHICH request ended the
            // session. Android has never shown the iOS symptom, but it shares
            // the caller — so if this line ever appears here it means the
            // backend labelled an account gone, and that is worth being able to
            // read off a bug report rather than infer.
            android.util.Log.w(
                "Platform",
                "Forced sign-out: path=$path status=$status accountGone=$accountGone"
            )
            auth.signOut()
        }
    }
}