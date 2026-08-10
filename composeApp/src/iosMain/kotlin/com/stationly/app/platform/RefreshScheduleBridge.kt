package com.stationly.app

import com.stationly.core.platform.BackgroundBoardRefresher
import com.stationly.core.platform.RefreshScheduleAppGroup
import com.stationly.core.repository.RefreshPolicyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Swift's entry point to the refresh schedule.
 *
 * Exported from `composeApp` rather than `core` for the same reason
 * [PushPayloadBridge] is: Kotlin/Native only generates the
 * completionHandler/async bridge for suspend functions in the ROOT framework
 * module, so `core`'s own suspend functions never appear in the ObjC header.
 * These are thin delegates; the logic lives in `RefreshPolicyRepository` and
 * `RefreshScheduleAppGroup`.
 *
 * Every call here starts on the main thread (K/N's launch-thread restriction
 * for exported suspend functions) and hops off it immediately.
 */
object RefreshScheduleBridge {

    /**
     * Refetch the policy if the cached copy has aged out, then republish the
     * schedule. Called on launch and foreground.
     *
     * Returns true if the widget's schedule actually changed, which is the only
     * case where reloading timelines is worth the budget it costs.
     */
    suspend fun syncAndPublish(): Boolean = withContext(Dispatchers.Default) {
        if (RefreshPolicyRepository.isStale()) {
            RefreshPolicyRepository.refreshFromBackend()
        }
        RefreshScheduleAppGroup.publish()
    }

    /**
     * Force a policy refetch regardless of TTL, then republish — the response
     * to a `policy.update` push, which is the backend saying "what you have is
     * stale" ahead of the TTL.
     */
    suspend fun forcePolicyRefresh(): Boolean = withContext(Dispatchers.Default) {
        RefreshPolicyRepository.refreshFromBackend()
        RefreshScheduleAppGroup.publish()
    }

    /**
     * Begin a boost. [requestedMinutes] is a request, not an instruction: the
     * policy's own ceiling caps it, so a sender asking for eight hours gets
     * ninety minutes. Pass 0 for the policy default.
     *
     * The stored boost carries an ABSOLUTE deadline, which is what lets it
     * expire without a stop push ever arriving.
     */
    suspend fun startBoost(tierId: String, requestedMinutes: Int, reason: String): Boolean =
        withContext(Dispatchers.Default) {
            RefreshPolicyRepository.startBoost(
                tierId = tierId,
                requestedMinutes = requestedMinutes,
                reason = reason,
            )
            RefreshScheduleAppGroup.publish()
        }

    /** End a boost early. The absolute deadline means this is an optimisation,
     *  never a correctness requirement. */
    suspend fun stopBoost(): Boolean = withContext(Dispatchers.Default) {
        RefreshPolicyRepository.stopBoost()
        RefreshScheduleAppGroup.publish()
    }

    /** Minutes between background wakes for the tier in force, or 0 when the
     *  current tier disables them (overnight). Read by the BGTask scheduler. */
    suspend fun backgroundTaskMinutes(): Int = withContext(Dispatchers.Default) {
        RefreshPolicyRepository.decideNow().backgroundTaskMinutes
    }

    /**
     * Fetch every tracked board and republish the widget — the work a background
     * wake or a `widget.refresh` push exists to do.
     *
     * Goes through the app's real pipeline (network → SQLite → App Group), not
     * the widget extension's cut-down fallback, so the board in the app ends up
     * as fresh as the one on the home screen. See [BackgroundBoardRefresher].
     */
    suspend fun refreshAllBoards(): Boolean = withContext(Dispatchers.Default) {
        val refreshed = BackgroundBoardRefresher.refreshAll()
        // Republish afterwards as well: a refresh can cross a window boundary,
        // and the schedule the widget reads should reflect the tier it is
        // actually in by the time the reload lands.
        RefreshScheduleAppGroup.publish()
        refreshed
    }

    /** A one-line summary for the on-device trace — a widget extension has no
     *  console, so this is how a schedule is inspected on a real device. */
    suspend fun describeDecision(): String = withContext(Dispatchers.Default) {
        val d = RefreshPolicyRepository.decideNow()
        "tier=${d.tierId} interval=${d.intervalMinutes}m bg=${d.backgroundTaskMinutes}m " +
            "boost=${d.boostActive} degraded=${d.degraded} spent=${d.spentToday}"
    }
}
