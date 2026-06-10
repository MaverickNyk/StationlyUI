package com.stationly.core.model

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

/**
 * Transport mode (Tube, DLR, Overground, etc.)
 * Mirrors the MindTheTimeAndroid TransportMode data class
 */
@Serializable
data class TransportMode(
    val modeName: String,
    val displayName: String
)

/**
 * Line information
 * Mirrors the MindTheTimeAndroid LineInfo data class
 */
@Serializable
data class LineInfo(
    val id: String,
    val name: String,
    val modeName: String
)

/**
 * Station brief information with associated lines
 * Mirrors the MindTheTimeAndroid StationBrief data class
 */
@Serializable
data class StationBrief(
    val naptanId: String,
    val commonName: String,
    val lines: List<LineSummary>? = null
) {
    @Serializable
    data class LineSummary(
        val id: String,
        val name: String
    )
    
    // Helper method to safely get lines
    fun getLinesOrEmpty(): List<LineSummary> = lines ?: emptyList()
}

/**
 * Line route response with directions and destinations
 * Mirrors the MindTheTimeAndroid LineRouteResponse data class
 */
@Serializable
data class LineRouteResponse(
    val id: String,
    val name: String,
    val modeName: String,
    val directions: List<DirectionInfo>
) {
    @Serializable
    data class Destination(
        val id: String,
        val name: String
    )
    
    @Serializable
    data class DirectionInfo(
        val direction: String,
        val destinations: List<Destination>
    )
}

/**
 * User selection - saved station configuration
 * This is the core model that drives the widget and app
 * Mirrors the MindTheTimeAndroid UserSelection data class
 */
@Serializable
data class UserSelection(
    val mode: String,
    val line: String,
    val station: String,
    val stationName: String,
    val direction: String,
    val destinations: List<String>, // Keep names for display
    val destinationIds: List<String> // Add IDs for accurate matching
)

/**
 * Line status information
 * Mirrors the MindTheTimeAndroid LineStatus data class
 */
@Serializable
data class LineStatus(
    val id: String,
    val name: String,
    val mode: String,
    val statusSeverityDescription: String,
    val reason: String?,
    val lastUpdatedTime: String
)

/**
 * FCM payload for real-time predictions
 * Mirrors the MindTheTimeAndroid FcmPayload data class
 */
@Serializable
data class FcmPayload(
    val lines: Map<String, LineData>,
    val id: String,
    val name: String,
    val lut: String // Last update time
)

/**
 * Line data within FCM payload
 * Mirrors the MindTheTimeAndroid LineData data class
 */
@Serializable
data class LineData(
    val id: String,
    val name: String,
    val dirs: Map<String, DirectionPredictions>
)

/**
 * Direction predictions within FCM payload
 * Mirrors the MindTheTimeAndroid DirectionPredictions data class
 */
@Serializable
data class DirectionPredictions(
    val preds: List<PredictionItem>
)

/**
 * Individual prediction item
 * Mirrors the MindTheTimeAndroid PredictionItem data class
 */
@Serializable
data class PredictionItem(
    val destId: String = "",
    val displayName: String = "",
    val platform: String = "",
    val eta: String = "",
    val stopLetter: String? = null
)

/**
 * Widget state - used for rendering widget UI
 * This is NEW for StationlyUI to standardize widget data
 */
@Serializable
data class WidgetState(
    val stationName: String,
    val lineName: String,
    val predictions: List<PredictionDisplay>,
    val status: String?,
    val lastUpdated: Long, // Unix timestamp
    // Selection direction (e.g. "eastbound"). Optional + defaulted so every
    // existing call site (Android included) is unchanged; the iOS widget uses it
    // to render the "Line: Platform N (Direction)" header like Android's board.
    val direction: String = ""
)

/**
 * Prediction display format for widget
 * NEW for StationlyUI - formatted for widget display
 */
@Serializable
data class PredictionDisplay(
    val destination: String,
    val platform: String,
    val eta: String, // "Due", "X min", etc. — formatted at receipt time.
    val isDue: Boolean,
    val stopLetter: String? = null,
    /**
     * Absolute arrival time as epoch millis. Lets the UI tick the
     * minutes-remaining label locally between FCM pushes — at 12:25 with
     * targetEpochMs=12:30 the row reads "5 min", at 12:26 it ticks to
     * "4 min" without waiting for a new FCM. Nullable as a defensive
     * fallback: if the FCM payload's ISO timestamp fails to parse,
     * consumers fall through to the stored `eta` string instead of
     * dropping the row.
     */
    val targetEpochMs: Long? = null,
)

/**
 * API Response wrapper
 * NEW for StationlyUI - standardizes API responses
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: String? = null
)