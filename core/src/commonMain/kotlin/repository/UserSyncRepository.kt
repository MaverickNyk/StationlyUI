package com.stationly.core.repository

import com.stationly.core.model.sdui.*
import com.stationly.core.service.SduiApiService
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.StorageManager
import kotlinx.coroutines.flow.first

class UserSyncRepository(
    private val apiService: SduiApiService,
    private val sqlStorage: SqlStorage,
    private val storageManager: StorageManager
) {
    /**
     * Syncs user profile with backend and refreshes local database with saved stations.
     * Returns the list of subscribed stations for FCM topic handling.
     */
    suspend fun syncUserAndGetSavedStations(
        uid: String, 
        email: String, 
        displayName: String?, 
        photoURL: String?, 
        provider: String?
    ): List<SubscribedStation> {
        val result = try {
            // 1. Sync profile to Firestore (updates or creates user)
            android.util.Log.d("UserSync", ">>> [USER_SYNC] Starting sync for $email (uid: $uid)")
            val profile = apiService.syncProfile(
                SyncProfileRequest(
                    uid = uid,
                    email = email,
                    displayName = displayName,
                    photoURL = photoURL,
                    signInProvider = provider
                )
            )
            
            android.util.Log.d("UserSync", ">>> [USER_SYNC] Sync success. Profile has ${profile.stations.size} saved stations.")
            
            // 2. Wipe local database and cache to ensure clean state
            sqlStorage.clearAllData()
            storageManager.clearCache()
            
            // 3. Populate local selections from profile
            profile.stations.forEach { station ->
                android.util.Log.d("UserSync", ">>> [USER_SYNC] Restoring station: ${station.name} (${station.id}) on line ${station.line}")
                sqlStorage.saveSelection(
                    UserSelection(
                        mode = station.mode,
                        line = station.line,
                        station = station.id,
                        stationName = station.name,
                        direction = station.direction,
                        destinations = emptyList(),
                        destinationIds = emptyList()
                    )
                )
            }
            
            profile.stations
        } catch (e: Exception) {
            android.util.Log.e("UserSync", "!!! [USER_SYNC] Profile sync failed", e)
            throw e
        }
        
        return result
    }
}
