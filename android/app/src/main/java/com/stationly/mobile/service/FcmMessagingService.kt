package com.stationly.mobile.service

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stationly.core.model.*
import com.stationly.core.platform.Platform
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.util.StationlyFormatters
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
    
    // Repositories
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val from = remoteMessage.from
        
        when {
            remoteMessage.data.containsKey("sdui_payload") -> {
                handleSduiUpdate(remoteMessage)
            }
            from != null && from.contains("LineStatus_") -> {
                handleLineStatusUpdate(remoteMessage)
            }
            from != null && from.contains("Station_") -> {
                handlePredictionUpdate(remoteMessage)
            }
            else -> {
                Log.d("FCM", ">>> [IGNORED] No matching handler for: $from. Data: ${remoteMessage.data}")
            }
        }
    }

    private fun handleSduiUpdate(remoteMessage: RemoteMessage) {
        val sduiJson = remoteMessage.data["sdui_payload"] ?: return

        try {
            val format = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val sduiPayload = format.decodeFromString<com.stationly.core.model.sdui.SduiWidgetPayload>(sduiJson)
            
            val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("sdui_layout_${sduiPayload.id}", sduiJson).apply()
            
            // Extract predictions from SDUI rows if they contain data
            // This ensures the App's SQL-based board is in sync with the visual SDUI update
            val extractedPredictions = mutableListOf<PredictionDisplay>()
            sduiPayload.components.forEach { component ->
                if (component is com.stationly.core.model.sdui.SduiWidgetComponent.Row) {
                    if (component.destination.isNotBlank() && component.destination != "---" && component.eta.isNotBlank()) {
                         extractedPredictions.add(
                             PredictionDisplay(
                                 destination = component.destination,
                                 platform = "Unknown", // Will be refined by extraction logic if available
                                 eta = component.eta,
                                 isDue = component.eta.equals("Due", ignoreCase = true)
                             )
                         )
                    }
                }
            }

            val selections = getAllSelections()
            // Match by Station ID (Case Insensitive)
            val matchingSelections = selections.filter { it.station.equals(sduiPayload.id, ignoreCase = true) }
            
            matchingSelections.forEach { selection ->
                CoroutineScope(Dispatchers.IO).launch {
                    if (extractedPredictions.isNotEmpty()) {
                        Platform.sqlStorage.savePredictions(selection.station, selection.line, extractedPredictions)
                        
                        // Ping the app to refresh
                        prefs.edit().putString("predictions_${selection.station}_${selection.line}", System.currentTimeMillis().toString()).apply()
                    }
                    
                    launch(Dispatchers.Main) {
                        updateWidgetFromStorage(this@FcmMessagingService, selection)
                    }
                }
            }
            Log.d("FCM", "Successfully processed SDUI layout and synced predictions")
        } catch (e: Exception) {
            Log.e("FCM", "Error parsing SDUI layout template", e)
        }
    }
    
    private fun handleLineStatusUpdate(remoteMessage: RemoteMessage) {
        val payloadJson = remoteMessage.data["payload"] ?: return
        
        try {
            val status = gson.fromJson(payloadJson, LineStatus::class.java)
            CoroutineScope(Dispatchers.IO).launch {
                Platform.sqlStorage.saveLineStatus(status)
                
                // Ping SharedPreferences to trigger UI updates
                val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("line_status_data", System.currentTimeMillis().toString()).apply()
                
                // Update widget with current data from storage
                val selections = getAllSelections()
                selections.forEach { selection ->
                    // Match line status by line ID (Case Insensitive)
                    if (status.id.equals(selection.line, ignoreCase = true)) {
                         updateWidgetFromStorage(this@FcmMessagingService, selection)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FCM", "Error processing line status update", e)
        }
    }
    
    private fun handlePredictionUpdate(remoteMessage: RemoteMessage) {
        val payloadJson = remoteMessage.data["payload"] ?: return
        
        try {
            // Priority 1: Extract station identity from topic name to handle Child Stop ID mismatches
            val stationIdFromTopic = remoteMessage.from?.replace("/topics/Station_", "") ?: ""
            
            // Parse FCM payload using KMP model
            val payload = gson.fromJson(payloadJson, FcmPayload::class.java)
            
            // Get all selections
            val allSelections = getAllSelections()
            if (allSelections.isEmpty()) return
            
            // Find selections that match this station (Check Topic First, then Payload ID as fallback)
            val matchingSelections = allSelections.filter { 
                it.station.equals(stationIdFromTopic, ignoreCase = true) || 
                it.station.equals(payload.id, ignoreCase = true)
            }
            
            if (matchingSelections.isEmpty()) {
                Log.d("FCM", "Message for ${stationIdFromTopic ?: payload.id} does not match any active selection. Ignoring.")
                return
            }
            
            matchingSelections.forEach { selection ->
                // Get line data
                val lineIdLower = selection.line.lowercase()
                val lineData = payload.lines[lineIdLower]
                
                // If lineData is null, we can't show departures for this line
                if (lineData == null) {
                    Log.d("FCM", "Payload lines [${payload.lines.keys}] missing selection line: $lineIdLower. Ignoring.")
                    return@forEach
                }
                
                // Get predictions for the direction
                val dirData = lineData.dirs[selection.direction]
                val preds = dirData?.preds ?: emptyList()
                
                // Determine a valid platform to fallback to if "Unknown" is encountered
                val knownPlatform = preds.firstOrNull { 
                    !it.platform.equals("Unknown", ignoreCase = true) && it.platform.isNotBlank() 
                }?.platform ?: "Unknown"

                // Format and sort predictions for widget (Earliest ETA first)
                val formattedPredictions = preds.map { pred ->
                    val etaString = StationlyFormatters.formatETA(pred.eta)
                    val displayPlatform = if (pred.platform.equals("Unknown", ignoreCase = true) || pred.platform.isBlank()) knownPlatform else pred.platform
                    
                    com.stationly.core.model.PredictionDisplay(
                        destination = StationlyFormatters.formatDestination(pred.displayName),
                        platform = displayPlatform,
                        eta = etaString,
                        isDue = etaString == "Due",
                        stopLetter = pred.stopLetter
                    )
                }.distinctBy { "${it.destination}_${it.platform}_${it.eta}" }
                
                // Use unified processor to ensure symmetry between App and Widget
                val sortedPredictions = com.stationly.core.util.GlobalBoardProcessor.processPredictions(formattedPredictions)
                
                // Save predictions to SQL
                CoroutineScope(Dispatchers.IO).launch {
                    Platform.sqlStorage.savePredictions(selection.station, selection.line, sortedPredictions)
                    
                    // Ping SharedPreferences to trigger UI updates without sending the payload
                    val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("predictions_${selection.station}_${selection.line}", System.currentTimeMillis().toString()).apply()
                    
                    // Update widget with current data from storage (immediate)
                    launch(Dispatchers.Main) {
                        updateWidgetFromStorage(this@FcmMessagingService, selection)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("FCM", "Error processing FCM payload", e)
        }
    }
    
    private fun updateWidgetFromStorage(context: Context, selection: UserSelection) {
        val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        
        // Load line status from SQL
        var lineStatusSeverity: String? = null
        var lineStatusReason: String? = null
        
        val cachedStatus = Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
        if (cachedStatus != null) {
            lineStatusSeverity = cachedStatus.statusSeverityDescription
            lineStatusReason = cachedStatus.reason
        }
        
        // Load predictions from SQL
        val predictions = Platform.sqlStorage.getPredictions(selection.station, selection.line)
        
        // Load SDUI template if it exists for this station
        var sduiPayload: com.stationly.core.model.sdui.SduiWidgetPayload? = null
        val sduiJson = prefs.getString("sdui_layout_${selection.station}", null)
        if (sduiJson != null) {
            try {
                val format = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                sduiPayload = format.decodeFromString<com.stationly.core.model.sdui.SduiWidgetPayload>(sduiJson)
                Log.d("FCM", "Loaded valid SDUI template from storage")
            } catch (e: Exception) {
                Log.e("FCM", "Error loading SDUI template from storage", e)
            }
        }
        
        // Use unified binding logic to inject live data into the template
        if (sduiPayload != null && predictions.isNotEmpty()) {
             sduiPayload = com.stationly.core.util.GlobalBoardProcessor.bindSduiTemplate(
                 sduiPayload,
                 predictions,
                 lineStatusSeverity,
                 lineStatusReason
             )
        }
        
        val hasLoadedData = predictions.isNotEmpty()

        // Update widget with whatever data we have
        DepartureWidgetProvider.updateWidgetContent(
            context,
            selection.stationName,
            selection.line.replaceFirstChar { it.uppercase() },
            predictions,
            lineStatusSeverity,
            lineStatusReason,
            sduiPayload,
            hasLoadedData
        )
    }
    
    private fun getAllSelections(): List<UserSelection> {
        return Platform.sqlStorage.getAllSelections()
    }
    
    override fun onNewToken(token: String) {
        Log.d("FCM", "Refreshed token: $token")
        // In real implementation, send token to server
    }
}