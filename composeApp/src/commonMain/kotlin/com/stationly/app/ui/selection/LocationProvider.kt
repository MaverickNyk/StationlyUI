package com.stationly.app.ui.selection

interface LocationProvider {
    suspend fun getCurrentLocation(): Pair<Double, Double>?
}

object NoOpLocationProvider : LocationProvider {
    override suspend fun getCurrentLocation(): Pair<Double, Double>? = null
}
