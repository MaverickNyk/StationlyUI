package com.stationly.app.platform

import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

/**
 * One-time bundle check: the Copy-Compose-Resources build phase lands the
 * resources at <bundle>/compose-resources/ — if the directory is missing the
 * app must not attempt any Res.* read (MissingResourceException → SIGABRT).
 */
actual val composeResourcesBundled: Boolean by lazy {
    val resPath = NSBundle.mainBundle.resourcePath ?: return@lazy false
    NSFileManager.defaultManager.fileExistsAtPath("$resPath/compose-resources")
}
