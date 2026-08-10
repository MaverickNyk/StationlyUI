package com.stationly.core.platform

import com.stationly.core.model.WidgetState
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.SyncPredictionsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetch every tracked board from the network and republish the widget, from a
 * context where no UI exists.
 *
 * ## Why the widget extension's own refresh cannot serve this
 * `WidgetRefreshService` (Swift, in the extension) exists because that process
 * can reach neither KMP nor the SQLite file, so it re-implements a minimum of
 * the pipeline and writes only the App Group — a documented, accepted gap. The
 * APP has none of those limits: when it is awake it can run the real pipeline
 * and leave SQLite and the App Group agreeing with each other.
 *
 * So a background wake goes through here rather than through the extension's
 * fallback, and the board the user next opens in the app is as fresh as the one
 * on their home screen. That is the whole reason the background task is worth
 * its battery: it repairs both stores, not just the one on the surface.
 *
 * Reuses [DepartureRepository.refreshBoards], which already deduplicates by
 * stop and by line and runs the remainder concurrently — a background slice is
 * short, and N stations must cost roughly one round trip rather than N.
 */
object BackgroundBoardRefresher {

    private val repository: DepartureRepository by lazy {
        DepartureRepository(
            NetworkModule.tflApi,
            Platform.storageManager,
            Platform.sqlStorage,
            SyncPredictionsUseCase(Platform.sqlStorage),
        )
    }

    /**
     * Returns true if at least one board was refreshed.
     *
     * Every failure mode collapses to false rather than throwing: the caller is
     * a background task that must report an outcome to iOS and reschedule
     * regardless, and a thrown exception there costs future scheduling.
     */
    suspend fun refreshAll(): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            val selections = Platform.sqlStorage.getAllSelections()
            if (selections.isEmpty()) return@runCatching false

            val result = repository.refreshBoards(selections)

            // Rebuild the App Group from what just landed in SQL. The argument
            // is ignored by design — `IosWidgetManager.updateWidget` rebuilds
            // EVERY station's board rather than trusting one caller's state,
            // which is what keeps a widget pinned to the user's third station
            // correct. See its docstring.
            Platform.widgetManager.updateWidget(
                WidgetState(
                    stationName = "",
                    lineName = "",
                    predictions = emptyList(),
                    status = null,
                    lastUpdated = 0L,
                )
            )
            // `allFailed` is the only outcome that means the network was
            // unreachable; a partial result still improved some boards.
            !result.allFailed
        }.getOrElse { false }
    }
}
