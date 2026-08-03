package com.stationly.core.repository

import com.stationly.core.model.sdui.*
import com.stationly.core.service.SduiApiService
import com.stationly.core.model.UserSelection
import com.stationly.core.platform.StorageManager
import com.stationly.core.usecase.StationLifecycleUseCase
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
        provider: String?,
        deviceId: String? = null,
        deviceInfo: DeviceInfo? = null
    ): List<SubscribedStation> {
        val result = try {
            // 1. Sync profile to Firestore (creates or updates the user record).
            //    deviceId registers this device's session so the backend only
            //    releases subscriptions on the last device's logout.
            val profile = apiService.syncProfile(
                SyncProfileRequest(
                    uid = uid,
                    email = email,
                    displayName = displayName,
                    photoURL = photoURL,
                    signInProvider = provider,
                    deviceId = deviceId,
                    deviceInfo = deviceInfo
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
                        // Without this a restore groups bus boards per POLE, so
                        // one stop comes back as several identically-named cards.
                        parentStationId = station.parentStationId.orEmpty(),
                        stationName = station.name,
                        direction = station.direction,
                        destinations = emptyList(),
                        destinationIds = emptyList()
                    )
                )
            }
            
            profile.stations
        } catch (e: Exception) {
            println("[UserSyncRepository] Profile sync failed: ${e.message}")
            throw e
        }
        
        return result
    }

    /**
     * Non-destructive reconcile of local state against the cloud profile.
     *
     * Unlike [syncUserAndGetSavedStations] (which wipes everything and is only
     * safe at login), this diffs the cloud `stations` against the local
     * selections and applies the minimal set of changes — so it can run
     * mid-session (triggered by a `user_sync` FCM push or an app-foreground
     * tick) without flickering the board or dropping live predictions for
     * stations that didn't change.
     *
     * - Stations in the cloud but missing locally → [StationLifecycleUseCase.setupStation]
     *   (saves, subscribes FCM topics, fetches initial data).
     * - Stations local but no longer in the cloud → [StationLifecycleUseCase.discardStation]
     *   (unsubscribes, clears data, removes the selection).
     * - Unchanged stations are left completely untouched.
     *
     * Returns the fetched cloud profile so the caller can reconcile other
     * fields (e.g. display name) on the platform side.
     */
    suspend fun reconcile(uid: String, lifecycle: StationLifecycleUseCase): UserProfileResponse {
        val profile = apiService.getUserProfile(uid)

        // Safety: only reconcile against the profile we actually asked for. If
        // the response doesn't match (shouldn't happen — getUserProfile throws
        // on non-200), leave local state untouched rather than risk wiping a
        // board the user is watching.
        if (profile.uid != uid) return profile

        // Identity = station id + line (matches the uniqueness UserService
        // uses when adding/removing a station server-side).
        // DIRECTION is part of the key. Without it, both directions of one line
        // that resolve to the SAME naptan (the normal case on tube) collapse to
        // one key, and a restore silently drops one of them.
        fun key(id: String, line: String, direction: String) = "$id|$line|$direction"

        val cloud = profile.stations
        val local = sqlStorage.getAllSelections()
        val cloudKeys = cloud.map { key(it.id, it.line, it.direction) }.toSet()
        val localKeys = local.map { key(it.station, it.line, it.direction) }.toSet()

        // Remove local selections no longer present in the cloud.
        local.filter { key(it.station, it.line, it.direction) !in cloudKeys }.forEach { sel ->
            // Pass the survivors so shared topics are not torn down: several
            // boards can sit on one naptan (two routes at one bus pole, several
            // lines at one station).
            val remaining = sqlStorage.getAllSelections().filterNot {
                it.station == sel.station && it.line == sel.line && it.direction == sel.direction
            }
            lifecycle.discardStation(sel, clearSelectionInRepo = true, remaining = remaining)
        }

        // Add cloud stations missing locally.
        cloud.filter { key(it.id, it.line, it.direction) !in localKeys }.forEach { st ->
            lifecycle.setupStation(
                UserSelection(
                    mode = st.mode,
                    line = st.line,
                    station = st.id,
                    parentStationId = st.parentStationId.orEmpty(),
                    stationName = st.name,
                    direction = st.direction,
                    destinations = emptyList(),
                    destinationIds = emptyList()
                ),
                isFirstTime = false
            )
        }

        return profile
    }
}
