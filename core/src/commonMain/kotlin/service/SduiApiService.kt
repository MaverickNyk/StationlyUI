package com.stationly.core.service

import com.stationly.core.model.sdui.*
import com.stationly.core.platform.Platform
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * SDUI API Service Interface
 */
interface SduiApiService {
    suspend fun getSelectionLayout(track: String? = null): SduiAppScreen
    suspend fun getLoginLayout(): SduiAppScreen
    suspend fun getRegisterLayout(): SduiAppScreen
    suspend fun getForgotPasswordLayout(): SduiAppScreen
    suspend fun getAboutLayout(): SduiAppScreen
    suspend fun getHomeAnnouncement(): SduiAppScreen
    suspend fun getHomeConfig(): SduiStrings
    suspend fun getDropdownData(urlPath: String): List<SduiDropdownOption>
    suspend fun getNearbyStations(lat: Double, lon: Double, mode: String? = null): List<SduiDropdownOption>

    /**
     * Resolves the exact physical stop (naptanId) for a grouped station + mode + line + direction.
     * Returns the original [stationId] if no better match is found.
     */
    suspend fun resolveStation(stationId: String, mode: String, line: String, direction: String): String

    // Auth
    suspend fun sendPasswordResetEmail(email: String): Boolean

    // User Sync & Firestore
    suspend fun syncProfile(request: SyncProfileRequest): UserProfileResponse
    suspend fun syncStations(uid: String, stations: List<SubscribedStation>): Boolean
    suspend fun getUserProfile(uid: String): UserProfileResponse
    suspend fun logOut(uid: String): Boolean
    suspend fun deleteAccount(uid: String): Boolean
}

/**
 * Ktor-based implementation of SduiApiService
 */
class SduiApiServiceImpl(private val client: HttpClient) : SduiApiService {
    
    private val baseUrl = Platform.getBaseUrl() + "/api/v1"
    
    override suspend fun getSelectionLayout(track: String?): SduiAppScreen {
        val url = if (track != null) "$baseUrl/sdui/app/layout?track=$track" else "$baseUrl/sdui/app/layout"
        return client.get(url).body()
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

    override suspend fun getAboutLayout(): SduiAppScreen {
        return client.get("$baseUrl/sdui/app/about").body()
    }

    override suspend fun getHomeAnnouncement(): SduiAppScreen {
        return client.get("$baseUrl/sdui/app/home-announcement").body()
    }

    override suspend fun getHomeConfig(): SduiStrings {
        return client.get("$baseUrl/sdui/app/home-config").body()
    }

    override suspend fun getDropdownData(urlPath: String): List<SduiDropdownOption> {
        val fullUrl = if (urlPath.startsWith("http")) urlPath else "$baseUrl$urlPath"
        return client.get(fullUrl).body()
    }

    override suspend fun getNearbyStations(lat: Double, lon: Double, mode: String?): List<SduiDropdownOption> {
        val url = "$baseUrl/stations/nearby?lat=$lat&lon=$lon" + if (mode != null) "&mode=$mode" else ""
        return client.get(url).body()
    }

    override suspend fun resolveStation(stationId: String, mode: String, line: String, direction: String): String {
        return try {
            val response: kotlinx.serialization.json.JsonObject = client.get("$baseUrl/stations/resolve") {
                url {
                    parameters.append("station", stationId)
                    parameters.append("mode", mode)
                    parameters.append("line", line)
                    parameters.append("direction", direction)
                }
            }.body()
            (response["naptanId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: stationId
        } catch (_: Exception) { stationId }
    }

    override suspend fun sendPasswordResetEmail(email: String): Boolean {
        @kotlinx.serialization.Serializable
        data class ResetRequest(val email: String)

        val response = client.post("$baseUrl/auth/forgot-password") {
            contentType(ContentType.Application.Json)
            setBody(ResetRequest(email))
        }
        return response.status == HttpStatusCode.OK
    }

    override suspend fun syncProfile(request: SyncProfileRequest): UserProfileResponse {
        return client.post("$baseUrl/user/sync/profile") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    override suspend fun syncStations(uid: String, stations: List<SubscribedStation>): Boolean {
        val response = client.post("$baseUrl/user/sync/stations") {
            contentType(ContentType.Application.Json)
            setBody(SyncStationsRequest(uid, stations))
        }
        return response.status == HttpStatusCode.OK
    }

    override suspend fun getUserProfile(uid: String): UserProfileResponse {
        return client.get("$baseUrl/user/sync/profile?uid=$uid").body()
    }

    override suspend fun logOut(uid: String): Boolean {
        @kotlinx.serialization.Serializable
        data class LogOutRequest(val uid: String)

        val response = client.post("$baseUrl/user/logout") {
            contentType(ContentType.Application.Json)
            setBody(LogOutRequest(uid))
        }
        return response.status == HttpStatusCode.OK
    }

    override suspend fun deleteAccount(uid: String): Boolean {
        @kotlinx.serialization.Serializable
        data class DeleteAccountRequest(val uid: String)

        val response = client.post("$baseUrl/user/delete-account") {
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest(uid))
        }
        return response.status == HttpStatusCode.OK
    }
}

object SduiApiServiceFactory {
    fun create(): SduiApiService = NetworkModule.sduiApi
}
