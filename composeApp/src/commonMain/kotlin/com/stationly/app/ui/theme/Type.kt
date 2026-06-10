package com.stationly.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.stationly.app.resources.Res
import com.stationly.app.resources.inter_tight_black
import com.stationly.app.resources.inter_tight_bold
import com.stationly.app.resources.inter_tight_extrabold
import com.stationly.app.resources.inter_tight_medium
import com.stationly.app.resources.inter_tight_regular
import com.stationly.app.resources.inter_tight_semibold
import org.jetbrains.compose.resources.Font

/**
 * DisplayFamily — **Inter Tight**, Stationly's brand voice. Compose-Multiplatform
 * port of `android/.../ui/theme/Type.kt`'s `DisplayFamily`.
 *
 * Android loads Inter Tight via Google Downloadable Fonts (GMS provider); iOS has
 * no such provider, so the static weights are bundled in
 * `composeResources/font/inter_tight_*.ttf` (sliced from the official OFL variable
 * font). Applied to the wordmark + section headlines so the brand lockup looks
 * identical on both platforms. Body text intentionally stays on the system
 * default — matches Android's `BodyFamily = FontFamily.Default`.
 *
 * `@Composable` because the compose-resources `Font` loader is composable; read it
 * like the theme palette vals: `Text(..., fontFamily = DisplayFamily)`.
 *
 * NOTE: bundling here only affects the shared `composeApp` (what iOS renders). The
 * shipping Android app is the separate `android/` module, which keeps using GMS
 * downloadable fonts — so there is no Android APK bloat and no behaviour change.
 */
val DisplayFamily: FontFamily
    @Composable get() = FontFamily(
        Font(Res.font.inter_tight_regular,   FontWeight.Normal,    FontStyle.Normal),
        Font(Res.font.inter_tight_medium,    FontWeight.Medium,    FontStyle.Normal),
        Font(Res.font.inter_tight_semibold,  FontWeight.SemiBold,  FontStyle.Normal),
        Font(Res.font.inter_tight_bold,      FontWeight.Bold,      FontStyle.Normal),
        Font(Res.font.inter_tight_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
        Font(Res.font.inter_tight_black,     FontWeight.Black,     FontStyle.Normal),
    )
