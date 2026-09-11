package com.stationly.mobile.dream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.stationly.app.platform.openSystemScreensaverSettings
import com.stationly.app.ui.dream.DreamSettingsScreen
import com.stationly.app.ui.theme.StationlyThemeHost

/**
 * Configuration screen for the screensaver. Reachable via the gear next to
 * "Stationly" in Settings → Display → Screen saver, wired through
 * `stationly_dream_info.xml`'s `settingsActivity` attribute.
 *
 * ## It hosts the SHARED screen now (AV2-6.2)
 * This file was ~700 lines of Compose that drew a layout picker, theme tiles,
 * clock tiles and a station list — a second implementation of
 * `com.stationly.app.ui.dream.DreamSettingsScreen`, written before there was a
 * shared one. Both read the same four settings out of the same preferences file.
 * Two screens editing one set of values is how they stop agreeing, and the one
 * the user reaches depends on which door they came through.
 *
 * ## The three manifest attributes are load-bearing — do not remove them
 * `taskAffinity=""`, `launchMode="singleTask"` and `excludeFromRecents="true"`.
 * System Settings starts this Activity, and without them Android stacks it onto
 * Stationly's own task: back-navigation then walks through `MainActivity` and
 * the whole app history instead of returning to Settings, and the screensaver
 * picker appears in Recents as if it were the app.
 */
class DreamSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StationlyThemeHost {
                DreamSettingsScreen(
                    onBack = { finish() },
                    // "Start now" is an iOS affordance — an iOS app owns its own
                    // screensaver and can present it. On Android only the system
                    // starts a dream, so this hands the user to the screen where
                    // that button actually exists, one tap from where they are.
                    onStartDream = {
                        openSystemScreensaverSettings()
                        finish()
                    },
                )
            }
        }
    }
}
