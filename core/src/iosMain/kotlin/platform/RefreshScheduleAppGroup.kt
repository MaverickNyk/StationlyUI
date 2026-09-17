package com.stationly.core.platform

import com.stationly.core.repository.RefreshPolicyRepository
import com.stationly.core.refresh.RefreshSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

/**
 * Publishes the refresh schedule where the widget extension can read it.
 *
 * ## The ownership split this preserves
 * The extension cannot run Kotlin, so every rule about WHEN to refresh — window
 * matching, midnight wrap, boost expiry, budget governing — would otherwise
 * have to be reimplemented in Swift, in a process with no tests, against a
 * policy document it would have to parse itself. That is the same trap
 * `WidgetRefreshService` documents for board identity, and the answer is the
 * same: **Kotlin decides, Swift reads**.
 *
 * So this writes a flat list of segments, and the extension's entire share of
 * the logic is "find the one containing now".
 *
 * ## Wire shape
 * Seconds, not millis, and a deliberately small flat record — this is decoded
 * by `RefreshScheduleStore.swift` with `Codable`, and every field here has a
 * counterpart there. Seconds because that is the unit of
 * `Date.timeIntervalSince1970` and everything else the App Group carries;
 * mixing the two is how a schedule ends up 1000× out with no error anywhere.
 */
object RefreshScheduleAppGroup {

    /** One segment as the extension reads it. Keep in step with the Swift
     *  `RefreshSegmentDTO` — field names are the contract. */
    @Serializable
    private data class SegmentDto(
        val start: Double,
        val end: Double,
        val tier: String,
        val interval: Int,
        val dense: Int,
        val sparseStep: Int,
        val horizon: Int,
        val boost: Boolean,
    )

    private val json = Json { encodeDefaults = true }

    private val defaults: NSUserDefaults?
        get() = NSUserDefaults(suiteName = IosAppGroup.ID)

    /**
     * Recompute and publish. Cheap enough to call on every launch, every
     * foreground and every push — it is a policy read plus a few dozen
     * evaluations, no network.
     *
     * Returns whether the stored schedule changed, so the caller can decide
     * whether a widget reload is warranted. Writing an identical schedule and
     * reloading anyway would spend the very budget this exists to protect.
     */
    suspend fun publish(): Boolean = withContext(Dispatchers.Default) {
        val d = defaults ?: return@withContext false
        val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()

        resetLedgerOnNewBuild(d, nowMs)
        val segments = RefreshPolicyRepository.schedule(nowMs)
        if (segments.isEmpty()) return@withContext false

        val encoded = runCatching {
            json.encodeToString(ListSerializer(SegmentDto.serializer()), segments.map { it.toDto() })
        }.getOrNull() ?: return@withContext false

        // A failed encode leaves the previous schedule in place rather than
        // clearing it: a stale schedule still refreshes the widget, an absent
        // one leaves the extension guessing.
        val changed = d.stringForKey(AppGroupKeys.WIDGET_REFRESH_SCHEDULE) != encoded
        if (changed) {
            d.setObject(encoded, forKey = AppGroupKeys.WIDGET_REFRESH_SCHEDULE)
        }
        // Stamped even when unchanged — this is how the extension tells "the app
        // agrees this schedule is current" from "the app has not run in days".
        d.setDouble(nowMs / 1000.0, forKey = AppGroupKeys.WIDGET_REFRESH_SCHEDULE_AT)
        d.synchronize()
        changed
    }

    /** The widget push token the extension was handed, if any. */
    fun widgetPushToken(): String? =
        defaults?.stringForKey(AppGroupKeys.WIDGET_PUSH_TOKEN)?.takeIf { it.isNotBlank() }

    /**
     * Start a fresh budget window whenever the installed build changes.
     *
     * ## Why a build change is the right trigger
     * The ledger models Apple's quota, and the model can be wrong — a metering
     * bug, a changed reload pattern, or a development cycle can all leave a
     * count that does not describe reality. When it is wrong in the HIGH
     * direction the consequence is severe and silent: the governor stretches
     * intervals toward the 24-hour window end, so the widget quietly stops
     * updating and looks broken, with nothing on screen to say why. Nothing
     * short of the window rolling repairs it.
     *
     * A new build is the one moment we know the previous count no longer
     * describes the code that produced it. It is also exactly the case that
     * bites during development, where a metering change means every install
     * inherits a tally from a binary that counted differently.
     *
     * Harmless in production: an app update genuinely starts a new usage
     * pattern, and Apple re-tunes its own allowance against usage anyway.
     */
    private fun resetLedgerOnNewBuild(d: NSUserDefaults, nowMs: Long) {
        val build = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String
            ?: return
        if (d.stringForKey(AppGroupKeys.WIDGET_BUDGET_BUILD) == build) return
        d.setObject(build, forKey = AppGroupKeys.WIDGET_BUDGET_BUILD)
        d.setDouble(nowMs / 1000.0, forKey = AppGroupKeys.WIDGET_BUDGET_WINDOW_START)
        d.setInteger(0, forKey = AppGroupKeys.WIDGET_BUDGET_COUNT)
        d.synchronize()
    }

    private fun RefreshSegment.toDto() = SegmentDto(
        start = startEpochMs / 1000.0,
        end = endEpochMs / 1000.0,
        tier = tierId,
        interval = intervalMinutes,
        dense = denseMinutes,
        sparseStep = sparseStepMinutes,
        horizon = horizonMinutes,
        boost = boostActive,
    )
}
