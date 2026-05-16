package com.stationly.app

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.stationly.app.ui.selection.LocationProvider
import kotlinx.coroutines.tasks.await

class AndroidLocationProvider(private val context: Context) : LocationProvider {
    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): Pair<Double, Double>? = try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val result = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
        result?.let { Pair(it.latitude, it.longitude) }
    } catch (e: Exception) {
        null
    }
}
