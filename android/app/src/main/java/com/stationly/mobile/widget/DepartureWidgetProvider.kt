package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.WidgetState
import com.stationly.core.platform.AndroidStorageManager
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.usecase.FormatDeparturesUseCase
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import com.stationly.mobile.R

/**
 * DepartureWidgetProvider - Android Home Screen Widget
 * 
 * This is the EXACT same widget implementation as MindTheTimeAndroid,
 * but adapted to use KMP core for data processing.
 * 
 * Key features preserved:
 * - Authentic London departure board layout
 * - Platform grouping
 * - ETA formatting ("Due", "X min")
 * - Line status display
 * - Click-to-open app
 * - Real-time updates via FCM
 */
class DepartureWidgetProvider : AppWidgetProvider() {
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Update all widgets
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        // Handle custom actions if needed
        when (intent.action) {
            ACTION_UPDATE_WIDGET -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(
                    android.content.ComponentName(context, DepartureWidgetProvider::class.java)
                )
                onUpdate(context, appWidgetManager, appWidgetIds)
            }
        }
    }
    
    companion object {
        const val ACTION_UPDATE_WIDGET = "com.stationly.mobile.ACTION_UPDATE_WIDGET"
        
        /**
         * Update a single widget instance
         * This mirrors the MindTheTimeAndroid implementation exactly
         */
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            stationName: String = "MindTheTime",
            lineName: String = "",
            predictions: List<PredictionDisplay> = emptyList(),
            lineStatus: String? = null
        ) {
            android.util.Log.d("Widget", "Updating widget $appWidgetId for $stationName")
            
            val views = RemoteViews(context.packageName, R.layout.widget_departure_board)
            
            // Set station name in header
            views.setTextViewText(R.id.line_name, stationName)
            
            // Set last updated timer
            views.setChronometer(
                R.id.last_updated_timer,
                SystemClock.elapsedRealtime(),
                "%s ago",
                true
            )
            
            // Show/hide line status
            if (lineStatus != null) {
                views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
                views.setTextViewText(R.id.status_severity, lineStatus)
                views.setTextViewText(
                    R.id.status_reason,
                    formatStatusReason("")
                )
            } else {
                views.setViewVisibility(R.id.status_container, android.view.View.GONE)
            }
            
            // Set up click intent to open app
            val intent = Intent(context, com.stationly.mobile.MainActivity::class.java)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_settings, pendingIntent)
            
            // Clear existing rows
            views.removeAllViews(R.id.rows_container)
            
            if (predictions.isEmpty()) {
                // Show waiting state
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)
                val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
                row.setTextViewText(R.id.destination_text, "All trains have departed!")
                row.setTextViewText(R.id.eta_text, "")
                row.setTextViewText(R.id.departure_number, "-")
                views.addView(R.id.rows_container, row)
            } else {
                // Show predictions grouped by platform
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)
                
                val groupedByPlatform = predictions.groupBy { it.platform }
                
                groupedByPlatform.forEach { (platform, platformPreds) ->
                    // Add platform header
                    val header = RemoteViews(context.packageName, R.layout.widget_platform_header)
                    val combinedTitle = if (lineName.isNotEmpty()) "$lineName : $platform" else platform
                    header.setTextViewText(R.id.platform_name, combinedTitle)
                    views.addView(R.id.rows_container, header)
                    
                    // Sort predictions by ETA
                    val sortedPreds = platformPreds.sortedBy { it.eta }
                    
                    // Add up to 3 predictions per platform
                    for (i in 0 until 3) {
                        val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
                        if (i < sortedPreds.size) {
                            val pred = sortedPreds[i]
                            row.setTextViewText(R.id.departure_number, (i + 1).toString())
                            row.setTextViewText(R.id.destination_text, formatDestination(pred.destination))
                            row.setTextViewText(R.id.eta_text, pred.eta)
                        } else {
                            row.setTextViewText(R.id.departure_number, (i + 1).toString())
                            row.setTextViewText(R.id.destination_text, "-")
                            row.setTextViewText(R.id.eta_text, "")
                        }
                        views.addView(R.id.rows_container, row)
                    }
                }
            }
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
        
        /**
         * Show waiting state in widget
         * Called when waiting for FCM updates
         */
        fun showWaitingState(
            context: Context,
            stationName: String,
            lineName: String
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DepartureWidgetProvider::class.java)
            )
            
            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_departure_board)
                views.setTextViewText(R.id.line_name, stationName)
                views.setViewVisibility(R.id.waiting_container, android.view.View.VISIBLE)
                views.setTextViewText(R.id.funny_message, getRandomFunnyMessage())
                views.setChronometer(
                    R.id.last_updated_timer,
                    SystemClock.elapsedRealtime(),
                    "%s ago",
                    true
                )
                
                // Set countdown
                val baseTime = SystemClock.elapsedRealtime() + 60000
                views.setChronometer(R.id.countdown, baseTime, null, true)
                
                // Set click intent
                val intent = Intent(context, com.stationly.mobile.MainActivity::class.java)
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.btn_settings, pendingIntent)
                
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
        
        /**
         * Update widget content with predictions
         * This is called from the FCM service or WorkManager
         */
        fun updateWidgetContent(
            context: Context,
            stationName: String,
            lineName: String,
            predictions: List<PredictionDisplay>,
            lineStatus: String? = null
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DepartureWidgetProvider::class.java)
            )
            
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    stationName,
                    lineName,
                    predictions,
                    lineStatus
                )
            }
        }
        
        // Private helper methods (same as MindTheTimeAndroid)
        
        private fun formatStatusReason(reason: String): String {
            var text = if (reason.contains(":")) reason.substringAfter(":").trim() else reason
            val firstDot = text.indexOf('.')
            if (firstDot != -1) {
                val secondDot = text.indexOf('.', firstDot + 1)
                text = if (secondDot != -1) text.substring(0, secondDot + 1)
                else text.substring(0, firstDot + 1)
            }
            return text
        }
        
        private fun formatDestination(name: String): String {
            val cleanName = name.replace(" Underground Station", "")
                .replace(" DLR Station", "")
                .replace(" Rail Station", "")
                .trim()
            return if (cleanName.length > 25) cleanName.take(22) + "..." else cleanName
        }
        
        private fun getRandomFunnyMessage(): String {
            return listOf(
                "⚡ Searching for Platform 9¾...",
                "📢 Mind the Gap!",
                "🪄 Clearing leaves from the tracks...",
                "☕ Driver's having a quick tea break...",
                "🏃‍♂️ Sprinting through the Ministry..."
            ).random()
        }
    }
}