import Foundation

/// Every App Group key this extension reads or writes, in one place.
///
/// ## Why this file exists
/// The same class of bug as `AppGroupID.swift`, and the project has already
/// paid for it once: these were raw string literals at ~20 call sites across
/// four files, with `widget_predictions`, `widget_last_updated`,
/// `widget_reload_signal` and the `widget_board_` prefix each spelled out in
/// two of them. A mistyped key does not fail the build — it reads `nil`, which
/// is indistinguishable from "the app never wrote it", and the symptom lands on
/// the home screen rather than in a compiler message.
///
/// ## The half that cannot be enforced
/// The Kotlin side has its own `AppGroupKeys` (`core/iosMain/platform/Platform.ios.kt`)
/// and Swift cannot import it, so these two objects are a contract kept by hand.
/// **Change both, in one commit.** The keys below are grouped by who writes
/// them, because that is what tells you whether a change here needs a matching
/// change over there.
enum AppGroupKeys {

    // MARK: - Written by KMP, read here
    //
    // The pre-multi-station board. Still written for the primary station, and
    // still what an unconfigured widget renders — see `AppGroupStorage`.

    static let stationName   = "widget_station_name"
    /// CANONICAL line id — an identity. `WidgetRefreshService.legacyFeed` keys
    /// the predictions payload on it, so it must not become display text.
    static let lineName      = "widget_line_name"
    /// The same line as a person reads it ("Hammersmith & City"), resolved by
    /// KMP. Separate from `lineName` because that one is looked up, not shown;
    /// bridging the two with `.capitalized` is what rendered "Hammersmith-City".
    static let lineDisplay   = "widget_line_display"
    static let predictions   = "widget_predictions"
    static let status        = "widget_status"
    static let direction     = "widget_direction"
    static let mode          = "widget_mode"
    static let lastUpdated   = "widget_last_updated"

    /// Bumped by KMP when a board changes; the app's `WidgetReloadObserver`
    /// turns it into `reloadAllTimelines()`. Written from here too, after a
    /// successful in-extension refresh.
    static let reloadSignal  = "widget_reload_signal"

    /// The naptan and API coordinates the in-extension refresh needs. It cannot
    /// reach KMP or the SQLite file, so its addressing has to be mirrored.
    static let stationId     = "widget_station_id"
    static let apiBaseURL    = "widget_api_base_url"
    static let apiKey        = "widget_api_key"

    /// Multi-station: the directory the configuration picker lists, and one
    /// key per station holding that station's whole board.
    static let stations      = "widget_stations"
    static func board(_ stationId: String) -> String { "widget_board_\(stationId)" }

    /// The account signed OUT — do not render a board, and do not go and get one.
    ///
    /// This extension can refill itself: `DepartureBoardProvider.timeline`
    /// fetches whenever what it holds is older than `staleAfterSeconds`, over
    /// REST authenticated by [apiKey] rather than by any user token, for a
    /// station named in an AppIntent configuration the app cannot reach. So a
    /// sign-out that only deletes App Group data is a sign-out this process
    /// undoes a couple of minutes later — which is how a signed-out account
    /// went on showing a live departure board, against the guarantee in
    /// `docs/USER_STATE_AND_ACTIVITY.md` ("Logout resets the widget").
    ///
    /// Distinct from "no stations" because that already means something else:
    /// a signed-in user who deleted their last board wipes to the same empty
    /// App Group and must keep the refresh path they have.
    ///
    /// Written by KMP's `IosWidgetManager.clearWidgetData`; cleared by the first
    /// board write made while somebody is signed in.
    static let signedOut     = "widget_signed_out"

    /// Which stations are actually ON the home screen, written BY the widget.
    ///
    /// The only direction this information can travel. WidgetKit tells the app
    /// how many widgets are placed and at what sizes (`getCurrentConfigurations`),
    /// but the station is inside an AppIntent configuration whose type is
    /// compiled into this target — reading it from the app would mean dragging
    /// `StationEntity`, and therefore `AppGroupStorage` and the roundel
    /// artwork, into the app target too. The widget already knows the answer,
    /// so it writes it down: one stamp per (station, family), refreshed on
    /// every timeline build. See `HomeStateProbe`, which reconciles the stamps
    /// against the authoritative COUNT.
    static let placements    = "widget_placements"

    // ⚠️ `widget_placed` / `widget_placed_at` used to live here: a cached copy of
    // the exact home screen from `getCurrentConfigurations`, kept so that the
    // synchronous `recommendations()` could order the gallery by which stations
    // were already taken. Both are gone with the assignment machinery.
    //
    // Do not reintroduce them without reading `StationResolver` rung 3 first.
    // Everything derived from that snapshot moved — widgets come and go, the
    // snapshot is only as fresh as the last caller that could read it, and
    // WidgetKit returns an empty list for it inside `timeline(for:in:)` — and a
    // moving input is what made widgets change station on their own. Nothing a
    // board renders may depend on it.
    //
    // A device that used a build between 2026-08-16 and 2026-08-17 may still
    // have these two keys in its App Group. They are inert.

    /// The refresh cadence, precomputed by KMP as a list of time SEGMENTS.
    ///
    /// A schedule rather than a single current decision, because this extension
    /// is the only thing running when the app is not: handed one decision, a
    /// phone untouched since last evening would still apply rush-hour cadence
    /// at 03:00. See `RefreshScheduleStore`.
    static let refreshSchedule   = "widget_refresh_schedule"
    /// When KMP last published the schedule — how we tell "the app agrees this
    /// is current" from "the app has not run in days".
    static let refreshScheduleAt = "widget_refresh_schedule_at"

    // MARK: - Written here, read by KMP

    /// Rolling 24-hour tally of timeline builds, and when that window opened.
    ///
    /// Written HERE because only this process sees a timeline build happen —
    /// the app is not running for most of them, so an app-side count would
    /// under-report badly and the budget governor would never engage until the
    /// widget had already been throttled. KMP reads these to decide how hard to
    /// economise; see `RefreshBudgetStore` on the Kotlin side.
    ///
    /// ## These two are a MIRROR now, not the ledger itself
    /// Apple budgets per widget (~40–70 builds a day each), so with two widgets
    /// on the home screen a single shared tally answers the wrong question: it
    /// reports the device's total spend against a ceiling that is per widget.
    /// The real ledger is [budgetCount(_:)] per widget, and these hold the
    /// MAXIMUM across them — the widget closest to being throttled, which is the
    /// one the governor has to protect. KMP's side of the contract is unchanged:
    /// it still reads exactly these two keys and still resets them on a build
    /// change.
    static let budgetWindowStart = "widget_budget_window_start"
    static let budgetCount       = "widget_budget_count"

    /// The per-widget ledger the two keys above summarise.
    ///
    /// Keyed by station and family (the same pair `placements` stamps) because
    /// WidgetKit hands the provider no instance identity of its own — the
    /// configuration and `context.family` are the whole of what it knows. Two
    /// widgets showing the same station at the same size therefore share a
    /// ledger entry and count as one; that is the same granularity the
    /// placement stamps already accept, and the failure is a modest over-count
    /// on a rare configuration rather than the ordering-dependent one below.
    /// Prefixes, so the set of ledger entries can be DERIVED by scanning the
    /// suite rather than stored alongside it.
    ///
    /// There used to be a `widget_budget_roster` array holding the ids. It was a
    /// shared blob that both processes read-modify-wrote, which is precisely the
    /// pattern the two counters above deliberately avoid, and for the same
    /// reason: `UserDefaults` has no atomic append, so two widgets registering
    /// in the same burst could silently drop one of them — and with it that
    /// widget's whole spend. Derivation cannot race, because every widget writes
    /// only keys bearing its own id.
    ///
    /// A device that ran a build from 2026-08-17 may still hold the old
    /// `widget_budget_roster` key. It is inert and `syncGeneration` clears it.
    static let budgetCountPrefix       = "widget_budget_count_"
    static let budgetWindowStartPrefix = "widget_budget_start_"
    static let nextScheduledAtPrefix   = "widget_next_scheduled_at_"

    static func budgetCount(_ ledgerId: String) -> String { budgetCountPrefix + ledgerId }
    static func budgetWindowStart(_ ledgerId: String) -> String { budgetWindowStartPrefix + ledgerId }

    /// Written by a build before 2026-08-17's refactor; read by nothing now.
    static let legacyBudgetRoster = "widget_budget_roster"

    /// The app build the per-widget ledger was counted under.
    ///
    /// KMP zeroes the mirror on a build change (`resetLedgerOnNewBuild`) and
    /// cannot reach the per-widget entries, which would then restore the old
    /// count the moment the mirror was recomputed. Stamping the generation here
    /// lets the first build after an update notice and wipe the whole roster.
    ///
    /// Compared against [budgetBuild], which KMP writes — see below.
    static let budgetGeneration  = "widget_budget_generation"

    /// When we last told WidgetKit to come back (`.after(next)`), per widget.
    ///
    /// Used to tell a SCHEDULED rebuild from an externally-triggered one. A
    /// build arriving well before this time was caused by a push or an
    /// app-side reload — both of which Apple meters on their own budgets — so
    /// charging it against the timeline quota over-counts. That over-counting
    /// is what drove the governor to a 627-minute interval on a real device.
    ///
    /// ## Why this had to become per widget
    /// It was one shared key, and with more than one widget it made metering
    /// depend on the order WidgetKit happened to invoke the providers in. A
    /// scheduled burst across three widgets charged ONE: the first build moved
    /// the marker a full interval into the future, and the two that followed
    /// milliseconds later read that new marker, concluded they had arrived far
    /// too early to be the schedule firing, and recorded themselves free. Once
    /// widgets drift out of lockstep it gets worse rather than better — whichever
    /// one lands just after the marker pays for all of them, and there are
    /// orderings where the widget that WAS on schedule is the one excused.
    static func nextScheduledAt(_ ledgerId: String) -> String {
        nextScheduledAtPrefix + ledgerId
    }

    // MARK: - Written by the app, read here

    /// The installed app build, stamped by KMP's `resetLedgerOnNewBuild`.
    ///
    /// Read here only to notice that it has changed, which is the signal to
    /// discard the per-widget ledger — see [budgetGeneration]. KMP owns the
    /// value; nothing in this target ever writes it.
    static let budgetBuild       = "widget_budget_build"

    /// Which widgets are ACTUALLY placed, as `family|stationId` descriptors,
    /// and when the app last looked.
    ///
    /// The answer to the one question this process cannot ask. WidgetKit has no
    /// deletion callback, and `getCurrentConfigurations` returns an EMPTY list
    /// when called from inside `timeline(for:in:)` — so from here a removed
    /// widget is indistinguishable from one that simply has not been asked for
    /// a timeline yet. The app can ask properly, and `HomeStateProbe` reconciles
    /// the host's authoritative count and families against [placements] to
    /// recover the stations. Published on every foreground.
    ///
    /// The timestamp is load-bearing, not diagnostic: a widget added after the
    /// observation must not be reaped for being missing from a list that
    /// predates it. See `RefreshScheduleStore.reap`.
    static let observedWidgets   = "widget_observed"
    static let observedWidgetsAt = "widget_observed_at"

    /// Heartbeat proving the APP is in the foreground right now.
    ///
    /// Exists because reloads requested while the app is foreground are EXEMPT
    /// from WidgetKit's budget, and counting them would be a serious
    /// over-charge: a fresh install with three widgets recorded fourteen
    /// timeline builds in five seconds, none of which cost anything. Left
    /// uncorrected, opening the app a few times a day would exhaust the modelled
    /// budget and leave the governor permanently degrading intervals it never
    /// needed to.
    ///
    /// A HEARTBEAT rather than a boolean because a flag has no way to be wrong
    /// safely: an app killed while foregrounded would strand it at `true` and
    /// silently stop all metering. A timestamp refreshed on the app's existing
    /// 5-second observer tick goes stale on its own within seconds of the app
    /// stopping, whatever the reason.
    static let appForegroundHeartbeat = "widget_app_foreground_heartbeat"

    /// The push token WidgetKit hands this extension (iOS 26+), for KMP to
    /// register with the backend. Addresses the WIDGET, not the app — a push to
    /// it reloads the timeline without launching us.
    static let widgetPushToken   = "widget_push_token"

    // MARK: - Written here, read here
    //
    // Extension-local state. KMP still knows two of these keys — it removes a
    // station's paging state when the station is deleted, because it is the
    // only side with an event for that.

    /// Which platform group a pager is showing, per station and per SECTION.
    ///
    /// Deliberately NOT `widget_board_page_…`: that name sits under the
    /// `widget_board_` prefix, so any prefix scan would read it as a station
    /// whose id begins "page_". Nothing scans by prefix today, and the point is
    /// that nothing should be able to start.
    ///
    /// The section is part of the key because the large board stacks TWO pagers
    /// (see `BoardSection`) and they walk different platforms: paging the
    /// Piccadilly half must not move the Victoria half underneath it. The
    /// unsectioned form is byte-identical to the key that shipped before
    /// sections existed, so a widget keeps the page it was already on across
    /// this update — and KMP still removes it by that name when a station is
    /// deleted (`Platform.ios.kt`, which removes the sectioned forms too).
    static func page(_ stationId: String, _ section: String = "") -> String {
        section.isEmpty ? "widget_page_\(stationId)" : "widget_page_\(stationId)#\(section)"
    }
    /// Which way that station's last page move went, for the push transition.
    static func pageDirection(_ stationId: String) -> String { "widget_page_dir_\(stationId)" }
    /// When it moved, so a render can tell an arrow press from new data
    /// arriving and pick the matching transition — see `WidgetBoardPage`.
    static func pageMovedAt(_ stationId: String) -> String { "widget_page_at_\(stationId)" }

    // Refresh timing and outcome — see `WidgetRefreshService`.
    //
    // The in-flight guard is global (one tap already refreshes every installed
    // board, so a second button has nothing left to fetch while the first runs);
    // the OUTCOME is per station, because a single flag would put a failure
    // warning on the widgets that refreshed perfectly well.

    /// When the currently-running refresh started, or 0 when none is.
    ///
    /// Replaced `widget_last_manual_refresh`, which held a 15-second lockout
    /// after every refresh — see `WidgetRefreshService.inFlightCeiling` for why
    /// the guard belongs on concurrency rather than on elapsed time. Deliberately
    /// a NEW key: the old one holds a "last completed at" timestamp, and reusing
    /// it would make a build installed over this one read that as a refresh
    /// running since then.
    static let refreshInFlightSince = "widget_refresh_inflight_since"

    /// When the last refresh SUCCEEDED. Drives the on-device trace and the
    /// "was this render caused by an arrow or by new data" comparison in
    /// `BoardRenderState`; deliberately drives no UI of its own.
    static let lastRefreshOk     = "widget_last_refresh_ok"

    /// Whether one station's last refresh failed, so its header can offer a
    /// retry. KMP removes these when a station is deleted, for the same reason
    /// it removes the paging keys: their lifetime is the station's, and only
    /// KMP has an event for "this station is gone".
    ///
    /// The empty id keeps the ORIGINAL flat key, so a widget with no configured
    /// station reads exactly what it read before, and a build installed over
    /// this one does not inherit a stuck warning glyph from a key it renamed.
    static func refreshFailed(_ stationId: String) -> String {
        stationId.isEmpty ? "widget_refresh_failed" : "widget_refresh_failed_\(stationId)"
    }
    /// Every "nothing to show, and here is why" message the board can print,
    /// plus the thresholds that choose between them.
    ///
    /// Written by `IosWidgetManager.publishFallbackCopy` from
    /// `BoardFallbackDefaults` and the cached SDUI overrides, so the widget and
    /// the home board say the same thing about the same station. The extension
    /// picks the row — it owns the render clock, and which row is correct moves
    /// inside a single timeline — but never writes the words. See
    /// `BoardFallbackTable`, and `docs/IOS_WIDGET_DESIGN.md` §6.5.
    static let boardFallback     = "widget_board_fallback"

    /// Bounded ring buffer of refresh breadcrumbs; an extension has no console.
    ///
    /// Deliberately short (20 entries) because it is chatty — every render, tap
    /// and timing line goes here — so during active use it covers only minutes.
    /// For "is the widget refreshing on schedule over hours?" use
    /// [scheduledBuildLog] instead.
    static let refreshTrace      = "widget_refresh_trace"

    /// One line per METERED build, and nothing else.
    ///
    /// Exists because [refreshTrace] cannot answer the question that actually
    /// matters operationally — *did the widget refresh by itself over the last
    /// few hours?* At 20 chatty entries it rolls in minutes, so a report of
    /// "it didn't update for two hours" was uninvestigable: by the time anyone
    /// looked, the evidence had been overwritten by the very act of looking.
    ///
    /// Only scheduled (charged) builds are recorded, so at ~42/day this spans
    /// well over a day in 60 entries and the gaps between lines ARE the answer.
    static let scheduledBuildLog = "widget_scheduled_build_log"
}
