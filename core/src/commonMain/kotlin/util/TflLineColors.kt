package com.stationly.core.util

/**
 * Canonical TfL brand colour palette as hex `#RRGGBB` strings.
 *
 * Single source of truth for the Android side — consumed today by
 * `FcmMessagingService` (to tint the notification chip when a status
 * change push is dispatched). The Compose UI keeps its own
 * `TFL_LINE_COLORS` map of pre-parsed `androidx.compose.ui.graphics.Color`
 * instances for direct use in `lineColorForTheme(...)` — same hex
 * values, different shape. iOS (KMP) and any future native consumer
 * can read from this file directly.
 *
 * The backend mirrors the same palette in
 * `stationly-backend/src/services/lineIconService.ts` (TypeScript;
 * languages can't share the map directly). When adding or retuning a
 * line, update BOTH files in the same commit — the lockstep is
 * load-bearing for cross-surface visual consistency (a status-change
 * push showing Piccadilly navy on the chip while the in-app pill
 * shows a different blue would be a brand failure).
 *
 * Bus lines (e.g. "bus_39") deliberately don't have entries here —
 * TfL doesn't publish per-bus-line colours; bus routes share the
 * generic bus mode icon and brand red. Callers should default to the
 * brand amber / route-mode colour when this returns null.
 */
object TflLineColors {

    private val palette: Map<String, String> = mapOf(
        // London Underground
        "bakerloo"          to "#B36305",
        "central"           to "#E32017",
        "circle"            to "#FFD300",
        "district"          to "#00782A",
        "hammersmith-city"  to "#F3A9BB",
        "jubilee"           to "#A0A5A9",
        "metropolitan"      to "#9B0056",
        "northern"          to "#000000",
        "piccadilly"        to "#003688",
        "victoria"          to "#0098D4",
        "waterloo-city"     to "#95CDBA",
        // London Overground (renamed lines, post-2024)
        "lioness"           to "#E2A12B",
        "mildmay"           to "#1A6DB4",
        "windrush"          to "#E2231A",
        "weaver"            to "#7B2D8B",
        "suffragette"       to "#00843D",
        "liberty"           to "#6B717E",
        // Other rail modes
        "dlr"               to "#00A4A7",
        "elizabeth"         to "#6950A1",
        "tram"              to "#84B817",
        "cable-car"         to "#E21836",
    )

    /**
     * Hex string (`#RRGGBB`) for a TfL line id, or `null` if the id
     * isn't a known TfL rail line (e.g. bus routes, unknown lines).
     * Case-insensitive on the line id.
     */
    fun hexFor(lineId: String?): String? {
        if (lineId.isNullOrBlank()) return null
        return palette[lineId.lowercase()]
    }
}
