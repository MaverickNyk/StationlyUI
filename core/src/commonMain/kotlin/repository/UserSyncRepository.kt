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
    /**
     * Non-destructive reconcile against the **board** list — the iOS path.
     *
     * Same diff as [reconcile], against [UserProfileResponse.boards] instead of
     * [UserProfileResponse.stations], and restoring the FILTER and the
     * CONFIGURATION with the board. That is the reason this is not a parameter
     * on the existing method: a legacy record has neither, so a shared
     * implementation would have to decide whether "absent from the payload"
     * means "cleared" or "leave what is there", and the honest answer differs
     * per schema.
     *
     * Restoring a filtered board unfiltered is worse than not restoring it: the
     * board looks right and shows exactly the trains the user excluded.
     */
    suspend fun reconcileBoards(uid: String, lifecycle: StationLifecycleUseCase): UserProfileResponse {
        val profile = apiService.getUserProfile(uid)
        if (profile.uid != uid) return profile

        fun key(id: String, line: String, direction: String) = "$id|$line|$direction"

        // A board with no selections says nothing — see [Board.isUsable]. It is
        // what a truncated or superseded payload decodes to, and dropping those
        // makes the guard below treat the account as having no board list, which
        // is what stops this deleting every board on the device.
        val cloud = profile.boards.filter { it.isUsable }
        val local = sqlStorage.getAllSelections()

        // ── The one case where the cloud is NOT the truth ──
        //
        // `boardsUpdatedAt == 0` means no client has ever written a board list
        // for this account, so `boards` is not a record of anything the user
        // did — it is derived server-side from the LEGACY `stations` array. On a
        // shared account that array holds whatever Android last saved, which is
        // one board, because Android wipes the rest before saving.
        //
        // Reconciling against it would then delete every board this device holds
        // but that one — silently, on an ordinary sync, on the first launch
        // after updating. The user did nothing to ask for it.
        //
        // So when the account has no real list and this device HAS boards, local
        // wins: nothing is removed, and the caller's next push claims them. It
        // happens once per account; the moment a real write lands,
        // `boardsUpdatedAt` is non-zero and the normal diff resumes.
        //
        // A second, narrower bail: the response HAD boards and none survived the
        // usability filter. Deliberately NOT "cloud is empty" — a genuinely
        // empty cloud list is the user deleting their last board on another
        // device, and that must still propagate here.
        val servedUnusableBoards = profile.boards.isNotEmpty() && cloud.isEmpty()
        if ((profile.boardsUpdatedAt == 0L || servedUnusableBoards) && local.isNotEmpty()) return profile

        // Flattened to the same (naptan, line, direction) rows the app runs on,
        // because that is the level a board is SET UP and TORN DOWN at — a topic
        // subscription and a prediction table are per (line, direction), not per
        // station. The board is the truth on the wire; the flat rows are its
        // projection.
        val cloudSelections = cloud.flatMap { it.toSelections() }
        val cloudKeys = cloudSelections.map { key(it.station, it.line, it.direction) }.toSet()
        val localByKey = local.associateBy { key(it.station, it.line, it.direction) }

        local.filter { key(it.station, it.line, it.direction) !in cloudKeys }.forEach { sel ->
            val remaining = sqlStorage.getAllSelections().filterNot {
                it.station == sel.station && it.line == sel.line && it.direction == sel.direction
            }
            lifecycle.discardStation(sel, clearSelectionInRepo = true, remaining = remaining)
        }

        cloudSelections.forEach { restored ->
            val existing = localByKey[key(restored.station, restored.line, restored.direction)]
            if (existing == null) {
                lifecycle.setupStation(restored, isFirstTime = false)
                return@forEach
            }
            // A board present on both sides can still have CHANGED — the user
            // edited its filter on another device. Comparing only the identity
            // triple (as the legacy path does) makes that edit invisible: the
            // key matches, the board is left alone, and the two devices show
            // different trains for what is nominally the same board.
            //
            // Every field the filter matches on has to be compared, or an edit
            // that only touched one of them syncs into storage and is never
            // re-applied to the departures already on this device. Taking a
            // whole branch changes `patternIds` and `viaKeys` and NOTHING else,
            // so the first two lines alone would have missed it entirely.
            val filterChanged = existing.filterMode != restored.filterMode ||
                existing.destinationIds != restored.destinationIds ||
                existing.viaStationIds != restored.viaStationIds ||
                existing.viaKeys != restored.viaKeys ||
                existing.patternIds != restored.patternIds
            if (filterChanged) lifecycle.updateBoardFilter(restored)
        }

        // No configuration is adopted here. Appearance is device-local — see
        // UserSettings — so a board arriving from another device keeps whatever
        // arrangement THIS device already had for it, or the defaults.

        return profile
    }

    /**
     * Non-destructive reconcile against the **legacy** `stations` list.
     *
     * Android's path, unchanged. iOS uses [reconcileBoards] — pointing it here
     * would diff against a list Android replaces wholesale, so an iPhone would
     * delete its own boards the first time the account's Android device saved
     * one.
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
