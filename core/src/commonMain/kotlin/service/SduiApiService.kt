package com.stationly.core.service

import com.stationly.core.activity.ActivityBatchRequest
import com.stationly.core.activity.ActivityUploadOutcome
import com.stationly.core.model.refresh.RefreshPolicy
import com.stationly.core.model.sdui.*
import com.stationly.core.platform.Platform
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Thrown when the backend reports a user profile no longer exists (HTTP 404).
 * Distinct from transient errors so callers (e.g. cross-device reconcile) can
 * react to a remote account deletion by forcing a logout, while treating
 * 429/5xx as "retry later" and leaving local state intact.
 */
class UserNotFoundException(message: String) : Exception(message)

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
    /**
     * App-wide theme tokens. Each call returns the canonical palette; the
     * Android side caches the response in SharedPrefs so cold launches
     * never block on this network call. See [SduiThemeTokens] docstring.
     */
    suspend fun getThemeTokens(): SduiThemeTokens

    /**
     * The cadence schedule for glanceable surfaces — see
     * [com.stationly.core.model.refresh.RefreshPolicy].
     *
     * Read on cold launch and when a `policy.update` push says the cached copy
     * is stale, NOT per refresh: the client caches the document and evaluates
     * it locally, so the schedule costs one small GET a day rather than a
     * request every time a widget wants to know what to do.
     */
    suspend fun getRefreshPolicy(): RefreshPolicy
    suspend fun getDropdownData(urlPath: String): List<SduiDropdownOption>
    suspend fun getNearbyStations(lat: Double, lon: Double, mode: String? = null): List<SduiDropdownOption>

    /**
     * Resolves the exact physical stop (naptanId) for a grouped station + mode + line + direction.
     * Returns the original [stationId] if no better match is found.
     */
    suspend fun resolveStation(stationId: String, mode: String, line: String, direction: String): String

    // Auth
    suspend fun sendPasswordResetEmail(email: String): Boolean
    /**
     * Triggers the backend to send a Stationly-branded verification email via Resend
     * (mirrors sendPasswordResetEmail). Identifies the user via their Firebase ID
     * token in the Authorization header — no email argument needed.
     */
    suspend fun sendVerificationEmail(): Boolean

    // User Sync & Firestore
    suspend fun syncProfile(request: SyncProfileRequest): UserProfileResponse
    /**
     * LEGACY board list — Android's write path. iOS uses [syncBoards].
     *
     * A full replace of `users/{uid}.stations`. Calling it from iOS would put
     * both platforms back on one array and reintroduce the cross-platform wipe
     * that splitting the lists exists to fix.
     */
    suspend fun syncStations(uid: String, stations: List<SubscribedStation>): Boolean
    /**
     * v2 board list. Full replace, guarded by [SyncBoardsRequest.updatedAt].
     *
     * A 200 with `applied = false` means the server declined the write and the
     * caller should re-read rather than retry — either it holds a NEWER list
     * (`reason = "stale"`), or the write would have emptied a non-empty list
     * without [allowEmpty] (`reason = "empty_rejected"`).
     *
     * The endpoint is platform-neutral by design: Android writes the legacy
     * `stations` array today and moves to this one unchanged when it adopts the
     * board model.
     */
    suspend fun syncBoards(
        boards: List<com.stationly.core.model.user.Board>,
        updatedAt: Long,
        allowEmpty: Boolean = false,
    ): SyncStateResponse
    suspend fun getUserProfile(uid: String): UserProfileResponse
    suspend fun logOut(uid: String, deviceId: String? = null): Boolean
    suspend fun deleteAccount(uid: String): Boolean

    /**
     * Upload a batch of queued activity events.
     *
     * Called on a schedule, never per action — see
     * [com.stationly.core.activity.ActivityLog]. Idempotent server-side, so a
     * batch whose response was lost is safe to resend.
     *
     * Returns the OUTCOME rather than a boolean because the caller's decision
     * is three-way, not two: a batch the server rejected as malformed must be
     * discarded, and one that failed transiently must be kept. Collapsing those
     * to "not ok" either loses events or retries a poisoned batch forever.
     */
    suspend fun uploadActivity(request: ActivityBatchRequest): ActivityUploadOutcome

    /**
     * Register / unregister this device's FCM token under the user's
     * profile. Required for `uid`-targeted admin notifications — the
     * server resolves UIDs to tokens via Firestore `users/{uid}/fcm_tokens`.
     * Both calls are idempotent; the client invokes register on cold
     * launch + on token rotation, and unregister on logout.
     */
    suspend fun registerFcmToken(token: String, platform: String = "android", appVersion: String? = null): Boolean
    suspend fun unregisterFcmToken(token: String): Boolean
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

    override suspend fun getThemeTokens(): SduiThemeTokens {
        return client.get("$baseUrl/sdui/app/theme-tokens").body()
    }

    override suspend fun getRefreshPolicy(): RefreshPolicy {
        return client.get("$baseUrl/sdui/app/refresh-policy").body()
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

    override suspend fun sendVerificationEmail(): Boolean {
        // Body is empty — the backend derives the user from the bearer token
        // already attached by StationlyAuth (Platform.getAuthToken()). Lives under
        // /user/* so the StationlyAuth Ktor plugin attaches the Firebase token;
        // /auth/* paths are token-less by convention (forgot-password, etc.).
        val response = client.post("$baseUrl/user/send-verification-email") {
            contentType(ContentType.Application.Json)
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

    override suspend fun syncBoards(
        boards: List<com.stationly.core.model.user.Board>,
        updatedAt: Long,
        allowEmpty: Boolean,
    ): SyncStateResponse {
        // No uid in the body: the server takes it from the validated bearer
        // token. This endpoint REPLACES a list the user cannot afford to lose,
        // so a self-asserted uid is not something it should ever accept.
        val response = client.post("$baseUrl/user/sync/boards") {
            contentType(ContentType.Application.Json)
            setBody(SyncBoardsRequest(boards, updatedAt, allowEmpty))
        }
        return if (response.status == HttpStatusCode.OK) response.body()
        else SyncStateResponse(success = false, applied = false, reason = "http_${response.status.value}")
    }

    override suspend fun uploadActivity(request: ActivityBatchRequest): ActivityUploadOutcome {
        val response = client.post("$baseUrl/user/activity/batch") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return when (response.status.value) {
            200 -> ActivityUploadOutcome.Accepted
            // 400 is the only status that means "this exact payload is wrong".
            // 401 will pass once the token refreshes and 429 once the window
            // rolls, so both keep the batch — and 5xx obviously does.
            400 -> ActivityUploadOutcome.Rejected
            else -> ActivityUploadOutcome.Retry
        }
    }

    override suspend fun getUserProfile(uid: String): UserProfileResponse {
        val response = client.get("$baseUrl/user/sync/profile?uid=$uid")
        // Distinguish "account gone" (404) from transient failures so callers
        // can force-logout on the former but safely retry the latter. Never
        // deserialize a non-200 body as a profile — a 401 "Unauthorized" JSON
        // has none of the required fields and would throw a confusing
        // MissingFieldException.
        return when (response.status) {
            HttpStatusCode.OK -> response.body()
            HttpStatusCode.NotFound ->
                throw UserNotFoundException("User profile not found — account deleted?")
            else ->
                throw IllegalStateException("getUserProfile failed: HTTP ${response.status.value}")
        }
    }

    override suspend fun logOut(uid: String, deviceId: String?): Boolean {
        @kotlinx.serialization.Serializable
        data class LogOutRequest(val uid: String, val deviceId: String? = null)

        val response = client.post("$baseUrl/user/logout") {
            contentType(ContentType.Application.Json)
            setBody(LogOutRequest(uid, deviceId))
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

    override suspend fun registerFcmToken(token: String, platform: String, appVersion: String?): Boolean {
        @kotlinx.serialization.Serializable
        data class RegisterFcmTokenRequest(val token: String, val platform: String, val appVersion: String?)
        val response = client.post("$baseUrl/user/fcm/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterFcmTokenRequest(token, platform, appVersion))
        }
        return response.status == HttpStatusCode.OK
    }

    override suspend fun unregisterFcmToken(token: String): Boolean {
        @kotlinx.serialization.Serializable
        data class UnregisterFcmTokenRequest(val token: String)
        val response = client.post("$baseUrl/user/fcm/unregister") {
            contentType(ContentType.Application.Json)
            setBody(UnregisterFcmTokenRequest(token))
        }
        return response.status == HttpStatusCode.OK
    }
}

object SduiApiServiceFactory {
    fun create(): SduiApiService = NetworkModule.sduiApi
}
