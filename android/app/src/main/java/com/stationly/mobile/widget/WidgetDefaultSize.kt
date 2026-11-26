package com.stationly.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.stationly.core.config.SduiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A new widget is as wide as the home screen. The provider XML manages that on 4
 * and 5 columns; no one XML can on 6 as well (see xml-v31/departure_widget_info),
 * and Android has no way to ask a launcher for its grid.
 *
 * Xiaomi's launcher publishes its own ("6x7"), so there the 6 column provider is
 * used. Anything unreadable, or any other launcher, keeps the default.
 */
object WidgetDefaultSize {

    private const val SIX_COLUMNS = "com.stationly.mobile.widget.SIX_COLUMNS"
    private const val XIAOMI_GRID = "miui_home_screen_cells_size"

    /** "6x7" is 6 columns. Null for anything else. Pure. */
    internal fun columns(grid: String?): Int? =
        Regex("^(\\d{1,2})x\\d{1,2}$").find(grid?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /** The provider meta-data to use, or null for the manifest's default. Pure. */
    internal fun variant(sdk: Int, columns: Int?): String? =
        if (sdk >= Build.VERSION_CODES.S && columns == 6) SIX_COLUMNS else null

    fun onAppStart(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                if (!apply(context)) return@launch
                // The served rules first, as every redraw without a screen does.
                SduiConfig.ensureLoaded()
                DepartureWidgetProvider.updateFromStorage(context)
            }.onFailure { Log.w("Widget", "Could not choose the default widget size", it) }
        }
    }

    /**
     * Tells the system only when the answer changed, because it makes the launcher
     * rebuild every widget already placed. True when it did.
     */
    private fun apply(context: Context): Boolean {
        val grid = runCatching { Settings.System.getString(context.contentResolver, XIAOMI_GRID) }
            .onFailure { Log.d("Widget", "Home screen grid unreadable: ${it.message}") }
            .getOrNull()
        val wanted = variant(Build.VERSION.SDK_INT, columns(grid))
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, DepartureWidgetProvider::class.java)
        // What the system has, not a note of what was asked: a note can be restored
        // from a backup onto another phone.
        val current = manager.getInstalledProvidersForPackage(context.packageName, null)
            .firstOrNull { it.provider == provider }?.targetCellWidth ?: return false
        if ((current == 6) == (wanted != null)) {
            if (grid != null) Log.d("Widget", "Home screen grid $grid: widget already $current columns by default")
            return false
        }
        manager.updateAppWidgetProviderInfo(provider, wanted)
        Log.d("Widget", "Home screen grid $grid: new widgets use ${wanted ?: "the default size"}")
        return true
    }
}
