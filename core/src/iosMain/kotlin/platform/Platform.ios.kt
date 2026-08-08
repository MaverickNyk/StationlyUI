package com.stationly.core.platform

import com.stationly.core.config.AppConfig
import com.stationly.core.model.PredictionsPayload
import com.stationly.core.model.PredictionDisplay
import com.stationly.core.model.UserSelection
import com.stationly.core.model.WidgetState
import com.stationly.core.repository.DepartureRepository
import com.stationly.core.repository.SelectionRepository
import com.stationly.core.repository.SqlStorage
import com.stationly.core.service.NetworkModule
import com.stationly.core.usecase.FormatDeparturesUseCase
import com.stationly.core.usecase.ProcessPredictionsUseCase
import com.stationly.core.usecase.SyncPredictionsUseCase
import com.stationly.core.util.GlobalBoardProcessor
import com.stationly.core.util.LineShortNames
import com.stationly.core.util.MultiLineBoardProcessor
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The App Group shared by the app, the widget extension and all Kotlin/Native
 * code — the single Kotlin-side source of truth.
 *
 * `composeApp/iosMain` reads it from here rather than re-declaring it: it was
 * previously copied into four files, and the 2026-07-25 rename from
 * `group.com.stationly.mobile` had to find every one. A missed copy does not
 * fail the build — it silently opens an empty suite, which is indistinguishable
 * from "the data was never written".
 *
 * Swift cannot import this, so both targets keep their own `AppGroupID.swift`;
 * those two plus the `application-groups` entitlements in `project.yml` are
 * the remaining copies that must move together.
 */
object IosAppGroup {
    const val ID = "group.com.stationly.shared"
}

private const val APP_GROUP_ID = IosAppGroup.ID

// ── All NSUserDefaults keys used across KMP and Swift ──
object AppGroupKeys {
    // Widget data written by KMP, read by WidgetKit extension
    const val WIDGET_STATION_NAME     = "widget_station_name"
    const val WIDGET_LINE_NAME        = "widget_line_name"
    const val WIDGET_PREDICTIONS      = "widget_predictions"
    const val WIDGET_STATUS           = "widget_status"
    const val WIDGET_DIRECTION        = "widget_direction"
    const val WIDGET_MODE             = "widget_mode"
    const val WIDGET_LAST_UPDATED     = "widget_last_updated"
    const val WIDGET_RELOAD_SIGNAL    = "widget_reload_signal"

    // Everything the widget extension needs to refresh ITSELF against the
    // REST API when the user taps refresh. The extension is a separate
    // process that can't reach KMP or open the SQLite DB, so the primary
    // selection's identity and the API coordinates have to be mirrored here
    // — without the naptanId it has a station NAME but nothing addressable.
    // Written on every widget write so they can never drift from the board
    // actually on screen.
    const val WIDGET_STATION_ID       = "widget_station_id"
    const val WIDGET_API_BASE_URL     = "widget_api_base_url"
    const val WIDGET_API_KEY          = "widget_api_key"

    // ── Multi-station widgets ──
    //
    // A widget is configured with ONE station (the iOS Weather model: several
    // widgets, each pinned to a place), so the App Group has to carry EVERY
    // tracked station rather than just the primary. Two keys do it:
    //
    //  - [WIDGET_STATIONS] is the directory the configuration picker reads —
    //    id, name, mode, lines. Small, and read while the user is in the widget
    //    editor, which is a context where the app may not be running at all.
    //  - [WIDGET_BOARD_PREFIX] + grouping id is one station's whole board.
    //    Keyed rather than nested in one blob so a push touching one station
    //    rewrites one key: NSUserDefaults has no partial write, and re-encoding
    //    every station's departures on every frame of a live stream is work
    //    that scales with how many boards the user keeps.
    //
    // The legacy single-board keys above are still written for the primary
    // station. They are what a widget added before this build reads, and the
    // window between installing the update and WidgetKit next asking for a
    // timeline is exactly when a half-migrated App Group would show "No station
    // set" on a widget that was working a second ago.
    const val WIDGET_STATIONS         = "widget_stations"
    const val WIDGET_BOARD_PREFIX     = "widget_board_"

    // Written by the EXTENSION (which platform the medium board is showing for
    // a station, and which way it last moved), declared here because their
    // LIFETIME is the station's: KMP is the only side with an event for "this
    // station is gone", so it is the only side that can clean them up.
    // Mirrored in the widget target's own AppGroupKeys.swift.
    //
    // NOT `widget_board_page_…`, which is what these were first called. That
    // name sits UNDER [WIDGET_BOARD_PREFIX], so any code that ever scans by
    // that prefix reads `widget_board_page_940GZZ…` as a station whose id is
    // `page_940GZZ…`. Nothing does today — the stale-key sweep diffs the
    // directory instead — but the trap would be invisible until someone
    // reintroduced a prefix scan, and by then it deletes real boards.
    const val WIDGET_PAGE_PREFIX     = "widget_page_"
    const val WIDGET_PAGE_DIR_PREFIX = "widget_page_dir_"
    const val WIDGET_PAGE_AT_PREFIX  = "widget_page_at_"

    // Whether one station's last in-extension refresh failed, so its widget can
    // show "tap to retry". Written by the extension for the same reason as the
    // paging keys, and cleaned up here for the same reason: KMP is the only side
    // that knows a station has been deleted.
    //
    // NOT under [WIDGET_BOARD_PREFIX] — see the note above on why that matters.
    const val WIDGET_REFRESH_FAILED_PREFIX = "widget_refresh_failed_"

    // FCM topic management — written by KMP, processed by Swift FCMBridge
    const val FCM_TOPICS              = "fcm_topics"
    const val FCM_SUBSCRIBE_PENDING   = "fcm_subscribe_pending"
    const val FCM_UNSUBSCRIBE_PENDING = "fcm_unsubscribe_pending"
    const val FCM_TOKEN               = "fcm_token"    // FCM registration token stored by AppDelegate

    // Auth state — written by Swift AuthBridge, read by KMP IosPlatformAuthProvider
    const val FIREBASE_AUTH_TOKEN     = "firebase_auth_token"
    const val FIREBASE_USER_EMAIL     = "firebase_user_email"
    const val FIREBASE_USER_NAME      = "firebase_user_display_name"
    const val FIREBASE_USER_PHOTO     = "firebase_user_photo_url"
    const val FIREBASE_USER_UID       = "firebase_user_uid"

    // Auth command protocol — KMP writes, Swift reads and executes, then clears
    const val AUTH_PENDING_COMMAND    = "auth_pending_command"
    const val AUTH_PENDING_ERROR      = "auth_pending_error"
    const val AUTH_OPERATION_SUCCESS  = "auth_operation_success"  // for non-token operations (e.g. resetConfirm)
    // Written by Swift ONLY after the Firebase call fully completes. KMP must
    // wait on THIS (not on the command key vanishing — Swift clears that
    // immediately on receipt, long before an interactive Google flow finishes).
    const val AUTH_COMMAND_DONE       = "auth_command_done"

    // Profile metadata
    const val SIGNIN_PROVIDER         = "signin_provider"
    const val MEMBER_SINCE            = "member_since"
    const val FIREBASE_USER_EMAIL_VERIFIED = "firebase_user_email_verified"
    const val FIREBASE_USER_IS_EMAIL_PROVIDER = "firebase_user_is_email_provider"

    // Deep link — written by Swift AppDelegate, consumed once by KMP on app start
    const val PENDING_RESET_OOB_CODE  = "pending_reset_oob_code"
}

// ─────────────────────────────────────────────────────────
// Widget Manager
// ─────────────────────────────────────────────────────────

class IosWidgetManager : WidgetManager {

    private val appGroupDefaults: NSUserDefaults?
        get() = NSUserDefaults(suiteName = APP_GROUP_ID)

    /**
     * Android parity (PULL model): `AndroidWidgetManager` ignores the state it
     * is handed — every method broadcasts `ACTION_UPDATE_WIDGET` and the
     * provider re-reads from SQL at render time. iOS has no provider process,
     * so the re-read happens here: every trigger (a stream frame, an FCM push,
     * the home VM reloading ANY board, a station added or removed) rebuilds the
     * App Group from SQL.
     *
     * **[state] is deliberately unused, and must stay that way.** Writing the
     * caller's state verbatim made the widget last-writer-wins: the home VM
     * reloading its second board put a non-primary station on the widget, and
     * deleting one of two boards blanked it. Now that a widget can be pinned to
     * ANY station the argument is doubly wrong — it describes one board, and
     * this has to leave every station's board correct.
     *
     * The parameter survives only because [WidgetManager] is shared with
     * Android, where it is equally ignored.
     */
    override suspend fun updateWidget(state: WidgetState) = refreshAllBoards()

    override suspend fun showWaitingState(station: String, line: String) = refreshAllBoards()

    override suspend fun clearWidgetData() = withContext(Dispatchers.IO) {
        val d = appGroupDefaults ?: return@withContext
        wipe(d)
    }

    /**
     * Rebuild every station's board in the App Group from SQL, and tell
     * WidgetKit only if something actually moved.
     *
     * ## This runs on the hot path
     * Every stream frame and every push lands here (via [updateWidget]), which
     * on a busy station is every few seconds. Two things keep that affordable,
     * and both are load-bearing rather than micro-optimisation:
     *
     *  1. **Each board is built ONCE.** The primary station's board used to be
     *     built a second time to fill the legacy keys — the same ~3 SQL queries
     *     per selection, run twice, on every frame.
     *  2. **Writes are diffed, and the reload signal is bumped only if a value
     *     changed.** That signal is what makes Swift call
     *     `WidgetCenter.reloadAllTimelines()`, so bumping it unconditionally
     *     asked WidgetKit to regenerate every widget's timeline on every push —
     *     including pushes for a station none of the user's widgets show. Apple
     *     meters reloads (~40–70/day); this was spending them on no-ops.
     *
     * The remaining cost is ~3 SQL reads per tracked (line, direction) plus one
     * JSON encode per station. At a realistic 5 stations that is tens of
     * milliseconds on `Dispatchers.IO`, and it is the price of the pull model —
     * see the note on [updateWidget] for why the caller's own state is ignored.
     */
    private suspend fun refreshAllBoards() = withContext(Dispatchers.IO) {
        val d = appGroupDefaults ?: return@withContext
        val all = Platform.sqlStorage.getAllSelections()
        if (all.isEmpty()) {
            // Nothing left to show (last board deleted / logged out): wipe to
            // the widget's designed empty state ("No station set") — the iOS
            // analog of Android's "No boards yet" fallback rows.
            wipe(d)
            return@withContext
        }

        // API coordinates are selection-independent and are what lets the
        // extension refresh ITSELF (see WIDGET_API_BASE_URL). Deliberately not
        // counted as a change: they cannot alter what is on screen, so a
        // rotated key must not cost a timeline reload.
        putIfChanged(d, AppGroupKeys.WIDGET_API_BASE_URL, AppConfig.apiBaseUrl)
        putIfChanged(d, AppGroupKeys.WIDGET_API_KEY, Platform.getApiKey())

        // ── Every station, one key each ──
        //
        // A widget is configured with one station, so all of them have to be
        // here: the user may have pinned the third one to their home screen and
        // never look at the primary. Grouped on the HUB, exactly as the app's
        // home screen groups its cards — one card is one widget is one station,
        // and a bus hub's poles must not become several entries in the picker.
        // `groupBy` preserves first-encounter order, so the picker lists them
        // in the order the user added them.
        val byStation = all.groupBy { it.groupingId }
        val boards = byStation.map { (id, selections) -> buildBoard(id, selections) }
        val directory = byStation.map { (id, selections) ->
            WidgetStationRef(
                id = id,
                name = selections.first().stationName,
                mode = selections.first().mode,
                // DISPLAY names, resolved here rather than in Swift. The
                // extension would otherwise need its own copy of the line
                // vocabulary, and two copies of a naming map that the backend
                // is expected to own one day is one copy too many.
                lines = selections.map { LineShortNames.displayName(it.line) }.distinct(),
            )
        }

        // Read BEFORE the overwrite: this is the only record of which stations
        // had keys, and it is what [forgetStations] diffs against.
        val previousIds = storedStationIds(d)
        var changed = false

        encode(ListSerializer(WidgetStationRef.serializer()), directory)?.let {
            if (putIfChanged(d, AppGroupKeys.WIDGET_STATIONS, it)) changed = true
        }
        boards.forEach { board ->
            encode(WidgetBoard.serializer(), board)?.let {
                if (putIfChanged(d, AppGroupKeys.WIDGET_BOARD_PREFIX + board.id, it)) changed = true
            }
        }
        if (forgetStations(d, previousIds - byStation.keys)) changed = true

        // ── The primary, in the legacy single-board keys ──
        //
        // Still written, and not merely for compatibility: an unconfigured
        // widget (one added before this build, or one whose station has since
        // been deleted) resolves to the primary, and these are what it reads.
        if (writeLegacy(d, boards.first())) changed = true

        // ONE signal for the whole pass, and only when a value moved. Swift's
        // observer turns this into reloadAllTimelines(); see the note above.
        if (changed) bumpReloadSignal(d)
        d.synchronize()
    }

    /**
     * One station's whole board — every line and direction the user tracks
     * there, merged the way the app's card merges them.
     *
     * ## Merged, because a station is what the widget is configured with
     * The widget used to render one (line, direction) selection because that is
     * what a board WAS. A user who tracks the Circle and the District at
     * Edgware Road and pins that station to their home screen is asking for
     * Edgware Road, not for whichever of the two happens to sort first. The
     * merge is a plain concatenation followed by
     * [GlobalBoardProcessor.processPredictions], which groups by platform and
     * orders the groups by their soonest train — the same rule the widget's own
     * REST refresh applies, so a refresh tap cannot rearrange the board.
     *
     * The per-platform line prefixes the app's board adds at a mixed platform
     * ("(Cir.) Edgware Road") are deliberately NOT applied. The widget's own
     * refresh path re-derives rows from the REST payload and has no idea which
     * line each came from, so prefixes would appear from a push and vanish on a
     * refresh tap — a board that changes shape depending on who last wrote it.
     *
     * `lineName` is the line only when the station tracks exactly one, because
     * it is what the platform header prefixes ("Piccadilly: Platform 1"). With
     * two lines on one platform that prefix would name one of them and be wrong
     * about the other, so the header falls back to "Platform 1".
     *
     * Cap 8, not the default 3: the extension needs RESERVES, not just the
     * visible window. The large family renders 6 rows, and the departed-row
     * retention (WidgetData.ticked) can only hold a board together if
     * already-departed trains are still in the payload to fall back on. Display
     * caps stay in the views (BoardMetrics.maxRows); this is purely the buffer.
     */
    private fun buildBoard(stationId: String, boards: List<UserSelection>): WidgetBoard {
        val sql = Platform.sqlStorage
        val first = boards.firstOrNull()
        // Each departure is stamped with the line it came from BEFORE the merge,
        // which is the only moment that association still exists: after the
        // flatMap the rows are one list and nothing distinguishes a Circle train
        // from a Hammersmith & City one standing at the same platform. Resolved
        // to the short label here so the extension needs no line vocabulary —
        // see PredictionDisplay.lineShort.
        //
        // Blank on bus, matching MultiLineBoardProcessor.buildGroups exactly:
        // the backend already appends the route to the destination ("39 Nags
        // Head"), so a prefix would print it twice. Stamping it anyway and
        // relying on the RENDERER to suppress it — which is what this did
        // first — is two rules for one decision, and the second copy is the one
        // that gets forgotten.
        val isBus = MultiLineBoardProcessor.isBus(first?.mode)
        val merged = boards.flatMap { selection ->
            val label = if (isBus) "" else LineShortNames.shortName(selection.line)
            sql.getPredictions(selection.station, selection.line, selection.direction)
                .map { it.copy(lineShort = label) }
        }
        val tsMs = boards.mapNotNull {
            sql.getLastUpdatedTimestamp(it.station, it.line, it.direction)
        }.maxOrNull() ?: (NSDate().timeIntervalSince1970 * 1000).toLong()

        // Worst status across the station's lines would need a severity ranker
        // the extension does not have; the FIRST line that actually has
        // something to say speaks for the board, which on a healthy station is
        // "Good Service" either way.
        val status = boards.firstNotNullOfOrNull { board ->
            sql.getLineStatus(board.mode, board.line)?.let { s ->
                val reason = StationlyFormatters.formatStatusReason(s.reason ?: "").trim()
                if (reason.isNotEmpty()) "${s.statusSeverityDescription}: $reason"
                else s.statusSeverityDescription
            }
        }
        val lines = boards.map { it.line }.distinct()

        return WidgetBoard(
            id = stationId,
            // The naptan the extension refreshes AGAINST, which is a fetch key
            // and not the hub: on bus each direction resolves to its own pole,
            // and the hub id is not something the predictions endpoint knows.
            stationId = first?.station.orEmpty(),
            stationName = first?.stationName.orEmpty(),
            lineName = lines.singleOrNull().orEmpty(),
            direction = if (boards.size == 1) first?.direction.orEmpty() else "",
            mode = first?.mode.orEmpty(),
            status = status,
            lastUpdated = tsMs / 1000,
            // Everything the extension's own refresh needs to rebuild this
            // exact board from one REST call: which naptan, and which
            // (line, direction) pairs to keep out of the payload.
            feeds = boards.map {
                WidgetFeed(it.station, it.line, it.direction, LineShortNames.shortName(it.line))
            },
            predictions = GlobalBoardProcessor.processPredictions(merged, perPlatformCap = 8),
        )
    }

    /**
     * The station ids currently holding `widget_board_*` keys, read back out of
     * the directory.
     *
     * Deriving the list from the directory rather than scanning
     * `dictionaryRepresentation()` is the point: that call materialises the
     * ENTIRE user-defaults domain — every Apple-owned key in it — and this runs
     * on every push. It is also more precise, because the directory is exactly
     * what we wrote last time.
     */
    private fun storedStationIds(d: NSUserDefaults): Set<String> =
        d.stringForKey(AppGroupKeys.WIDGET_STATIONS)
            ?.let {
                runCatching {
                    json.decodeFromString(ListSerializer(WidgetStationRef.serializer()), it)
                }.getOrNull()
            }
            ?.mapTo(mutableSetOf()) { it.id }
            .orEmpty()

    /**
     * Drop everything belonging to stations the user no longer tracks.
     *
     * It has to happen on the normal write path rather than only in [wipe],
     * because a station is usually removed while others remain — [wipe] runs
     * only when the last one goes. Left behind, a stale board is not merely
     * clutter: a widget still configured for the deleted station would keep
     * rendering its last known departures for ever, with no refresh able to
     * correct them.
     *
     * The paging and refresh-outcome keys go too. They are written by the
     * extension rather than by us, but their LIFETIME is the station's, and the
     * extension has no event to clean them up on.
     */
    private fun forgetStations(d: NSUserDefaults, removed: Set<String>): Boolean {
        if (removed.isEmpty()) return false
        removed.forEach { id ->
            d.removeObjectForKey(AppGroupKeys.WIDGET_BOARD_PREFIX + id)
            d.removeObjectForKey(AppGroupKeys.WIDGET_PAGE_PREFIX + id)
            d.removeObjectForKey(AppGroupKeys.WIDGET_PAGE_DIR_PREFIX + id)
            d.removeObjectForKey(AppGroupKeys.WIDGET_PAGE_AT_PREFIX + id)
            d.removeObjectForKey(AppGroupKeys.WIDGET_REFRESH_FAILED_PREFIX + id)
        }
        return true
    }

    /**
     * The primary station in the pre-multi-station keys, which is what an
     * unconfigured widget reads. Returns whether anything actually changed.
     */
    private fun writeLegacy(d: NSUserDefaults, board: WidgetBoard): Boolean {
        val predictionsJson = encode(
            ListSerializer(PredictionDisplay.serializer()), board.predictions
        ) ?: return false

        var changed = false
        if (putIfChanged(d, AppGroupKeys.WIDGET_STATION_ID, board.stationId)) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_STATION_NAME, board.stationName)) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_LINE_NAME, board.lineName)) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_PREDICTIONS, predictionsJson)) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_STATUS, board.status.orEmpty())) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_DIRECTION, board.direction)) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_MODE, board.mode)) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_LAST_UPDATED, board.lastUpdated.toDouble())) changed = true
        return changed
    }

    /**
     * Write only when the stored value differs.
     *
     * The saving is not the `setObject` call, which is cheap — it is the
     * RELOAD the caller would otherwise ask WidgetKit for (see
     * [refreshAllBoards]) and the cfprefsd churn of rewriting identical
     * strings several times a minute for as many stations as the user keeps.
     */
    private fun putIfChanged(d: NSUserDefaults, key: String, value: String): Boolean {
        if (d.stringForKey(key) == value) return false
        d.setObject(value, forKey = key)
        return true
    }

    private fun putIfChanged(d: NSUserDefaults, key: String, value: Double): Boolean {
        if (d.objectForKey(key) != null && d.doubleForKey(key) == value) return false
        d.setDouble(value, forKey = key)
        return true
    }

    /**
     * Encode, or `null` if it fails.
     *
     * `null` means the caller SKIPS the write, which is deliberate: leaving the
     * last good value in place keeps a board on screen, where writing "" or
     * "[]" would blank a working widget because one field failed to serialise.
     */
    private fun <T> encode(serializer: KSerializer<T>, value: T): String? =
        runCatching { json.encodeToString(serializer, value) }.getOrNull()

    private fun wipe(d: NSUserDefaults) {
        // Clear the station id too — leaving it behind would let a refresh
        // tap repopulate the widget with the board the user just deleted.
        // The API coordinates are selection-independent, so they stay.
        //
        // The multi-station keys go the same way. A configured widget whose
        // station is gone falls back to the (now empty) legacy keys and renders
        // "No station set", which is the honest answer after a sign-out.
        forgetStations(d, storedStationIds(d))
        d.removeObjectForKey(AppGroupKeys.WIDGET_STATIONS)
        d.removeObjectForKey(AppGroupKeys.WIDGET_STATION_ID)
        d.removeObjectForKey(AppGroupKeys.WIDGET_STATION_NAME)
        d.removeObjectForKey(AppGroupKeys.WIDGET_LINE_NAME)
        d.removeObjectForKey(AppGroupKeys.WIDGET_PREDICTIONS)
        d.removeObjectForKey(AppGroupKeys.WIDGET_STATUS)
        d.removeObjectForKey(AppGroupKeys.WIDGET_DIRECTION)
        d.removeObjectForKey(AppGroupKeys.WIDGET_MODE)
        d.removeObjectForKey(AppGroupKeys.WIDGET_LAST_UPDATED)
        bumpReloadSignal(d)
        d.synchronize()
    }

    // Bumping the signal tells Swift WidgetReloadObserver to call
    // WidgetCenter.reloadAllTimelines()
    private fun bumpReloadSignal(d: NSUserDefaults) {
        val sig = d.integerForKey(AppGroupKeys.WIDGET_RELOAD_SIGNAL)
        d.setInteger(sig + 1, forKey = AppGroupKeys.WIDGET_RELOAD_SIGNAL)
    }

    override suspend fun formatForWidget(predictions: List<UserSelection>): WidgetState {
        return WidgetState(
            stationName = predictions.firstOrNull()?.stationName ?: "",
            lineName    = predictions.firstOrNull()?.line ?: "",
            predictions = emptyList(),
            status      = null,
            lastUpdated = NSDate().timeIntervalSince1970.toLong(),
            direction   = predictions.firstOrNull()?.direction ?: "",
            mode        = predictions.firstOrNull()?.mode ?: ""
        )
    }
}

// ─────────────────────────────────────────────────────────
// Notification Manager
// ─────────────────────────────────────────────────────────

class IosNotificationManager : NotificationManager {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun subscribeToTopics(topics: List<String>) = withContext(Dispatchers.IO) {
        val existing = pendingList(AppGroupKeys.FCM_TOPICS)
        defaults.setObject((existing + topics).distinct(), forKey = AppGroupKeys.FCM_TOPICS)
        val pending = pendingList(AppGroupKeys.FCM_SUBSCRIBE_PENDING)
        defaults.setObject((pending + topics).distinct(), forKey = AppGroupKeys.FCM_SUBSCRIBE_PENDING)
        defaults.synchronize()
        val (stations, lines) = parseTopics(topics)
        LiveStreamManager.subscribeTopics(stations, lines)
        Unit
    }

    override suspend fun unsubscribeFromTopics(topics: List<String>) = withContext(Dispatchers.IO) {
        val existing = pendingList(AppGroupKeys.FCM_TOPICS)
        defaults.setObject(existing - topics.toSet(), forKey = AppGroupKeys.FCM_TOPICS)
        val pending = pendingList(AppGroupKeys.FCM_UNSUBSCRIBE_PENDING)
        defaults.setObject((pending + topics).distinct(), forKey = AppGroupKeys.FCM_UNSUBSCRIBE_PENDING)
        defaults.synchronize()
        val (stations, lines) = parseTopics(topics)
        LiveStreamManager.unsubscribeTopics(stations, lines)
        Unit
    }

    /**
     * "Station_{naptanId}" / "LineStatus_{mode}_{line}" — the same topic
     * identifiers FCM already uses — mapped onto the stream's station/line
     * subscribe ids so a station add/remove keeps both channels in sync
     * without a second call site.
     */
    private fun parseTopics(topics: List<String>): Pair<List<String>, List<String>> {
        val stations = topics.mapNotNull { it.removePrefix("Station_").takeIf { s -> s != it } }
        val lines = topics.mapNotNull {
            it.removePrefix("LineStatus_").takeIf { rest -> rest != it }?.substringAfter("_", missingDelimiterValue = "")
        }.filter { it.isNotEmpty() }
        return stations to lines
    }

    override suspend fun clearAllTopics() = withContext(Dispatchers.IO) {
        val all = pendingList(AppGroupKeys.FCM_TOPICS)
        if (all.isNotEmpty()) {
            val pending = pendingList(AppGroupKeys.FCM_UNSUBSCRIBE_PENDING)
            defaults.setObject((pending + all).distinct(), forKey = AppGroupKeys.FCM_UNSUBSCRIBE_PENDING)
        }
        defaults.removeObjectForKey(AppGroupKeys.FCM_TOPICS)
        defaults.removeObjectForKey(AppGroupKeys.FCM_SUBSCRIBE_PENDING)
        defaults.synchronize()
        Unit
    }

    override suspend fun handleNotification(payload: Map<String, String>) {
        // Handled by Swift AppDelegate / UNUserNotificationCenterDelegate
        // FCM payload processing is done by PushPayloadBridge.processPayload()
    }

    /**
     * The device's FCM registration token, for backend registration.
     *
     * Reads the APP GROUP suite first: sign-out wipes the whole standard
     * NSUserDefaults domain (`clearAll` → `removePersistentDomainForName`),
     * which used to take the token with it. The next login then read "" and
     * skipped registration silently, leaving the backend with no token for
     * that user and every push failing "No registered tokens for uid".
     * Swift's AppDelegate writes both locations; the standard-domain read
     * stays as a fallback for a token persisted before that change.
     */
    override suspend fun registerDevice(): String =
        NSUserDefaults(suiteName = APP_GROUP_ID).stringForKey(AppGroupKeys.FCM_TOKEN)
            ?: defaults.stringForKey(AppGroupKeys.FCM_TOKEN)
            ?: ""

    private fun pendingList(key: String): List<String> =
        (defaults.arrayForKey(key) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
}

// ─────────────────────────────────────────────────────────
// Storage Manager
// ─────────────────────────────────────────────────────────

class IosStorageManager : StorageManager {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun saveSelections(selections: List<UserSelection>) = withContext(Dispatchers.IO) {
        defaults.setObject(
            json.encodeToString(ListSerializer(UserSelection.serializer()), selections),
            forKey = "selections"
        )
        defaults.synchronize()
        Unit
    }

    override suspend fun loadSelections(): List<UserSelection> = withContext(Dispatchers.IO) {
        val str = defaults.stringForKey("selections") ?: return@withContext emptyList()
        try { json.decodeFromString(ListSerializer(UserSelection.serializer()), str) }
        catch (_: Exception) { emptyList() }
    }

    override suspend fun saveLineStatus(lineId: String, statusJson: String) = withContext(Dispatchers.IO) {
        defaults.setObject(statusJson, forKey = "line_status_$lineId")
        defaults.synchronize()
        Unit
    }

    override suspend fun loadLineStatus(lineId: String): String? = withContext(Dispatchers.IO) {
        defaults.stringForKey("line_status_$lineId")
    }

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        allKeys().filter {
            it.startsWith("line_status_") || it.startsWith("predictions_") ||
            it.startsWith("cached_") || it == "selections"
        }.forEach { defaults.removeObjectForKey(it) }
        defaults.synchronize()
        Unit
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        // removePersistentDomainForName is the safe, API-sanctioned way to wipe all app UserDefaults
        val bundleId = NSBundle.mainBundle.bundleIdentifier
        if (bundleId != null) {
            defaults.removePersistentDomainForName(bundleId)
        } else {
            allKeys().forEach { defaults.removeObjectForKey(it) }
        }
        defaults.synchronize()
        Unit
    }

    override suspend fun saveString(key: String, value: String) = withContext(Dispatchers.IO) {
        defaults.setObject(value, forKey = key)
        defaults.synchronize()
        Unit
    }

    override suspend fun loadString(key: String): String? = withContext(Dispatchers.IO) {
        defaults.stringForKey(key)
    }

    // dictionaryRepresentation().keys is Set<Any?> — filterIsInstance avoids the broken List cast
    private fun allKeys(): List<String> =
        defaults.dictionaryRepresentation().keys.filterIsInstance<String>()
}

// ─────────────────────────────────────────────────────────
// Platform singleton
// ─────────────────────────────────────────────────────────

private const val IOS_API_KEY = "f7d6c5b4-3a2b-1c0d-e9f8-a7b6c5d4e3f2"

actual object Platform {
    private var _sqlStorage: SqlStorage? = null

    actual val widgetManager: WidgetManager       = IosWidgetManager()
    actual val notificationManager: NotificationManager = IosNotificationManager()
    actual val storageManager: StorageManager     = IosStorageManager()

    actual val sqlStorage: SqlStorage
        get() = _sqlStorage ?: SqlStorage(createDatabase(DriverFactory())).also { _sqlStorage = it }

    actual fun getPlatformName(): String = "iOS"
    actual fun getApiKey(): String       = IOS_API_KEY
    actual fun getEnvironment(): AppEnvironment {
        val env = NSBundle.mainBundle.objectForInfoDictionaryKey("StationlyEnvironment") as? String
        return if (env == "staging") AppEnvironment.STAGING else AppEnvironment.PRODUCTION
    }
    actual fun getBaseUrl(): String = com.stationly.core.config.AppConfig.apiBaseUrl

    actual suspend fun getAuthToken(): String? = withContext(Dispatchers.IO) {
        NSUserDefaults.standardUserDefaults.stringForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN)
    }

    actual suspend fun signOutFromAuthExpiry() {
        // iOS Firebase sign-out runs in Swift; enqueue the "signOut" command for the
        // AuthBridge to pick up. Token slot is cleared so any in-flight request that
        // re-reads it sees no token rather than the expired one.
        val ud = NSUserDefaults.standardUserDefaults
        if (ud.stringForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN) == null) return
        ud.removeObjectForKey(AppGroupKeys.FIREBASE_AUTH_TOKEN)
        ud.setObject("signOut", forKey = "auth_pending_command")
    }
}

// ─────────────────────────────────────────────────────────
// FCM Payload Bridge
// Called by Swift AppDelegate when an FCM push notification arrives.
// Swift serialises the push userInfo dict to JSON and calls processPayload().
// ─────────────────────────────────────────────────────────

/**
 * Diagnostic ring buffer for the push pipeline, written to the APP GROUP so it
 * can be pulled off a device with
 * `devicectl device copy from --domain-type appGroupDataContainer`.
 *
 * Why not os_log/print: `log stream` cannot target a device from recent macOS,
 * and `devicectl device process launch --console` does not capture `print()`
 * from a Compose/KMP process — so on-device push behaviour was effectively
 * unobservable. Silent-push failures give no user-visible signal by
 * definition, so without this the only evidence is "the widget didn't change",
 * which cannot distinguish "APNs never delivered it" from "we parsed it and
 * dropped it".
 *
 * Bounded to the last 40 entries; cheap enough to leave enabled.
 */
object PushTrace {
    private const val KEY = "push_trace"

    fun log(msg: String) {
        try {
            val d = NSUserDefaults(suiteName = APP_GROUP_ID)
            val existing = (d.arrayForKey(KEY) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val ts = NSDate().timeIntervalSince1970.toLong()
            d.setObject((existing + "$ts $msg").takeLast(40), forKey = KEY)
            d.synchronize()
        } catch (_: Exception) {
            // Diagnostics must never break the path they observe.
        }
    }
}

object PushPayloadBridge {

    private val processUseCase: ProcessPredictionsUseCase by lazy {
        ProcessPredictionsUseCase(
            departureRepository = DepartureRepository(
                NetworkModule.tflApi,
                Platform.storageManager,
                Platform.sqlStorage,
                SyncPredictionsUseCase(Platform.sqlStorage)
            ),
            widgetManager          = Platform.widgetManager,
            storageManager         = Platform.storageManager,
            formatDeparturesUseCase = FormatDeparturesUseCase(),
            // iOS persists selections only to SQLite — pass it so FCM can find
            // the primary board and actually update the widget.
            sqlStorage             = Platform.sqlStorage
        )
    }

    /**
     * Fire-and-forget variant for foreground deliveries where Swift doesn't
     * need to await the write (the WidgetReloadObserver picks up the signal).
     */
    fun processPayload(jsonString: String) {
        GlobalScope.launch(Dispatchers.IO) {
            processPayloadAndWait(jsonString)
        }
    }

    /**
     * Process an FCM message and return only when SQLite + the App Group have
     * been written. Swift sees this as `processPayloadAndWait(jsonString:completionHandler:)`
     * — AppDelegate awaits it in didReceiveRemoteNotification before calling
     * the background-fetch completion handler, so iOS doesn't suspend the
     * process mid-write and the widget reload sees fresh data.
     *
     * The argument is the WHOLE APNs userInfo dict as JSON. A real Syncer
     * topic push looks like:
     *   { "from": "/topics/Station_940GZZLUASL",   ← or LineStatus_tube_victoria
     *     "payload": "{…inner JSON string…}",       ← PredictionsPayload or LineStatus
     *     "aps": { "content-available": 1 }, … }
     * which is why decoding the top level directly as PredictionsPayload (the old
     * code) dropped every real push — the data lives one level down, exactly
     * like Android's remoteMessage.data["payload"].
     */
    suspend fun processPayloadAndWait(jsonString: String) {
        try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val topic = (root["from"] as? JsonPrimitive)?.contentOrNull
            val inner = (root["payload"] as? JsonPrimitive)?.contentOrNull
            PushTrace.log("kmp:enter topic=${topic ?: "-"} innerLen=${inner?.length ?: -1}")

            when {
                topic?.contains("LineStatus_") == true && inner != null -> {
                    PushTrace.log("kmp:route=lineStatus")
                    processUseCase.processLineStatusUpdate(json.decodeFromString(inner))
                }

                topic?.contains("Station_") == true && inner != null -> {
                    val sid = topic.substringAfter("Station_")
                    PushTrace.log("kmp:route=station sid=$sid")
                    processUseCase.processStationUpdate(
                        topicStationId = sid,
                        payload = json.decodeFromString(inner)
                    )
                }

                // No topic (direct/test push) — sniff the payload shape.
                inner != null -> {
                    val parsed = json.parseToJsonElement(inner).jsonObject
                    if ("lines" in parsed) {
                        processUseCase.processStationUpdate(null, json.decodeFromString(inner))
                    } else if ("statusSeverityDescription" in parsed) {
                        processUseCase.processLineStatusUpdate(json.decodeFromString(inner))
                    }
                }

                // Legacy/manual pushes that put the PredictionsPayload at the top level.
                "lines" in root ->
                    processUseCase.processStationUpdate(null, json.decodeFromString(jsonString))
            }
        } catch (e: Exception) {
            // This used to be a bare println — invisible on device. A decode
            // mismatch between the Syncer's payload and PredictionsPayload lands here
            // and would otherwise look identical to "the push never arrived".
            PushTrace.log("kmp:EXCEPTION ${e::class.simpleName}: ${e.message?.take(160)}")
            println("[PushPayloadBridge] Failed to process payload: ${e.message}")
        }
    }
}
