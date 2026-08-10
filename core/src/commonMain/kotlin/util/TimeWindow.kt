package com.stationly.core.util

import kotlinx.datetime.LocalTime

/**
 * Wall-clock window arithmetic, shared by every surface that reasons about
 * "is it currently between HH:mm and HH:mm".
 *
 * Both helpers were private to `BoardFallback` on the Compose side. They moved
 * here when [com.stationly.core.refresh.RefreshPolicyEvaluator] needed the same
 * question answered: a refresh tier and a "service ended for tonight" panel are
 * both a time range against Europe/London, and two implementations of a
 * midnight-wrap rule is exactly the kind of thing that disagrees at 00:05 on
 * one surface only.
 *
 * Deliberately NOT timezone-aware: the caller converts an instant to a local
 * time in whichever zone it cares about, and these compare wall clocks. That
 * keeps them pure and total — no `TimeZone.of` to throw on a bad SDUI string.
 */

/**
 * Parses an "HH:mm" SDUI override into a [LocalTime], else [default].
 *
 * Every failure — null, wrong shape, non-numeric, out of range — yields
 * [default] rather than throwing, because the input is backend-authored text
 * arriving on a device we cannot fix in the moment. A typo'd window must
 * degrade to the shipped schedule, not crash the widget.
 */
fun parseHHmm(raw: String?, default: LocalTime): LocalTime =
    parseHHmmOrNull(raw) ?: default

/** As [parseHHmm], but reports failure so a caller can skip the entry entirely. */
fun parseHHmmOrNull(raw: String?): LocalTime? {
    val parts = (raw?.trim() ?: return null).split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return LocalTime(h, m)
}

/**
 * Inclusive start, exclusive end. Supports windows that wrap midnight.
 *
 * The wrap branch is the whole point: "23:00–06:30" is one window, not two, and
 * a naive `now >= start && now < end` reports every overnight range as never
 * active — which is precisely the hours the night tier exists to cover.
 */
fun inTimeWindow(now: LocalTime, start: LocalTime, endExclusive: LocalTime): Boolean =
    if (start <= endExclusive) now >= start && now < endExclusive
    else now >= start || now < endExclusive
