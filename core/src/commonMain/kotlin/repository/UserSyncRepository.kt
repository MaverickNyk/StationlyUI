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
            // 1. Sync profile to Firestore (creates or updates the user record)
            val profile = apiService.syncProfile(
                SyncProfileRequest(
                    uid = uid,
                    email = email,
                    displayName = displayName,
                    photoURL = photoURL,
                    signInProvider = provider
                )
            )
            
            // 2. Wipe local database and cache to ensure clean state
            sqlStorage.clearAllData()
            storageManager.clearCache()
            
            // 3. Restore local selections from the cloud profile
            profile.stations.forEach { station ->
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
            android.util.Log.e("UserSyncRepository", "Profile sync failed", e)
            throw e
        }
        
        return result
    }
}
