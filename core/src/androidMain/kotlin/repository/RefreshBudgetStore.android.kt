package com.stationly.core.repository

import com.stationly.core.model.refresh.BudgetLedger

/**
 * Android keeps no separate tally: its widget is updated by FCM push and by its
 * own provider, neither of which is rationed the way WidgetKit rations timeline
 * builds. Returning null lets [RefreshPolicyRepository] use its own stored
 * ledger, which is the honest record here.
 */
actual object RefreshBudgetStore {
    actual suspend fun read(): BudgetLedger? = null
    actual suspend fun reset(nowEpochMs: Long) = Unit
}
