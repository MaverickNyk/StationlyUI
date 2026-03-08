package com.stationly.core.service

import com.stationly.core.model.sdui.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.contentType
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json

interface SduiApiService {
    suspend fun getSelectionLayout(): SduiAppScreen
    suspend fun getLoginLayout(): SduiAppScreen
    suspend fun getRegisterLayout(): SduiAppScreen
    suspend fun getForgotPasswordLayout(): SduiAppScreen
    suspend fun getDropdownData(url: String): List<SduiDropdownOption>
    
    // User Sync & Firestore
    suspend fun syncProfile(request: SyncProfileRequest): UserProfileResponse
    suspend fun syncStations(uid: String, stations: List<SubscribedStation>): Boolean
    suspend fun getUserProfile(uid: String): UserProfileResponse
    suspend fun logOut(uid: String): Boolean
}

class SduiApiServiceImpl(private val client: HttpClient) : SduiApiService {
    
    // Reverted to localhost to work with adb reverse tcp:3000 tcp:3000
    private val baseUrl = "http://localhost:3000/api/v1"
    
    override suspend fun getSelectionLayout(): SduiAppScreen {
        return client.get("$baseUrl/sdui/app/layout").body()
    }
    
    override suspend fun getLoginLayout(): SduiAppScreen {
        return client.get("$baseUrl/sdui/app/login").body()
    }

    override suspend fun getRegisterLayout(): SduiAppScreen {
        return client.get("$baseUrl/sdui/app/register").body()
    }

    override suspend fun getForgotPasswordLayout(): SduiAppScreen {
        return client.get("$baseUrl/sdui/app/forgot-password").body()
    }
    
    override suspend fun getDropdownData(urlPath: String): List<SduiDropdownOption> {
        val fullUrl = if (urlPath.startsWith("http")) urlPath else "$baseUrl$urlPath"
        return client.get(fullUrl).body()
    }

    override suspend fun syncProfile(request: SyncProfileRequest): UserProfileResponse {
        return client.post("$baseUrl/user/sync/profile") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    override suspend fun syncStations(uid: String, stations: List<SubscribedStation>): Boolean {
        val response = client.post("$baseUrl/user/sync/stations") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(SyncStationsRequest(uid, stations))
        }
        return response.status == io.ktor.http.HttpStatusCode.OK
    }

    override suspend fun getUserProfile(uid: String): UserProfileResponse {
        return client.get("$baseUrl/user/sync/profile?uid=$uid").body() // Simplified, backend might need query or body
    }

    override suspend fun logOut(uid: String): Boolean {
        @kotlinx.serialization.Serializable
        data class LogOutRequest(val uid: String)
        
        val response = client.post("$baseUrl/user/logout") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(LogOutRequest(uid))
        }
        return response.status == io.ktor.http.HttpStatusCode.OK
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
