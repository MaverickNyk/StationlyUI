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
import com.stationly.core.model.user.BoardConfig
import com.stationly.core.repository.UserSettings
import com.stationly.core.util.LineNameStore
import com.stationly.core.util.LineShortNames
import com.stationly.core.util.LineStatusRanker
import com.stationly.core.util.MultiLineBoardProcessor
import com.stationly.core.util.StationlyFormatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The App Group shared by the app, the widget extension and all Kotlin/Native
 * code — the single Kotlin-side source of truth.
 *
 * `composeApp/iosMain` reads it from here rather than re-declaring it.
 *
 * ## Why this reads the Info.plist rather than holding a literal
 * It used to be the constant `group.com.stationly.shared`, copied into four
 * compilation units (here, both `AppGroupID.swift` files, and the
 * `application-groups` entitlements), and the 2026-07-25 rename from
 * `group.com.stationly.mobile` had to find every one. A missed copy does not
 * fail the build — it silently opens an empty suite, which is
 * indistinguishable from "the data was never written".
 *
 * Since the staging/production split the value also has to DIFFER per build:
 * the two apps have different bundle ids, install side by side, and would
 * otherwise share one container. So all four now resolve the same
 * `StationlyAppGroup` Info.plist key, expanded at build time from
 * `STATIONLY_APP_GROUP` in `Config/Staging.xcconfig` / `Config/Production.xcconfig`.
 *
 * Necessarily a `val` and no longer a `const val` — the value is not known
 * until the bundle is read. Downstream `private const val APP_GROUP_ID`
 * declarations had to widen for the same reason.
 */
object IosAppGroup {
    val ID: String by lazy {
        val id = NSBundle.mainBundle.objectForInfoDictionaryKey("StationlyAppGroup") as? String
        require(!id.isNullOrEmpty()) {
            "StationlyAppGroup missing from Info.plist — check STATIONLY_APP_GROUP in Config/*.xcconfig"
        }
        id
    }
}

private val APP_GROUP_ID: String get() = IosAppGroup.ID

// ── All NSUserDefaults keys used across KMP and Swift ──
object AppGroupKeys {
    // Widget data written by KMP, read by WidgetKit extension
    const val WIDGET_STATION_NAME     = "widget_station_name"
    /**
     * CANONICAL line id. The extension's legacy refresh keys the predictions
     * payload on it, so it must stay an identity — [WIDGET_LINE_DISPLAY] is the
     * one to render.
     */
    const val WIDGET_LINE_NAME        = "widget_line_name"
    /**
     * The same line as a person reads it ("Hammersmith & City").
     *
     * Added rather than fixing [WIDGET_LINE_NAME] in place because that key has
     * two consumers wanting two different things: the refresh needs the id to
     * look up, the header needs a name to show. Swift bridged the gap with
     * `.capitalized`, which rendered "Hammersmith-City" on the board.
     */
    const val WIDGET_LINE_DISPLAY     = "widget_line_display"
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

    /**
     * Whether the account signed OUT, as opposed to simply having no boards.
     *
     * ## Why the wipe is not enough on its own
     * The extension is a separate process that can refill itself. Its timeline
     * build fetches whenever what it holds is more than two minutes old
     * (`DepartureBoardProvider.staleAfterSeconds`), over REST authenticated by
     * the API KEY rather than the user's token — and the station it fetches for
     * lives in an AppIntent configuration that nothing on this side can reach or
     * erase. So a sign-out that only deletes data is a sign-out the widget can
     * undo, and the boards come back a couple of minutes later looking exactly
     * as they did.
     *
     * ## Why it is not just "no stations"
     * Because that already means something else. `refreshAllBoards` wipes to the
     * same empty state when the user deletes their last board, and that user is
     * still signed in and must keep the refresh path they have. The two are only
     * distinguishable if the sign-out says so.
     *
     * ## Both edges belong to Swift
     * Raised by [IosWidgetManager.clearWidgetData] (reached only from
     * `StationLifecycleUseCase.cleanupAll`) **and** by `AuthBridge.logout()`,
     * which is the last step of every teardown — so no ordering between the two
     * halves of a sign-out can lose it. Lowered by `AuthBridge.persistUserIdentity`,
     * which runs only when Firebase has produced an actual user.
     *
     * It is NOT lowered on a board write, which was the first attempt and is
     * wrong in both directions: a `reconcileBoards` or live-stream frame still
     * in flight at sign-out can write a board with nobody signed in, and a user
     * who signs back in with nothing saved never reaches a board write at all
     * — leaving their widget telling them to sign in while they are signed in.
     */
    const val WIDGET_SIGNED_OUT       = "widget_signed_out"

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

    /**
     * Suffixes on [WIDGET_PAGE_PREFIX] for the boards that hold more than one
     * page — the large widget stacks two pagers (`BoardSection` in the widget
     * target), so one station has up to three stored positions.
     *
     * Listed here rather than spelled out at the removal site because that is
     * the only thing this side does with them: the extension writes them, and
     * KMP's whole job is knowing when the station they belong to is gone. A
     * section added in Swift and not added here leaks one integer per deleted
     * station — harmless in isolation, and exactly the kind of drift the
     * hand-kept contract between these two files exists to catch.
     */
    val WIDGET_PAGE_SECTIONS = listOf("", "#u", "#d")

    // Whether one station's last in-extension refresh failed, so its widget can
    // show "tap to retry". Written by the extension for the same reason as the
    // paging keys, and cleaned up here for the same reason: KMP is the only side
    // that knows a station has been deleted.
    //
    // NOT under [WIDGET_BOARD_PREFIX] — see the note above on why that matters.
    const val WIDGET_REFRESH_FAILED_PREFIX = "widget_refresh_failed_"

    // ── Refresh scheduling ──
    //
    // The cadence the widget extension applies, precomputed by KMP. A SEGMENTED
    // schedule rather than a single current decision, because the extension is
    // the only thing running when the app is not: handed one decision, a phone
    // untouched since last evening would still apply rush-hour cadence at 03:00
    // and spend the user's battery doing it. See `RefreshSegment`.
    const val WIDGET_REFRESH_SCHEDULE    = "widget_refresh_schedule"
    const val WIDGET_REFRESH_SCHEDULE_AT = "widget_refresh_schedule_at"

    // The reload tally, WRITTEN BY THE EXTENSION and read here.
    //
    // Direction matters: a WidgetKit timeline build is the thing Apple meters,
    // it happens in the extension, and the app is not running for most of them.
    // So the extension is the only process that can count honestly, and KMP is
    // a reader — see RefreshBudgetStore. KMP writes these only to open a fresh
    // window or to clear on wipe.
    const val WIDGET_BUDGET_WINDOW_START = "widget_budget_window_start"
    const val WIDGET_BUDGET_COUNT        = "widget_budget_count"

    // The build the tally above was accumulated by. A change means the count
    // was produced by different code and can no longer be trusted, so the
    // window is restarted — see `RefreshScheduleAppGroup.resetLedgerOnNewBuild`.
    const val WIDGET_BUDGET_BUILD        = "widget_budget_build"

    // Written by Swift when WidgetKit hands the extension its push token
    // (iOS 26+), read by KMP so it can be registered with the backend. The
    // token addresses the WIDGET, not the app — a push to it reloads the
    // timeline without launching us.
    const val WIDGET_PUSH_TOKEN          = "widget_push_token"

    // NOTE: the `fcm_topics` / `fcm_subscribe_pending` / `fcm_unsubscribe_pending`
    // / `fcm_token` keys that used to live here are GONE, along with
    // FirebaseMessaging on iOS. They were a ledger written by KMP for a Swift
    // bridge to flush into `Messaging.subscribe`, and that bridge no longer
    // exists — see the note atop `AppDelegate.swift` for what replaced it.
    // Do not reintroduce them: on this platform a "topic" is now only ever a
    // live-stream subscription id (see `IosNotificationManager.parseTopics`).

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

/**
 * What a line with no status record is reported as.
 *
 * TODO(i18n): the home board takes this word from `homeConfig`
 * (`board.good_service_label`). Remote config is not reachable from this write
 * path, and a widget saying "Good Service" in English while the app says it in
 * another language is a smaller wrong than a widget saying nothing — but the two
 * should share one source once config is available here.
 */
private const val GOOD_SERVICE = "Good Service"

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

    /**
     * The session ended — leave nothing for the widget to show, and say so.
     *
     * The flag is the half that makes this stick. Reached only from
     * `StationLifecycleUseCase.cleanupAll` (sign-out, account deletion, forced
     * logout), never from the ordinary "last board deleted" path, which is why
     * it can mean what it says. See [AppGroupKeys.WIDGET_SIGNED_OUT].
     */
    override suspend fun clearWidgetData() = withContext(Dispatchers.IO) {
        val d = appGroupDefaults ?: return@withContext
        d.setBool(true, forKey = AppGroupKeys.WIDGET_SIGNED_OUT)
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

        // NOTE: [AppGroupKeys.WIDGET_SIGNED_OUT] is deliberately NOT touched
        // here, in either direction. Both edges belong to Swift `AuthBridge`,
        // which is the only side that knows whether there is a session — see
        // the key's own doc. Lowering it on a board write was tried and is
        // wrong twice over: a stray `reconcileBoards` or live-stream frame
        // still in flight at sign-out can produce a board with nobody signed
        // in, and a user who signs back in with no boards saved never reaches a
        // board write at all.
        val all = Platform.sqlStorage.getAllSelections()
        if (all.isEmpty()) {
            // ── Empty on purpose, or empty in transit? ──
            //
            // A login restore clears SQLite before refilling it from the cloud,
            // so for the length of that operation this table is empty for a user
            // who has stations and is signing in to get them back. Wiping here
            // publishes "you have no stations" to every placed widget, and the
            // widget has no way to know it was told something transient.
            //
            // Storage cannot tell the two apart; only the caller's INTENT can,
            // which is why the restore raises this flag and nothing else does.
            // Skipping the write leaves the previous contents in place, which is
            // stale for a few seconds and then corrected by the board writes the
            // restore itself produces — where the wipe would have been wrong for
            // the same few seconds and visible on the home screen.
            if (WidgetRestore.inProgress) return@withContext
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
        // The headers built below name their lines, and this is what decides
        // whether they use the backend's short forms or the local fallback
        // table. Idempotent and one small string read; without it a widget
        // written before the home screen has ever loaded would bake the
        // fallback names into the App Group. See LineNameStore.
        LineNameStore.ensureLoaded()

        // Loaded FIRST, because it is what brings [UserSettings] into memory and
        // the ordering just below reads that same store. Arranging the directory
        // before the store was loaded would see every board UNPOSITIONED, tie on
        // the sort key, and silently fall back to insertion order — which is
        // precisely the bug the ordering exists to fix.
        val prefs = boardPrefs()

        // ── The directory is in the HOME SCREEN's order, not insertion order ──
        //
        // `groupBy` preserves first-encounter order, so this listed stations in
        // the order they were ADDED. The home screen has never agreed with that:
        // it sorts by each board's own `position` via [UserSettings.ordered],
        // which is what `SummaryScreen` uses. Dragging a station to the top of
        // the list therefore moved it on the home screen and nowhere else.
        //
        // Three surfaces read "first" off this list and every one of them means
        // the user's first: the configuration picker, the gallery's
        // `recommendations()`, and the station a newly added widget defaults to
        // (`StationEntityQuery.defaultResult`). They can no longer disagree with
        // the arrangement the user can actually see.
        val grouped = all.groupBy { it.groupingId }
        val byStation = UserSettings.ordered(grouped.keys.toList())
            .mapNotNull { id -> grouped[id]?.let { id to it } }
        // ONE clock for the whole write, not one per station. Every board here
        // is produced by the same pass and its block ordering is judged against
        // "has this train already left" — reading the clock per station would
        // let two boards written together disagree about that at a boundary,
        // for no benefit.
        val nowMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val boards = byStation.map { (id, selections) ->
            buildBoard(id, selections, prefs[id] ?: BoardConfig(), nowMs)
        }
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
                // The raw ids alongside them, for matching rather than
                // rendering — see `WidgetStationRef.lineIds`. Lower-cased here
                // so every consumer compares the same thing.
                lineIds = selections.map { it.line.trim().lowercase() }
                    .filter { it.isNotEmpty() }.distinct(),
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
            // ── Never move a board BACKWARDS in time ──
            //
            // Two processes write these keys. This one rebuilds from SQLite;
            // the widget extension's own refresh button fetches REST and writes
            // the App Group directly, because it cannot open the app's SQLite
            // (the documented gap in `WidgetRefreshService`). Nothing then
            // reconciled them, and plain last-writer-wins made the app the
            // loser's opponent: a stream frame landing seconds after a widget
            // refresh rewrote that board from SQL rows the refresh had already
            // superseded, stamped with SQL's older `getLastUpdatedTimestamp`.
            //
            // Observed on device: three boards refreshed by the widget at
            // T, all three showing timestamps ~140s BEFORE T a moment later —
            // so the user's tap fetched fresh departures, displayed them, and
            // then had them replaced by staler ones while the "ago" timer
            // counted backwards.
            //
            // With two writers and no shared lock, the only sound merge rule is
            // that the FRESHER OBSERVATION WINS. This is that rule, and it is
            // safe in both directions: the app's own timestamp advances on
            // every sync, so a genuinely newer app payload still overwrites
            // normally — it is only the stale-over-fresh case that is refused.
            if (shouldYieldToStored(d, board)) return@forEach
            encode(WidgetBoard.serializer(), board)?.let {
                if (putIfChanged(d, AppGroupKeys.WIDGET_BOARD_PREFIX + board.id, it)) changed = true
            }
        }
        // `grouped`, not `byStation`: the arrangement above turned the latter
        // into an ordered list, and this only needs the SET of ids that survive.
        if (forgetStations(d, previousIds - grouped.keys)) changed = true

        // ── The primary, in the legacy single-board keys ──
        //
        // Still written, for one case: a widget added before the configuration
        // existed, which has no station id to resolve and reads these keys
        // directly.
        //
        // It is no longer where a DELETED station's widget lands. That used to
        // fall through to here — a silent jump to whichever board happened to be
        // primary, through a path that can be staler than the per-station keys.
        // A widget whose station is gone now renders its own removed state
        // (`StationResolver`, in the widget target) and fetches nothing. These
        // keys are reached only by a widget with no configuration at all.
        //
        // "Primary" now means the FIRST BOARD ON THE HOME SCREEN, since `boards`
        // follows the user's arrangement. It used to mean the oldest.
        if (writeLegacy(d, boards.first())) changed = true

        // ONE signal for the whole pass, and only when a value moved. Swift's
        // observer turns this into reloadAllTimelines(); see the note above.
        if (changed) bumpReloadSignal(d)
        d.synchronize()
    }

    /**
     * One station's whole board — every line and direction the user tracks
     * there, grouped by exactly the code the app's own board is grouped by.
     *
     * ## Merged, because a station is what the widget is configured with
     * The widget used to render one (line, direction) selection because that is
     * what a board WAS. A user who tracks the Circle and the District at
     * Edgware Road and pins that station to their home screen is asking for
     * Edgware Road, not for whichever of the two happens to sort first.
     *
     * ## The grouping is [MultiLineBoardProcessor.buildGroups], not a flat list
     * This used to concatenate the predictions and hand the flat result to
     * [GlobalBoardProcessor.processPredictions], leaving the extension to group
     * it again on the far side of the wire. Two consequences, both live bugs:
     *
     *  - **A flat list cannot express a bus board.** The pole a departure was
     *    fetched from is the bus group key, and it exists only BEFORE the merge;
     *    once concatenated, two poles reporting `platform = ""` are one
     *    indistinguishable block with both directions interleaved. Smithwood
     *    Close is two naptans and must be two pages.
     *  - **The headers were re-invented in Swift** from `lineName.capitalized`
     *    plus the raw platform, which is why the widget said "Platform 1
     *    (Westbound)" where the home board says "Northern Platform 1 Westbound".
     *
     * [MultiLineBoardProcessor.headerFor] already produces the right string, so
     * the fix is to SEND it rather than to write a second implementation of it.
     * Every ordering rule (unassigned last, the pin, the sort) travels with it
     * for free — the extension could not have applied any of them.
     *
     * ## Reserves, not display depth
     * `rowCap` is [WIDGET_ROW_RESERVE] rather than the user's `rowsPerPlatform`:
     * the extension re-derives its ETA labels every minute from a timeline built
     * once, so it needs trains BEHIND the visible ones to shift into view, plus
     * departed ones to hold when a platform empties. Display caps stay in the
     * views (BoardMetrics.maxRows). Everything else in [prefs] — the sort, the
     * pin — does apply, so a station arranged on the home screen is arranged the
     * same way on the widget.
     *
     * `lineName` is the canonical line only when the station tracks exactly one;
     * with two lines the fallback header has no single line to name.
     */
    private fun buildBoard(
        stationId: String,
        boards: List<UserSelection>,
        prefs: BoardConfig,
        /** Shared across every board in this write — see the call site. */
        nowMs: Long,
    ): WidgetBoard {
        val sql = Platform.sqlStorage
        val first = boards.firstOrNull()
        // One Feed per tracked (line, direction), carrying the naptan it was
        // FETCHED from — that is the bus group key, and this is the last moment
        // it exists. See MultiLineBoardProcessor.Feed.stationId.
        val isBus = MultiLineBoardProcessor.isBus(first?.mode)
        val feeds = boards.map { selection ->
            MultiLineBoardProcessor.Feed(
                stationId = selection.station,
                line = selection.line,
                direction = selection.direction,
                predictions = sql.getPredictions(
                    selection.station, selection.line, selection.direction
                ),
            )
        }
        val groups = MultiLineBoardProcessor.buildGroups(
            feeds, isBus, prefs,
            rowCap = MultiLineBoardProcessor.ROW_RESERVE,
            // Block order is fixed HERE, at write time, and the extension never
            // re-orders (that would move pages under the user's arrows). So the
            // one thing it has to get right is not leading the board with a
            // platform whose trains have all left — which the payload can
            // contain, since SQL holds the previous fetch's rows too.
            nowMs = nowMs,
        )
        val tsMs = boards.mapNotNull {
            sql.getLastUpdatedTimestamp(it.station, it.line, it.direction)
        }.maxOrNull() ?: (NSDate().timeIntervalSince1970 * 1000).toLong()

        // ── The status the widget's one strip shows ──
        //
        // WORST FIRST, and the line is NAMED — the same two rules the home
        // board's strip follows, through the same [LineStatusRanker] it calls.
        //
        // This used to be "the first line that has anything to say", with a note
        // that ranking "would need a severity ranker the extension does not
        // have". True, and it was the wrong side to look: the extension cannot
        // rank, but this code can, and the ranker has been in commonMain all
        // along. The old rule produced the failure it was warned about at any
        // multi-line station — at King's Cross a part-closed Northern was hidden
        // behind a healthy Victoria that merely sorted first, so the widget said
        // "Good Service" while the app's own board said "Northern Part Closure".
        //
        // Only the leading entry travels. The home board ROTATES through the
        // rest every 8s, which a widget cannot do — it is a sequence of static
        // snapshots with no animation loop (IOS_WIDGET_DESIGN.md §2.1), and a
        // strip that changed its subject on the per-minute timeline was tried
        // for the marquee and read as broken. The worst line is the one that
        // changes a journey anyway.
        val status = LineStatusRanker.rotation(
            boards.distinctBy { it.mode to it.line }.map { board ->
                val s = sql.getLineStatus(board.mode, board.line)
                LineStatusRanker.Entry(
                    lineLabel = LineShortNames.displayName(board.line),
                    // A line we have no status for is NOT a disruption. Left
                    // blank it would rank as unknown-severity and lead the
                    // board with an empty sentence; "Good Service" is both what
                    // the home board substitutes and what the strip already
                    // falls back to when this field arrives empty.
                    severity = s?.statusSeverityDescription?.takeIf { it.isNotBlank() }
                        ?: GOOD_SERVICE,
                    reason = StationlyFormatters.formatStatusReason(s?.reason ?: "").trim(),
                )
            }
        ).firstOrNull()?.let { entry ->
            val label = LineStatusRanker.label(entry)
            // "Northern Part Closure: reason" — the strip splits on the first
            // colon and renders what precedes it bold, exactly as the home
            // board bolds the label and trails the reason.
            if (entry.reason.isNotBlank()) "$label: ${entry.reason}" else label
        }
        val lines = boards.map { it.line }.distinct()

        val wireGroups = groups.map { group ->
            WidgetGroup(
                key = group.key,
                header = group.header,
                headerVariants = group.headerVariants,
                label = group.label,
                mixesLines = group.mixesLines,
                // The line label is stamped onto the row here because after this
                // it is unrecoverable: on the wire a block is a list of
                // departures and nothing distinguishes a Circle train from an
                // H&C one standing at the same platform. buildGroups has already
                // blanked it on bus, where the backend appends the route to the
                // destination itself.
                predictions = group.departures.map { it.prediction.copy(lineShort = it.lineShort) },
            )
        }

        return WidgetBoard(
            id = stationId,
            // The naptan the extension refreshes AGAINST, which is a fetch key
            // and not the hub: on bus each direction resolves to its own pole,
            // and the hub id is not something the predictions endpoint knows.
            stationId = first?.station.orEmpty(),
            stationName = first?.stationName.orEmpty(),
            lineName = lines.singleOrNull().orEmpty(),
            lineDisplay = lines.singleOrNull()?.let { LineShortNames.displayName(it) }.orEmpty(),
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
            // The user's display depth, carried across the process boundary
            // because the extension cannot read the preference itself — see
            // [WidgetBoard.rowCap].
            rowCap = prefs.rowCap,
            groups = wireGroups,
            // Flattened FROM the groups rather than built alongside them, so the
            // legacy flat keys and an older extension build can never show a
            // different board from the one the groups describe.
            predictions = wireGroups.flatMap { it.predictions },
        )
    }

    /**
     * Every board's arrangement, by board id.
     *
     * ## Why the widget can read these at all
     * The extension cannot read them for itself — the settings are in the app's
     * own NSUserDefaults suite and the widget lives in the App Group — so "the
     * widget cannot see the board arrangement" was an open item from the day the
     * settings screen landed. It was only ever true of the EXTENSION. This runs
     * in the app, on the same store the settings screen writes, so the
     * arrangement is applied here and nothing crosses the process boundary
     * except the finished blocks.
     *
     * Read through [UserSettings], which owns the storage key and the type. Both
     * used to be duplicated in this file, because the store lived in `composeApp`
     * where core cannot see it — copies that failed SILENTLY when they drifted,
     * since a renamed key or field reads as absent and the widget just reverts
     * to a default arrangement with nothing logged anywhere.
     *
     * An unread store is defaults everywhere — a widget arranged as the board
     * was before it had settings, which is the right fallback and never an empty
     * board.
     */
    private suspend fun boardPrefs(): Map<String, BoardConfig> {
        UserSettings.ensureLoaded()
        return UserSettings.configs.value
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
    /**
     * Whether the stored board is fresher than the one we are about to write,
     * and recently enough for that to still be true.
     *
     * ## Why the guard is BOUNDED
     * The race it exists for is a seconds-scale one: a stream frame landing just
     * after the widget's own REST refresh, rewriting that board from SQL rows
     * the refresh had already superseded. Refusing the write is right there.
     *
     * Refusing it FOREVER is not, and an unbounded rule quietly does that,
     * because the timestamp is a property of the DEPARTURES while this write
     * also carries everything else about the board: the station's name, its
     * mode, its feeds, and the arrangement the user just chose on the settings
     * screen. A quiet station's SQL timestamp can sit still for minutes, and in
     * that window an unbounded guard would swallow a re-sort, a re-pin and a
     * rename alike — the user changes their board and the widget refuses,
     * with nothing on screen to explain why.
     *
     * [STALE_WRITE_GRACE_SECONDS] bounds it. Inside the window the extension's
     * fresher rows are protected, which is the whole race; outside it the app
     * wins, so no change can be held off for longer than that no matter how
     * quiet the station is.
     */
    private fun shouldYieldToStored(d: NSUserDefaults, board: WidgetBoard): Boolean {
        val stored = storedBoardLastUpdated(d, board.id)
        if (board.lastUpdated >= stored) return false
        val nowSeconds = (NSDate().timeIntervalSince1970).toLong()
        return nowSeconds - stored < STALE_WRITE_GRACE_SECONDS
    }

    /**
     * How long a board written by the widget extension is protected from being
     * rewritten with older SQL. Comfortably longer than the race it guards (a
     * stream frame arriving seconds later) and short enough that no user-visible
     * change waits on it. See [shouldYieldToStored].
     */
    private val STALE_WRITE_GRACE_SECONDS = 90L

    /**
     * The `lastUpdated` already stored for one station's board, or 0.
     *
     * Parsed out of the JSON rather than decoding the whole [WidgetBoard]: this
     * runs per station on every push, and every field except this one would be
     * decoded only to be thrown away.
     *
     * A board that fails to parse reads as 0, which lets the write through —
     * the right way to fail, since an unreadable board is exactly the one most
     * in need of replacing.
     */
    private fun storedBoardLastUpdated(d: NSUserDefaults, id: String): Long =
        d.stringForKey(AppGroupKeys.WIDGET_BOARD_PREFIX + id)?.let { raw ->
            runCatching {
                json.parseToJsonElement(raw).jsonObject["lastUpdated"]?.jsonPrimitive?.long
            }.getOrNull()
        } ?: 0L

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
            AppGroupKeys.WIDGET_PAGE_SECTIONS.forEach { section ->
                d.removeObjectForKey(AppGroupKeys.WIDGET_PAGE_PREFIX + id + section)
            }
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
        // Same two-writer rule as the per-station boards above, bounded the same
        // way and for the same reason: the extension's legacy refresh writes
        // these flat keys directly, and rewriting them from older SQL would walk
        // an unconfigured widget's "ago" timer backwards — but these keys also
        // carry the PRIMARY station's identity, so an unbounded guard would pin
        // a stale station name on the widget after a reorder.
        val storedTs = d.doubleForKey(AppGroupKeys.WIDGET_LAST_UPDATED).toLong()
        val nowSeconds = (NSDate().timeIntervalSince1970).toLong()
        if (board.lastUpdated < storedTs && nowSeconds - storedTs < STALE_WRITE_GRACE_SECONDS) {
            return false
        }
        val predictionsJson = encode(
            ListSerializer(PredictionDisplay.serializer()), board.predictions
        ) ?: return false

        var changed = false
        if (putIfChanged(d, AppGroupKeys.WIDGET_STATION_ID, board.stationId)) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_STATION_NAME, board.stationName)) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_LINE_NAME, board.lineName)) changed = true
        if (putIfChanged(d, AppGroupKeys.WIDGET_LINE_DISPLAY, board.lineDisplay)) changed = true
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

/**
 * Routes the app's subscription intent to the LIVE STREAM. Nothing here talks
 * to Firebase Cloud Messaging any more.
 *
 * ## Why the FCM half is gone
 * This class used to do two things per call: drive the stream, and maintain an
 * FCM topic ledger in `NSUserDefaults` for a Swift bridge to flush into
 * `Messaging.subscribe`. The second half has been removed entirely — iOS no
 * longer links FirebaseMessaging, so those queues had no reader, and a ledger
 * nothing consumes is worse than no ledger: it looks like a working
 * subscription path to anyone reading the code.
 *
 * What iOS uses instead: the live stream while the app is foregrounded (below),
 * the adaptive refresh schedule while it is not (`RefreshPolicyEvaluator`), and
 * direct APNs pushes for immediate triggers (`WidgetPushService` on the
 * backend). See the note atop `AppDelegate.swift`.
 *
 * The METHOD NAMES still say "topics" because [NotificationManager] is shared
 * with Android, where they genuinely are FCM topics. On this side a topic is
 * just an identifier that [parseTopics] turns into a stream subscription.
 */
class IosNotificationManager : NotificationManager {

    override suspend fun subscribeToTopics(topics: List<String>) = withContext(Dispatchers.IO) {
        val (stations, lines) = parseTopics(topics)
        LiveStreamManager.subscribeTopics(stations, lines)
    }

    override suspend fun unsubscribeFromTopics(topics: List<String>) = withContext(Dispatchers.IO) {
        val (stations, lines) = parseTopics(topics)
        LiveStreamManager.unsubscribeTopics(stations, lines)
    }

    /**
     * "Station_{naptanId}" / "LineStatus_{mode}_{line}" mapped onto the
     * stream's station/line subscribe ids, so a station add/remove keeps the
     * stream in sync without a second call site.
     *
     * The identifiers are the ones Android's FCM topics use, which is
     * deliberate: one vocabulary for "what this device cares about", whatever
     * transport a platform happens to use to act on it.
     */
    private fun parseTopics(topics: List<String>): Pair<List<String>, List<String>> {
        val stations = topics.mapNotNull { it.removePrefix("Station_").takeIf { s -> s != it } }
        val lines = topics.mapNotNull {
            it.removePrefix("LineStatus_").takeIf { rest -> rest != it }?.substringAfter("_", missingDelimiterValue = "")
        }.filter { it.isNotEmpty() }
        return stations to lines
    }

    override suspend fun clearAllTopics() = withContext(Dispatchers.IO) {
        LiveStreamManager.unsubscribeAll()
    }

    override suspend fun handleNotification(payload: Map<String, String>) {
        // Nothing to do. Push handling is entirely Swift-side now
        // (`WidgetPushRegistrar.handle`), and the envelopes it receives are
        // SIGNALS — "refetch", "policy changed", "boost" — so there is no
        // payload for shared code to decode.
    }

    /**
     * No device token to report from Kotlin.
     *
     * This used to return the FCM registration token, which the shared
     * `registerDevice()` flow posted to `/user/fcm/register`. iOS no longer has
     * one: the device is addressed by its APNs tokens, which are issued to the
     * app and to the widget extension and registered from Swift
     * (`WidgetPushRegistrar`) where they actually live.
     *
     * The empty string is the established "nothing to register" signal — every
     * caller already guards on `isNotBlank()` — so this correctly no-ops the
     * FCM registration path on iOS while leaving Android's untouched.
     */
    override suspend fun registerDevice(): String = ""
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

    /**
     * The APP-GROUP suite, which `clearAll` does not touch — it removes the
     * app's own persistent domain, and the group is a separate one. The same
     * place `DeviceIdentity` and the screensaver's settings already live, and
     * for the same reason.
     */
    private val durable = NSUserDefaults(suiteName = APP_GROUP_ID)

    override suspend fun saveDurable(key: String, value: String) = withContext(Dispatchers.IO) {
        durable.setObject(value, forKey = key)
        durable.synchronize()
        Unit
    }

    override suspend fun loadDurable(key: String): String? = withContext(Dispatchers.IO) {
        durable.stringForKey(key)
    }

    override suspend fun removeDurable(key: String) = withContext(Dispatchers.IO) {
        durable.removeObjectForKey(key)
        durable.synchronize()
        Unit
    }

    // dictionaryRepresentation().keys is Set<Any?> — filterIsInstance avoids the broken List cast
    private fun allKeys(): List<String> =
        defaults.dictionaryRepresentation().keys.filterIsInstance<String>()
}

// ─────────────────────────────────────────────────────────
// Platform singleton
// ─────────────────────────────────────────────────────────

/**
 * The backend API key for the environment this build targets.
 *
 * Was a single hardcoded constant shared by staging AND production — the only
 * platform where that was true, since Android has read per-flavor keys out of
 * `local.properties` since it shipped. It now comes from the `StationlyApiKey`
 * Info.plist key, expanded from `STATIONLY_API_KEY`: committed in
 * `Config/Staging.xcconfig` (that value has been in this repo's git history
 * since the iOS app existed, so there is nothing left to protect) and supplied
 * for production by the git-ignored `Config/Secrets.xcconfig`.
 *
 * A production build with no secrets file gets an obviously-invalid
 * placeholder and is rejected by the backend, which is the right outcome for a
 * build holding no production credential.
 *
 * This is about keeping production credentials out of git, NOT about secrecy
 * on device: a key inside a shipped iOS binary is extractable whichever route
 * it travels there.
 */
private val IOS_API_KEY: String by lazy {
    val key = NSBundle.mainBundle.objectForInfoDictionaryKey("StationlyApiKey") as? String
    // Trapped, not defaulted to "". An empty key is not a runtime condition —
    // it means STATIONLY_API_KEY did not reach the Info.plist — and the
    // symptom it produces is every backend call failing authentication with
    // nothing on the device explaining why. Same reasoning as IosAppGroup.
    require(!key.isNullOrEmpty()) {
        "StationlyApiKey missing from Info.plist — check STATIONLY_API_KEY in Config/*.xcconfig"
    }
    key
}

/**
 * Which environment this build targets, from the `StationlyEnvironment`
 * Info.plist key.
 *
 * ## Why this traps instead of defaulting
 * It used to read:
 *
 * ```
 * return if (env == "staging") AppEnvironment.STAGING else AppEnvironment.PRODUCTION
 * ```
 *
 * which made PRODUCTION the outcome of every failure — a missing key, an empty
 * expansion, an xcconfig that did not apply, a typo. `AppConfig` turns that
 * directly into `api.stationly.co.uk` and the production web origin, so a
 * *staging* build with one broken plist key would have talked to the
 * production backend, silently, with a staging Firebase identity.
 *
 * Of the four things this split expands into the Info.plist, this was the only
 * reader that degraded quietly rather than trapping — `IosAppGroup.ID`,
 * `AppGroupID.value` and `DepartureEntry.scheme` all refuse to start. An
 * unreadable environment is a build misconfiguration, and the safe response to
 * a build that cannot say which backend it is for is to not run at all.
 *
 * Both values are matched explicitly, so a *third* value (a typo like
 * "Staging", or some future "preprod") is an error rather than quietly
 * becoming production.
 */
private val IOS_ENVIRONMENT: AppEnvironment by lazy {
    when (val env = NSBundle.mainBundle.objectForInfoDictionaryKey("StationlyEnvironment") as? String) {
        "staging"    -> AppEnvironment.STAGING
        "production" -> AppEnvironment.PRODUCTION
        else -> throw IllegalStateException(
            "StationlyEnvironment is '${env ?: "<missing>"}', expected 'staging' or 'production' — " +
                "check STATIONLY_ENVIRONMENT in Config/*.xcconfig. Refusing to guess: the old code " +
                "defaulted to production here, which pointed staging builds at the production backend."
        )
    }
}

actual object Platform {
    private var _sqlStorage: SqlStorage? = null

    actual val widgetManager: WidgetManager       = IosWidgetManager()
    actual val notificationManager: NotificationManager = IosNotificationManager()
    actual val storageManager: StorageManager     = IosStorageManager()

    actual val sqlStorage: SqlStorage
        get() = _sqlStorage ?: SqlStorage(createDatabase(DriverFactory())).also { _sqlStorage = it }

    actual fun getPlatformName(): String = "iOS"
    actual fun getApiKey(): String       = IOS_API_KEY
    actual fun getEnvironment(): AppEnvironment = IOS_ENVIRONMENT
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

// PushPayloadBridge lived here: it decoded an FCM topic push (Station_* /
// LineStatus_*) into the prediction pipeline for the Swift AppDelegate.
//
// Removed with FirebaseMessaging. iOS receives no data pushes any more — the
// APNs envelopes it does get are SIGNALS ("refetch", "policy changed",
// "boost"), handled entirely in Swift by `WidgetPushRegistrar`, which then
// calls back into `RefreshScheduleBridge.refreshAllBoards()` to run the real
// pipeline. See the note atop `AppDelegate.swift`.
//
// `ProcessPredictionsUseCase` itself is very much alive: `LiveStreamManager`
// is its remaining iOS caller and constructs it the same way this did.
