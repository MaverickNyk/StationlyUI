package com.stationly.app.platform

/**
 * iOS cannot, and this is not a gap to close: an app can never read a widget's
 * AppIntent configuration (`getCurrentConfigurations` returns `[]` inside a
 * timeline), so there is no list to show and nothing to write. Long-press and
 * "Edit Widget" is the whole of it.
 */
actual val widgetsAreEditableInApp: Boolean = false

/**
 * iOS has no system screensaver, so the app is the only thing that can present
 * one and the Start button is the whole entry point.
 */
actual val screensaverIsStartedBySystem: Boolean = false
