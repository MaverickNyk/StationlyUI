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
        // Which board changed. Without it the receiver has to assume "all of
        // them", and this fires once per processed payload: a refresh over
        // several stops then redrew every placed widget several times each.
        // Blank stays "all", which is what the other two callers mean.
        intent.putExtra("stationId", state.stationId)
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

/**
 * Android's FCM topics, and the ledger that is the only record of them.
 *
 * ## Why there is a ledger at all
 * FCM has no "what is this device subscribed to" call. The subscription lives in
 * the Google Play services process, survives the app being killed, updated and
 * uninstalled-with-backup, and outlives the account that asked for it. So the
 * only way to know what we asked for is to write it down, and the only way for
 * the written record to be worth anything is for EVERY path that changes a
 * subscription to maintain it. That is this class, and nothing else should
 * reach `FirebaseMessaging.subscribeToTopic` directly.
 *
 * Two deliberate exceptions, both documented at their call sites:
 *  - `StationlyApplication` subscribes `stationly_all`, tracked by its own
 *    boolean because it is not a board and must never be reconciled away, and
 *  - `FcmMessagingService.onNewToken` re-sends the ledger after a token
 *    rotation, because the new token starts subscribed to nothing.
 *
 * ## The bug this class shipped with
 * [subscribeToTopics] added to the ledger and [unsubscribeFromTopics] did not
 * remove from it, so the record only ever grew. It read as harmless — it is only
 * a list — right up until something diffs against it, at which point a topic
 * that was subscribed, removed and re-added is "already subscribed" and the
 * board it belongs to silently never receives another push.
 */
class AndroidNotificationManager(
    private val context: Context
) : NotificationManager {

    override suspend fun subscribeToTopics(topics: List<String>) {
        if (topics.isEmpty()) return
        val added = topics.filter { subscribe(it) }
        if (added.isNotEmpty()) writeLedger(readLedger() + added)
    }

    override suspend fun unsubscribeFromTopics(topics: List<String>) {
        if (topics.isEmpty()) return
        // Dropped from the ledger only on success. A failed unsubscribe leaves a
        // live subscription behind, and the ledger entry is the only thing that
        // will ever find it again — the next reconcile sees a topic no board
        // wants and retries. Forgetting it here is how a leak becomes permanent.
        val removed = topics.filter { unsubscribe(it) }
        if (removed.isNotEmpty()) writeLedger(readLedger() - removed.toSet())
    }

    /**
     * The whole set, stated. See [NotificationManager.reconcileTopics].
     *
     * Cheap when there is nothing to do, which is almost always: two set
     * differences over a `SharedPreferences` read, and no FCM call at all. That
     * matters more than it sounds — this runs on every foreground, and it is
     * also what makes upgrade day a no-op. A v1 install arrives with its ledger
     * already written by this same class under this same key, so the diff is
     * empty and nobody re-subscribes anything (risk R6).
     */
    override suspend fun reconcileTopics(desired: List<String>) {
        val ledger = readLedger()
        val plan = TopicLedger.plan(ledger, desired)
        if (plan.isEmpty) return

        android.util.Log.d(
            TAG,
            "reconcile: +${plan.subscribe.size} -${plan.unsubscribe.size} " +
                "(ledger ${ledger.size} → ${desired.distinct().size})",
        )
        val added = plan.subscribe.filter { subscribe(it) }
        val removed = plan.unsubscribe.filter { unsubscribe(it) }
        writeLedger(readLedger() + added - removed.toSet())
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

    /**
     * Everything, gone: sign-out, account deletion, forced logout.
     *
     * Deliberately driven off the LEDGER rather than off the selections. They
     * are usually the same set, and when they are not it is because a
     * subscription outlived its board — exactly the row that must not be left
     * behind on a device somebody else is about to sign into.
     */
    override suspend fun clearAllTopics() {
        val topics = readLedger()
        topics.forEach { unsubscribe(it) }
        prefs().edit().remove(KEY_TOPICS).apply()
    }

    // ── the ledger ───────────────────────────────────────────────────────────

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * A COPY. `getStringSet` hands back the live set held by the
     * `SharedPreferences` instance, and mutating it (or holding it across an
     * `apply`) is documented as undefined.
     */
    private fun readLedger(): Set<String> =
        (prefs().getStringSet(KEY_TOPICS, emptySet()) ?: emptySet()).toSet()

    private fun writeLedger(topics: Set<String>) {
        prefs().edit().putStringSet(KEY_TOPICS, HashSet(topics)).apply()
    }

    private suspend fun subscribe(topic: String): Boolean = try {
        FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
        android.util.Log.d(TAG, "subscribed $topic")
        true
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Failed to subscribe to $topic", e)
        false
    }

    private suspend fun unsubscribe(topic: String): Boolean = try {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).await()
        android.util.Log.d(TAG, "unsubscribed $topic")
        true
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Failed to unsubscribe from $topic", e)
        false
    }

    private companion object {
        const val TAG = "NotificationManager"
        const val PREFS = "StationlyPrefs"

        /**
         * The key v1 writes, unchanged and load-bearing. An upgrading device
         * carries its subscriptions in here; renaming it would make every v1
         * install look unsubscribed, and the first reconcile would re-subscribe
         * the lot on upgrade day.
         */
        const val KEY_TOPICS = "fcm_topics"
    }
}

// Android StorageManager implementation
class AndroidStorageManager(
    private val context: Context
) : StorageManager {
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        context.getSharedPreferences(DURABLE_PREFS, android.content.Context.MODE_PRIVATE)
    }

    override suspend fun saveDurable(key: String, value: String) {
        durablePrefs.edit().putString(key, value).apply()
    }

    override suspend fun loadDurable(key: String): String? = durablePrefs.getString(key, null)

    override suspend fun removeDurable(key: String) {
        durablePrefs.edit().remove(key).apply()
    }

    /**
     * The two file names, named rather than spelled out at each use.
     *
     * They were four inline literals until AV2-3.5, which is fine right up
     * until something OUTSIDE this class has to agree with one of them. Two
     * things now do: the app's own `AppSettings` (`:android:app`), which reads
     * the theme the shared UI writes, and `V1ThemeCarryOverTest`, which is the
     * only thing that would notice either name drifting.
     *
     * `PREFS` in particular is the file the v1 Android app has been writing
     * since launch. Renaming it does not migrate anything; it abandons it.
     */
    internal companion object {
        const val PREFS = "StationlyPrefs"
        const val DURABLE_PREFS = "stationly_durable_prefs"
    }
}

// Android Platform implementation
actual object Platform {
    /**
     * The process-wide application context, set once by the host's
     * `Application.onCreate` and never reassigned.
     *
     * Public because `:composeApp`'s Android `actual`s need a `Context` and
     * this is already the one place the app has agreed to keep it — every other
     * Android platform service in the codebase (`widgetManager`,
     * `notificationManager`, `storageManager`, `sqlStorage`) is built from it.
     * A second context holder in `composeApp/androidMain` would be a parallel
     * mechanism for a job that already has one, with its own initialisation
     * order to get wrong.
     *
     * `private set` because [initialize] is the only legitimate writer. Reading
     * it before that throws `UninitializedPropertyAccessException` naming this
     * property, which is the right failure: it means the host skipped
     * `Platform.initialize`, and nothing downstream can paper over that.
     */
    lateinit var appContext: Context
        private set

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