package com.stationly.mobile.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-disk cache for the per-mode icons and tint colours served by the
 * backend's `/modes` endpoint. The widget + fullscreen dream's station-row
 * roundel reads from this cache so the icon paints without a network
 * round-trip on every render.
 *
 * Populated lazily during board setup when `SelectionViewModel` calls
 * `/modes`, with a safety-net warm-up from `SummaryViewModel` when the
 * user has restored selections without going through setup.
 *
 * File layout:
 *   <filesDir>/mode_icons/<modeName>.png   — icon bitmap (sanitised name)
 *   <filesDir>/mode_icons/tints.json       — { mode -> tintHex } map
 *   <filesDir>/mode_icons/version.txt      — backend iconVersion stamp
 *
 * Versioning: when the backend bumps `iconVersion`, the cache wipes its
 * PNGs so the next download grabs fresh assets. Tint map is rewritten on
 * every `/modes` fetch so it stays current regardless of version bump.
 *
 * Decoded bitmaps live in a small LruCache so warm renders skip the
 * disk read + decode entirely.
 *
 * Fall-back chain at render time:
 *   1. Memory cache hit  → return decoded bitmap.
 *   2. Disk hit          → decode, populate memory, return.
 *   3. Miss              → caller renders `R.drawable.mode_roundel`
 *                          tinted via [tintFor] / [ModeColors].
 */
object ModeIconCache {

    private const val TAG = "ModeIconCache"
    private const val DIR             = "mode_icons"
    private const val TINTS_FILE      = "tints.json"
    private const val VERSION_FILE    = "version.txt"
    private const val MEMORY_CACHE_SIZE = 8 // ~8 modes max; each PNG ≈ 40-60KB decoded

    private val memoryCache: LruCache<String, Bitmap> = LruCache(MEMORY_CACHE_SIZE)

    private fun cacheDir(context: Context): File {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Sanitised on-disk filename for a mode (lowercase, only a-z/0-9/-). */
    private fun safeName(mode: String) = mode.lowercase().replace(Regex("[^a-z0-9-]"), "_")

    private fun iconFile(context: Context, modeName: String): File =
        File(cacheDir(context), "${safeName(modeName)}.png")

    private fun tintsFile(context: Context): File =
        File(cacheDir(context), TINTS_FILE)

    private fun versionFile(context: Context): File =
        File(cacheDir(context), VERSION_FILE)

    /** Returns the cached file if present, else null. Synchronous, fast. */
    fun cachedFile(context: Context, modeName: String?): File? {
        if (modeName.isNullOrBlank()) return null
        val f = iconFile(context, modeName)
        return if (f.exists() && f.length() > 0L) f else null
    }

    /**
     * Decode the cached bitmap if present, else null. Memory-cached after
     * first decode so repeated calls (widget re-render every minute) are
     * cheap. Synchronous I/O on miss.
     */
    fun cachedBitmap(context: Context, modeName: String?): Bitmap? {
        if (modeName.isNullOrBlank()) return null
        val key = safeName(modeName)
        memoryCache.get(key)?.let { return it }
        val f = cachedFile(context, modeName) ?: return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)?.also {
                memoryCache.put(key, it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode cached icon for $modeName", e)
            null
        }
    }

    /**
     * Tint colour (hex int) for the given mode, sourced from the most
     * recent /modes fetch. Returns null when nothing's cached — caller
     * should fall back to [ModeColors.forMode] for hardcoded defaults.
     */
    fun tintFor(context: Context, modeName: String?): Int? {
        if (modeName.isNullOrBlank()) return null
        return try {
            val f = tintsFile(context)
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            val hex = obj.optString(modeName.lowercase(), "").takeIf { it.isNotBlank() }
                ?: return null
            android.graphics.Color.parseColor(hex)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read tint for $modeName", e)
            null
        }
    }

    /**
     * Sync the cache with the latest /modes response. Wipes PNGs when the
     * backend `iconVersion` differs from what's on disk; writes the tint
     * map; downloads any missing icons. Safe to call repeatedly — work
     * is skipped when nothing has changed.
     */
    suspend fun sync(
        context: Context,
        modes: List<ModeSyncEntry>,
        iconVersion: String?,
    ) = withContext(Dispatchers.IO) {
        // Version check — wipe stale PNGs if backend bumped its bundle.
        // Tints + version stamp get rewritten regardless.
        val storedVersion = runCatching { versionFile(context).readText().trim() }.getOrNull()
        if (!iconVersion.isNullOrBlank() && storedVersion != iconVersion) {
            wipeIcons(context)
            runCatching { versionFile(context).writeText(iconVersion) }
            memoryCache.evictAll()
        }

        // Persist the latest tint map. Even when versions match we rewrite
        // in case the backend added a new mode without bumping the
        // icon-asset version.
        val tintObj = JSONObject()
        modes.forEach { entry ->
            if (!entry.tintHex.isNullOrBlank()) {
                tintObj.put(entry.modeName.lowercase(), entry.tintHex)
            }
        }
        runCatching { tintsFile(context).writeText(tintObj.toString()) }

        // Download any missing icons.
        modes.forEach { entry ->
            if (!entry.iconUrl.isNullOrBlank() && cachedFile(context, entry.modeName) == null) {
                downloadOne(context, entry.modeName, entry.iconUrl)
            }
        }
    }

    private fun wipeIcons(context: Context) {
        val dir = cacheDir(context)
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".png")) {
                runCatching { f.delete() }
            }
        }
    }

    private fun downloadOne(context: Context, mode: String, url: String) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Icon HTTP ${conn.responseCode} for $mode at $url")
                conn.disconnect()
                return
            }
            val bmp: Bitmap? = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            conn.disconnect()
            if (bmp == null) {
                Log.w(TAG, "Decoded null bitmap for $mode")
                return
            }
            val target = iconFile(context, mode)
            FileOutputStream(target).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // Prime the memory cache so the first widget render after a
            // download doesn't re-decode the file we just wrote.
            memoryCache.put(safeName(mode), bmp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download $mode icon from $url", e)
        }
    }

    /**
     * Lightweight payload type for [sync] — narrower than SduiDropdownOption
     * so callers in core can stay independent of the SDUI model classes.
     */
    data class ModeSyncEntry(
        val modeName: String,
        val iconUrl: String?,
        val tintHex: String?,
    )
}
