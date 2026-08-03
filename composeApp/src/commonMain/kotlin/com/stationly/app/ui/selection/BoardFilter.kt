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
) {
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
            FilterMode.VIA -> viaStops.isNotEmpty()
        }

    /** Human summary of the via picks, for the card and board tags. */
    val viaSummary: String
        get() = when (viaStopNames.size) {
            0 -> "a stop"
            1 -> viaStopNames[0]
            2 -> "${viaStopNames[0]} & ${viaStopNames[1]}"
            else -> "${viaStopNames[0]} & ${viaStopNames.size - 1} more"
        }
}
