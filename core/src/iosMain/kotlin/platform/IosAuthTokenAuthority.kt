package com.stationly.core.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import com.stationly.core.util.JwtClaims
import kotlin.concurrent.Volatile
import platform.Foundation.NSUserDefaults

/**
 * The one place iOS decides what bearer token an outbound request carries.
 *
 * ## The failure this exists to prevent
 * `Platform.getAuthToken()` used to be a single `NSUserDefaults` read of
 * `firebase_auth_token`. Firebase ID tokens live exactly one hour, and nothing
 * refreshed that key on the request path — it is written by sign-in, by
 * `settleAuthState`, and by `AuthBridge.refreshTokenIfNeeded()` on foreground,
 * none of which is ordered before an HTTP call. So any request under `/user/` issued
 * more than an hour after the last foreground carried a dead bearer, the backend
 * answered 401, and `NetworkModule` turned that into `signOutFromAuthExpiry()`.
 *
 * Measured shape of the bug: the app signed itself out roughly an hour after
 * last foreground, and the user found the login screen on the next icon or
 * widget tap. Two triggers, both confirmed by reading the code and both
 * unreachable by any ordering fix:
 *
 *  - `AppDelegate.handleDidBecomeActive()` starts `refreshTokenIfNeeded()` and
 *    `ActivityBridge.uploadActivityIfStale()` in two detached tasks with nothing
 *    sequencing them, and the upload usually wins;
 *  - `ActivityUploadScheduler` runs at 03:00 on a charger in a process that
 *    never foregrounded, so `refreshTokenIfNeeded()` has not run at all. That
 *    one is the silent overnight logout, and no amount of task ordering inside
 *    `didBecomeActive` reaches it.
 *
 * The only durable answer is for the REQUEST to resolve the token, which is what
 * Android has always done (`currentUser.getIdToken(false)`, auto-refreshed by
 * the SDK). This object is the iOS equivalent.
 *
 * ## Why a direct framework call and not the NSUserDefaults command bridge
 * The existing `auth_pending_command` protocol is polled by
 * `IosPlatformAuthProvider` every 250 ms. That is fine for a login button and
 * far too coarse to sit in front of every HTTP request: it would add up to a
 * quarter of a second of latency to calls that need none, and it serialises
 * through a key that a second command can overwrite. Swift registers a resolver
 * here instead (see `AuthTokenBridge` in composeApp, and `wireAuthTokenBridge`
 * in `AppDelegate`), and the crossing is a direct call in both directions.
 *
 * ## What this object does NOT do
 * It does not write `firebase_auth_token`. Swift does, inside the resolver,
 * because the write has to be made under the same "has the session ended while
 * this was in flight" guard that `AuthBridge.refreshTokenIfNeeded()` uses — see
 * the torn-identity failure documented on `AuthBridge.discardTornIdentity`.
 * Kotlin holding a second writer for that key is exactly the second spelling
 * that comment warns about.
 *
 * It also never signs anybody out. A token this cannot produce is reported as
 * null and nothing more; deciding that a session is over belongs to
 * `AuthBridge`, which can tell a deleted account from a train tunnel.
 */
object IosAuthTokenAuthority {

    /**
     * How close to expiry a token may get before this crosses to Firebase.
     *
     * Five minutes, and the trade runs in both directions:
     *
     *  - **Lower** and the fast path stops being a fast path in the only moment
     *    that matters. A token is minted with 60 minutes of life; a 1-minute
     *    window means requests keep using it until it has 60 seconds left, and
     *    a request that starts inside that window, hits a slow network and takes
     *    20 seconds to reach the backend can still arrive expired. The device
     *    clock is the other half of this: it is the input to every comparison
     *    here and it is not guaranteed accurate, so the window has to absorb
     *    ordinary skew as well as latency.
     *  - **Higher** and every request inside a widening band crosses the
     *    language boundary and pays a network round trip to Google for a token
     *    that was going to work anyway. At 30 minutes, half of every token's
     *    life is spent refreshing.
     *
     * Five is also what the Firebase SDKs themselves use as the point at which
     * a cached token stops being handed out, so iOS and Android now cross at the
     * same moment rather than at two thresholds that could drift apart.
     */
    private const val REFRESH_WINDOW_SECONDS = 5L * 60

    /**
     * How long to wait for Swift before giving up on a crossing.
     *
     * This sits inside `onRequest`, i.e. in front of the HTTP call rather than
     * inside it, so Ktor's own 15-second request timeout has not started yet and
     * would not rescue a resolver that never calls back. Ten seconds is longer
     * than any healthy `getIDToken()` and short enough that a wedged Firebase
     * cannot hang a user-visible request indefinitely — on timeout the stored
     * token is used, which is the same outcome as the old behaviour and no worse.
     */
    private const val BRIDGE_TIMEOUT_MS = 10_000L

    /**
     * Set by `AuthTokenBridge.register()` at launch, from `AppDelegate`.
     *
     * Null until then, and null forever in any process that does not run
     * `didFinishLaunchingWithOptions` — which is why [token] degrades to the
     * stored key rather than failing when it is missing. The widget extension is
     * a different process and never registers one.
     */
    @Volatile
    var resolver: ((forceRefresh: Boolean, completion: (String?) -> Unit) -> Unit)? = null

    /**
     * Serialises crossings, so a burst of requests on an aged-out token makes
     * one refresh and not one per request.
     *
     * Ten parallel calls to `getIDToken()` do not cost ten round trips — the
     * Firebase SDK coalesces them — but they do cost ten thread hops, ten
     * continuations and ten trips through the resolver, on a path that runs
     * before every single HTTP request. Waiters re-read the cache after
     * acquiring the lock, so all but the first return without crossing at all.
     */
    private val crossing = Mutex()

    // Volatile because [token]'s first rung reads this pair WITHOUT taking
    // [crossing] — that is what makes the fast path free — while a crossing
    // writes it on whichever thread Swift called back on.
    //
    // Both are written token-then-expiry and read in the same order, so the one
    // interleaving a racing reader can see is the NEW token beside the OLD
    // expiry. Both outcomes of that are safe: an old expiry that still looks
    // fresh serves a token that is newer than the one it was judged by, and an
    // old expiry that looks stale costs one unnecessary crossing. Neither can
    // serve a token that has actually expired, which is the only reading that
    // would matter.
    @Volatile
    private var cachedToken: String? = null

    /** Unix seconds from the cached token's `exp` claim; 0 when unknown. */
    @Volatile
    private var cachedExpiry: Long = 0

    /**
     * A token good for at least [REFRESH_WINDOW_SECONDS] more, if one can be had.
     *
     * ## The ladder, cheapest rung first
     *  1. the in-memory token, if it still has life left — no lock, no crossing,
     *     no `NSUserDefaults` read;
     *  2. `firebase_auth_token`, if IT still has life left. This rung is what
     *     makes a cold launch quiet: Swift has usually just written a fresh
     *     token from `settleAuthState`, and adopting it costs one defaults read
     *     instead of a round trip to Google;
     *  3. Swift, which asks Firebase.
     *
     * ## Why an unusable token is still returned rather than null
     * If rung 3 cannot produce one — offline, Firebase wedged, no resolver
     * registered — this hands back whatever is stored, expired or not. Sending a
     * dead bearer and sending none both end in 401, so the choice is not between
     * working and failing; it is between two failures. The stored token is the
     * better of the two because it can still be RIGHT: the expiry test above
     * depends on the device clock, and a phone whose clock is fast will call a
     * perfectly good token dead. Returning null in that case would strip the
     * Authorization header off a request that would have succeeded.
     *
     * Neither outcome is allowed to end a session — see `NetworkModule`, where a
     * 401 without the server's `account_gone` marker is a retry and not a
     * sign-out.
     */
    suspend fun token(): String? {
        readCacheIfFresh()?.let { return it }

        return crossing.withLock {
            // Re-checked under the lock. Everything queued behind a crossing
            // that has just succeeded should take its result, not start another.
            readCacheIfFresh()?.let { return@withLock it }
            crossToSwift(forceRefresh = false) ?: storedToken()
        }
    }

    /**
     * Ignore every cache and ask Firebase.
     *
     * The 401-retry path only. Deliberately still behind [crossing]: a burst of
     * requests that all 401 together — which is what a genuinely dead token
     * produces — must make one forced refresh between them, not one each.
     *
     * ## This does NOT fall back to the stored token, and [token] does
     * The difference is what the caller already knows. [token] falls back
     * because its expiry test is a guess made from the device clock, and a phone
     * running fast will call a perfectly good token dead — there, the stored
     * token may well still work.
     *
     * Here it cannot. The only caller is the 401 retry, which is holding a
     * server's verdict that this exact credential was refused. Handing the same
     * token back would spend a second request to be told the same thing, and
     * would record an `auth.401_survived` row blaming a retry that never had a
     * different token to try. Null is the honest answer: there is nothing new to
     * send, so do not send anything.
     */
    suspend fun forceRefresh(): String? = crossing.withLock {
        crossToSwift(forceRefresh = true)
    }

    /**
     * How many times this has crossed into Swift since launch.
     *
     * The fast path's only observable. "Ten requests, zero new crossings" is the
     * difference between a cache that works and one that is silently refetching
     * on every call, and neither shape can be told from the other by watching
     * the app. Kept out of [PushTrace] because a per-request line would evict
     * the whole 40-entry ring within a minute of ordinary use — the stream alone
     * fills it in twenty minutes.
     */
    @Volatile
    var crossings: Int = 0
        private set
    // `+=` on a volatile is not atomic, and here it does not need to be: every
    // write happens inside [crossToSwift], which is only ever called while
    // [crossing] is held. Volatile is for the READER, which is a test on another
    // thread.

    /**
     * Bumped by [invalidate]. A crossing carries the value it started with and
     * refuses to publish its result if it no longer matches.
     *
     * ## The race this closes
     * A crossing is a network round trip in the general case, and a sign-out can
     * land inside it. Without this, the sequence is:
     *
     * ```
     * request → crossToSwift starts
     *           …sign-out runs, invalidate() clears the cache…
     *           crossing returns a token and writes it back
     * ```
     *
     * and the cache is repopulated with the ENDED session's bearer, which every
     * subsequent request then uses. That is the same shape as the write that
     * resurrected a token in `AuthBridge.refreshTokenIfNeeded` — documented on
     * `persistFetchedToken`, and found on device in August — arriving one layer
     * further down.
     *
     * Swift guards its half already: `persistFetchedToken` refuses to write once
     * `expectingSignOut` is set, and the resolver then reports nil. But that
     * guard cannot cover the Kotlin-initiated path, because
     * `Platform.signOutFromAuthExpiry` only ENQUEUES the `signOut` command —
     * `AuthBridge` picks it up on a later run loop turn, so for a window after
     * the cache is cleared Swift still believes the session is live and will
     * happily hand back a token. This closes that window from the Kotlin side,
     * which is the side that opened it.
     */
    @Volatile
    private var generation: Int = 0

    /** Drop the in-memory copy. Called on sign-out, so the next session cannot
     *  be handed the last one's token from a cache that outlived it. */
    fun invalidate() {
        generation += 1
        cachedToken = null
        cachedExpiry = 0
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * The in-memory token if it is comfortably alive, else the stored one if IT
     * is — adopting it into the cache on the way past.
     */
    private fun readCacheIfFresh(): String? {
        val cutoff = nowSeconds() + REFRESH_WINDOW_SECONDS
        cachedToken?.let { if (cachedExpiry > cutoff) return it }

        val stored = storedToken() ?: return null
        val exp = expiryOf(stored)
        // exp == 0 means the token did not parse. Not treated as fresh: an
        // unreadable token is exactly what a truncated or corrupted defaults
        // write leaves behind, and crossing to Firebase repairs that while
        // trusting it would keep sending it for an hour.
        if (exp > cutoff) {
            cachedToken = stored
            cachedExpiry = exp
            return stored
        }
        return null
    }

    private fun storedToken(): String? =
        NSUserDefaults.standardUserDefaults
            .stringForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN)
            ?.takeIf { it.isNotBlank() }

    /**
     * One trip into Swift, traced.
     *
     * Traced on the CROSSING and never on the fast path, deliberately: the ring
     * is bounded at 40 entries, and a line per request would evict everything
     * else the push pipeline writes there within a minute. It also makes the
     * fast path directly observable — "no crossing lines" is the evidence that
     * a healthy token is not being re-fetched.
     */
    private suspend fun crossToSwift(forceRefresh: Boolean): String? {
        val call = resolver
        if (call == null) {
            PushTrace.log("auth:token no resolver registered")
            return null
        }
        // Captured before the crossing, compared after — see [generation].
        val startedAt = generation

        crossings += 1
        val answer = CompletableDeferred<String?>()
        call(forceRefresh) { token -> answer.complete(token?.takeIf { it.isNotBlank() }) }

        val token = withTimeoutOrNull(BRIDGE_TIMEOUT_MS) { answer.await() }
        if (token == null) {
            PushTrace.log("auth:token cross force=$forceRefresh → none")
            // The cache is cleared rather than left holding a token the refresh
            // has just declined to renew: if Firebase would not give us one, the
            // one we have is not something to keep serving from memory. The
            // stored key is untouched — Swift owns it.
            //
            // Cleared directly rather than through [invalidate], which would bump
            // the generation and invalidate a concurrent crossing that has done
            // nothing wrong. Failing to get a token is not a session ending.
            if (startedAt == generation) {
                cachedToken = null
                cachedExpiry = 0
            }
            return null
        }
        if (startedAt != generation) {
            // A sign-out landed while this was in flight. The token is real and
            // belongs to a session that is over: it is neither cached nor
            // returned, because a request authenticated as a user who has just
            // signed out is worse than an unauthenticated one.
            PushTrace.log("auth:token cross → dropped, session ended mid-flight")
            return null
        }
        cachedToken = token
        cachedExpiry = expiryOf(token)
        PushTrace.log("auth:token cross force=$forceRefresh → exp=$cachedExpiry")
        return token
    }

    private fun nowSeconds(): Long = Clock.System.now().epochSeconds

    /**
     * The `exp` claim, in Unix seconds, or 0 if it cannot be read.
     *
     * Reading the JWT rather than remembering when we fetched it, because the
     * two are not the same fact. Swift's `refreshTokenIfNeeded` writes the key
     * on every foreground and the resolver writes it too, so a "fetched at"
     * stamp kept here would be wrong about tokens this object did not fetch —
     * and rung 2 of [token] exists precisely to use those. The claim travels
     * with the token and is right for all of them.
     *
     * Shared with `NetworkModule`, which reads the `sub` claim off a rejected
     * token for the same underlying reason: see [JwtClaims].
     */
    private fun expiryOf(jwt: String): Long = JwtClaims.expiry(jwt)
}
