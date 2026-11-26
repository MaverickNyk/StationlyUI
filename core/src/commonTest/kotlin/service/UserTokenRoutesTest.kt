package com.stationly.core.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The signed-in user's token goes to the routes that demand it, and to no other. */
class UserTokenRoutesTest {

    private val seen = mutableListOf<HttpRequestData>()

    private val client = HttpClient(MockEngine { request ->
        seen += request
        respond(
            """{"ok":true,"recorded":true}""",
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }) {
        install(ContentNegotiation) { json(NetworkModule.json) }
        install(StationlyAuth.plugin(apiKey = { "key" }, identity = { "android;2.0;6" }, userToken = { "TOKEN" }))
    }

    @Test
    fun `a Play purchase is sent with the users token`() = runTest {
        val outcome = SduiApiServiceImpl(client).verifyPlayPurchase("uk.co.stationly.support.t4", "purchase")

        assertEquals(PlayVerifyOutcome.Credited(true), outcome)
        assertEquals("Bearer TOKEN", seen.single().headers[HttpHeaders.Authorization])
        assertEquals("key", seen.single().headers["X-Stationly-Key"])
    }

    @Test
    fun `user routes still carry it`() = runTest {
        client.get("https://example.test/api/v1/user/sync/profile?uid=u")
        assertEquals("Bearer TOKEN", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `public routes never do`() = runTest {
        for (path in listOf("modes", "lines/status", "stations/search", "sdui/app/home-config", "auth/forgot-password", "support-money/web")) {
            client.get("https://example.test/api/v1/$path")
        }
        seen.forEach { assertNull(it.headers[HttpHeaders.Authorization], it.url.encodedPath) }
    }
}
