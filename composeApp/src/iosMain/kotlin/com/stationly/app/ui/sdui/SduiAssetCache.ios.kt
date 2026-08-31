package com.stationly.app.ui.sdui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile

/**
 * The iOS half of [SduiAssetCache]. See its docstring for the invalidation rule.
 *
 * ## Why Caches and not Application Support
 * Everything in here is re-downloadable from a URL the payload still carries, so
 * Caches is the directory Apple's own guidance points at, and it keeps the app's
 * backup size flat. iOS may evict it under storage pressure, which costs one
 * re-download and is the correct outcome: a help video is not worth holding
 * storage a user needs for photos.
 */
@OptIn(ExperimentalForeignApi::class)
actual object SduiAssetCache {

    private const val DIR_NAME = "sdui-assets"

    actual suspend fun localPath(url: String): String? {
        val target = pathFor(url) ?: return null
        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(target)) return target

        return withContext(Dispatchers.Default) {
            val remote = NSURL.URLWithString(url) ?: return@withContext null
            // A whole-file read rather than a streaming download. These assets
            // are capped at a couple of megabytes by the encoder script, and a
            // download task would need a delegate and a completion bridge for a
            // file that fits in memory several times over.
            val data = NSData.dataWithContentsOfURL(remote) ?: return@withContext null
            if (data.length.toLong() == 0L) return@withContext null

            ensureDir()
            // Old versions go before the new one lands, not after: if the write
            // fails, the device is left with nothing to play rather than with a
            // stale file that no payload references any more.
            reapOtherVersions(url)
            if (data.writeToFile(target, atomically = true)) target else null
        }
    }

    actual fun cachedPath(url: String): String? {
        val target = pathFor(url) ?: return null
        return target.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
    }

    /**
     * `.../widget_stack.mp4?v=6a1f9c2b` becomes `<caches>/sdui-assets/widget_stack-6a1f9c2b.mp4`.
     *
     * The version is part of the FILE NAME rather than of a sidecar record, so
     * "do I have this exact version" is a single `fileExistsAtPath` with nothing
     * to keep in step, and [reapOtherVersions] can find a file's predecessors by
     * prefix alone.
     */
    private fun pathFor(url: String): String? {
        val dir = cacheDir() ?: return null
        val (base, ext) = baseAndExtension(url) ?: return null
        return "$dir/$base-${version(url)}.$ext"
    }

    private fun baseAndExtension(url: String): Pair<String, String>? {
        val path = url.substringBefore('?').substringAfterLast('/')
        if (path.isBlank()) return null
        val ext = path.substringAfterLast('.', "")
        val base = path.substringBeforeLast('.', path)
            // The name reaches the filesystem, so anything that could climb out
            // of the directory or collide is flattened first.
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
        if (base.isBlank() || ext.isBlank()) return null
        return base to ext.filter { it.isLetterOrDigit() }
    }

    /**
     * The `v` query parameter, or a hash of the whole URL when there is none.
     *
     * The fallback matters: an unversioned URL must still be cacheable, and it
     * must still be a DIFFERENT key from the versioned form of the same file, or
     * a payload that gains versioning would keep serving the old download.
     */
    private fun version(url: String): String {
        val query = url.substringAfter('?', "")
        val v = query.split('&')
            .firstOrNull { it.startsWith("v=") }
            ?.removePrefix("v=")
            ?.filter { it.isLetterOrDigit() }
        if (!v.isNullOrBlank()) return v
        return url.hashCode().toUInt().toString(16)
    }

    private fun reapOtherVersions(url: String) {
        val dir = cacheDir() ?: return
        val (base, _) = baseAndExtension(url) ?: return
        val keep = pathFor(url)
        val fm = NSFileManager.defaultManager
        @Suppress("UNCHECKED_CAST")
        val names = fm.contentsOfDirectoryAtPath(dir, null) as? List<String> ?: return
        names.filter { it.startsWith("$base-") }
            .map { "$dir/$it" }
            .filter { it != keep }
            .forEach { fm.removeItemAtPath(it, null) }
    }

    private fun cacheDir(): String? {
        val root = NSSearchPathForDirectoriesInDomains(
            NSCachesDirectory, NSUserDomainMask, true,
        ).firstOrNull() as? String ?: return null
        return "$root/$DIR_NAME"
    }

    private fun ensureDir() {
        val dir = cacheDir() ?: return
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }
}
