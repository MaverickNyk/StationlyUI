package com.stationly.app.platform

import androidx.compose.runtime.Composable

/**
 * No-op. iOS's status bar appearance is the host's — `ContentView` /
 * `Info.plist` decide it, and Compose has no window to reach for. Nothing here
 * has changed; the `expect` exists for Android, where `enableEdgeToEdge()` was
 * reading the SYSTEM dark-mode setting rather than the app's own theme.
 */
@Composable
actual fun ApplySystemBarAppearance(darkTheme: Boolean) = Unit
