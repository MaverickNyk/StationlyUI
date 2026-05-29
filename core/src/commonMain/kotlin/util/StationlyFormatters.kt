package com.stationly.core.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Unified formatters for all platforms
 */
object StationlyFormatters {
    
    fun formatStatusReason(reason: String): String {
        if (reason.isBlank()) return ""
        var text = if (reason.contains(":")) reason.substringAfter(":").trim() else reason
        if (text.isEmpty()) return ""
        
        val firstDot = text.indexOf('.')
        if (firstDot != -1) {
            val secondDot = text.indexOf('.', firstDot + 1)
            text = if (secondDot != -1) text.substring(0, secondDot + 1)
            else text.substring(0, firstDot + 1)
        }
        return " $text"
    }

    /**
     * Format the line prefix shown alongside the station name on the
     * widget + fullscreen-dream station strip.
     *
     *   Bus 39           ← bus, line is the route number (uppercased for N30 etc.)
     *   DLR              ← DLR
     *   Elizabeth        ← Elizabeth line
     *   Piccadilly       ← Tube / Overground / Tram — capitalised line name
     *
     * Templates can be overridden via homeConfig under `station.label.<mode>`
     * (with `{line}` placeholder, substituted with the formatted line name).
     * Bus line ids are uppercased before substitution. Falls back to
     * hardcoded defaults when the homeConfig is empty (offline / first
     * launch) so the strip never renders blank.
     *
     * Empty mode/line returns an empty string so callers can fall back
     * gracefully to just the station name.
     */
    fun formatLinePrefix(
        mode: String?,
        line: String?,
        strings: Map<String, String> = emptyMap(),
    ): String {
        val m = mode?.lowercase()?.trim().orEmpty()
        val l = line?.trim().orEmpty()
        if (m.isEmpty() && l.isEmpty()) return ""

        // Bus line ids are uppercased ("n30" → "N30"); everything else
        // gets the line name formatted (e.g. "elizabeth-line" → "Elizabeth Line").
        val formattedLine = if (m == "bus") l.uppercase()
        else l.split('-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        // Look up the per-mode template first, then the global default.
        // Hardcoded fallbacks mirror the original switch behaviour so the
        // formatter still works without any SDUI data loaded.
        val template = strings["station.label.$m"]
            ?: strings["station.label.default"]
            ?: defaultTemplateForMode(m)
        return template.replace("{line}", formattedLine).trim()
    }

    private fun defaultTemplateForMode(mode: String): String = when (mode) {
        "bus" -> "Bus {line}"
        "dlr" -> "DLR"
        "elizabeth", "elizabeth-line" -> "Elizabeth"
        else -> "{line}"
    }

    /**
     * Format "{Line}: {Station}" — used on the widget + fullscreen-dream
     * station strip. Falls back to the station name alone when the prefix
     * can't be derived (e.g. no selection yet).
     */
    fun formatStationLabel(
        mode: String?,
        line: String?,
        stationName: String,
        strings: Map<String, String> = emptyMap(),
    ): String {
        val prefix = formatLinePrefix(mode, line, strings)
        return if (prefix.isEmpty()) stationName else "$prefix: $stationName"
    }

    /**
     * Human-readable mode name — used by the line-status dialog and any
     * other surface that needs to display a mode label. Reads from
     * `station.mode.<mode>` in homeConfig, with sensible defaults.
     */
    fun formatModeName(
        mode: String?,
        strings: Map<String, String> = emptyMap(),
    ): String {
        val m = mode?.lowercase()?.trim().orEmpty()
        if (m.isEmpty()) return ""
        strings["station.mode.$m"]?.let { return it }
        return when (m) {
            "tube"                          -> "Tube"
            "overground"                    -> "Overground"
            "dlr"                           -> "DLR"
            "elizabeth", "elizabeth-line"   -> "Elizabeth line"
            "tram"                          -> "Tram"
            "national-rail", "national_rail"-> "National Rail"
            "bus"                           -> "Bus"
            "river-bus", "river_bus"        -> "River Bus"
            "cable-car", "cable_car"        -> "Cable Car"
            else -> m.split('-', '_')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    fun formatDestination(name: String): String {
        val cleanName = name.replace(" Underground Station", "")
            .replace(" DLR Station", "")
            .replace(" Rail Station", "")
            .trim()
        return if (cleanName.length > 25) cleanName.take(22) + "..." else cleanName
    }

    fun getRandomFunnyMessage(): String {
        return listOf(
            "🚇 Wrangling the pigeons...",
            "☕ Heating up the third rail...",
            "🛤️ Checking for leaves on the line...",
            "📡 Decoding underground sonar...",
            "🧬 Sequencing DNA of delayed trains...",
            "🧼 Polishing the platform tiles...",
            "⚡ Searching for Platform 9¾...",
            "📢 Mind the Gap!",
            "🎫 Checking your travel magic..."
        ).random()
    }
    
    fun formatLastUpdated(lastUpdated: Long): String {
        if (lastUpdated == 0L) return "Never"
        val now = Clock.System.now().toEpochMilliseconds()
        val diff = now - lastUpdated
        val seconds = diff / 1000
        
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            else -> "${seconds / 3600}h ago"
        }
    }

    fun formatETA(etaIso: String): String {
        return try {
            val etaTime = Instant.parse(etaIso)
            val now = Clock.System.now()
            val duration = etaTime - now

            when {
                duration.inWholeSeconds < 30 -> "Due"
                duration.inWholeSeconds < 60 -> "1 min"
                else -> "${(duration.inWholeSeconds + 30) / 60} min"
            }
        } catch (e: Exception) {
            "Due"
        }
    }

    /**
     * Parse the FCM/TfL ISO timestamp into an absolute epoch-millis value
     * so the UI can self-tick the minutes-remaining label without
     * re-deriving from the now-stale formatted string. Returns null when
     * the input isn't a parseable ISO instant (legacy strings like "Due"
     * or "5 min" that arrive pre-formatted).
     */
    fun parseTargetEpochMs(etaIso: String): Long? = try {
        Instant.parse(etaIso).toEpochMilliseconds()
    } catch (_: Exception) {
        null
    }

    /**
     * Re-format a `PredictionDisplay`'s ETA given the *current* wall
     * clock. Used by the per-minute ticker on the widget / dot-matrix
     * surfaces — preserves the same "Due / 1 min / N min" rounding the
     * receive-time formatter uses, so a row labelled "5 min" at FCM
     * receipt ticks cleanly down to "4 min", "3 min", ..., "Due".
     *
     * If targetEpochMs is null (FCM ISO timestamp failed to parse),
     * returns [staleFallback] verbatim — typically the row's
     * receive-time `eta` string, which is at least as fresh as the
     * last FCM push.
     */
    fun formatMinutesRemaining(targetEpochMs: Long?, nowMs: Long, staleFallback: String): String {
        if (targetEpochMs == null) return staleFallback
        val secondsRemaining = (targetEpochMs - nowMs) / 1000
        return when {
            secondsRemaining < 30 -> "Due"
            secondsRemaining < 60 -> "1 min"
            else -> "${(secondsRemaining + 30) / 60} min"
        }
    }

    fun sortPredictions(predictions: List<com.stationly.core.model.PredictionDisplay>): List<com.stationly.core.model.PredictionDisplay> {
        return predictions.sortedWith(compareBy { 
            val raw = it.eta.lowercase().trim()
            when {
                raw.contains("due") -> 0
                raw.contains("min") -> raw.replace(" min", "").toIntOrNull() ?: 999
                else -> raw.toIntOrNull() ?: 999
            }
        })
    }
}
