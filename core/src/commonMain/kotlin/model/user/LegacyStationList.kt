package com.stationly.core.model.user

import com.stationly.core.model.UserSelection
import com.stationly.core.model.sdui.SubscribedStation
import com.stationly.core.model.sdui.UserProfileResponse

/**
 * The v1 flat station list — `users/{uid}.stations` — as a pair of conversions.
 *
 * ## Why this exists at all
 * The account holds TWO lists, deliberately: `boards` (the v2 model, with
 * filters) and `stations` (v1's flat rows). The subscription registry reads the
 * UNION of them, and they are never merged back into one field.
 *
 * While Android was frozen, only v1 wrote `stations` and only iOS wrote
 * `boards`, so nothing needed to convert between them. Android v2 does: during
 * the rollout an account can hold a v1 device and a v2 device at once, and
 * `syncStations` is a full REPLACE — it diffs the old ids against the new ones
 * to inc/dec subscription counts, so a post that omits a board deletes it. A v2
 * device that writes only `boards` leaves the v1 device reading a list that
 * still describes the world as it was before the upgrade.
 *
 * So v2 dual-writes: `boards` as the authority, and the same content flattened
 * through [toSubscribedStations] into `stations` beside it. A v1 device then
 * sees a **degraded but correct** view rather than a stale or emptied one.
 *
 * ## Why it is one definition and not four
 * It was four. `ProfileViewModel`, `SelectionViewModel` and `SummaryViewModel`
 * each built `SubscribedStation` by hand, and `UserSyncRepository` unpacked it
 * by hand on the way back. **Two of the three outbound copies dropped
 * `parentStationId`**, which is not cosmetic: a station restored without it
 * groups on its own naptan, so one bus hub comes back as a card per pole, all
 * with the same name. The fix belongs at the one place the conversion is
 * defined, which is here.
 *
 * ## What the flat form cannot carry, and why that is fine
 * Filters. [BoardFilter] has no representation in `SubscribedStation` and gets
 * no smuggled encoding here — a v1 client cannot render a filtered board, so a
 * filter it cannot honour is better dropped than half-described. The board list
 * remains the authority; this is the lossy projection, and it is only ever read
 * by a client that predates the thing it loses.
 */
fun List<Board>.toSubscribedStations(): List<SubscribedStation> =
    flatMap { board ->
        board.selections.map { selection ->
            SubscribedStation(
                // The RESOLVED pole, which is what departures are fetched from.
                // On a bus hub this differs per line and per direction.
                id = selection.naptanId,
                name = board.name,
                line = selection.line,
                mode = selection.mode,
                direction = selection.direction,
                // The hub. Load-bearing on restore — see the KDoc above.
                //
                // Always written, even where it equals `id`. `UserSelection`
                // treats blank as "same as station" and `Board.id` has already
                // resolved that fallback, so writing it explicitly costs
                // nothing and removes a second place where the fallback could
                // be got wrong.
                parentStationId = board.id,
            )
        }
    }

/**
 * The way back: the flat list as the rows the fetch pipeline runs on.
 *
 * Filters come back empty, because the flat form never carried them. That is
 * the correct reading rather than a lossy one — an empty filter means "show
 * everything", which is exactly what a board restored from a v1 client should
 * do. It is not a filter that failed to load; there was never one to load.
 */
fun List<SubscribedStation>.toUserSelections(): List<UserSelection> =
    map { station ->
        UserSelection(
            mode = station.mode,
            line = station.line,
            station = station.id,
            // Nullable on the wire because the backend may not persist it yet.
            // Blank falls back to grouping on `station`, which is the pre-hub
            // behaviour and the only safe answer when the hub is unknown.
            parentStationId = station.parentStationId.orEmpty(),
            stationName = station.name,
            direction = station.direction,
            destinations = emptyList(),
            destinationIds = emptyList(),
        )
    }

/**
 * Which of the account's two lists **is** the account, right now.
 *
 * `boards` wins whenever it says anything, and `stations` answers when it does
 * not. That order is the whole of AV2-4.3's task (d), and it is one function
 * because it was three: the login restore, the board setup that follows it, and
 * the mid-session reconcile each decided it separately, and one of the three
 * hand-built [UserSelection] from [SubscribedStation] inline — a fifth copy of
 * the conversion whose other four copies are what this file exists to end.
 *
 * ## `isUsable` is load-bearing in both directions
 * A board with no selections says nothing: it is what a truncated payload, or a
 * response from a backend that predates this shape, decodes to. Those must not
 * suppress the legacy fallback — a caller seeing "one board" that happens to be
 * empty would restore nothing and show a home screen of cards that never
 * populate. Equally, a genuinely EMPTY `boards` array is a real answer (the user
 * deleted their last board on another device) and the fallback is then correct
 * too: an account with no boards and no stations folds to nothing either way.
 *
 * ## The third rung is not here
 * "Else fall back to the local rows the migration preserved" cannot be expressed
 * against a profile, because it is the decision to leave local state ALONE. It
 * lives where local state is visible: `UserSyncRepository.reconcileBoards`
 * returns early when the account has never had a board written
 * (`boardsUpdatedAt == 0`) and this device holds boards, and the login restore
 * only ever runs against a cloud profile it has just fetched.
 */
fun UserProfileResponse.effectiveBoards(): List<Board> {
    val usable = boards.filter { it.isUsable }
    return usable.ifEmpty { Board.fromSelections(stations.toUserSelections()) }
}
