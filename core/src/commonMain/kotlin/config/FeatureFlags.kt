package com.stationly.core.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a feature is on for this app: `flag.<key>` in home-config.
 *
 * Fails closed. Absent, unread or anything but "true" is off, so an app that has
 * never heard from the backend runs without the feature. The backend decides per
 * platform: `stationly-backend/docs/FEATURE_FLAGS.md`.
 */
object FeatureFlags {

    const val STATION_STATUS = "stationStatus.show"

    private val _on = MutableStateFlow<Set<String>>(emptySet())

    /** The keys that are on, for a surface that must redraw when one changes. */
    val on: StateFlow<Set<String>> = _on.asStateFlow()

    fun isOn(key: String): Boolean = key in _on.value

    /** Adopt a served config. Called by [SduiConfig] only. */
    fun refresh(strings: Map<String, String>) {
        _on.value = strings.filter { (k, v) -> k.startsWith(PREFIX) && v == "true" }.keys.map { it.removePrefix(PREFIX) }.toSet()
    }

    fun resetForTest() {
        _on.value = emptySet()
    }

    private const val PREFIX = "flag."
}
