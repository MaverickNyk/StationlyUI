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
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
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
        val pendingResult = goAsync()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                updateFromStorage(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        // Handle custom actions if needed
        when (intent.action) {
            ACTION_UPDATE_WIDGET -> {
                android.util.Log.d("Widget", "Broadcast received: ACTION_UPDATE_WIDGET")
                val pendingResult = goAsync()
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        updateFromStorage(context)
                    } catch (e: Exception) {
                        android.util.Log.e("Widget", "Error updating from storage", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
    
    companion object {
        const val ACTION_UPDATE_WIDGET = "com.stationly.mobile.ACTION_UPDATE_WIDGET"
        
        fun updateFromStorage(context: Context) {
            android.util.Log.d("Widget", "Force updating from storage...")
            val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
            if (selections.isEmpty()) {
                android.util.Log.w("Widget", "No selections found in storage")
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(
                    android.content.ComponentName(context, DepartureWidgetProvider::class.java)
                )
                for (id in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, id) 
                }
                return
            }
            
            val selection = selections.first()
            android.util.Log.d("Widget", "Updating for station: ${selection.stationName}")
            val prefs = context.getSharedPreferences("StationlyPrefs", Context.MODE_PRIVATE)
            
            var lineStatusSeverity: String? = null
            var lineStatusReason: String? = null
            
            val cachedStatus = com.stationly.core.platform.Platform.sqlStorage.getLineStatus(selection.mode, selection.line)
            if (cachedStatus != null) {
                lineStatusSeverity = cachedStatus.statusSeverityDescription
                lineStatusReason = cachedStatus.reason
            }
            
            val predictions = com.stationly.core.util.StationlyFormatters.sortPredictions(
                com.stationly.core.platform.Platform.sqlStorage.getPredictions(selection.station, selection.line)
            )
            
            var sduiPayload: com.stationly.core.model.sdui.SduiWidgetPayload? = null
            val sduiJson = prefs.getString("sdui_layout_${selection.station}", null)
            if (sduiJson != null) {
                try {
                    val format = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    sduiPayload = format.decodeFromString<com.stationly.core.model.sdui.SduiWidgetPayload>(sduiJson)
                } catch (e: Exception) {}
            }
            
            if (sduiPayload != null && predictions.isNotEmpty()) {
                 sduiPayload = com.stationly.core.util.GlobalBoardProcessor.bindSduiTemplate(
                     sduiPayload,
                     predictions,
                     lineStatusSeverity,
                     lineStatusReason
                 )
            }
            
            val hasLoadedData = predictions.isNotEmpty()

            updateWidgetContent(
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
            lineStatusReason: String? = null,
            sduiPayload: com.stationly.core.model.sdui.SduiWidgetPayload? = null,
            hasLoadedData: Boolean = true
        ) {
            android.util.Log.d("Widget", "Updating widget $appWidgetId for $stationName with ${predictions.size} departures")
            
            val views = RemoteViews(context.packageName, R.layout.widget_departure_board)
            val hasSelection = com.stationly.core.platform.Platform.sqlStorage.getAllSelections().isNotEmpty()
            
            // Set station name in header
            views.setTextViewText(R.id.line_name, stationName)
            
            // Handle empty/dead state for Chronometer & Status when no selection
            if (hasSelection) {
                views.setViewVisibility(R.id.last_updated_timer, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
                
                views.setChronometer(
                    R.id.last_updated_timer,
                    SystemClock.elapsedRealtime(),
                    "%s ago",
                    true
                )
                
                if (lineStatusSeverity != null) {
                    views.setTextViewText(R.id.status_severity, lineStatusSeverity)
                    views.setTextViewText(
                        R.id.status_reason, 
                        StationlyFormatters.formatStatusReason(lineStatusReason ?: "")
                    )
                } else {
                    views.setTextViewText(R.id.status_severity, "Status")
                    views.setTextViewText(R.id.status_reason, "Connecting to TfL signals...")
                }
            } else {
                views.setViewVisibility(R.id.last_updated_timer, android.view.View.INVISIBLE)
                views.setViewVisibility(R.id.status_container, android.view.View.GONE)
            }
            
            // Set up click intent to open app
            val intent = Intent(context, com.stationly.mobile.MainActivity::class.java)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_settings, pendingIntent)
            
            // Clear existing rows setup
            val rowViews = mutableListOf<RemoteViews>()
            
            // Checking actual selection state
             // (Already defined at start of function)
            
            if (sduiPayload != null) {
                // SDUI Rendering Path (Server-Driven)
                
                // 1. Check for dynamic theming
                val defaultTextColor = context.getColor(R.color.tfl_amber)
                var dynTextColor = defaultTextColor
                val theme = sduiPayload.theme
                
                theme?.primaryColor?.let {
                    dynTextColor = com.stationly.mobile.util.SduiThemeManager.parseColor(it, defaultTextColor)
                    views.setTextColor(R.id.line_name, dynTextColor)
                    views.setTextColor(R.id.last_updated_timer, dynTextColor)
                }
                theme?.backgroundColor?.let {
                    val dynBgColor = com.stationly.mobile.util.SduiThemeManager.parseColor(it, android.graphics.Color.BLACK)
                    views.setInt(R.id.departure_board, "setBackgroundColor", dynBgColor)
                }

                views.setTextViewText(R.id.line_name, sduiPayload.title)
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)
                // Keep status container visible as set above
                
                
                sduiPayload.components.forEach { component ->
                    when (component) {
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Header -> {
                            val header = RemoteViews(context.packageName, R.layout.widget_platform_header)
                            header.setTextViewText(R.id.platform_name, component.title)
                             val headerColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.color, dynTextColor)
                             header.setTextColor(R.id.platform_name, headerColor)
                            rowViews.add(header)
                        }
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Row -> {
                            val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
                            row.setTextViewText(R.id.departure_number, component.index)
                            row.setTextViewText(R.id.destination_text, component.destination)
                            row.setTextViewText(R.id.eta_text, component.eta)
                                                        val etaColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.etaColor, dynTextColor)
                            
                            row.setTextColor(R.id.departure_number, dynTextColor)
                            row.setTextColor(R.id.destination_text, dynTextColor)
                            row.setTextColor(R.id.eta_text, etaColor)
                            rowViews.add(row)
                        }
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Status -> {
                            views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
                            views.setTextViewText(R.id.status_severity, component.severity)
                            views.setTextViewText(R.id.status_reason, component.reason)
                            if (dynTextColor != defaultTextColor) {
                                views.setTextColor(R.id.status_severity, dynTextColor)
                                views.setTextColor(R.id.status_reason, dynTextColor)
                            }
                        }
                        is com.stationly.core.model.sdui.SduiWidgetComponent.Message -> {
                            val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
                            row.setTextViewText(R.id.departure_number, "-")
                            row.setTextViewText(R.id.destination_text, component.text)
                            row.setTextViewText(R.id.eta_text, "")
                             val msgColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.color, dynTextColor)
                            row.setTextColor(R.id.departure_number, dynTextColor)
                            row.setTextColor(R.id.destination_text, msgColor)
                            rowViews.add(row)
                        }
                    }
                }
            } else {
                // Unified Legacy Path (Perfectly Synced with Inside-App Board)
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)
                
                val hasSelection = com.stationly.core.platform.Platform.sqlStorage.getAllSelections().isNotEmpty()
                val isLoggedIn = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
                val hasEverUpdated = if (hasSelection) {
                    val selection = com.stationly.core.platform.Platform.sqlStorage.getAllSelections().first()
                    com.stationly.core.platform.Platform.sqlStorage.hasPredictionsInDatabase(selection.station, selection.line)
                } else false
                
                val legacyRows = com.stationly.core.util.GlobalBoardProcessor.prepareLegacyRows(
                    predictions,
                    lineName,
                    hasSelection,
                    isLoggedIn,
                    hasEverUpdated
                )

                legacyRows.forEach { row ->
                    when (row) {
                        is com.stationly.core.util.LegacyRow.Header -> {
                            val header = RemoteViews(context.packageName, R.layout.widget_platform_header)
                            header.setTextViewText(R.id.platform_name, row.title)
                            rowViews.add(header)
                        }
                        is com.stationly.core.util.LegacyRow.Departure -> {
                            val dep = RemoteViews(context.packageName, R.layout.widget_departure_row)
                            // Hide number for spacer rows
                            dep.setTextViewText(R.id.departure_number, if (row.index > 0) row.index.toString() else "")
                            dep.setTextViewText(R.id.destination_text, row.destination)
                            dep.setTextViewText(R.id.eta_text, row.eta)
                            rowViews.add(dep)
                        }
                        is com.stationly.core.util.LegacyRow.Message -> {
                            val msg = RemoteViews(context.packageName, R.layout.widget_platform_header)
                            msg.setTextViewText(R.id.platform_name, row.text)
                            rowViews.add(msg)
                        }
                    }
                }
            }
            
            applyRowsToWidget(views, rowViews)
            
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
                val rowViews = mutableListOf<RemoteViews>()
                val header = RemoteViews(context.packageName, R.layout.widget_platform_header)
                header.setTextViewText(R.id.platform_name, "🛰️ Syncing live signals...")
                rowViews.add(header)
                
                for (i in 0 until 3) {
                    val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
                    row.setTextViewText(R.id.departure_number, "")
                    row.setTextViewText(R.id.destination_text, "")
                    row.setTextViewText(R.id.eta_text, "")
                    rowViews.add(row)
                }
                
                applyRowsToWidget(views, rowViews)
                
                views.setChronometer(
                    R.id.last_updated_timer,
                    SystemClock.elapsedRealtime(),
                    "%s ago",
                    true
                )
                
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
            lineStatusReason: String? = null,
            sduiPayload: com.stationly.core.model.sdui.SduiWidgetPayload? = null,
            hasLoadedData: Boolean = true
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
                    lineStatusReason,
                    sduiPayload,
                    hasLoadedData
                )
            }
        }

        private fun applyRowsToWidget(views: RemoteViews, rowViews: List<RemoteViews>) {
            // Using the LinearLayout path for maximum reliability across all Android versions
            // This avoids issues with RemoteCollectionItems on some launchers.
            views.setViewVisibility(R.id.rows_list, android.view.View.GONE)
            views.setViewVisibility(R.id.rows_container, android.view.View.VISIBLE)
            views.removeAllViews(R.id.rows_container)
            rowViews.forEach { views.addView(R.id.rows_container, it) }
        }
    }
}