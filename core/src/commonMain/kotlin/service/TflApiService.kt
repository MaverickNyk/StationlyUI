package com.stationly.core.service

import com.stationly.core.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json

/**
 * TFL API Service Interface
 * 
 * This interface defines all the API endpoints needed for the Stationly app.
 * It mirrors the MindTheTimeAndroid TflApiService but is platform-agnostic.
 * 
 * Base URL: https://api.stationly.co.uk/api/v1
 * (New Unified Backend)
 */
interface TflApiService {
    suspend fun getModes(): List<TransportMode>
    suspend fun getLines(mode: String): List<LineInfo>
    suspend fun searchStations(searchKey: String): List<StationBrief>
    suspend fun getRoute(lineId: String): LineRouteResponse
    suspend fun getLineStatuses(lineId: String?, mode: String? = null): List<LineStatus>
    suspend fun getPredictions(naptanId: String): FcmPayload
}

/**
 * Ktor-based implementation of TflApiService
 * This will be used by all platforms (Android, iOS, Web)
 */
class TflApiServiceImpl(private val client: HttpClient) : TflApiService {
    
    private val baseUrl = "https://api.stationly.co.uk/api/v1"
    
    override suspend fun getModes(): List<TransportMode> {
        return client.get("$baseUrl/modes").body()
    }
    
    override suspend fun getLines(mode: String): List<LineInfo> {
        return client.get("$baseUrl/lines/mode/$mode").body()
    }
    
    override suspend fun searchStations(searchKey: String): List<StationBrief> {
        return client.get("$baseUrl/stations/search") {
            parameter("searchKey", searchKey)
        }.body()
    }
    
    override suspend fun getRoute(lineId: String): LineRouteResponse {
        return client.get("$baseUrl/lines/$lineId/route").body()
    }
    
    override suspend fun getLineStatuses(lineId: String?, mode: String?): List<LineStatus> {
        return client.get("$baseUrl/lines/status") {
            lineId?.let { parameter("lineId", it) }
            mode?.let { parameter("mode", it) }
        }.body()
    }
    override suspend fun getPredictions(naptanId: String): FcmPayload {
        return client.get("$baseUrl/stations/predictions/$naptanId").body()
    }
}

object TflApiServiceFactory {
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
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 10000
            }
        }
    }

    fun create(): TflApiService {
        return TflApiServiceImpl(client)
    }
}