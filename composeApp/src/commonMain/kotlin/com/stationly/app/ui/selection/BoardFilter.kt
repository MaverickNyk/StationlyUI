package com.stationly.app.ui.selection

import com.stationly.core.model.FilterMode
import com.stationly.core.model.sdui.SduiRouteStop

/**
 * The user's filter INTENT for one board, while they are still editing it.
 *
 * Deliberately holds only what was asked for, never the resolution. Turning
 * "via Green Park" into the set of destination ids that pass through it needs
 * route data and is done once on save by `BoardFilterResolver` — doing it here
 * would re-run on every recomposition of the sheet.
 *
 * Keeping intent and resolution apart also survives the route changing: an
 * allow-list resolved during engineering works can be recomputed later from the
 * intent, without asking the user again.
 */
data class BoardFilter(
    val mode: FilterMode = FilterMode.ALL,
    /** Chosen termini, for [FilterMode.DESTINATIONS]. */
    val destinationIds: Set<String> = emptySet(),
    /**
     * Chosen stops to travel through, for [FilterMode.VIA].
     *
     * A SET because a junction line offers a real multi-choice — "either branch"
     * is a legitimate answer, and each stop contributes its own downstream set
     * for the resolver to union. Names are carried alongside purely for the
     * summary text; ids are what filter.
     */
    val viaStops: List<SduiRouteStop> = emptyList(),
    /**
     * Whole services taken from the map's terminus chips, by pattern id.
     *
     * Separate from [viaStops] because they answer different questions. "All
     * Morden via Bank trains" is about where a train GOES; "trains through Bank"
     * is about a PLACE. Expressing the first as the second is what put a tick on
     * a station in the middle of a branch nobody had tapped.
     *
     * Names are kept for the summary line, since a pattern id is unreadable.
     */
    val patterns: List<PatternPick> = emptyList(),
) {
    /** One taken service: its id, and what to call it. */
    data class PatternPick(val id: String, val label: String)

    val patternIds: Set<String> get() = patterns.mapTo(LinkedHashSet()) { it.id }
    val patternNames: List<String> get() = patterns.map { it.label }
    /** Ids only, for membership checks on the map. */
    val viaStopIds: Set<String> get() = viaStops.mapTo(LinkedHashSet()) { it.id }
    val viaStopNames: List<String> get() = viaStops.map { it.name }

    /**
     * True when this filter would actually narrow the board.
     *
     * A mode with nothing picked yet (DESTINATIONS with no ticks, VIA with no
     * stop) is treated as no filter at all, so a half-finished sheet can never
     * save a board that hides everything.
     */
    val isActive: Boolean
        get() = when (mode) {
            FilterMode.ALL -> false
            FilterMode.DESTINATIONS -> destinationIds.isNotEmpty()
            FilterMode.VIA -> viaStops.isNotEmpty() || patterns.isNotEmpty()
        }

    /**
     * Human summary of the picks, for the card and board tags.
     *
     * Whole services read as their own name ("Morden via Bank"), stops as the
     * place. Mixing them in one list is fine: they are all answers to "what do
     * you want to see", and naming them separately would be a distinction the
     * user did not make.
     */
    val viaSummary: String
        get() {
            val names = patterns.map { it.label } + viaStopNames
            return when (names.size) {
                0 -> "a stop"
                1 -> names[0]
                2 -> "${names[0]} & ${names[1]}"
                else -> "${names[0]} & ${names.size - 1} more"
            }
        }
}
