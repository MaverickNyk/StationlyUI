package com.stationly.app.ui.sdui

import com.stationly.app.platform.AndroidAppContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android half of [SduiAssetCache]. See its docstring for the invalidation
 * rule — the version lives in the file name, so "do I have this exact version"
 * is one `File.exists()` with nothing to keep in step.
 *
 * ## Why `cacheDir` and not `filesDir`
 * Everything in here is re-downloadable from a URL the payload still carries,
 * so this is what `cacheDir` is for: Android may reclaim it under storage
 * pressure, which costs one re-download. `filesDir` would instead grow the
 * app's footprint permanently and be backed up. Same call the iOS half makes
 * with Caches over Application Support.
 *
 * ## The naming rules are duplicated with the iOS half, on purpose
 * The two caches are per-device stores that never meet, so unlike the mode-icon
 * directory in AV2-3.2 there is no contract between them to break — the file
 * names only have to be self-consistent on one device. Sharing them would mean
 * moving pure code into `commonMain` and editing the iOS actual, for no
 * correctness gain.
 */
actual object SduiAssetCache {

    private const val DIR_NAME = "sdui-assets"
    /** Generous for a help GIF, small enough that a wrong URL cannot fill a disk. */
    private const val MAX_BYTES = 16L * 1024 * 1024
    private const val TIMEOUT_MS = 20_000

    actual suspend fun localPath(url: String): String? {
        val target = fileFor(url) ?: return null
        if (target.exists()) return target.path

        return withContext(Dispatchers.IO) {
            val bytes = runCatching { download(url) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) return@withContext null

            target.parentFile?.mkdirs()
            // Old versions go before the new one lands, not after: if the write
            // fails the device is left with nothing to play rather than with a
            // stale file no payload references any more.
            reapOtherVersions(url)

            // Write beside the target and rename, so a download killed halfway
            // cannot leave a truncated file that `exists()` will happily serve
            // forever. This is what the iOS half gets from `atomically = true`.
            val temp = File(target.parentFile, "${target.name}.part")
            runCatching {
                temp.writeBytes(bytes)
                if (temp.renameTo(target)) target.path else null
            }.getOrNull().also { if (it == null) temp.delete() }
        }
    }

    actual fun cachedPath(url: String): String? = fileFor(url)?.takeIf { it.exists() }?.path

    private fun download(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return null
            // A whole-file read, capped. These assets are held to a couple of
            // megabytes by the encoder script; streaming to disk would buy
            // nothing but a partial file to clean up.
            val declared = connection.contentLengthLong
            if (declared > MAX_BYTES) return null
            val bytes = connection.inputStream.use { it.readBytes(MAX_BYTES.toInt() + 1) }
            return bytes.takeIf { it.size <= MAX_BYTES }
        } finally {
            connection.disconnect()
        }
    }

    private fun reapOtherVersions(url: String) {
        val dir = cacheDir() ?: return
        val base = baseAndExtension(url)?.first ?: return
        val keep = fileFor(url)?.name
        dir.listFiles()
            ?.filter { it.name.startsWith("$base-") && it.name != keep }
            ?.forEach { it.delete() }
    }

    /**
     * `.../widget_stack.mp4?v=6a1f9c2b` becomes
     * `<cache>/sdui-assets/widget_stack-6a1f9c2b.mp4`.
     */
    private fun fileFor(url: String): File? {
        val dir = cacheDir() ?: return null
        val (base, ext) = baseAndExtension(url) ?: return null
        return File(dir, "$base-${assetVersion(url)}.$ext")
    }

    private fun cacheDir(): File? =
        runCatching { File(AndroidAppContext.context.cacheDir, DIR_NAME) }.getOrNull()

    /** Read a stream, but never more than [limit] bytes. */
    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return ByteArray(limit + 1)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

/**
 * The file name's stem and extension, sanitised.
 *
 * Internal rather than private so it can be tested without a device: the
 * sanitisation is the part with teeth, since this string reaches the
 * filesystem and the URL comes from a backend payload.
 */
internal fun baseAndExtension(url: String): Pair<String, String>? {
    val path = url.substringBefore('?').substringAfterLast('/')
    if (path.isBlank()) return null
    val ext = path.substringAfterLast('.', "").filter { it.isLetterOrDigit() }
    val base = path.substringBeforeLast('.', path)
        // Anything that could climb out of the directory, or collide with the
        // `-<version>` separator, is flattened before it becomes a path.
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("")
    if (base.isBlank() || ext.isBlank()) return null
    return base to ext
}

/**
 * The `v` query parameter, or a hash of the whole URL when there is none.
 *
 * The fallback matters twice over: an unversioned URL must still be cacheable,
 * and it must key DIFFERENTLY from the versioned form of the same file — or a
 * payload that gains versioning would keep serving the old download.
 */
internal fun assetVersion(url: String): String {
    val v = url.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.startsWith("v=") }
        ?.removePrefix("v=")
        ?.filter { it.isLetterOrDigit() }
    if (!v.isNullOrBlank()) return v
    return url.hashCode().toUInt().toString(16)
}
