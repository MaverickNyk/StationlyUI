package com.stationly.mobile.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.stationly.mobile.R

/**
 * Stationly typography — display + body font families backed by
 * Google Downloadable Fonts.
 *
 * Why downloadable fonts (and not bundled TTFs)?
 *   - No APK bloat (Inter Tight family is ~800KB across weights).
 *   - Fonts are cached by Google Play Services across apps that use
 *     the same provider, so first-launch latency is rare.
 *   - The font name strings ARE the wire format — changing display
 *     font becomes a one-line code change rather than swapping asset
 *     files. (Could be promoted to an SDUI token later if we want
 *     truly remote-driven typography.)
 *
 * Why two families?
 *   - [DisplayFamily] — for the wordmark, headings, station names —
 *     a tight geometric sans that reads as "brand voice".
 *   - [BodyFamily] — defaults to the system sans-serif via FontFamily.Default
 *     so body text picks up Android Pixel-perfect glyphs and respects
 *     the user's font scale + RTL settings.
 *
 * If the Fonts provider is unavailable (no GMS, no network, blocked
 * device), Compose silently falls back to FontFamily.SansSerif — so
 * the app never crashes and text remains readable.
 */

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = R.array.com_google_android_gms_fonts_certs,
)

private val interTight = GoogleFont("Inter Tight")

/**
 * Display family — Stationly's brand voice. Inter Tight, with the
 * standard weight palette so headings/wordmarks can pick any weight.
 */
val DisplayFamily: FontFamily = FontFamily(
    Font(googleFont = interTight, fontProvider = provider, weight = FontWeight.Normal,   style = FontStyle.Normal),
    Font(googleFont = interTight, fontProvider = provider, weight = FontWeight.Medium,   style = FontStyle.Normal),
    Font(googleFont = interTight, fontProvider = provider, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(googleFont = interTight, fontProvider = provider, weight = FontWeight.Bold,     style = FontStyle.Normal),
    Font(googleFont = interTight, fontProvider = provider, weight = FontWeight.ExtraBold, style = FontStyle.Normal),
    Font(googleFont = interTight, fontProvider = provider, weight = FontWeight.Black,    style = FontStyle.Normal),
)

/** Body family — system sans by default. Swap to a Google Font here
 *  if you want body text to also pick up Inter Tight or similar. */
val BodyFamily: FontFamily = FontFamily.Default
