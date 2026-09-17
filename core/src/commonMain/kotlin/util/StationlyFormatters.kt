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

    /**
     * Compose the platform-header text shown on every surface (home board,
     * widget, dream): "<linePrefix>: <platform>" — e.g. "Piccadilly: Platform 1".
     *
     * When [platform] is blank — which the backend now emits for bus arrivals
     * with no assigned stop (instead of a confusing "Stop not assigned") — we
     * keep the row but show just the line prefix, dropping the ": " suffix.
     * The platform string itself is fully backend-owned (formatPlatform /
     * getPresentablePlatform); the client never derives it.
     */
    fun platformHeaderText(linePrefix: String, platform: String): String = when {
        platform.isBlank()      -> linePrefix
        linePrefix.isEmpty()    -> platform
        else                    -> "$linePrefix: $platform"
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

    /**
     * Convert an ISO arrival timestamp into a board label using the same
     * rules TfL's own countdown indicators use:
     *
     *   secs < 60s   → "Due"           // train is arriving / at the platform
     *   secs ≥ 60s   → "${secs / 60} min"     // floor (truncate, NOT round)
     *
     * Floor rounding matches what riders see on the TfL platform signs and
     * tfl.gov.uk: a train 90s away reads "1 min", a train 119s away reads
     * "1 min", a train 120s away reads "2 min". TfL deliberately
     * under-promises so the rider gets to the platform on time.
     *
     * Past-target seconds also land in the "Due" bucket; callers that want
     * to drop departed trains do so upstream (see PredictionTicker).
     */
    fun formatETA(etaIso: String): String {
        return try {
            val etaTime = Instant.parse(etaIso)
            val now = Clock.System.now()
            val secs = (etaTime - now).inWholeSeconds
            when {
                secs < 60 -> "Due"
                else      -> "${secs / 60} min"
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
     * clock. Used by the per-minute ticker on every Android surface
     * (home, dream, widget) — uses the same TfL-style floor rounding
     * as [formatETA] so a row labelled "5 min" at FCM receipt ticks
     * cleanly down to "4 min", "3 min", ..., "Due" matching what the
     * rider sees on the platform sign.
     *
     *   secs < 60s   → "Due"
     *   secs ≥ 60s   → "${secs / 60} min"     // floor
     *
     * If targetEpochMs is null (FCM ISO timestamp failed to parse),
     * returns [staleFallback] verbatim — typically the row's
     * receive-time `eta` string, which is at least as fresh as the
     * last FCM push.
     */
    fun formatMinutesRemaining(targetEpochMs: Long?, nowMs: Long, staleFallback: String): String {
        if (targetEpochMs == null) return staleFallback
        val secs = (targetEpochMs - nowMs) / 1000
        return when {
            secs < 60 -> "Due"
            else      -> "${secs / 60} min"
        }
    }

    /**
     * Sort predictions by actual arrival time. Falls back to parsing the
     * eta string when `targetEpochMs` is null (defensive: pre-formatted
     * SDUI rows, malformed FCM timestamps).
     *
     * Was previously string-only — parsing "1 min" / "Due" back to a sort
     * key. That broke once the per-platform bump rule started lying about
     * minute labels: a bumped "3 min" row would sort after a real "2 min"
     * row from a different platform even though its train is actually
     * closer. Sorting by absolute target keeps the cross-platform hero
     * picker honest regardless of what the row label says.
     */
    fun sortPredictions(predictions: List<com.stationly.core.model.PredictionDisplay>): List<com.stationly.core.model.PredictionDisplay> =
        predictions.sortedWith(compareBy { arrivalSortKey(it) })

    /**
     * How soon this departure actually arrives, as an absolute epoch-millis key.
     *
     * The ONE place a prediction is turned into something orderable, so nothing
     * has to re-derive it. Anything comparing two departures — sorting a board,
     * picking the soonest across several lines, deciding whether one is
     * imminent — must go through here rather than parsing [eta], which is a
     * DISPLAY string: it has already been rounded to a minute, and the
     * per-platform bump rule deliberately alters it so two same-platform trains
     * never show the same label. Reading it back is reading a lie.
     *
     * Falls back to parsing that string only when `targetEpochMs` is genuinely
     * absent (pre-formatted SDUI rows, malformed FCM timestamps), and sorts
     * anything unparseable to the end rather than the front.
     */
    fun arrivalSortKey(prediction: com.stationly.core.model.PredictionDisplay): Long =
        prediction.targetEpochMs ?: run {
            val raw = prediction.eta.lowercase().trim()
            when {
                raw.contains("due") -> 0L
                raw.contains("min") -> (raw.replace(" min", "").toLongOrNull() ?: 999L) * 60_000L
                else -> (raw.toLongOrNull() ?: 999L) * 60_000L
            }
        }

    /**
     * The number this row is SHOWING, as an Int. `Due` is 0.
     *
     * Deliberately reads the `eta` label rather than recomputing from
     * `targetEpochMs`, and that is not laziness — it is the opposite of
     * [arrivalSortKey] and the two must not be confused:
     *
     *  - [arrivalSortKey] answers "which train is actually soonest" and is for
     *    ORDERING and picking. It must ignore the label.
     *  - this answers "what does this row say" and is for anything that has to
     *    AGREE with what the user can see — the hero's big number, its progress
     *    bar, an urgency highlight.
     *
     * By the time a row reaches the UI its `eta` has been through
     * `tickPredictions`, which re-derives the minute AND applies the
     * per-platform bump so two trains on one platform never show the same label
     * ("Due, Due" → "Due, 1 min"). Recomputing from the timestamp here would
     * silently undo that bump and let the hero contradict the row below it.
     */
    fun displayedMinutes(prediction: com.stationly.core.model.PredictionDisplay): Int = when {
        prediction.isDue -> 0
        prediction.eta.trim().equals("Due", ignoreCase = true) -> 0
        else -> prediction.eta.replace(" min", "").trim().toIntOrNull() ?: 0
    }
}
