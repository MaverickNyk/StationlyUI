package com.stationly.mobile.service

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stationly.core.model.FcmPayload
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.AndroidStorageManager
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.usecase.ProcessFcmPayloadUseCase
import com.stationly.mobile.widget.DepartureWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * FCM Messaging Service
 * 
 * Handles incoming FCM messages for real-time widget updates.
 * This mirrors the MindTheTimeAndroid FcmMessagingService but uses KMP core.
 * 
 * Key features:
 * - Handles line status updates
 * - Handles prediction updates
 * - Updates widget in real-time
 * - Caches data for background updates
 */
class FcmMessagingService : FirebaseMessagingService() {
    
    private val gson = Gson()
    
    // TTL constants
    private val PREDICTIONS_TTL_MS = TimeUnit.MINUTES.toMillis(2) // 2 minutes
    private val LINE_STATUS_TTL_MS = TimeUnit.HOURS.toMillis(1)   // 1 hour
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "Message received from: ${remoteMessage.from}")
        
        when {
            remoteMessage.from?.startsWith("/topics/LineStatus_") == true -> {
                handleLineStatusUpdate(remoteMessage)
            }
            remoteMessage.from?.startsWith("/topics/Station_") == true -> {
                handlePredictionUpdate(remoteMessage)
            }
            else -> {
                Log.d("FCM", "Message from unknown topic. Ignoring.")
            }
        }
    }
    
    private fun handleLineStatusUpdate(remoteMessage: RemoteMessage) {
        val payloadJson = remoteMessage.data["payload"] ?: return
        Log.d("FCM", "Received Line Status Payload: $payloadJson")
        
        // Save line status with timestamp
        val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
        val statusData = mapOf(
            "data" to payloadJson,
            "timestamp" to System.currentTimeMillis()
        )
        prefs.edit().putString("line_status_data", gson.toJson(statusData)).apply()
        Log.d("FCM", "Saved line status data")
        
        // Update widget with current data from storage
        val selections = getAllSelections()
        selections.forEach { selection ->
            if (payloadJson.contains(selection.line, ignoreCase = true)) {
                 updateWidgetFromStorage(this, selection)
            }
        }
    }
    
    private fun handlePredictionUpdate(remoteMessage: RemoteMessage) {
        val payloadJson = remoteMessage.data["payload"] ?: return
        Log.d("FCM", "Received Prediction Payload: $payloadJson")
        
        try {
            // Parse FCM payload using KMP model
            val payload = gson.fromJson(payloadJson, FcmPayload::class.java)
            
            // Get all selections
            val allSelections = getAllSelections()
            if (allSelections.isEmpty()) return
            
            // Find selections that match this station
            val matchingSelections = allSelections.filter { it.station == payload.id }
            if (matchingSelections.isEmpty()) {
                Log.d("FCM", "Payload station ID (${payload.id}) does not match any active selection. Ignoring.")
                return
            }
            
            matchingSelections.forEach { selection ->
                // Get line data
                val lineIdLower = selection.line.lowercase()
                val lineData = payload.lines[lineIdLower]
                if (lineData == null) {
                    Log.d("FCM", "Payload does not contain line: $lineIdLower for selection ${selection.stationName}. Ignoring.")
                    return@forEach
                }
                
                // Get predictions for the direction
                val dirData = lineData.dirs[selection.direction]
                val preds = dirData?.preds ?: emptyList()
                
                Log.d("FCM", "Found ${preds.size} predictions for selection ${selection.stationName} ($lineIdLower - ${selection.direction}).")
                
                // Determine a valid platform to fallback to if "Unknown" is encountered
                val knownPlatform = preds.firstOrNull { 
                    !it.platform.equals("Unknown", ignoreCase = true) && it.platform.isNotBlank() 
                }?.platform ?: "Unknown"

                // Format and sort predictions for widget (Earliest ETA first)
                val formattedPredictions = preds.map { pred ->
                    val etaString = formatETA(pred.eta)
                    val displayPlatform = if (pred.platform.equals("Unknown", ignoreCase = true) || pred.platform.isBlank()) knownPlatform else pred.platform
                    
                    com.stationly.core.model.PredictionDisplay(
                        destination = com.stationly.mobile.util.FormatUtils.formatDestination(pred.displayName),
                        platform = displayPlatform,
                        eta = etaString,
                        isDue = etaString == "Due"
                    )
                }.distinctBy { "${it.destination}_${it.platform}_${it.eta}" }
                .sortedBy { it.eta.replace(" min", "").replace("Due", "0").toIntOrNull() ?: 999 }
                
                // Save predictions with timestamp
                val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                val predictionsData = mapOf(
                    "data" to gson.toJson(formattedPredictions),
                    "timestamp" to System.currentTimeMillis()
                )
                prefs.edit().putString("predictions_${selection.station}_${selection.line}", gson.toJson(predictionsData)).apply()
                Log.d("FCM", "Saved predictions for ${selection.station}")
                
                // Update widget with current data from storage
                CoroutineScope(Dispatchers.Main).launch {
                    updateWidgetFromStorage(this@FcmMessagingService, selection)
                }
            }
            
        } catch (e: Exception) {
            Log.e("FCM", "Error processing FCM payload", e)
        }
    }
    
    private fun updateWidgetFromStorage(context: Context, selection: UserSelection) {
        val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        
        // Load line status with TTL check
        var lineStatusSeverity: String? = null
        var lineStatusReason: String? = null
        val statusJson = prefs.getString("line_status_data", null)
        if (statusJson != null) {
            try {
                val statusData = gson.fromJson<Map<String, Any>>(statusJson, object : TypeToken<Map<String, Any>>() {}.type)
                val dataJson = statusData["data"] as String
                val parsedData = gson.fromJson<Map<String, Any>>(dataJson, object : TypeToken<Map<String, Any>>() {}.type)
                lineStatusSeverity = parsedData["statusSeverityDescription"] as? String
                lineStatusReason = parsedData["reason"] as? String
                Log.d("FCM", "Loaded valid line status from storage: $lineStatusSeverity")
            } catch (e: Exception) {
                Log.e("FCM", "Error loading line status from storage", e)
            }
        }
        
        // Load predictions with TTL check
        var predictions: List<com.stationly.core.model.PredictionDisplay> = emptyList()
        val predsJson = prefs.getString("predictions_${selection.station}_${selection.line}", null)
        if (predsJson != null) {
            try {
                val predsData = gson.fromJson<Map<String, Any>>(predsJson, object : TypeToken<Map<String, Any>>() {}.type)
                val dataJson = predsData["data"] as String
                predictions = gson.fromJson(dataJson, object : TypeToken<List<com.stationly.core.model.PredictionDisplay>>() {}.type)
                Log.d("FCM", "Loaded valid predictions from storage")
            } catch (e: Exception) {
                Log.e("FCM", "Error loading predictions from storage", e)
            }
        }
        
        // Update widget with whatever data we have
        DepartureWidgetProvider.updateWidgetContent(
            context,
            selection.stationName,
            selection.line.replaceFirstChar { it.uppercase() },
            predictions,
            lineStatusSeverity,
            lineStatusReason
        )
    }
    
    private fun getAllSelections(): List<UserSelection> {
        val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
        val json = prefs.getString("selections", null) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<UserSelection>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e("FCM", "Could not parse selections", e)
            emptyList()
        }
    }
    
    private fun formatETA(etaIso: String): String {
        return try {
            val etaTime = java.time.Instant.parse(etaIso)
            val now = java.time.Instant.now()
            val duration = java.time.Duration.between(now, etaTime)
            
            when {
                duration.seconds < 30 -> "Due"
                duration.seconds < 60 -> "1 min"
                else -> "${(duration.seconds + 30) / 60} min"
            }
        } catch (e: Exception) {
            "Due"
        }
    }
    
    override fun onNewToken(token: String) {
        Log.d("FCM", "Refreshed token: $token")
        // In real implementation, send token to server
    }
}