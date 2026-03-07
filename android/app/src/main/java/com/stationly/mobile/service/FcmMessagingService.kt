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
    private val selectionRepository = SelectionRepository(Platform.storageManager, Platform.sqlStorage)
    private val departureRepository = DepartureRepository(
        com.stationly.core.service.TflApiServiceFactory.create(), 
        Platform.storageManager, 
        Platform.sqlStorage
    )
    
    // Throttle map to prevent excessive widget updates (Station ID -> Last Update Time)
    private val widgetThrottleMap = mutableMapOf<String, Long>()
    private val THROTTLE_INTERVAL_MS = 2000L // 2 seconds minimum between updates per station
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "Message received from: ${remoteMessage.from}")
        
        when {
            remoteMessage.data.containsKey("sdui_payload") -> {
                handleSduiUpdate(remoteMessage)
            }
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

    private fun handleSduiUpdate(remoteMessage: RemoteMessage) {
        val sduiJson = remoteMessage.data["sdui_payload"] ?: return
        Log.d("FCM", "Received SDUI Layout Template: $sduiJson")

        try {
            // Validate the SDUI payload syntax
            val format = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val sduiPayload = format.decodeFromString<com.stationly.core.model.sdui.SduiWidgetPayload>(sduiJson)
            
            // Save the layout template for future predictions to use
            val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("sdui_layout_${sduiPayload.id}", sduiJson).apply()
            
            // Apply it immediately with existing data
            val selections = getAllSelections()
            selections.filter { it.station == sduiPayload.id }.forEach { selection ->
                CoroutineScope(Dispatchers.Main).launch {
                    updateWidgetFromStorage(this@FcmMessagingService, selection)
                }
            }
            Log.d("FCM", "Successfully cached SDUI layout template")
        } catch (e: Exception) {
            Log.e("FCM", "Error parsing SDUI layout template payload in FCM", e)
        }
    }
    
    private fun handleLineStatusUpdate(remoteMessage: RemoteMessage) {
        val payloadJson = remoteMessage.data["payload"] ?: return
        Log.d("FCM", "Received Line Status Payload: $payloadJson")
        
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
                    Log.d("FCM", "Saved predictions for \${selection.station}")
                    
                    // Ping SharedPreferences to trigger UI updates without sending the payload
                    val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("predictions_\${selection.station}_\${selection.line}", System.currentTimeMillis().toString()).apply()
                    
                    // Update widget with current data from storage (with throttling)
                    launch(Dispatchers.Main) {
                        val now = System.currentTimeMillis()
                        val lastUpdate = widgetThrottleMap[selection.station] ?: 0L
                        if (now - lastUpdate > THROTTLE_INTERVAL_MS) {
                            widgetThrottleMap[selection.station] = now
                            updateWidgetFromStorage(this@FcmMessagingService, selection)
                        } else {
                            Log.d("FCM", "Throttling widget update for ${selection.station}")
                        }
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
        Log.d("FCM", "Loaded ${predictions.size} predictions from SQL")
        
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