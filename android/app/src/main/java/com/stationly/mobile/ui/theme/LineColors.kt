package com.stationly.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The TfL line palette, and the one function that picks from it.
 *
 * ## Why this is in `ui/theme` and not where it was
 * It used to live at the top of `ui/summary/components/Board.kt`, v1's
 * departure board. AV2-3.5 deleted that whole screen — the shared UI in
 * `:composeApp` renders every board now — but the **Daydream** still runs on
 * v1's Compose tree (`dream/`), and it draws line dots and pills with these
 * colours. Leaving 2,000 lines of dead board alive to host twenty lines of
 * colour data was the wrong half to keep.
 *
 * `:composeApp` carries its own copy for the shared UI. That is a duplicate and
 * it is deliberate for now: the dream's future is **Q3** (does Daydream survive
 * into v2 at all?), and EPIC-06 either ports it onto the shared palette or
 * deletes it. Merging the two before that answer arrives would mean giving
 * `:android:app`'s dream a dependency on the shared module's theme internals for
 * a feature that may not exist in a month. If the two ever disagree, the
 * shared one is right.
 */

// Official TfL line colours — canonical palette as published by TfL. Use
// [lineColorForTheme] in UI code so dark-mode-unfriendly lines (deep navy,
// dark green, dark magenta, dark purple) get a brightened variant instead
// of disappearing against a near-black canvas.
val TFL_LINE_COLORS = mapOf(
    "bakerloo"          to Color(0xFFB36305),
    "central"           to Color(0xFFE32017),
    "circle"            to Color(0xFFFFD300),
    "district"          to Color(0xFF00782A),
    "hammersmith-city"  to Color(0xFFF3A9BB),
    "jubilee"           to Color(0xFFA0A5A9),
    "metropolitan"      to Color(0xFF9B0056),
    "northern"          to Color(0xFF888888),
    "piccadilly"        to Color(0xFF003688),
    "victoria"          to Color(0xFF0098D4),
    "waterloo-city"     to Color(0xFF95CDBA),
    "dlr"               to Color(0xFF00A4A7),
    "elizabeth"         to Color(0xFF6950A1),
    "lioness"           to Color(0xFFE2A12B),
    "mildmay"           to Color(0xFF1A6DB4),
    "windrush"          to Color(0xFFE2231A),
    "weaver"            to Color(0xFF7B2D8B),
    "suffragette"       to Color(0xFF00843D),
    "liberty"           to Color(0xFF6B717E),
    "tram"              to Color(0xFF84B817),
    "cable-car"         to Color(0xFFE21836),
)

/**
 * Lightened variants of TfL line colours for use against the dark app/
 * dream canvas (≈ #0A0A0A). Only the lines whose canonical colour has
 * low enough luminance to muddle into the background are overridden;
 * everything else falls back to [TFL_LINE_COLORS] unchanged.
 *
 * Hues are preserved, lightness boosted enough to read clearly at small
 * pill/dot sizes without losing the line identity.
 */
val TFL_LINE_COLORS_DARK = mapOf(
    "piccadilly"   to Color(0xFF3B7AE0),  // #003688 → brighter navy
    "suffragette"  to Color(0xFF1FB54E),  // #00843D → brighter green
    "metropolitan" to Color(0xFFD14990),  // #9B0056 → brighter magenta
    "weaver"       to Color(0xFFB069BE),  // #7B2D8B → brighter purple
    "mildmay"      to Color(0xFF4C95D8),  // #1A6DB4 → brighter mid-blue
    "district"     to Color(0xFF2BB55D),  // #00782A → brighter green
    "bakerloo"     to Color(0xFFD17F2A),  // #B36305 → brighter umber
    "elizabeth"    to Color(0xFF9482D0),  // #6950A1 → brighter purple
)

/**
 * Warmed-up variants of the grey TfL lines for use on the cream light
 * canvas. The canonical greys (#888, #A0A5A9, #6B717E) all but vanish
 * against the warm off-white background — a hint of warm-grey lift
 * keeps the line identity visible without going off-brand.
 */
val TFL_LINE_COLORS_LIGHT = mapOf(
    "northern"  to Color(0xFF6E6A66),  // #888888 → warm darker grey
    "jubilee"   to Color(0xFF7A7E83),  // #A0A5A9 → mid grey with hint of warmth
    "liberty"   to Color(0xFF5A6068),  // #6B717E → deeper warm grey
)

/**
 * Pick the right TfL line colour for the current theme.
 *  - Light theme uses the canonical palette, but substitutes warmer/
 *    deeper variants for the otherwise-invisible greys.
 *  - Dark theme substitutes brightened variants for the handful of
 *    lines that would otherwise lose contrast on the near-black canvas.
 */
fun lineColorForTheme(line: String?, isDark: Boolean): Color {
    val key = line?.lowercase() ?: return TflAmber
    if (isDark) TFL_LINE_COLORS_DARK[key]?.let { return it }
    if (!isDark) TFL_LINE_COLORS_LIGHT[key]?.let { return it }
    return TFL_LINE_COLORS[key] ?: TflAmber
}
