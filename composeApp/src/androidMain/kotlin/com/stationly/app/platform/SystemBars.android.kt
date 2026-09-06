package com.stationly.app.platform

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Set the status- and navigation-bar icons to contrast with the app's own
 * canvas. See the `expect` for the bug.
 *
 * `isAppearanceLightStatusBars` reads backwards until you say it out loud: it
 * means "the bars are LIGHT, so draw dark icons on them". A light app canvas
 * therefore wants `true`, which is `!darkTheme`.
 *
 * In a `SideEffect` rather than a `LaunchedEffect`: this touches the window,
 * which is not Compose state, and it must land on the frame the theme changed
 * on rather than one composition later — a visible flash of unreadable icons is
 * the whole thing being fixed.
 *
 * The view's context is walked to an Activity rather than cast to one. Compose
 * can be hosted in a `ContextWrapper` (a dialog, a `ComposeView` inside another
 * container), and a cast that works in the app and throws in a dialog is a
 * crash waiting for the first person who opens one.
 */
@Composable
actual fun ApplySystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
