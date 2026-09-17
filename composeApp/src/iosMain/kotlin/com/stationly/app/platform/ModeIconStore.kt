package com.stationly.app.platform

import com.stationly.core.platform.IosAppGroup
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

// `val`, not `const val`: since the staging/production split the App Group
// identifier is read from the Info.plist at runtime (see `IosAppGroup`), so it
// is not a compile-time constant any more.
private val APP_GROUP_ID: String get() = IosAppGroup.ID
private const val DIR          = "mode_icons"
private const val TINTS_FILE   = "tints.json"
private const val VERSION_FILE = "version.txt"

private val tintsJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalForeignApi::class)
actual object ModeIconStore {

    // ~8 modes max; decoded skia bitmaps are small — plain map is enough.
    private val memoryCache = mutableMapOf<String, ImageBitmap>()

    private val cacheDirPath: String? by lazy {
        val container = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_ID)
            ?.path ?: return@lazy null
        val dir = "$container/$DIR"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null
        )
        dir
    }

    /** Sanitised on-disk filename — must match the widget's ModeIconProvider.safeName. */
    private fun safeName(mode: String) =
        mode.lowercase().replace(Regex("[^a-z0-9-]"), "_")

    private fun iconPath(mode: String): String? =
        cacheDirPath?.let { "$it/${safeName(mode)}.png" }

    actual fun hasIcon(mode: String?): Boolean {
        if (mode.isNullOrBlank()) return false
        val path = iconPath(mode) ?: return false
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    actual fun cachedIconBitmap(mode: String?): ImageBitmap? {
        if (mode.isNullOrBlank()) return null
        val key = safeName(mode)
        memoryCache[key]?.let { return it }
        val path = iconPath(mode) ?: return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return try {
            Image.makeFromEncoded(data.toByteArray()).toComposeImageBitmap()
                .also { memoryCache[key] = it }
        } catch (_: Exception) {
            null
        }
    }

    actual suspend fun sync(entries: List<ModeIconEntry>, iconVersion: String?) =
        withContext(Dispatchers.IO) {
            val dir = cacheDirPath ?: return@withContext
            val fm = NSFileManager.defaultManager

            // Version check — wipe stale PNGs when the backend bumps its bundle.
            val versionPath = "$dir/$VERSION_FILE"
            val stored = NSString.stringWithContentsOfFile(
                versionPath, encoding = NSUTF8StringEncoding, error = null
            )?.trim()
            if (!iconVersion.isNullOrBlank() && stored != iconVersion) {
                (fm.contentsOfDirectoryAtPath(dir, error = null) ?: emptyList<Any?>())
                    .filterIsInstance<String>()
                    .filter { it.endsWith(".png") }
                    .forEach { fm.removeItemAtPath("$dir/$it", error = null) }
                memoryCache.clear()
                @Suppress("CAST_NEVER_SUCCEEDS")
                (iconVersion as NSString).writeToFile(
                    versionPath, atomically = true, encoding = NSUTF8StringEncoding, error = null
                )
            }

            // Persist the latest tint map (rewritten every sync, like Android).
            val tints = entries
                .filter { !it.tintHex.isNullOrBlank() }
                .associate { it.modeName.lowercase() to it.tintHex!! }
            val tintsStr = tintsJson.encodeToString(
                MapSerializer(String.serializer(), String.serializer()), tints
            )
            @Suppress("CAST_NEVER_SUCCEEDS")
            (tintsStr as NSString).writeToFile(
                "$dir/$TINTS_FILE", atomically = true, encoding = NSUTF8StringEncoding, error = null
            )

            // Download any missing icons.
            entries.forEach { entry ->
                val url = entry.iconUrl
                if (!url.isNullOrBlank() && !hasIcon(entry.modeName)) {
                    val nsUrl = NSURL.URLWithString(url) ?: return@forEach
                    val data = NSData.dataWithContentsOfURL(nsUrl) ?: return@forEach
                    iconPath(entry.modeName)?.let { data.writeToFile(it, atomically = true) }
                }
            }
        }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { memcpy(it.addressOf(0), bytes, length) }
        return out
    }
}
