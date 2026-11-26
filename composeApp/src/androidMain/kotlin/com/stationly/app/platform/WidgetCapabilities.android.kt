package com.stationly.app.platform

/**
 * Android can. `WidgetConfigureActivity` in manager mode lists every placed
 * widget and rebinds any of them, reached from Home settings → Widgets.
 */
actual val widgetsAreEditableInApp: Boolean = true

/**
 * Android's `DreamService` is started by the OS — charging, docked, or idle on
 * the lock screen, whichever the user chose in Settings → Display → Screen
 * saver. The app never starts it.
 */
actual val screensaverIsStartedBySystem: Boolean = true
