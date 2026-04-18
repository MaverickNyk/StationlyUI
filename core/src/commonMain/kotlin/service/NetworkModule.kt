package com.stationly.core.service

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
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
        }
    }
    
    // Lazy API Service singletons
    val tflApi: TflApiService by lazy { TflApiServiceImpl(httpClient) }
    val sduiApi: SduiApiService by lazy { SduiApiServiceImpl(httpClient) }
}
