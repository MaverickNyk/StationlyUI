package com.stationly.core.service

import com.stationly.core.activity.ActivityEvents
import com.stationly.core.activity.ActivityLog
import com.stationly.core.platform.Platform
import com.stationly.core.util.JwtClaims
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.api.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import com.stationly.core.config.ReleaseGate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared Network Module
 *
 * Provides a single, pre-configured HttpClient to be used across the entire app.
 * Using a singleton here ensures unified connection pooling and shared interceptors.
 */
object NetworkModule {
    
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        coerceInputValues = true
    }

    // Fire-and-forget scope for the sign-out and its activity row. The decision
    // is made on the response path and must not block it; the work it triggers
    // reaches the keychain and the UI.
    private val authExpiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: HttpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 10000
            }

            // Shared Auth Interceptor for security (API Key + Firebase Token)
            install(StationlyAuth.Plugin)

            // Classifies 401s. Installed AFTER StationlyAuth so the bearer it
            // attaches is already on the request this can see and replace.
            install(authExpiryGuard(PlatformAuthExpiryActions))

            // Catches 426 Upgrade Required from ANY endpoint — the one path the
            // hard update gate actually rests on. See [upgradeRequiredGuard].
            install(upgradeRequiredGuard)

            expectSuccess = false
        }
    }

    /**
     * Leave evidence, then end the session.
     *
     * ## The uid comes from the REJECTED TOKEN, not from storage
     * Storage has usually been wiped by the time this runs — see [ActivityLog.persist]
     * for what that costs. Measured on device, disabling the account in the
     * console and foregrounding:
     *
     * ```
     * 22:45:42 token refresh → account gone, signing out        ← AuthBridge, first
     * 22:45:42 auth:forced-logout path=…/user/sync/profile …    ← this, after
     * ```
     *
     * Reading the key EARLIER did not fix it. That was tried, and the re-run
     * produced an empty uid again — the two verdicts land within the same
     * millisecond and there is no ordering to win.
     *
     * The token that was just rejected is immune to all of this. It carries the
     * uid in its `sub` claim, it is already in this function's hands, and no
     * teardown can erase it. It is also the more truthful answer: the row
     * describes a credential being refused, so the account it belongs to is the
     * one that credential was minted for — not whoever storage happens to name
     * afterwards.
     *
     * ## Why this is unconditional and permanent
     * Before this, a forced logout left no trace anywhere. The user found a login
     * screen; the activity table showed their session simply stopping; nothing
     * recorded which request had done it or what the server had actually said.
     * That absence is why the stale-token bug took several rounds to find — every
     * hypothesis looked equally consistent with the evidence, because there was
     * none. This is not scaffolding to be removed once the bug feels fixed. It is
     * the permanent answer to "why am I looking at a login screen", and the next
     * time this fires for a reason nobody predicted it will be the only thing
     * that says so.
     *
     * The matching `PushTrace` line is written by the iOS actual of
     * [Platform.signOutFromAuthExpiry], because the ring buffer is the channel
     * that can be pulled off a device that never uploaded its activity queue.
     */
    internal fun forceLogout(path: String, status: Int, accountGone: Boolean, bearer: String?) {
        val uid = bearer?.let { JwtClaims.subject(it) }
        authExpiryScope.launch {
            // ── The dedupe covers the ROW, and only the row ──
            //
            // A foreground fires several `/user/` calls at once, so a gone
            // account 401s all of them and each arrives here. The first device
            // run recorded the same forced logout TWICE, one second apart, which
            // turns the one event meant to answer "what ended this session" into
            // something that has to be counted before it can be read.
            //
            // The SIGN-OUT below is deliberately outside the window. An earlier
            // version returned early on a duplicate and so skipped the sign-out
            // too — which reads as a tidy-up and is actually a behaviour change:
            // a second, genuinely distinct `account_gone` arriving inside the
            // window would have been dropped entirely. Suppressing a log line is
            // housekeeping; suppressing the response to "this account is gone" is
            // not, and nothing about deduplicating a diagnostic should decide it.
            // `signOutFromAuthExpiry` is idempotent — it returns immediately when
            // there is no token left — so calling it every time costs nothing.
            //
            // A window rather than a permanent latch: this object outlives
            // sign-outs, and a latch would silently suppress the tripwire for a
            // LATER, genuinely separate logout in the same process — the exact
            // failure the tripwire exists to prevent.
            //
            // Under a mutex because the check and its stamp are one step: the
            // concurrent 401s this exists to collapse would otherwise all read
            // the old timestamp, all pass, and all record — which is the bug,
            // not the fix. (The same correction `UserSyncBridge.reconcileOnForeground`
            // needed, for the same reason.)
            val firstOfBurst = forcedLogoutMutex.withLock {
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastForcedLogoutAt < FORCED_LOGOUT_DEDUPE_MS) {
                    false
                } else {
                    lastForcedLogoutAt = now
                    true
                }
            }
            if (firstOfBurst) {
                ActivityLog.recordBlocking(
                    ActivityEvents.AUTH_FORCED_LOGOUT,
                    mapOf(
                        "path" to path,
                        "status" to status.toString(),
                        "account_gone" to accountGone.toString(),
                    ),
                    uid = uid,
                )
            }
            Platform.signOutFromAuthExpiry(path, status, accountGone)
        }
    }

    private val forcedLogoutMutex = Mutex()
    private var lastForcedLogoutAt: Long = 0

    /**
     * How long after one forced-logout row to treat another as the same event.
     *
     * Thirty seconds is longer than any burst of concurrent requests from one
     * foreground, and far shorter than any two sign-outs a human could cause —
     * the gap between them has to include noticing a login screen and signing
     * back in.
     */
    private const val FORCED_LOGOUT_DEDUPE_MS = 30_000L

    /**
     * The backend refused this build (426). Records it on the SAME scope the
     * forced-logout row uses.
     *
     * A second module-level `CoroutineScope` was the first version of this, and
     * it was a duplicate of [authExpiryScope] with the same lifetime, the same
     * dispatcher and the same one job. Two scopes doing one thing is two places
     * to leak and two things to reason about at teardown.
     */
    internal fun noteUpgradeRequired(path: String, minimumVersion: String) {
        authExpiryScope.launch {
            ActivityLog.recordBlocking(
                ActivityEvents.APP_UPGRADE_REQUIRED,
                mapOf(
                    "path" to path,
                    "min" to minimumVersion,
                    "installed" to Platform.appVersion(),
                ),
            )
        }
    }

    /** Recorded when a 401 was NOT allowed to end the session. See
     *  [AuthExpiryGuard] — this is the line that shows the guard doing its job,
     *  and its absence is what would make a silent regression look like nothing. */
    internal fun noteSurvived401(path: String, retried: Boolean) {
        // No uid capture here, and none needed: this path deliberately does NOT
        // end the session, so the key it would be read from is still there when
        // the row is written.
        authExpiryScope.launch {
            ActivityLog.recordBlocking(
                ActivityEvents.AUTH_401_SURVIVED,
                mapOf("path" to path, "retried" to retried.toString()),
            )
        }
    }
    
    // Lazy API Service singletons
    val tflApi: TflApiService by lazy { createTflApiService(httpClient) }
    val sduiApi: SduiApiService by lazy { SduiApiServiceImpl(httpClient) }
}

/**
 * What a 401 is allowed to mean.
 *
 * ## The default used to be "end the session", and that was backwards
 * The previous version signed the user out on ANY 401 outside a hand-kept list
 * of exempt paths. The list named the `/auth/` routes, the three `/sdui/app/`
 * login layouts and everything under `/user/sync/`; it did not name
 * `/user/activity/batch`, which is
 * precisely the endpoint the foreground activity upload and the nightly
 * `BGProcessingTask` both hit. Combined with an iOS token that was never
 * refreshed on the request path (see `IosAuthTokenAuthority`), that turned an
 * ordinary expired credential into a full sign-out roughly an hour after the
 * user last opened the app.
 *
 * A path allowlist could not have fixed it either, and adding
 * `/user/activity/batch` to the list would only have moved the bug to whichever
 * `/user/` endpoint was called next. The list was answering the wrong question: not "which paths are
 * exempt" but "what did the server actually say".
 *
 * ## The server says which kind of 401 this is, so ask it
 * `AuthMiddleware.validateUserToken` verifies with `checkRevoked: true` and
 * labels the failure: `account_gone` for a deleted, disabled or revoked user,
 * `token_invalid` for one that merely expired or was malformed. Only the first
 * is a fact about the ACCOUNT. So:
 *
 *  - **`account_gone` → sign out**, on any path, including the ones the old skip
 *    list exempted. A device left running on a deleted account keeps calling
 *    `/user/sync/profile`, so the one signal that could end that session used to be
 *    suppressed on the only path carrying it.
 *  - **anything else → refresh the token and retry once.** A bare 401 says the
 *    credential did not work, which is a thing to repair, not a person to log
 *    out.
 *  - **retry also 401s → record it and leave the session alone.**
 *
 * ## The asymmetry, which `AuthBridge.swift` argues throughout and this file
 * never applied
 * `settleAuthState`, `discardTornIdentity` and `isSessionGone` are all built on
 * one observation: the two failure directions do not cost the same. Missing a
 * real deletion for one more foreground is invisible — the account is gone, the
 * user is not using it, and the next refresh catches it. Taking a live account
 * away in front of somebody is not: it is unrecoverable without their password,
 * it happens at the moment they opened the app to use it, and to them it is
 * indistinguishable from the app losing their data.
 *
 * That argument was written on the Swift side and stopped at the language
 * boundary. This file was the one place still treating an unexplained failure as
 * proof of a sign-out, which is the same mistake `isSessionGone` exists to avoid
 * — and it is why enumerating "gone" and defaulting everything else to retry is
 * the correct shape here too, rather than enumerating the transient cases.
 *
 * ## Why this is a `Send` hook and not `HttpResponseValidator`
 * Two reasons, and the first is a bug the validator version had.
 *
 * `validateResponse` gave no way to read the body without taking it away from
 * the caller. `HttpResponse.bodyAsText()` consumes the response's content
 * channel, so the `account_gone` check was quietly stealing the body of every
 * 401 it inspected — `SduiApiService.syncProfile` calls `.body()`
 * unconditionally and would have got a consumed channel rather than the parse
 * error it expects. Worse, the read was wrapped in `runCatching { … }
 * .getOrDefault(false)`: if it ever failed, `account_gone` silently became
 * false. Here the call is buffered with `save()` first, so the body is read from
 * memory and the caller still gets a complete, re-readable response.
 *
 * Second, a retry has to re-issue the request, and `Send` is the only place that
 * can. This mirrors what Ktor's own `HttpRequestRetry` does.
 */
/**
 * Everything [authExpiryGuard] is allowed to do to the world outside the
 * request.
 *
 * ## Why this is an interface and not three direct calls
 * Because without it the policy is untestable, and it is the policy that decides
 * whether a user keeps their account. Reaching straight for `Platform` and
 * `NetworkModule` pins the guard to a `Platform` actual that needs Firebase and
 * an Android `Context` to exist at all, so no unit test can construct one — and
 * the two cases that matter cannot be produced against the real backend either:
 * `account_gone` needs a deleted account, and the retry branch needs a server
 * that rejects a freshly minted token, which a correct server never does.
 *
 * That is not a hypothetical gap. The retry path shipped from this session
 * having never executed once, on device or anywhere; see the session handover.
 * Three methods behind an interface is the whole cost of being able to say what
 * it does.
 */
internal interface AuthExpiryActions {
    /** Force a new token. Null when none can be had — offline, or no session. */
    suspend fun refreshToken(): String?

    /** The server says this account is gone. Record it and end the session. */
    fun onAccountGone(path: String, status: Int, bearer: String?)

    /** A 401 that was NOT allowed to end the session. */
    fun onSurvived(path: String, retried: Boolean)
}

/** The production wiring. A thin delegate on purpose — anything with a decision
 *  in it belongs in the guard, where the tests can reach it. */
internal object PlatformAuthExpiryActions : AuthExpiryActions {
    override suspend fun refreshToken(): String? =
        runCatching { Platform.refreshAuthToken() }.getOrNull()

    override fun onAccountGone(path: String, status: Int, bearer: String?) =
        NetworkModule.forceLogout(path, status, accountGone = true, bearer = bearer)

    override fun onSurvived(path: String, retried: Boolean) =
        NetworkModule.noteSurvived401(path, retried)
}

internal fun authExpiryGuard(
    actions: AuthExpiryActions,
) = createClientPlugin("StationlyAuthExpiryGuard") {
    on(Send) { request ->
        // Captured before `proceed`, for both of its readers below: whether this
        // request is retryable at all, and — if the account turns out to be gone
        // — whose uid to file the tripwire row against.
        val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")

        // Copied BEFORE proceeding, because `proceed` hands the builder to the
        // engine and a retry needs one that has not been through it. Same
        // approach as `HttpRequestRetry.prepareRequest`. The body has already
        // been rendered to `OutgoingContent` by the time a `Send` hook runs, so
        // the copy carries a re-sendable body — true for every request this app
        // makes, all of which are JSON or empty. It would NOT be true of a
        // streaming upload, and this would need revisiting if one is ever added.
        //
        // Only for requests carrying a bearer, because only those are ever
        // retried. This hook sits in front of EVERY call the app makes, and the
        // overwhelming majority — `/stations`, `/lines`, `/sdui`, the departure
        // fetches — are API-key-only and can never reach the retry below. Copying
        // a builder for all of them to serve the few that might 401 is work done
        // on the hot path for nothing.
        val retryable = bearer?.let { HttpRequestBuilder().takeFrom(request) }

        val call = proceed(request)
        if (call.response.status != HttpStatusCode.Unauthorized) return@on call

        val path = call.request.url.encodedPath

        // Buffer the whole response so reading it here does not consume it. The
        // 401 bodies this inspects are a two-field JSON error, so the memory
        // cost is nil, and it is paid only on 401 — every other response goes
        // back untouched above.
        val buffered = runCatching { call.save() }.getOrNull()
        val body = buffered?.let { runCatching { it.response.bodyAsText() }.getOrNull() }
        // If `save()` itself failed there is nothing to hand back but the
        // original, whose channel may now be partially read. That is a bad
        // outcome and an unreachable one in practice; it is preferred to
        // swallowing the call entirely.
        val settled = buffered ?: call

        // Matching the marker rather than parsing the envelope: the field is
        // `code`, but `getUserProfile` and the SDUI error shapes do not share a
        // body type, and a substring test cannot throw on a shape it did not
        // expect. A body that could not be read at all reads as "not gone",
        // which is the safe direction — see the asymmetry above.
        val accountGone = body?.contains("\"account_gone\"") == true

        if (accountGone) {
            // The bearer that was just refused — the tripwire reads its `sub`
            // claim for the uid, because storage will have been wiped by then.
            actions.onAccountGone(path, HttpStatusCode.Unauthorized.value, bearer)
            return@on settled
        }

        // ── Not gone. Refresh once and try again ──
        //
        // Only for a request that actually carried a bearer. `/auth/*` and the
        // login layouts are token-less by design (see `StationlyAuth`, which
        // attaches one only under `/user/`), so a 401 there is the server
        // talking about something else and a retry would just repeat it.
        if (bearer == null || retryable == null) return@on settled

        val fresh = actions.refreshToken()
        if (fresh.isNullOrBlank()) {
            // Offline, or no session to refresh. Deliberately NOT a sign-out:
            // "we could not get a new token just now" is the single most common
            // thing to be wrong about, and it is what happens on every train.
            actions.onSurvived(path, retried = false)
            return@on settled
        }

        // The header has to be replaced by hand. `StationlyAuth.onRequest` runs
        // in the REQUEST pipeline, which has already completed by the time a
        // `Send` hook is reached — so proceeding again re-sends the copied
        // builder verbatim, stale bearer included, unless this sets it.
        retryable.headers.remove(HttpHeaders.Authorization)
        retryable.headers.append(HttpHeaders.Authorization, "Bearer $fresh")

        // ── One retry, and the cost of it ──
        //
        // `HttpTimeout` applies per engine call, so a request that ends up here
        // can take its full 15 s, then a token refresh, then another 15 s. That
        // ceiling is only ever reached on a 401 whose retry also times out, and
        // the alternative — a shorter budget for the retry than the first
        // attempt — would make the repair fail on exactly the slow networks it
        // exists for. Nothing user-facing waits on this: the callers are the
        // activity upload and the foreground profile reconcile, both of which
        // already run detached.
        val retried = proceed(retryable)
        if (retried.response.status == HttpStatusCode.Unauthorized) {
            // A fresh token the server still rejects. It could be a revoked
            // account whose `account_gone` label did not survive, or a backend
            // fault, or a clock so far out that nothing we mint verifies — and
            // this cannot tell them apart. So it records and stops: one more
            // foreground of a session that may be dead costs nothing, and
            // `AuthBridge.refreshTokenIfNeeded` asks Google directly on the next
            // one, which CAN tell them apart.
            actions.onSurvived(path, retried = true)
        }
        retried
    }
}

/**
 * Turns a 426 from any endpoint into a blocking update verdict.
 *
 * ## Why the gate rests on this and not on the config document
 * The client also evaluates `/sdui/app/release-policy` locally, and for a
 * healthy client that is enough. It is not enough for the case the gate exists
 * for, because a document-only gate has three holes:
 *
 *  1. **It is one screen.** The check it replaces ran on the home screen after
 *     a config fetch, so a launch into a board, a widget tap or a deep link
 *     never reached it.
 *  2. **It can be cached away.** Offline, the client falls back to its last
 *     cached document, which predates the floor being raised.
 *  3. **It asks the broken build to police itself.** A floor is raised
 *     precisely when an old build is doing something the server cannot cope
 *     with. Trusting that build to evaluate a document correctly and stop is
 *     trusting the thing already concluded to be wrong.
 *
 * A 426 has none of those. It reaches every route, cannot come from a cache,
 * and needs no cooperation — the request simply does not succeed.
 *
 * ## Read, then handed back untouched
 * Same shape as [authExpiryGuard] and for the same reason: `bodyAsText()`
 * consumes the content channel, so the response is buffered with `save()` first
 * and the caller still receives a complete, re-readable one. The caller's own
 * error handling then runs as it always did — this guard changes what the app
 * SHOWS, never what a call site receives.
 *
 * ## Never a sign-out
 * Deliberately a status the auth stack does not use. A gate that borrowed 401
 * or 403 would collide with [authExpiryGuard], which ends sessions on some
 * 401s, and an old build would start logging people out as a side effect of
 * being old.
 */
internal val upgradeRequiredGuard = createClientPlugin("StationlyUpgradeRequiredGuard") {
    on(Send) { request ->
        val call = proceed(request)
        if (call.response.status != HttpStatusCode.UpgradeRequired) return@on call

        // Buffered so reading the body here does not take it from the caller.
        // Paid only on 426, which is a small JSON error and, in a correct
        // deployment, never happens at all.
        val buffered = runCatching { call.save() }.getOrNull()
        val settled = buffered ?: call
        val body = buffered?.let { runCatching { it.response.bodyAsText() }.getOrNull() }

        // Parsed leniently. The client must react to a 426 from a backend older
        // or newer than itself, and a strict decode that threw on an unexpected
        // shape would drop the one signal that cannot be missed. A body that
        // cannot be read at all still blocks — the STATUS is the fact, the body
        // only supplies the words and the links — and `ReleaseGate` falls back
        // to the cached document, then drops the block entirely if it ends up
        // with nowhere to send the user.
        val rejection = parseUpgradeRejection(body)

        ReleaseGate.onUpgradeRequired(rejection)
        NetworkModule.noteUpgradeRequired(
            path = call.request.url.encodedPath,
            minimumVersion = rejection.minimumVersion.orEmpty(),
        )

        settled
    }
}

/**
 * The fields of a 426 body, or nulls.
 *
 * Extracted from the plugin so it is reachable from a test — the plugin itself
 * needs a live engine, and every branch worth asserting here is a malformed
 * body, which a correct backend never sends. Blank and wrongly-typed values
 * collapse to null so that every consumer downstream is one `?:`.
 */
internal fun parseUpgradeRejection(body: String?): ReleaseGate.UpgradeRejection {
    val fields = body?.let {
        runCatching { NetworkModule.json.decodeFromString<Map<String, JsonElement>>(it) }.getOrNull()
    } ?: return ReleaseGate.UpgradeRejection()

    fun str(key: String): String? =
        (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    return ReleaseGate.UpgradeRejection(
        storeUrl = str("storeUrl"),
        storeUrlWeb = str("storeUrlWeb"),
        minimumVersion = str("minimumVersion"),
        title = str("title"),
        message = str("message"),
        cta = str("cta"),
    )
}

/**
 * Platform seam for predictions/line status. Android's actual is
 * `TflApiServiceImpl(httpClient)` verbatim — unchanged REST behaviour. iOS's
 * actual wraps it in [com.stationly.core.service.StreamBackedTflApiService]
 * so predictions/line status resolve over the live WebSocket stream instead
 * of REST, while every other endpoint (modes/lines/search/route) still goes
 * through the same REST implementation.
 */
expect fun createTflApiService(httpClient: HttpClient): TflApiService
