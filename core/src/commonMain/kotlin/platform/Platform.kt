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

    /**
     * A bearer token that is still valid when the caller uses it — not merely
     * the last one this device happened to see.
     *
     * ## The contract this did not used to have
     * It used to promise nothing about freshness, and iOS took that literally:
     * its actual was one `NSUserDefaults` read of `firebase_auth_token`. Firebase
     * ID tokens live exactly one hour, and that key is written only by sign-in,
     * by `settleAuthState`, and by `AuthBridge.refreshTokenIfNeeded()` on
     * foreground — none of which is sequenced before an outbound request. So
     * every call under `/user/` made more than an hour after the last foreground
     * carried a dead bearer, the backend answered 401, and the 401 handler
     * signed the user out. The app was not timing out; it was deliberately
     * ending a healthy session because a stale token looked like a revoked one.
     *
     * Android never had the bug because its actual asks the SDK
     * (`currentUser.getIdToken(false)`), which refreshes when the cached token is
     * close to expiry. That behaviour is now the CONTRACT rather than one
     * platform's accident, and iOS implements it too — see
     * `IosAuthTokenAuthority`.
     *
     * ## What "valid" is allowed to mean
     * Fresh enough to survive the round trip, not freshly minted. Implementations
     * may — and should — return a cached token while it has comfortable life
     * left; crossing to the auth SDK on every request would put a lock and, on
     * iOS, a language boundary in front of every HTTP call for no gain.
     *
     * Returns null when there is no session, and ALSO when there is one but no
     * token can be produced right now (offline at the moment the cached one
     * aged out). Null is not evidence that the user is signed out — nothing may
     * treat it that way. See [signOutFromAuthExpiry] for what is allowed to end
     * a session.
     */
    suspend fun getAuthToken(): String?

    /**
     * Go and ask the auth SDK for a token, ignoring any cache.
     *
     * The retry half of the 401 handling in `NetworkModule`: a 401 that the
     * server did NOT label `account_gone` means "this credential did not work",
     * and the one repair worth attempting is a forced refresh followed by a
     * single retry. [getAuthToken] cannot serve that path, because by
     * construction it is entitled to hand back the very token that just failed.
     *
     * Deliberately NOT used on the ordinary request path. A forced refresh is a
     * network round trip to Google on every call, and the expiry window
     * [getAuthToken] already honours makes it unnecessary — see
     * `IosAuthTokenAuthority.REFRESH_WINDOW_SECONDS` for the trade.
     *
     * Returns null if no token could be obtained, including when the session has
     * genuinely ended. The caller must not read that as a sign-out either; the
     * platform's own auth listener owns that conclusion.
     */
    suspend fun refreshAuthToken(): String?

    /**
     * End this device's session because the backend says the ACCOUNT is gone.
     *
     * ## Only ever called for a labelled 401
     * The parameters exist to keep that honest. `NetworkModule` calls this only
     * when the 401 body carried `"account_gone"` — the marker
     * `AuthMiddleware.validateUserToken` sets for a deleted, disabled or
     * revoked user, as distinct from `token_invalid` for one that merely
     * expired. A bare 401 must never reach here; it means the credential was
     * wrong, not that the person is gone, and the two used to be treated alike.
     *
     * ## Every call leaves evidence, and that is a requirement
     * [path], [status] and [accountGone] are recorded by the caller as an
     * `auth.forced_logout` activity row and, on iOS, a `PushTrace` line. A
     * forced logout used to leave no trace at all: the user found a login
     * screen, the activity table showed the session simply stopping, and there
     * was nothing to say which request had ended it. That absence is what made
     * this bug take several rounds to find, so the tripwire is part of the fix
     * and not scaffolding to be tidied away later.
     *
     * @param path the request path that returned the 401, e.g. `/user/activity/batch`
     * @param status the HTTP status, always 401 today — recorded rather than
     *   assumed so a future caller widening this cannot do so silently
     * @param accountGone whether the response body carried the `account_gone`
     *   marker; false here means the sign-out was driven by something other than
     *   the server's own verdict, which is a bug worth being able to see
     */
    suspend fun signOutFromAuthExpiry(path: String, status: Int, accountGone: Boolean)
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
