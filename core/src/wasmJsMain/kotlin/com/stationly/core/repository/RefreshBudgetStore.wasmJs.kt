package com.stationly.core.repository

import com.stationly.core.model.refresh.BudgetLedger

/** The web build has no widget and nothing metering it. */
actual object RefreshBudgetStore {
    actual suspend fun read(): BudgetLedger? = null
    actual suspend fun reset(nowEpochMs: Long) = Unit
}
