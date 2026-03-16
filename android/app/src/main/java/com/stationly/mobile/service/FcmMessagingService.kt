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
    
    private val syncPredictionsUseCase = com.stationly.core.usecase.SyncPredictionsUseCase(Platform.sqlStorage)
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
                // Unrecognised topic — silently ignore
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
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val processedPredictions = syncPredictionsUseCase.execute(payload, selection)
                        
                        // Ping SharedPreferences to trigger UI updates without sending the payload
                        val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("predictions_${selection.station}_${selection.line}", System.currentTimeMillis().toString()).apply()
                        
                        // Update widget with current data from storage (immediate)
                        launch(Dispatchers.Main) {
                            updateWidgetFromStorage(this@FcmMessagingService, selection)
                        }
                    } catch (e: Exception) {
                        Log.e("FCM", "Error syncing predictions for ${selection.stationName}", e)
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