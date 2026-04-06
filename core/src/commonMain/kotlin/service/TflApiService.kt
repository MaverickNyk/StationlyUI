package com.stationly.core.service

import com.stationly.core.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*

/**
 * TFL API Service Interface
 * 
 * Base URL: https://api.stationly.co.uk/api/v1
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
    fun create(): TflApiService = NetworkModule.tflApi
}