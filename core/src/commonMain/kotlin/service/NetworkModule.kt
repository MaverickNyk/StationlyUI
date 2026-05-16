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
                        // Sign out asynchronously — the UI subscribes to the resulting
                        // FirebaseAuth state change and navigates to login. Platform
                        // implementation no-ops if there's no signed-in user, so this
                        // is safe to call for every 401.
                        authExpiryScope.launch { Platform.signOutFromAuthExpiry() }
                    }
                }
            }
        }
    }
    
    // Lazy API Service singletons
    val tflApi: TflApiService by lazy { TflApiServiceImpl(httpClient) }
    val sduiApi: SduiApiService by lazy { SduiApiServiceImpl(httpClient) }
}
