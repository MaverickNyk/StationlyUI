package com.stationly.core.service

import com.stationly.core.model.sdui.SduiAppScreen
import com.stationly.core.model.sdui.SduiDropdownOption
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json

interface SduiApiService {
    suspend fun getSelectionLayout(): SduiAppScreen
    suspend fun getDropdownData(url: String): List<SduiDropdownOption>
}

class SduiApiServiceImpl(private val client: HttpClient) : SduiApiService {
    
    // Using localhost since we will reverse port-forward 3000 to the physical device using adb
    private val baseUrl = "http://localhost:3000"
    
    override suspend fun getSelectionLayout(): SduiAppScreen {
        return client.get("$baseUrl/sdui/app/layout").body()
    }
    
    override suspend fun getDropdownData(urlPath: String): List<SduiDropdownOption> {
        val fullUrl = if (urlPath.startsWith("http")) urlPath else "$baseUrl$urlPath"
        return client.get(fullUrl).body()
    }
}

object SduiApiServiceFactory {
    private val client by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 10000
            }
        }
    }

    fun create(): SduiApiService {
        return SduiApiServiceImpl(client)
    }
}
