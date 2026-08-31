package com.stationly.app.ui.sdui

/**
 * Files the backend serves that are cached locally on device.
 *
 * ## Invalidation rule
 * The backend appends a content hash to every asset URL
 * (`widget_stack.gif?v=6a1f9c2b`, see `assetVersionService.ts`). This keys its
 * stored copy on the FULL url, so:
 *
 *  - the same asset unchanged is the same URL, and is never downloaded twice;
 *  - a replaced asset is a different URL, so it is a cache miss and fetched once;
 *  - no manual version bumping, because the hash is derived from file bytes.
 *
 * ## Old versions are reaped on the way in
 * Storing `<name>-<version>.<ext>` lets a new version delete its predecessors by
 * prefix as it lands, keeping device disk usage bounded.
 */
expect object SduiAssetCache {

    /**
     * A local file path for [url], downloading it if this device does not have
     * it yet.
     *
     * Null when the download fails or the platform does not cache files.
     */
    suspend fun localPath(url: String): String?

    /**
     * The stored path for [url] if this device already has it, without touching
     * the network or suspending.
     */
    fun cachedPath(url: String): String?
}
