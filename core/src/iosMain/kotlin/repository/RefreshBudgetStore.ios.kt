package com.stationly.core.repository

import com.stationly.core.model.refresh.BudgetLedger
import com.stationly.core.platform.AppGroupKeys
import com.stationly.core.platform.IosAppGroup
import platform.Foundation.NSUserDefaults

/**
 * Reads the tally the widget extension keeps in the App Group.
 *
 * Two plain integers rather than a JSON blob, because both processes touch them
 * and `NSUserDefaults` has no partial write: encoding a struct would mean each
 * side reading, decoding, mutating and re-encoding a shared value, and the two
 * would clobber each other's increments under exactly the conditions this is
 * meant to measure — a burst of reloads.
 *
 * KMP only ever WRITES here to open a fresh window. The counting itself belongs
 * to the extension; see the key declarations in `AppGroupKeys`.
 */
actual object RefreshBudgetStore {

    private val defaults: NSUserDefaults?
        get() = NSUserDefaults(suiteName = IosAppGroup.ID)

    actual suspend fun read(): BudgetLedger? {
        val d = defaults ?: return null
        // Stored as seconds (the natural unit for an NSUserDefaults double and
        // for everything else the extension writes); the shared model is in
        // millis.
        val startSeconds = d.doubleForKey(AppGroupKeys.WIDGET_BUDGET_WINDOW_START)
        if (startSeconds <= 0.0) return null
        return BudgetLedger(
            windowStartEpochMs = (startSeconds * 1000).toLong(),
            reloadCount = d.integerForKey(AppGroupKeys.WIDGET_BUDGET_COUNT).toInt(),
        )
    }

    actual suspend fun reset(nowEpochMs: Long) {
        val d = defaults ?: return
        d.setDouble(nowEpochMs / 1000.0, forKey = AppGroupKeys.WIDGET_BUDGET_WINDOW_START)
        d.setInteger(0, forKey = AppGroupKeys.WIDGET_BUDGET_COUNT)
        d.synchronize()
    }
}
