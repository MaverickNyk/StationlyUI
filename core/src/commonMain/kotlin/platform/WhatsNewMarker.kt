package com.stationly.core.platform

/**
 * "Show the What's new screen once": a durable key, written by the Android
 * v1 → 2.0 database migration (`V1Upgrade.mark`) and spent by the screen
 * itself (`WhatsNew` in composeApp).
 *
 * A plain string in the durable store, so shared code can read it through
 * `StorageManager.loadDurable` without knowing which platform wrote it. Only
 * Android's migration ever writes it: a fresh install, a later update and
 * every iOS install never see the screen.
 */
object WhatsNewMarker {
    const val KEY = "whats_new_v2_pending"
    const val PENDING = "pending"
}
