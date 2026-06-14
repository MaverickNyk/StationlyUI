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
// Packaging is wired via the "Copy Compose Resources" build phase in
// iosApp/project.yml; `composeResourcesBundled` guards the runtime read so a
// stale/manual build that skipped the phase degrades to the system face
// instead of crashing the first frame (the Session-3 device crash).
val DisplayFamily: FontFamily
    @Composable get() = if (com.stationly.app.platform.composeResourcesBundled) FontFamily(
        Font(Res.font.inter_tight_regular,   FontWeight.Normal),
        Font(Res.font.inter_tight_medium,    FontWeight.Medium),
        Font(Res.font.inter_tight_semibold,  FontWeight.SemiBold),
        Font(Res.font.inter_tight_bold,      FontWeight.Bold),
        Font(Res.font.inter_tight_extrabold, FontWeight.ExtraBold),
        Font(Res.font.inter_tight_black,     FontWeight.Black),
    ) else FontFamily.Default

/**
 * BodyFamily — the system face (SF on iOS). Mirrors Android's `BodyFamily =
 * FontFamily.Default` so body text/labels match Android exactly (system glyphs,
 * font-scale, RTL). [DisplayFamily] (Inter Tight) carries the brand on the
 * wordmark/headings, same split as Android. Type sizes/weights stay inline
 * per-component to match Android's structure (no separate scale object).
 */
val BodyFamily: FontFamily = FontFamily.Default
