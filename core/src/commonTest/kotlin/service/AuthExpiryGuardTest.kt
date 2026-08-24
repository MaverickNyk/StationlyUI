package com.stationly.core.service

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 401 policy, driven against scripted responses.
 *
 * These exist because the two branches that matter cannot be produced against
 * the real backend: `account_gone` needs a deleted account, and the retry branch
 * needs a server that rejects a token it has just accepted. The retry shipped
 * from the session that wrote it having never executed once — on device or
 * anywhere — which is exactly the kind of thing that is only discovered when
 * somebody's session ends for the wrong reason.
 */
class AuthExpiryGuardTest {

    private class RecordingActions(
        private val refreshed: String? = "fresh-token",
    ) : AuthExpiryActions {
        var refreshCalls = 0
        var goneCalls = mutableListOf<Triple<String, Int, String?>>()
        var survivedCalls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun refreshToken(): String? {
            refreshCalls++
            return refreshed
        }

        override fun onAccountGone(path: String, status: Int, bearer: String?) {
            goneCalls += Triple(path, status, bearer)
        }

        override fun onSurvived(path: String, retried: Boolean) {
            survivedCalls += path to retried
        }
    }

    /** Every request the engine saw, so a retry is visible as a second entry. */
    private class Script(private val responses: List<Pair<HttpStatusCode, String>>) {
        val seen = mutableListOf<HttpRequestData>()
        fun handler(): MockEngineConfig.() -> Unit = {
            addHandler { request ->
                seen += request
                val (status, body) = responses[minOf(seen.size - 1, responses.size - 1)]
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
    }

    private fun client(script: Script, actions: AuthExpiryActions) = HttpClient(MockEngine) {
        engine(script.handler())
        expectSuccess = false
        install(authExpiryGuard(actions))
    }

    private suspend fun HttpClient.getUser(bearer: String? = TOKEN) =
        get("https://example.test/api/v1/user/sync/profile") {
            if (bearer != null) headers.append(HttpHeaders.Authorization, "Bearer $bearer")
        }

    // ── the ordinary case ────────────────────────────────────────────────────

    @Test
    fun successPassesThroughUntouched() = runTest {
        val script = Script(listOf(HttpStatusCode.OK to """{"ok":true}"""))
        val actions = RecordingActions()
        val response = client(script, actions).getUser()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"ok":true}""", response.bodyAsText())
        assertEquals(1, script.seen.size, "a 200 must not be retried")
        assertEquals(0, actions.refreshCalls)
        assertTrue(actions.goneCalls.isEmpty())
        assertTrue(actions.survivedCalls.isEmpty())
    }

    // ── account_gone: the one thing allowed to end a session ─────────────────

    @Test
    fun accountGoneEndsTheSessionAndDoesNotRetry() = runTest {
        val script = Script(listOf(HttpStatusCode.Unauthorized to GONE_BODY))
        val actions = RecordingActions()
        client(script, actions).getUser()

        assertEquals(1, actions.goneCalls.size)
        val (path, status, bearer) = actions.goneCalls.single()
        assertEquals("/api/v1/user/sync/profile", path)
        assertEquals(401, status)
        // The rejected bearer is handed over so the tripwire can read its `sub`
        // claim — storage is wiped by the time the row is written.
        assertEquals(TOKEN, bearer)
        assertEquals(0, actions.refreshCalls, "a gone account must not be refreshed")
        assertEquals(1, script.seen.size, "a gone account must not be retried")
    }

    /**
     * The regression that motivated moving off `HttpResponseValidator`: reading
     * the body to classify it used to CONSUME it, so the caller got an empty or
     * already-read channel.
     */
    @Test
    fun callerCanStillReadTheBodyAfterTheGuardInspectedIt() = runTest {
        val script = Script(listOf(HttpStatusCode.Unauthorized to GONE_BODY))
        val response = client(script, RecordingActions()).getUser()

        assertEquals(GONE_BODY, response.bodyAsText())
        // Twice, because a buffered call must be re-readable, not merely readable
        // once more than before.
        assertEquals(GONE_BODY, response.bodyAsText())
    }

    // ── a bare 401 is a credential problem, not a person problem ─────────────

    @Test
    fun plainUnauthorizedRefreshesAndRetriesOnceWithTheNewBearer() = runTest {
        val script = Script(
            listOf(
                HttpStatusCode.Unauthorized to INVALID_BODY,
                HttpStatusCode.OK to """{"ok":true}""",
            )
        )
        val actions = RecordingActions(refreshed = "brand-new-token")
        val response = client(script, actions).getUser()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, script.seen.size, "exactly one retry")
        assertEquals(1, actions.refreshCalls)
        assertEquals(
            "Bearer brand-new-token",
            script.seen[1].headers[HttpHeaders.Authorization],
            "the retry must carry the REFRESHED bearer, not the one that just failed",
        )
        assertTrue(actions.goneCalls.isEmpty(), "an expired token is not a gone account")
        assertTrue(actions.survivedCalls.isEmpty(), "a retry that worked is not a survival")
    }

    /** The retry must not lose anything else the request was carrying. */
    @Test
    fun retryPreservesMethodBodyAndOtherHeaders() = runTest {
        val script = Script(
            listOf(
                HttpStatusCode.Unauthorized to INVALID_BODY,
                HttpStatusCode.OK to "{}",
            )
        )
        val actions = RecordingActions(refreshed = "t2")
        client(script, actions).post("https://example.test/api/v1/user/activity/batch") {
            headers.append(HttpHeaders.Authorization, "Bearer $TOKEN")
            headers.append("X-Stationly-Key", "api-key")
            setBody("""{"deviceId":"d","events":[]}""")
        }

        assertEquals(2, script.seen.size)
        val retry = script.seen[1]
        assertEquals(HttpMethod.Post, retry.method)
        assertEquals("api-key", retry.headers["X-Stationly-Key"])
        assertEquals("/api/v1/user/activity/batch", retry.url.encodedPath)
        assertEquals(
            """{"deviceId":"d","events":[]}""",
            (retry.body as io.ktor.http.content.TextContent).text,
        )
    }

    @Test
    fun refreshFailingLeavesTheSessionAlone() = runTest {
        val script = Script(listOf(HttpStatusCode.Unauthorized to INVALID_BODY))
        val actions = RecordingActions(refreshed = null)
        client(script, actions).getUser()

        assertEquals(1, script.seen.size, "nothing new to send, so nothing to retry")
        assertEquals(listOf("/api/v1/user/sync/profile" to false), actions.survivedCalls)
        assertTrue(actions.goneCalls.isEmpty(), "offline is not a deleted account")
    }

    @Test
    fun aRetryThatAlso401sRecordsButDoesNotSignOut() = runTest {
        val script = Script(listOf(HttpStatusCode.Unauthorized to INVALID_BODY))
        val actions = RecordingActions(refreshed = "still-rejected")
        client(script, actions).getUser()

        assertEquals(2, script.seen.size)
        assertEquals(listOf("/api/v1/user/sync/profile" to true), actions.survivedCalls)
        assertTrue(
            actions.goneCalls.isEmpty(),
            "an unexplained 401 must never end a session, however many times it repeats",
        )
    }

    // ── requests with no credential ──────────────────────────────────────────

    @Test
    fun tokenlessRequestIsNeitherRetriedNorSignedOut() = runTest {
        val script = Script(listOf(HttpStatusCode.Unauthorized to NO_CODE_BODY))
        val actions = RecordingActions()
        client(script, actions).getUser(bearer = null)

        assertEquals(1, script.seen.size, "/auth/* and the login layouts carry no token")
        assertEquals(0, actions.refreshCalls)
        assertTrue(actions.goneCalls.isEmpty())
        assertTrue(actions.survivedCalls.isEmpty())
    }

    /**
     * A gone account must be honoured even where the old path allowlist used to
     * exempt it — `/user/sync/` is exactly what a device left running on a
     * deleted account keeps calling.
     */
    @Test
    fun accountGoneIsHonouredOnFormerlyExemptPaths() = runTest {
        for (path in listOf("/api/v1/user/sync/profile", "/api/v1/user/sync/boards")) {
            val script = Script(listOf(HttpStatusCode.Unauthorized to GONE_BODY))
            val actions = RecordingActions()
            client(script, actions).get("https://example.test$path") {
                headers.append(HttpHeaders.Authorization, "Bearer $TOKEN")
            }
            assertEquals(1, actions.goneCalls.size, path)
        }
    }

    /** An unreadable or empty body must read as "not gone" — the safe direction. */
    @Test
    fun anUnlabelledBodyIsNeverTreatedAsGone() = runTest {
        for (body in listOf("", "not json at all", """{"error":"Unauthorized"}""")) {
            val script = Script(listOf(HttpStatusCode.Unauthorized to body))
            val actions = RecordingActions(refreshed = null)
            client(script, actions).getUser()
            assertTrue(actions.goneCalls.isEmpty(), "body=<$body>")
        }
    }

    /** A non-401 failure is none of this plugin's business. */
    @Test
    fun otherErrorStatusesAreIgnored() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound,
                              HttpStatusCode.InternalServerError, HttpStatusCode.TooManyRequests)) {
            val script = Script(listOf(status to GONE_BODY))
            val actions = RecordingActions()
            val response = client(script, actions).getUser()
            assertEquals(status, response.status)
            assertEquals(1, script.seen.size, "$status must not be retried")
            assertTrue(actions.goneCalls.isEmpty(), "$status is not an auth verdict")
            assertNull(actions.survivedCalls.firstOrNull(), "$status is not a 401")
        }
    }

    private companion object {
        const val TOKEN = "stale.token.value"
        const val GONE_BODY =
            """{"error":"Unauthorized","code":"account_gone","message":"This account is no longer active."}"""
        const val INVALID_BODY =
            """{"error":"Unauthorized","code":"token_invalid","message":"Invalid Firebase ID Token."}"""
        const val NO_CODE_BODY =
            """{"error":"Unauthorized","message":"Missing or invalid Authorization header."}"""
    }
}
