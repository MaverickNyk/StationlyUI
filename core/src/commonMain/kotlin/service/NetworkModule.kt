package com.stationly.core.service

import com.stationly.core.platform.Platform
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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

    // Fire-and-forget scope for the 401 handler. Cannot suspend inside the response
    // validator without blocking the response pipeline.
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

            expectSuccess = false
            HttpResponseValidator {
                validateResponse { response ->
                    if (response.status == HttpStatusCode.Unauthorized) {
                        val path = response.call.request.url.encodedPath
                        // Skip sign-out for endpoints that are themselves part of the
                        // auth handshake — if /auth/* or /sdui/app/login is returning
                        // 401, signing the user out doesn't help, it creates an
                        // infinite loop where every subsequent request also 401s.
                        // Same for the user-sync endpoints called during onboarding;
                        // the LoginViewModel's own rollback handles those.
                        val isAuthEndpoint = path.contains("/auth/")
                            || path.endsWith("/sdui/app/login")
                            || path.endsWith("/sdui/app/register")
                            || path.endsWith("/sdui/app/forgot-password")
                            || path.contains("/user/sync/")
                        if (!isAuthEndpoint) {
                            authExpiryScope.launch { Platform.signOutFromAuthExpiry() }
                        }
                    }
                }
            }
        }
    }
    
    // Lazy API Service singletons
    val tflApi: TflApiService by lazy { createTflApiService(httpClient) }
    val sduiApi: SduiApiService by lazy { SduiApiServiceImpl(httpClient) }
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
