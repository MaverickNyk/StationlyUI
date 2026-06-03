package com.stationly.mobile.util

import android.content.Context
import org.json.JSONObject

/**
 * Persistent store for the SDUI homeConfig string map. Owned by
 * SummaryViewModel (writes it after every `/homeConfig` fetch) and read
 * by surfaces that don't have a ViewModel reference — the widget render
 * path, the DreamBoard, and DreamSettingsActivity (launched cold from
 * system Settings).
 *
 * Stored as a single JSON blob in SharedPreferences so the cache survives
 * process death and is cheap to read synchronously from any thread.
 * Returns an empty map when nothing has ever been fetched; callers should
 * already carry hardcoded fallbacks for offline first-launch safety.
 */
object HomeConfigStore {
    private const val PREFS = "StationlyHomeConfig"
    private const val KEY   = "home_config_json"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { k -> put(k, obj.optString(k, "")) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun write(context: Context, config: Map<String, String>) {
        val obj = JSONObject()
        config.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit().putString(KEY, obj.toString()).apply()
    }
}
