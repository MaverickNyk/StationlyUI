package com.stationly.app.platform

import androidx.compose.runtime.Composable

/**
 * Make the system bars' ICONS legible against the theme the app is actually
 * drawing.
 *
 * ## The bug this exists to fix, seen on a Pixel 7 Pro
 * The Android host calls `enableEdgeToEdge()` once, in `onCreate`, with no
 * arguments — and the no-argument form decides light-or-dark status bar icons
 * from the SYSTEM's dark-mode setting. The app's theme is its own: a user can
 * run their phone in dark mode and pick Light in Stationly, which is exactly
 * what the test device was doing. The result is white status-bar icons on a
 * cream canvas. The clock, the battery and the wifi indicator all but vanish,
 * and nothing about it looks like a bug worth reporting — it reads as the phone
 * being a bit odd.
 *
 * It is the same shape in reverse for a light phone running Stationly in Dark.
 *
 * ## Why here rather than in the host
 * `enableEdgeToEdge()` runs once at `onCreate` and the answer changes at
 * runtime: the user flips the theme in Profile, or the system flips under an
 * app set to SYSTEM. This is called from `StationlyThemeHost`, which is the one
 * place that knows the RESOLVED answer — after `AppTheme.SYSTEM` has been
 * turned into a boolean — and it recomposes when that answer changes.
 *
 * No-op on iOS, whose host manages its own status bar appearance.
 *
 * @param darkTheme what the app is drawing, NOT what the system is set to.
 */
@Composable
expect fun ApplySystemBarAppearance(darkTheme: Boolean)
