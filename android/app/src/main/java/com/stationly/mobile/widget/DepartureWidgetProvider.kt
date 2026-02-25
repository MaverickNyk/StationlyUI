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
import com.stationly.mobile.util.FormatUtils
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
            stationName: String = "Stationly",
            lineName: String = "",
            predictions: List<PredictionDisplay> = emptyList(),
            lineStatusSeverity: String? = null,
            lineStatusReason: String? = null
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
            if (lineStatusSeverity != null) {
                views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
                views.setTextViewText(R.id.status_severity, lineStatusSeverity)
                views.setTextViewText(
                    R.id.status_reason,
                    FormatUtils.formatStatusReason(lineStatusReason ?: "")
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
            
            // Checking actual selection state
            val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            val json = prefs.getString("selections", "[]") ?: "[]"
            val hasSelection = json.length > 5
            
            if (predictions.isEmpty()) {
                // Show waiting state or no selection state
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)
                
                val header = RemoteViews(context.packageName, R.layout.widget_platform_header)
                header.setTextViewText(R.id.platform_name, if (hasSelection) "Service Update" else "Welcome to Stationly")
                views.addView(R.id.rows_container, header)
                
                for (i in 0 until 3) {
                    val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
                    if (i == 0) {
                        row.setTextViewText(R.id.departure_number, "-")
                        row.setTextViewText(R.id.destination_text, if (hasSelection) "All trains have departed!" else "Select a station inside the app")
                    } else {
                        row.setTextViewText(R.id.departure_number, "")
                        row.setTextViewText(R.id.destination_text, "")
                    }
                    row.setTextViewText(R.id.eta_text, "")
                    views.addView(R.id.rows_container, row)
                }
            } else {
                // Show predictions grouped by platform
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)
                
                val groupedByPlatform = predictions.groupBy { it.platform }
                
                groupedByPlatform.forEach { (platform, platformPreds) ->
                    // Add platform header
                    val header = RemoteViews(context.packageName, R.layout.widget_platform_header)
                    val combinedTitle = if (lineName.isNotEmpty()) "${lineName.replaceFirstChar { it.uppercase() }} : $platform" else platform
                    header.setTextViewText(R.id.platform_name, combinedTitle)
                    views.addView(R.id.rows_container, header)
                    
                    // We assume predictions are already sorted by time when parsed by FCM service
                    // Add exactly 3 predictions per platform
                    for (i in 0 until 3) {
                        val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
                        if (i < platformPreds.size) {
                            val pred = platformPreds[i]
                            row.setTextViewText(R.id.departure_number, (i + 1).toString())
                            row.setTextViewText(R.id.destination_text, FormatUtils.formatDestination(pred.destination))
                            row.setTextViewText(R.id.eta_text, pred.eta)
                        } else {
                            row.setTextViewText(R.id.departure_number, "")
                            row.setTextViewText(R.id.destination_text, "")
                            row.setTextViewText(R.id.eta_text, "")
                        }
                        views.addView(R.id.rows_container, row)
                    }
                }
            }
            
            // Important: Handle appWidgetId correctly if updating all from invalid
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } else {
                val componentName = android.content.ComponentName(context, DepartureWidgetProvider::class.java)
                appWidgetManager.updateAppWidget(componentName, views)
            }
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
                views.removeAllViews(R.id.rows_container)
                views.setViewVisibility(R.id.waiting_container, android.view.View.VISIBLE)
                views.setTextViewText(R.id.funny_message, FormatUtils.getRandomFunnyMessage())
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
            lineStatusSeverity: String? = null,
            lineStatusReason: String? = null
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
                    lineStatusSeverity,
                    lineStatusReason
                )
            }
        }
    }
}