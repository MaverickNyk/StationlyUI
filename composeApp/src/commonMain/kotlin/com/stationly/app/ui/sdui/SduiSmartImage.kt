package com.stationly.app.ui.sdui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Renders an image or animated GIF from [url].
 *
 * Checks local cache synchronously via [SduiAssetCache] so already-cached
 * files render on the first composition frame without flicker.
 *
 * On iOS, multi-frame images (GIFs) are decoded with ImageIO and rendered
 * natively using UIKit UIImageView for smooth looping. Static images (PNG, JPEG)
 * render directly.
 *
 * On Android, Coil handles both static images and animated formats.
 */
@Composable
expect fun SduiSmartImage(
    url: String?,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    modifier: Modifier = Modifier
)
