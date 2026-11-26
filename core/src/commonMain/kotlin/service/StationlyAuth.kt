package com.stationly.core.service

import com.stationly.core.platform.Platform
import io.ktor.client.plugins.api.*
import io.ktor.http.*

object StationlyAuth {

    /**
     * How this build identifies itself to the backend: `<platform>;<version>;<build>`.
     *
     * ## Why every request and not just the config fetch
     * This is what `versionGateMiddleware` reads to decide whether the backend
     * will still serve this client. Putting it on one endpoint would recreate
     * the hole the server-side gate exists to close: a client that only
     * identifies itself when it asks for config can only be refused when it asks
     * for config, and the whole point is that a build the server cannot serve is
     * refused on the routes it actually calls.
     *
     * Cheap enough to be unconditional — three short strings the platform reads
     * from its own bundle, no I/O — and useful well beyond the gate: every
     * request log line now names the exact binary that made it.
     *
     * Lower-cased platform because the server matches on the literals `ios` and
     * `android`, and anything else it does not recognise passes ungated. That
     * is the safe direction (see `parseClientIdentity`), but it would also mean
     * a capitalisation slip here silently disables the gate for the whole
     * platform, which is why the value is derived rather than typed twice.
     *
     * ## Memoised, but NEVER on a fallback value
     * `by lazy` was the first version and it was a lockout waiting to happen.
     * Android's `Platform.appVersion()` reads through `appContext`, a `lateinit`
     * that is set in `Platform.initialize()`; if anything issues a request
     * before that lands, the read fails, the actual returns its `"0"` fallback,
     * and `lazy` caches `"0"` for the life of the process. A client reporting
     * version 0 is below every possible floor, so the gate would block a
     * perfectly current build — permanently, and only on whichever launches lost
     * that race.
     *
     * So the value is cached only once it is real, and recomputed until then.
     * The recompute is three string reads and, on Android, one `PackageManager`
     * call; it happens at most a handful of times before the first valid answer
     * latches, and never again.
     */
    private var cachedIdentity: String? = null

    private fun clientIdentity(): String {
        cachedIdentity?.let { return it }

        val version = Platform.appVersion()
        val identity = "${Platform.getPlatformName().lowercase()};$version;${Platform.appBuild()}"

        // `UNREADABLE_VERSION` is what both actuals return when the bundle or
        // the package cannot be read, so it is the one answer never worth
        // keeping.
        if (version != UNREADABLE_VERSION) cachedIdentity = identity
        return identity
    }

    /** The version both `Platform.appVersion()` actuals fall back to. */
    private const val UNREADABLE_VERSION = "0"

    /**
     * Whether the backend route at [path] sits behind `validateUserToken`.
     *
     * Everything under `/user/`, and the one route outside it this client calls.
     * Missing that one meant every Play purchase was refused with a 401, so it
     * was never recorded and Google refunded it. The backend has others
     * (`/sdui/app/profile/:uid`, `/device/register`): add a route here when a
     * shared call to it is added.
     */
    internal fun needsUserToken(path: String): Boolean =
        path.contains("/user/") || path.endsWith("/support-money/verify-play")

    val Plugin = plugin(
        apiKey = { Platform.getApiKey() },
        identity = { clientIdentity() },
        userToken = { Platform.getAuthToken() },
    )

    /** [Plugin] with its three values passed in, so a test can look at a real request. */
    internal fun plugin(
        apiKey: () -> String,
        identity: () -> String,
        userToken: suspend () -> String?,
    ) = createClientPlugin("StationlyAuthPlugin") {
        onRequest { request, _ ->
            // LAYER 1: Global API Key
            request.headers.append("X-Stationly-Key", apiKey())

            // LAYER 2: Which build is asking. Read by the server-side version
            // gate; see `clientIdentity`.
            request.headers.append("X-Stationly-Client", identity())

            // LAYER 3: Firebase Auth (only where the backend asks for it)
            if (needsUserToken(request.url.encodedPath)) {
                val token = userToken()
                if (token != null) {
                    request.headers.append(HttpHeaders.Authorization, "Bearer $token")
                }
            }
        }
    }
}
