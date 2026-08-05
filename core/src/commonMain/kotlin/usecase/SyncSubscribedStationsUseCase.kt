package com.stationly.core.usecase

import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SubscribedStation
import com.stationly.core.platform.StorageManager
import com.stationly.core.service.SduiApiService

/**
 * Pushes the user's CURRENT board list to the backend, so another device (and a
 * reinstall) sees what this one sees.
 *
 * Deliberately separate from [StationLifecycleUseCase.discardStation], which
 * owns the LOCAL teardown — topics, cached rows, the widget. Deleting a board
 * has to do both, and the two were only ever wired together at the call site:
 * every screen that deleted a board re-implemented this mapping, and a screen
 * that forgot it left the backend holding a board the user had removed, ready
 * for cloud restore to bring back.
 *
 * Always sends the WHOLE list rather than a delta — `/user/sync/stations`
 * replaces what it is given, so a full list cannot half-apply.
 */
class SyncSubscribedStationsUseCase(
    private val sduiApi: SduiApiService,
    private val storageManager: StorageManager,
) {
    /**
     * Best effort by design: the local delete has already happened and is the
     * source of truth for this device, so a failed sync must not surface as a
     * failed delete. The next save or delete re-sends the full list.
     *
     * @return true when the backend accepted the list.
     */
    suspend fun sync(selections: List<UserSelection>): Boolean {
        val uid = storageManager.loadString(UID_KEY) ?: return false
        return runCatching { sduiApi.syncStations(uid, selections.map { it.toSubscribedStation() }) }
            .getOrDefault(false)
    }

    private fun UserSelection.toSubscribedStation() = SubscribedStation(
        // The FETCH naptan, matching what the selection flow sends. The hub
        // rides along in `parentStationId` so a restore can rebuild the card
        // grouping without re-resolving every pole.
        id = station,
        parentStationId = parentStationId,
        name = stationName,
        line = line,
        mode = mode,
        direction = direction,
    )

    private companion object {
        const val UID_KEY = "firebase_user_uid"
    }
}
