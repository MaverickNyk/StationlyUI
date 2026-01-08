package com.stationly.core.service

import com.stationly.core.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * TFL API Service Interface
 * 
 * This interface defines all the API endpoints needed for the Stationly app.
 * It mirrors the MindTheTimeAndroid TflApiService but is platform-agnostic.
 * 
 * Base URL: https://api.stationly.co.uk/StationlyBE/
 * (Same as MindTheTimeAndroid - no changes needed)
 */
interface TflApiService {
    suspend fun getModes(): List<TransportMode>
    suspend fun getLines(mode: String): List<LineInfo>
    suspend fun searchStations(searchKey: String): List<StationBrief>
    suspend fun getRoute(lineId: String): LineRouteResponse
    suspend fun getLineStatuses(lineId: String?): List<LineStatus>
}

/**
 * Ktor-based implementation of TflApiService
 * This will be used by all platforms (Android, iOS, Web)
 */
class TflApiServiceImpl : TflApiService {
    
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
            })
        }
    }
    
    private val baseUrl = "https://api.stationly.co.uk/StationlyBE/api/v1"
    
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
    
    override suspend fun getLineStatuses(lineId: String?): List<LineStatus> {
        return client.get("$baseUrl/lines/status") {
            lineId?.let { parameter("lineId", it) }
        }.body()
    }
}

/**
 * Factory for creating TflApiService instances
 * Allows for easy testing and platform-specific implementations
 */
object TflApiServiceFactory {
    fun create(): TflApiService {
        return TflApiServiceImpl()
    }
}