package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                updateFromStorage(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TIMER_DIM -> { setTimerColor(context, COLOR_DIM); return }
            ACTION_TIMER_RED -> { setTimerColor(context, COLOR_RED); return }
        }
        val actions = listOf(ACTION_UPDATE_WIDGET, ACTION_MANUAL_REFRESH)
        if (intent.action in actions) {
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    if (intent.action != ACTION_UPDATE_WIDGET) {
                        showRefreshSpinner(context)
                        val selections = com.stationly.core.platform.Platform.sqlStorage.getAllSelections()
                        val repo = com.stationly.core.repository.DepartureRepository(
                            com.stationly.core.service.TflApiServiceFactory.create(),
                            com.stationly.core.platform.Platform.storageManager,
                            com.stationly.core.platform.Platform.sqlStorage,
                            com.stationly.core.usecase.SyncPredictionsUseCase(com.stationly.core.platform.Platform.sqlStorage)
                        )
                        selections.forEach { repo.fetchInitialData(it) }
                    }
                    updateFromStorage(context)
                } catch (e: Exception) {
                    android.util.Log.e("Widget", "Error during refresh", e)
                    updateFromStorage(context)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
    
    companion object {
        const val ACTION_UPDATE_WIDGET = "com.stationly.mobile.ACTION_UPDATE_WIDGET"
        const val ACTION_MANUAL_REFRESH = "com.stationly.mobile.ACTION_MANUAL_REFRESH"
        const val ACTION_TIMER_DIM = "com.stationly.mobile.ACTION_TIMER_DIM"
        const val ACTION_TIMER_RED = "com.stationly.mobile.ACTION_TIMER_RED"

        private const val COLOR_AMBER = 0xFFFFB300.toInt()
        private const val COLOR_DIM   = 0xFF888888.toInt()
        private const val COLOR_RED   = 0xFFFF3B30.toInt()

        fun triggerRefresh(context: Context) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val lastRefresh = prefs.getLong("last_refresh_ms", 0L)
            if (System.currentTimeMillis() - lastRefresh < 60_000L) return
            prefs.edit().putLong("last_refresh_ms", System.currentTimeMillis()).apply()
            context.sendBroadcast(Intent(context, DepartureWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
            })
        }
        
        private fun setTimerColor(context: Context, color: Int) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(context, DepartureWidgetProvider::class.java))
            val views = RemoteViews(context.packageName, com.stationly.mobile.R.layout.widget_departure_board)
            views.setTextColor(com.stationly.mobile.R.id.last_updated_timer, color)
            for (id in ids) mgr.partiallyUpdateAppWidget(id, views)
        }

        private fun scheduleTimerColorAlarms(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val now = SystemClock.elapsedRealtime()

            val dimIntent = android.app.PendingIntent.getBroadcast(
                context, 10,
                Intent(context, DepartureWidgetProvider::class.java).apply { action = ACTION_TIMER_DIM },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val redIntent = android.app.PendingIntent.getBroadcast(
                context, 11,
                Intent(context, DepartureWidgetProvider::class.java).apply { action = ACTION_TIMER_RED },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(dimIntent)
            alarmManager.cancel(redIntent)
            alarmManager.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 60_000L, dimIntent)
            alarmManager.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 180_000L, redIntent)
        }

        fun showRefreshSpinner(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DepartureWidgetProvider::class.java)
            )
            val views = RemoteViews(context.packageName, com.stationly.mobile.R.layout.widget_departure_board)
            views.setViewVisibility(com.stationly.mobile.R.id.btn_refresh, android.view.View.GONE)
            views.setViewVisibility(com.stationly.mobile.R.id.progress_refresh, android.view.View.VISIBLE)
            for (id in ids) appWidgetManager.partiallyUpdateAppWidget(id, views)
        }

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
                } catch (e: Exception) {
                    android.util.Log.w("Widget", "Failed to parse SDUI layout for ${selection.station}", e)
                }
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
            
            // Status and timer always visible — layout never shifts
            views.setViewVisibility(R.id.status_container, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.last_updated_timer, if (hasSelection) android.view.View.VISIBLE else android.view.View.INVISIBLE)

            if (hasSelection) {
                views.setChronometer(R.id.last_updated_timer, SystemClock.elapsedRealtime(), "%s ago", true)
                views.setTextColor(R.id.last_updated_timer, COLOR_AMBER)
                scheduleTimerColorAlarms(context)
            }

            when {
                lineStatusSeverity != null -> {
                    views.setTextViewText(R.id.status_severity, lineStatusSeverity)
                    views.setTextViewText(R.id.status_reason, StationlyFormatters.formatStatusReason(lineStatusReason ?: ""))
                }
                hasSelection -> {
                    views.setTextViewText(R.id.status_severity, "Status")
                    views.setTextViewText(R.id.status_reason, "Connecting to TfL signals...")
                }
                else -> {
                    views.setTextViewText(R.id.status_severity, "Stationly")
                    views.setTextViewText(R.id.status_reason, "Open the app to get started")
                }
            }
            
            // Set up click intent to open app
            val intent = Intent(context, com.stationly.mobile.MainActivity::class.java)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_settings, pendingIntent)

            // Set up manual refresh intent
            val refreshIntent = Intent(context, DepartureWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
            }
            val refreshPendingIntent = android.app.PendingIntent.getBroadcast(
                context, 1, refreshIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_refresh, refreshPendingIntent)
            views.setViewVisibility(R.id.btn_refresh, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.progress_refresh, android.view.View.GONE)

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
                    // Don't override timer color — stale alarms manage it independently
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
                            row.setTextViewText(R.id.destination_text, component.destination)
                            row.setTextViewText(R.id.eta_text, component.eta)
                            val etaColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.etaColor, dynTextColor)
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
                            row.setTextViewText(R.id.destination_text, component.text)
                            row.setTextViewText(R.id.eta_text, "")
                            val msgColor = com.stationly.mobile.util.SduiThemeManager.parseColor(component.color, dynTextColor)
                            row.setTextColor(R.id.destination_text, msgColor)
                            rowViews.add(row)
                        }
                    }
                }
            } else {
                // Unified Legacy Path (Perfectly Synced with Inside-App Board)
                views.setViewVisibility(R.id.waiting_container, android.view.View.GONE)

                val isLoggedIn = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
                val hasEverUpdated = if (hasSelection) {
                    val sel = com.stationly.core.platform.Platform.sqlStorage.getAllSelections().first()
                    com.stationly.core.platform.Platform.sqlStorage.hasPredictionsInDatabase(sel.station, sel.line)
                } else false
                
                val legacyRows = com.stationly.core.util.GlobalBoardProcessor.prepareLegacyRows(
                    predictions,
                    lineName,
                    hasSelection,
                    isLoggedIn,
                    hasEverUpdated,
                    lineStatusSeverity,
                    lineStatusReason,
                    java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
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
                            dep.setTextViewText(R.id.destination_text, row.destination)
                            dep.setTextViewText(R.id.eta_text, row.eta)
                            dep.setInt(
                                R.id.destination_text, "setGravity",
                                if (row.index == 0) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                            )
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                views.setViewVisibility(R.id.rows_container, android.view.View.GONE)
                views.setViewVisibility(R.id.rows_list, android.view.View.VISIBLE)
                val builder = RemoteViews.RemoteCollectionItems.Builder()
                rowViews.forEachIndexed { index, rv -> builder.addItem(index.toLong(), rv) }
                views.setRemoteAdapter(R.id.rows_list, builder.setHasStableIds(false).build())
            } else {
                views.setViewVisibility(R.id.rows_list, android.view.View.GONE)
                views.setViewVisibility(R.id.rows_container, android.view.View.VISIBLE)
                views.removeAllViews(R.id.rows_container)
                rowViews.forEach { views.addView(R.id.rows_container, it) }
            }
        }
    }
}