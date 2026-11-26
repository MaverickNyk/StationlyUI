package com.stationly.app.platform

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The per-mode roundels, cached on disk.
 *
 * ## It writes into the shipped app's cache, on purpose
 * The layout below is `android/`'s `ModeIconCache`, byte for byte:
 *
 *     <filesDir>/mode_icons/<sanitised mode>.png
 *     <filesDir>/mode_icons/tints.json
 *     <filesDir>/mode_icons/version.txt
 *
 * One directory, two readers. A v1 user opening the shared UI sees roundels
 * that are already downloaded, and — the part that would be easy to miss — the
 * **widget keeps working**. `DepartureWidgetProvider` renders from
 * `ModeIconCache`, so a v2 store that cached somewhere else would leave the
 * home-screen widget drawing untinted fallback shapes for as long as both
 * exist. `:composeApp` cannot import `ModeIconCache` (the dependency runs the
 * other way), so the two agree on a file layout instead of on code. AV2-3.5
 * deletes one of them.
 *
 * That is also why [sync] still writes `tints.json` even though nothing in the
 * shared `expect` reads tints: v1's widget does.
 *
 * ## Fall-back chain at render time, unchanged
 *   1. [cachedIconBitmap] → the backend's roundel
 *   2. `tints.json` colour → a drawn roundel in the backend's colour
 *   3. null → the caller's hardcoded mode colour
 */
actual object ModeIconStore {

    private const val DIR = "mode_icons"
    private const val TINTS_FILE = "tints.json"
    private const val VERSION_FILE = "version.txt"

    /** ~8 modes exist; each decoded PNG is 40–60KB. */
    private val memory = LruCache<String, ImageBitmap>(8)

    private fun cacheDir(): File =
        File(AndroidAppContext.context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun iconFile(mode: String) = File(cacheDir(), "${modeIconFileName(mode)}.png")

    actual suspend fun sync(entries: List<ModeIconEntry>, iconVersion: String?) =
        withContext(Dispatchers.IO) {
            // A bumped version means the backend reissued the artwork. Wipe the
            // PNGs so the downloads below fetch it; leave tints and the stamp to
            // be rewritten unconditionally underneath.
            val stored = runCatching { File(cacheDir(), VERSION_FILE).readText().trim() }.getOrNull()
            if (!iconVersion.isNullOrBlank() && stored != iconVersion) {
                cacheDir().listFiles()?.forEach { f ->
                    // `.part` too: a download killed mid-write leaves one, and
                    // nothing else ever collects them.
                    if (f.name.endsWith(".png") || f.name.endsWith(".part")) {
                        runCatching { f.delete() }
                    }
                }
                memory.evictAll()
                runCatching { File(cacheDir(), VERSION_FILE).writeText(iconVersion) }
            }

            // Rewritten on every sync even when the version matched, because the
            // backend can add a mode — or recolour one — without reissuing the
            // artwork bundle.
            val tints = JSONObject()
            entries.forEach { e ->
                if (!e.tintHex.isNullOrBlank()) tints.put(e.modeName.lowercase(), e.tintHex)
            }
            runCatching { File(cacheDir(), TINTS_FILE).writeText(tints.toString()) }

            entries.forEach { e ->
                if (!e.iconUrl.isNullOrBlank() && !hasIcon(e.modeName)) {
                    download(e.modeName, e.iconUrl)
                }
            }
        }

    actual fun hasIcon(mode: String?): Boolean {
        if (mode.isNullOrBlank()) return false
        val f = iconFile(mode)
        // Length as well as existence: a download interrupted mid-write leaves a
        // zero-byte file, and "the file is there" would then mean "never retry".
        return f.exists() && f.length() > 0L
    }

    actual fun cachedIconBitmap(mode: String?): ImageBitmap? {
        if (mode.isNullOrBlank()) return null
        val key = modeIconFileName(mode)
        memory.get(key)?.let { return it }
        if (!hasIcon(mode)) return null
        // Synchronous decode, called from inside `remember` during composition.
        // Deliberate, and the same call the shipped app makes: the memory cache
        // means it happens once per mode per process, and the alternative is a
        // roundel that pops in a frame late on every board.
        return runCatching {
            BitmapFactory.decodeFile(iconFile(mode).absolutePath)
                ?.asImageBitmap()
                ?.also { memory.put(key, it) }
        }.getOrNull()
    }

    private fun download(mode: String, url: String) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "GET"
            }
            try {
                conn.connect()
                if (conn.responseCode !in 200..299) return
                val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: return
                // Written to a temp file and renamed, so a failure part-way
                // through cannot leave a truncated PNG at the real name — which
                // `hasIcon` would then treat as cached and never re-fetch.
                val tmp = File(cacheDir(), "${modeIconFileName(mode)}.png.part")
                FileOutputStream(tmp).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                if (tmp.renameTo(iconFile(mode))) {
                    memory.put(modeIconFileName(mode), bitmap.asImageBitmap())
                } else {
                    tmp.delete()
                }
            } finally {
                conn.disconnect()
            }
        }
        // A missing roundel is a designed fallback, not a failure worth
        // surfacing: the board draws a tinted shape instead.
    }
}

/**
 * A mode name as a filename: lowercase, and anything outside `a-z0-9-` becomes
 * `_`.
 *
 * Must stay identical to `ModeIconCache.safeName` in the shipped app, because
 * the two read each other's files. "national-rail" and "elizabeth-line" pass
 * through untouched; "Tube" lowercases; a mode with a space or a slash in it
 * lands on the same name from either side.
 *
 * Public, rather than internal, because that agreement is the whole point and
 * `:android:app` is where it can be checked — it is the only module that can
 * see both implementations. `V1V2StorageContractTest` calls this against the
 * real `ModeIconCache.safeName`. Not part of the shared UI's API: this file is
 * `androidMain`, so nothing in `commonMain` or on iOS can reach it.
 */
fun modeIconFileName(mode: String): String =
    mode.lowercase().replace(Regex("[^a-z0-9-]"), "_")
