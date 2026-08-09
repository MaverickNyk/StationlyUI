package com.stationly.core.util

import com.stationly.core.model.PredictionDisplay
import com.stationly.core.util.MultiLineBoardProcessor.Group
import com.stationly.core.util.MultiLineBoardProcessor.GroupedDeparture

/**
 * What a board says at ONE instant: every ETA re-derived, departed trains shed,
 * the queue shifted up, and — when the payload is old enough for it to mean
 * something — the last departures held as "Gone".
 *
 * ## Why this exists as one object
 * The backend sends an absolute arrival time; every surface turns that into
 * "2 min". Doing it in three places produced three answers, which is the bug
 * this file closes:
 *
 *  - The home board ran [MultiLineBoardProcessor] over rows that had ALREADY
 *    been ticked and filtered per line, so the monotonic bump was applied per
 *    `platform` while the board itself groups by POLE on bus. At an unlettered
 *    hub every pole reports `platform = ""`, so two poles were bumped as one
 *    queue and the home board read "Due, 1 min, 2 min" where the widget — which
 *    bumps per block — read "Due, Due, Due".
 *  - The home board had no retention at all, so a platform running out of trains
 *    padded itself with BLANK rows while the widget showed "Gone".
 *
 * The pipeline is now the same on both, and the order of the three steps is the
 * design:
 *
 * ```
 * raw departures (SQL, ROW_RESERVE deep)
 *   → MultiLineBoardProcessor.buildGroups   grouping · block order · headers
 *   → BoardTicker.tick                      shed · retain · bump · cap
 *   → rows
 * ```
 *
 * Capping BEFORE the shed is the mistake waiting here: trimmed to the three rows
 * that fit, a block has nothing left to shift up as its trains leave, so the
 * board empties out and stays empty until the next push. The reserves have to
 * survive as far as this file.
 *
 * ## This is a PORT, and the original is Swift
 * `WidgetData.ticked(at:)` in `iosApp/StationlyWidget/AppGroupStorage.swift` is
 * the same algorithm, and it has to stay the same algorithm — the widget
 * extension is a separate process that cannot call Kotlin, so parity is by hand.
 * Every constant below is duplicated there with a pointer back. Change one,
 * change both, in one commit.
 */
object BoardTicker {

    /**
     * How long after its arrival time a train stays on the board.
     *
     * 30s — the dwell of a tube train at a London platform, i.e. what the
     * physical indicator does: the "Due" line stays up while the train sits with
     * its doors open.
     */
    const val DEPARTED_GRACE_MS: Long = 30_000L

    /**
     * How old the payload must be before a departed row is worth holding.
     *
     * "Gone" means "this board is stale, refresh me". Retaining unconditionally
     * makes it mean "this platform is quiet", which is a different and much less
     * useful statement — and it produces rows reading "Gone" immediately after a
     * successful refresh, because a fresh payload legitimately contains trains
     * that have already left (TfL reports them, and SQL holds the previous
     * fetch's rows too).
     *
     * So: fresh payload, no retention — a platform showing two trains instead of
     * three is the truth, and the board says so by being short rather than by
     * lying. Old payload, hold the last known departures — there the label is
     * accurate, and it is the only signal the user gets that they are looking at
     * history.
     *
     * 60s because that is already what "fresh" means on this board: it is the
     * footer's amber threshold in [StaleColor]. A second definition of freshness
     * with a different number is exactly the drift that leaves two parts of one
     * board disagreeing about the same payload.
     */
    const val RETENTION_MIN_AGE_MS: Long = 60_000L

    /**
     * The label a retained departed train carries.
     *
     * "Gone" over "Departed" on purpose: the ETA column is the widest-label
     * column and it sets where the destination truncates, so four characters
     * keeps long station names intact where eight would clip them. It also
     * matches the rhythm of "Due" — both are states, not durations.
     */
    const val DEPARTED_LABEL: String = "Gone"

    /**
     * Whether this row is a retained already-departed train.
     *
     * Read off the LABEL rather than re-derived from `targetEpochMs`, so the
     * "has it left" decision lives in exactly one place (this file, where the
     * grace period and the retention rules are). A second copy in a renderer
     * would drift the first time either constant moved. Same rule as Swift's
     * `DepartureRow.hasDeparted`.
     */
    fun isGone(prediction: PredictionDisplay): Boolean = prediction.eta == DEPARTED_LABEL

    /**
     * The board re-derived for [nowMs] — the only entry point a surface should
     * use.
     *
     * Block ORDER and identity are untouched: they are [MultiLineBoardProcessor]'s,
     * and a board that re-ordered itself every minute would move blocks under
     * the reader (on the widget, under their paging arrows). Blocks left with no
     * rows at all ARE dropped — an empty block is a header with nothing under
     * it, and a board whose blocks all empty is the caller's to describe.
     *
     * ## It does NOT apply the display cap
     * The blocks that come out still carry their reserves, and the renderer
     * takes what it has room for ([MultiLineBoardProcessor.rowsFrom], and
     * `prefix(slots)` in the Swift views). Capping here would be the same code
     * in one fewer place and it would be wrong, because the board is not the
     * only thing reading these blocks: the home screen's hero picks the soonest
     * train PER LINE out of them, and the depth setting is explicitly not
     * allowed to re-point the hero (see `StationBoard.boardPrefs`). Capped here,
     * a line whose next train sits below another line's rows would report
     * "no departures" while its train was two minutes away.
     *
     * Labels are unaffected by where the cap lands: the bump runs over the whole
     * block before anything is trimmed, so the first N rows read identically
     * either way.
     *
     * @param payloadAgeMs how old the data behind [groups] is. Gates retention —
     *   see [RETENTION_MIN_AGE_MS]. Pass 0 to never retain.
     * @param displayRows how many rows each block will SHOW. Not applied here —
     *   it is the retention target. Retained rows sort to the top of their
     *   block, so holding more of them than the renderer will draw would push
     *   live trains off the bottom, which is the exact opposite of the point.
     */
    fun tick(
        groups: List<Group>,
        nowMs: Long,
        payloadAgeMs: Long,
        displayRows: Int,
    ): List<Group> {
        if (groups.isEmpty()) return groups

        // Decided once for the whole board, because it is a property of the
        // PAYLOAD rather than of any one block: every block here was written by
        // the same fetch and is therefore exactly as old as every other.
        val keepAtLeast =
            if (displayRows > 0 && payloadAgeMs >= RETENTION_MIN_AGE_MS) displayRows else 0

        return groups.mapNotNull { group ->
            // Retention is a per-BLOCK question even though its gate is global:
            // a busy Platform 1 with six upcoming trains says nothing about
            // whether a quiet Platform 2 should hold its last departures.
            val ticked = tickBlock(group.departures, nowMs, keepAtLeast)
            if (ticked.isEmpty()) null else group.copy(departures = ticked)
        }
    }

    /**
     * One flat list of departures re-derived at [nowMs], for callers with no
     * blocks to speak of.
     *
     * The list IS the block as far as the bump is concerned, so pass rows that
     * belong to one platform (or one pole) — mixing two and bumping them
     * together is the bus bug described at the top of this file.
     */
    fun tickRows(
        rows: List<PredictionDisplay>,
        nowMs: Long,
        keepAtLeast: Int = 0,
    ): List<PredictionDisplay> =
        tickBlock(rows.map { GroupedDeparture(prediction = it, line = "", lineShort = "") }, nowMs, keepAtLeast)
            .map { it.prediction }

    /** One block's rows at [nowMs], with its own shortfall backfilled. */
    private fun tickBlock(
        rows: List<GroupedDeparture>,
        nowMs: Long,
        keepAtLeast: Int,
    ): List<GroupedDeparture> {
        if (rows.isEmpty()) return rows
        val cutoff = nowMs - DEPARTED_GRACE_MS

        // Rows with no target cannot be judged, so they count as live and pass
        // through untouched — a malformed timestamp must never delete a row.
        val live = mutableListOf<GroupedDeparture>()
        val departed = mutableListOf<GroupedDeparture>()
        rows.forEach { row ->
            val target = row.prediction.targetEpochMs
            if (target == null || target >= cutoff) live.add(row) else departed.add(row)
        }

        // Back-fill only THIS block's shortfall. Where its live rows already
        // fill the slots the departed ones stay dropped: they sort to the top,
        // so retaining them would push genuine upcoming trains out of view.
        val shortfall = (keepAtLeast - live.size).coerceAtLeast(0)
        val retained = if (shortfall > 0) {
            // The most RECENT departures — the ones that just left are the ones
            // worth knowing about.
            departed.sortedBy { it.prediction.targetEpochMs ?: 0L }.takeLast(shortfall)
        } else {
            emptyList()
        }

        return bump(retained + live, nowMs)
    }

    /**
     * Labels one block: "Gone" for what has left, then the monotonic bump over
     * what has not.
     *
     * Two trains in one block may not share a label — if the rounding collides
     * them the later one shifts up ("Due, Due, Due" → "Due, 1 min, 2 min").
     * Collisions ACROSS blocks are fine and deliberately left alone: they are
     * different queues in different places.
     *
     * Retained rows are labelled, never ticked. Counting them down past zero —
     * "Due" forever, or a negative minute — is exactly the confusion retention
     * exists to remove. They also sit OUT of the bump, so a train that has
     * already left can never shift a real one's label.
     */
    private fun bump(rows: List<GroupedDeparture>, nowMs: Long): List<GroupedDeparture> {
        val cutoff = nowMs - DEPARTED_GRACE_MS
        fun hasLeft(row: GroupedDeparture): Boolean {
            val target = row.prediction.targetEpochMs ?: return false
            return target < cutoff
        }

        val gone = rows.filter(::hasLeft).map { it.relabelled(DEPARTED_LABEL, isDue = false) }
        val remaining = rows.filterNot(::hasLeft)

        if (remaining.size <= 1) {
            // A single row still has to be re-derived against the current now.
            // Routed through the SAME labelling as a bumped row (a one-row block
            // has nothing to collide with, so the bump is a no-op) rather than a
            // parallel branch — see [label] for the bug that parallel branch had.
            return gone + remaining.map { row ->
                val minutes = minutesAt(row, nowMs) ?: return@map row
                row.relabelled(label(minutes), isDue = minutes == 0)
            }
        }

        val (withTarget, withoutTarget) = remaining.partition { it.prediction.targetEpochMs != null }
        val sorted = withTarget.sortedBy { it.prediction.targetEpochMs }
        var prevMin = -1   // "Due" == 0; -1 means nothing taken yet
        val bumped = sorted.map { row ->
            val effective = maxOf(minutesAt(row, nowMs) ?: 0, prevMin + 1)
            prevMin = effective
            row.relabelled(label(effective), isDue = effective == 0)
        }
        // Gone first (chronological); rows with no target pin to the end.
        return gone + bumped + withoutTarget
    }

    /**
     * Whole minutes until this row arrives, or null when it has no target.
     *
     * TfL-style FLOOR rounding, never nearest: a train 90s out reads "1 min",
     * one 119s out still reads "1 min". The platform indicators under-promise on
     * purpose so the rider gets there in time, and the app has no business
     * being more optimistic than the sign on the wall.
     */
    private fun minutesAt(row: GroupedDeparture, nowMs: Long): Int? {
        val target = row.prediction.targetEpochMs ?: return null
        val secs = (target - nowMs) / 1000
        return if (secs < 60) 0 else (secs / 60).toInt()
    }

    /**
     * The board's word for a whole-minute countdown. Zero is "Due".
     *
     * ## One definition of "due", which there was not
     * `isDue` used to mean two different things inside [bump]: a lone row got
     * `secs < 30` while a bumped one got `minutes == 0`. So a single departure
     * 45 seconds out was labelled "Due" and flagged `isDue = false` — the label
     * and the flag disagreeing about the same train, which is precisely the
     * inconsistency this whole file exists to remove. It reached the widget,
     * where the tint is taken from the flag: a lone "Due" rendered in ordinary
     * amber where the same train on a busier platform rendered red.
     *
     * `isDue` now means exactly "this row reads Due", which is also what
     * [SyncPredictionsUseCase] has always written at ingest
     * (`isDue = etaString == "Due"`) and what every consumer already assumed.
     */
    private fun label(minutes: Int): String = if (minutes == 0) "Due" else "$minutes min"
}
