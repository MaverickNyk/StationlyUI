package com.stationly.core.service

import com.stationly.core.model.*
import com.stationly.core.platform.LiveStreamManager

/**
 * iOS's [TflApiService]: predictions and line status — the two live,
 * repeatedly-fetched endpoints — are served entirely by
 * [LiveStreamManager] (the WebSocket stream), never by REST. Everything
 * else (mode/line lookup, station search, route info — one-shot dropdown
 * data, not live board data) delegates straight to [rest] unchanged.
 */
class StreamBackedTflApiService(private val rest: TflApiService) : TflApiService {

    override suspend fun getModes(): List<TransportMode> = rest.getModes()
    override suspend fun getLines(mode: String): List<LineInfo> = rest.getLines(mode)
    override suspend fun searchStations(searchKey: String): List<StationBrief> = rest.searchStations(searchKey)
    override suspend fun getRoute(lineId: String): LineRouteResponse = rest.getRoute(lineId)

    override suspend fun getLineStatuses(lineId: String?, mode: String?): List<LineStatus> {
        // Both null-checks matter: a query missing either half is the
        // "all lines for a mode" / "everything" shape, which the stream has no
        // subscription for and REST still serves.
        if (lineId == null || mode == null) return rest.getLineStatuses(lineId, mode)
        return LiveStreamManager.ensureLine(lineId)
    }

    override suspend fun getPredictions(naptanId: String): FcmPayload =
        LiveStreamManager.ensureStation(naptanId)
}
