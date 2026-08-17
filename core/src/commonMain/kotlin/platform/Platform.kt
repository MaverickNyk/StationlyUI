package com.stationly.core.platform

import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import kotlin.concurrent.Volatile

enum class AppEnvironment { STAGING, PRODUCTION }

interface WidgetManager {
    suspend fun updateWidget(state: WidgetState)
    suspend fun showWaitingState(station: String, line: String)
    suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState
    suspend fun clearWidgetData()
}

/**
 * "Local storage is empty on purpose, on its way to being refilled."
 *
 * A login restore CLEARS SQLite and then re-inserts from the cloud profile, so
 * for the length of that operation `getAllSelections()` answers honestly and
 * wrongly: the account has stations, this device just does not hold them yet.
 * The iOS widget write treats an empty selection table as "the user deleted
 * their last board" and wipes the App Group, which puts *every* placed widget on
 * "Open the app to add a station" — for a user who is at that moment signing in
 * and has done nothing wrong.
 *
 * The two cases are identical in storage and can only be told apart by INTENT,
 * which is known here and nowhere else. So the restore says so, and the widget
 * write skips the wipe while it is being said. Nothing else changes: boards
 * written *during* the restore still publish as each one is set up, so widgets
 * refill progressively and no explicit "publish once at the end" step is needed
 * — one less thing that can be forgotten on a new restore path.
 *
 * ## Deliberately not a counter
 * Restores do not nest and do not overlap: there is one, on the login path,
 * awaited. If two ever did overlap, the first to finish would clear the flag
 * early and the second would behave exactly as it does today — a wipe. That is
 * the current behaviour, not a new failure, which is what makes a plain flag
 * safe enough to prefer over an atomic counter.
 *
 * In-memory only, so a process killed mid-restore cannot leave it stuck raised.
 */
object WidgetRestore {
    @Volatile
    private var restoring: Boolean = false

    /** Whether a destructive restore is in flight. Read by the widget write. */
    val inProgress: Boolean get() = restoring

    /**
     * Run [block] with the widget's empty-state wipe suppressed.
     *
     * Wrap the WHOLE restore — the clear, the re-insert, and the board setup
     * that follows — not just the clear. The gap that matters is the one where
     * the table is empty, but the boards only come back one at a time, and a
     * refresh landing between two of them would see a partial list rather than
     * an empty one, which is written correctly anyway.
     */
    suspend fun <T> during(block: suspend () -> T): T {
        restoring = true
        try {
            return block()
        } finally {
            restoring = false
        }
    }
}

interface NotificationManager {
    suspend fun subscribeToTopics(topics: List<String>)
    suspend fun unsubscribeFromTopics(topics: List<String>)
    suspend fun handleNotification(payload: Map<String, String>)
    suspend fun registerDevice(): String
    suspend fun clearAllTopics()
}

interface StorageManager {
    suspend fun saveSelections(selections: List<UserSelection>)
    suspend fun loadSelections(): List<UserSelection>
    suspend fun saveLineStatus(lineId: String, statusJson: String)
    suspend fun loadLineStatus(lineId: String): String?
    suspend fun clearCache()
    suspend fun clearAll()
    suspend fun saveString(key: String, value: String)
    suspend fun loadString(key: String): String?

    /**
     * Storage that SURVIVES [clearAll], for state that outlives a session.
     *
     * Logout wipes the app's whole defaults domain, which is right for anything
     * naming the user. It is wrong for the settings that make the app look the
     * way they left it — those are kept per account id and restored when the
     * same person signs back in on the same device, and they are not identity:
     * "three rows per platform, board first" says nothing about who anybody is.
     *
     * The screensaver's settings already did this by hand; this is the same
     * mechanism, declared once. See [com.stationly.core.repository.UserSettings].
     */
    suspend fun saveDurable(key: String, value: String)
    suspend fun loadDurable(key: String): String?

    /**
     * Drop one durable key.
     *
     * The counterpart [clearAll] deliberately cannot provide: durable storage
     * survives a session wipe by design, so the ONE case that must still be able
     * to erase it — an account being deleted — needs an explicit way to say so.
     * Without this, settings namespaced to a uid that no longer exists would sit
     * on the device forever.
     */
    suspend fun removeDurable(key: String)
}

expect object Platform {
    val widgetManager: WidgetManager
    val notificationManager: NotificationManager
    val storageManager: StorageManager
    val sqlStorage: com.stationly.core.repository.SqlStorage

    fun getPlatformName(): String
    fun getApiKey(): String
    fun getEnvironment(): AppEnvironment
    fun getBaseUrl(): String
    suspend fun getAuthToken(): String?

    /**
     * Invoked by the network layer when the backend returns 401 for a request that
     * carried a Firebase token. Signs the user out of Firebase locally — the platform
     * UI layer subscribes to FirebaseAuth state changes and navigates to login as a
     * side effect. No-op if there is no signed-in user (the 401 was about something
     * other than the user's session).
     */
    suspend fun signOutFromAuthExpiry()
}

/**
 * Live-departure WebSocket stream lifecycle, driven by the platform's own
 * foreground/background signal (Android has none of this — FCM + the
 * existing SQLite-backed board poll are unchanged there, so every member is
 * a no-op on that actual).
 */
expect object LiveStream {
    /** App became foreground-active: connect (idempotent) and subscribe to
     * every saved selection. */
    fun notifyForeground()

    /** App left the foreground: disconnect cleanly. No background execution
     * is attempted — foreground-only, by design. */
    fun notifyBackground()

    /**
     * Pull-to-refresh.
     *
     * ⚠️ Does NOT force a reconnect when the socket is healthy — it forces a
     * *resubscribe*, because the server replays a cached snapshot on every
     * subscribe frame. Tearing down a live connection here cost a TLS
     * handshake plus an auth round-trip before the first byte of data and made
     * a pull take ~10s; see `IOS_LIVE_STREAM.md` §4.3 before changing this.
     * Only a dead or never-established socket gets the full reconnect.
     */
    fun notifyPullToRefresh()
}
