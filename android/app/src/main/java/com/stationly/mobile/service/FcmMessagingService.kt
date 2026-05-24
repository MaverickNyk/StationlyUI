package com.stationly.mobile.service

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
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
import kotlinx.coroutines.tasks.await
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

        // Generic notification path. ANY FCM data message that carries
        // a `notification_payload` field gets parsed + posted via the
        // central NotificationDispatcher. This is how the backend will
        // push announcements, marketing, system notices, etc. — without
        // needing per-type wire formats or app-side branching. The data
        // sync paths below still run if the message ALSO carries
        // sdui_payload / line status / predictions (a status-change FCM
        // already in flight can sync state AND post a notification).
        remoteMessage.data["notification_payload"]?.let { json ->
            dispatchRemoteNotification(json)
        }

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
                // Unrecognised topic — silently ignore. The notification
                // path above may still have posted something.
            }
        }
    }

    /**
     * Parse and post a backend-sent notification payload. Failures are
     * logged and swallowed — a malformed payload should never prevent
     * the data-sync side of the FCM message from running.
     */
    private fun dispatchRemoteNotification(json: String) {
        try {
            val format = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val payload = format.decodeFromString<
                com.stationly.core.model.notification.NotificationPayload
            >(json)
            NotificationDispatcher.dispatch(this, payload)
        } catch (e: Exception) {
            Log.e("FCM", "Invalid notification_payload, skipping", e)
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
            // SDUI rows ship eta as a pre-formatted string ("5 min" / "Due")
            // rather than an ISO timestamp, so we derive the absolute arrival
            // time by adding the parsed minutes to NOW. This lets the row
            // self-tick down to "Due" between FCM pushes; without it the
            // SDUI-driven boards would freeze on the receipt-time value.
            val receiptMs = System.currentTimeMillis()
            val extractedPredictions = mutableListOf<PredictionDisplay>()
            sduiPayload.components.forEach { component ->
                if (component is com.stationly.core.model.sdui.SduiWidgetComponent.Row) {
                    if (component.destination.isNotBlank() && component.destination != "---" && component.eta.isNotBlank()) {
                         val etaTrim = component.eta.trim()
                         val isDue = etaTrim.equals("Due", ignoreCase = true)
                         val minutes = if (isDue) 0
                             else etaTrim.replace(" min", "", ignoreCase = true).trim().toIntOrNull()
                         val target = minutes?.let { receiptMs + it * 60_000L }
                         extractedPredictions.add(
                             PredictionDisplay(
                                 destination = component.destination,
                                 platform = "Unknown", // Will be refined by extraction logic if available
                                 eta = etaTrim,
                                 isDue = isDue,
                                 targetEpochMs = target,
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
                // Read the previous status BEFORE saving so we can diff
                // for a transition notification. Looking up by mode+line
                // matches the same shape the rest of the app uses.
                val prevStatus = Platform.sqlStorage.getLineStatus(status.mode, status.id)

                Platform.sqlStorage.saveLineStatus(status)

                // Ping SharedPreferences to trigger UI updates
                val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("line_status_data", System.currentTimeMillis().toString()).apply()

                // Walk active selections — for the ones subscribed to this
                // line, refresh widget AND (if the transition is
                // significant) post a status-change notification. We post
                // ONE notification per line (not per selection) by tracking
                // whether we've already dispatched for this line in this
                // FCM cycle.
                val selections = getAllSelections()
                val subscribedToThisLine = selections.filter {
                    status.id.equals(it.line, ignoreCase = true)
                }
                if (subscribedToThisLine.isEmpty()) return@launch

                subscribedToThisLine.forEach { selection ->
                    updateWidgetFromStorage(this@FcmMessagingService, selection)
                }

                // Build + dispatch one notification IF this is a
                // significant transition (good service ⇄ disruption, or
                // crossed into a severe state). Uses the first matching
                // selection just to pick a "deep-link station" for the
                // tap target; the notification is about the line not
                // the station.
                val firstSelection = subscribedToThisLine.first()
                maybeDispatchStatusChangeNotification(
                    prevStatus = prevStatus,
                    newStatus  = status,
                    deepLinkStationId = firstSelection.station,
                )
            }
        } catch (e: Exception) {
            Log.e("FCM", "Error processing line status update", e)
        }
    }

    /**
     * Decide if a line-status transition deserves a user-facing
     * notification and dispatch one if so. Encapsulated here so the
     * "what counts as significant?" policy is in one obvious place.
     *
     * Significant transitions:
     *   - Was Good Service → now anything else (degradation).
     *   - Was anything else → now Good Service (recovery — useful
     *     "you can stop checking" signal).
     *   - Stepped INTO the severe-disruption band (Severe Delays,
     *     Service Closed, Part Suspended) from a milder state.
     *
     * Trivial degradations (Good Service → Minor Delays) still notify
     * because the cost of an over-notify on a subscribed line is low;
     * the user wanted to know about that line. We can tune the threshold
     * here later if telemetry shows fatigue.
     */
    private fun maybeDispatchStatusChangeNotification(
        prevStatus: LineStatus?,
        newStatus: LineStatus,
        deepLinkStationId: String,
    ) {
        val prevSev   = prevStatus?.statusSeverityDescription?.trim().orEmpty()
        val newSev    = newStatus.statusSeverityDescription.trim()

        // No-op if status didn't change, or we've never seen this line
        // before (first-launch on a degraded line shouldn't fire a "you
        // entered an alert" notification — the user has to OPT IN by
        // experiencing the line being fine first).
        if (prevStatus == null) return
        if (prevSev.equals(newSev, ignoreCase = true)) return

        val wasGood = prevSev.equals("Good Service", ignoreCase = true)
        val isGood  = newSev.equals("Good Service", ignoreCase = true)
        val severeStates = setOf(
            "Severe Delays", "Service Closed", "Part Suspended",
            "Suspended", "Planned Closure",
        )
        val enteredSevere = newSev in severeStates && prevSev !in severeStates

        val significant = wasGood || isGood || enteredSevere
        if (!significant) return

        val lineLabel = newStatus.name.takeIf { it.isNotBlank() }
            ?: newStatus.id.replaceFirstChar { it.uppercase() }

        val title = if (isGood) {
            "$lineLabel · Good Service"
        } else {
            "$lineLabel · $newSev"
        }
        val body = if (isGood) {
            "Service has recovered. Previously $prevSev."
        } else {
            (newStatus.reason?.takeIf { it.isNotBlank() })
                ?: "Was $prevSev — tap to see your board."
        }
        // Hex for the line — defaults to brand amber if the line isn't
        // in our known palette (a bus line, an unfamiliar overground line).
        val color: String? = com.stationly.core.util.TflLineColors.hexFor(newStatus.id)

        // Severity bucket — drives the title colour in the dispatcher.
        // The line identity stays in the title text ("Piccadilly · …");
        // the visual cue users actually scan for is "is this bad news?".
        //   - Good Service                            → success (green)
        //   - Severe / Suspended / Closed / Closure  → danger  (red)
        //   - Minor Delays / Part Closure / Reduced  → warning (amber)
        //   - everything else                         → neutral (system default)
        val severity: String = when {
            isGood -> "success"
            newSev in severeStates -> "danger"
            newSev.equals("Minor Delays", ignoreCase = true) ||
            newSev.equals("Part Closure", ignoreCase = true) ||
            newSev.equals("Reduced Service", ignoreCase = true) -> "warning"
            else -> "neutral"
        }

        val payload = com.stationly.core.model.notification.NotificationPayload(
            type             = "line_status_change",
            title            = title,
            body             = body,
            severity         = severity,
            // Short context so the chip carries both "Piccadilly" (large
            // icon + line colour) AND "Status update" (subtitle) at a glance.
            subtitle         = if (isGood) "Service restored" else "Status update",
            channel          = com.stationly.core.model.notification.NotificationChannelIds.LINE_STATUS,
            priority         = if (isGood) "default" else "high",
            color            = color,
            style            = "bigText",
            deepLink         = "stationly://home?station=$deepLinkStationId",
            groupKey         = "stationly_line_status",
            // Stable id per-line — so a Piccadilly status flapping
            // updates the existing chip instead of stacking new ones.
            notificationId   = ("line_status::" + newStatus.id.lowercase()).hashCode(),
            lineId           = newStatus.id,
            lineName         = lineLabel,
            previousStatus   = prevSev,
            newStatus        = newSev,
        )
        NotificationDispatcher.dispatch(this, payload)
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
    
    /**
     * Tell the Daydream (and any other dynamically-registered refresh listeners)
     * that fresh data has landed in SQL. Uses its own action — the widget
     * broadcast is component-targeted and never reaches dynamic receivers.
     */
    private fun broadcastDreamRefresh(context: Context) {
        val intent = android.content.Intent(
            com.stationly.mobile.dream.StationlyDreamService.ACTION_DREAM_REFRESH
        ).setPackage(context.packageName)
        context.sendBroadcast(intent)
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

        // Notify the Daydream too — it has its own dynamic receiver since the
        // widget broadcast is component-targeted and won't reach it.
        broadcastDreamRefresh(context)
    }
    
    private fun getAllSelections(): List<UserSelection> {
        return Platform.sqlStorage.getAllSelections()
    }
    
    // Note: `TflLineColors.hexFor(lineId)` (in core/util) is the
    // single source of truth for the TfL brand palette on Android.
    // Status-change notifications below tint the chip via that helper
    // — no local palette lives here anymore.

    override fun onNewToken(token: String) {
        Log.d("FCM", "FCM token rotated — re-subscribing topics + re-registering with backend")
        val prefs = getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
        val topics = prefs.getStringSet("fcm_topics", emptySet()) ?: emptySet()
        val fcm = FirebaseMessaging.getInstance()
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Re-subscribe to the line/station topics this device is
            //    subscribed to so prediction + status pushes keep flowing
            //    against the new token.
            topics.forEach { topic ->
                try {
                    fcm.subscribeToTopic(topic).await()
                } catch (e: Exception) {
                    Log.e("FCM", "Re-subscribe failed for $topic after token rotation", e)
                }
            }
            // 2. Register the new token under the user's profile so
            //    `uid`-targeted admin notifications can resolve to it.
            //    No-op if user isn't authed; FcmTokenRegistrar handles
            //    auth state + retries on auth-token transient failures.
            FcmTokenRegistrar.registerIfAuthenticated(this@FcmMessagingService, token)
        }
    }
}