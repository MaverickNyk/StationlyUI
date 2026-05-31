package com.stationly.mobile.util

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stale-while-revalidate cache for SDUI payloads (layouts, configs).
 *
 * The app is SDUI-driven, so almost every screen's content is a backend payload.
 * To keep transitions instant WITHOUT giving up "configure on the go", every such
 * payload should be cached with this helper and used as:
 *
 * ```
 * SduiCache.read<SduiAppScreen>(ctx, "auth_layout_login")?.let { paint(it) }  // instant
 * viewModelScope.launch {
 *     try { val fresh = api.getLoginLayout(); paint(fresh); SduiCache.write(ctx, "auth_layout_login", fresh) }
 *     catch (_) { /* keep cached copy */ }
 * }
 * ```
 *
 * Contract (SWR): paint the last-known payload immediately, then ALWAYS re-fetch
 * from the backend and replace. The cache only removes the blank network wait on
 * first paint — the backend stays the source of truth on every open, so any
 * server-side change (copy, layout, A/B, enable/disable) still lands within ~1s
 * of the next open. This is NOT a TTL cache that could hide backend changes.
 *
 * Mirrors the existing idioms (`cached_app_layout`, `cached_modes`, ThemeRepository)
 * — same SharedPreferences file, namespaced keys.
 */
object SduiCache {
    // Public so the inline reified read/write can reference them from call sites.
    const val PREFS = "StationlyPrefs"
    val json = Json { ignoreUnknownKeys = true }

    /** Last-known payload for [key], or null if absent / unparseable. */
    inline fun <reified T> read(context: Context, key: String): T? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("sdui_cache_$key", null) ?: return null
        return runCatching { json.decodeFromString<T>(raw) }.getOrNull()
    }

    /** Persist a fresh payload for [key] (best-effort; failures are swallowed). */
    inline fun <reified T> write(context: Context, key: String, value: T) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("sdui_cache_$key", json.encodeToString(value)).apply()
        }
    }
}
