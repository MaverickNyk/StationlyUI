package com.stationly.app.ui.util

/**
 * Is [installed] older than [minimum]? Port of Android
 * `SummaryViewModel.isVersionBelow` — same dotted-segment comparison, same
 * lenient parse (non-numeric segments are dropped, missing segments read as
 * 0, so "1.2" vs "1.2.0" compares equal).
 *
 * Drives the SDUI `app.minVersion` force-update nudge. Kept permissive on
 * purpose: a malformed value from the backend must never lock users behind an
 * update dialog they cannot satisfy.
 */
fun isVersionBelow(installed: String, minimum: String): Boolean {
    fun parse(v: String) = v.trim().split(".").mapNotNull { it.toIntOrNull() }
    val ins = parse(installed)
    val min = parse(minimum)
    for (i in 0 until maxOf(ins.size, min.size)) {
        val a = ins.getOrElse(i) { 0 }
        val b = min.getOrElse(i) { 0 }
        if (a < b) return true
        if (a > b) return false
    }
    return false
}
