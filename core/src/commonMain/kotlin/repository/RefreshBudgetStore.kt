package com.stationly.core.repository

import com.stationly.core.model.refresh.BudgetLedger

/**
 * The platform's own record of refreshes spent, when it lives somewhere the
 * shared storage cannot reach.
 *
 * ## Why this needs a platform hook at all
 * On iOS the thing being metered is a WidgetKit timeline build, and those
 * happen inside the **widget extension** — a separate process that never runs
 * Kotlin and cannot open the app's storage. Only it knows when a reload
 * actually occurred, so only it can keep an honest tally. It writes two plain
 * integers into the App Group; this reads them back so the governor in
 * `RefreshPolicyEvaluator` is counting real spend rather than the app's guess
 * at it.
 *
 * A governor fed the app's guess would be worse than no governor: it would
 * under-count badly (the app is not running for most of the reloads) and so
 * would never engage until the widget had already been throttled.
 *
 * Platforms that do not meter this way return null and the repository falls
 * back to its own stored ledger. Android is one — its widget updates arrive by
 * push and are not rationed — so this is deliberately not something every
 * platform has to implement.
 */
expect object RefreshBudgetStore {
    /** The platform tally, or null where the platform does not keep one. */
    suspend fun read(): BudgetLedger?

    /** Open a fresh 24-hour window. Called when the stored one has aged out. */
    suspend fun reset(nowEpochMs: Long)
}
