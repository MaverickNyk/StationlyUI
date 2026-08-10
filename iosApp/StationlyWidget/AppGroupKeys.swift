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
    static let budgetWindowStart = "widget_budget_window_start"
    static let budgetCount       = "widget_budget_count"

    /// When we last told WidgetKit to come back (`.after(next)`).
    ///
    /// Used to tell a SCHEDULED rebuild from an externally-triggered one. A
    /// build arriving well before this time was caused by a push or an
    /// app-side reload — both of which Apple meters on their own budgets — so
    /// charging it against the timeline quota over-counts. That over-counting
    /// is what drove the governor to a 627-minute interval on a real device.
    static let nextScheduledAt   = "widget_next_scheduled_at"

    // MARK: - Written by the app, read here

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

    /// Which platform group the medium board is showing, per station.
    ///
    /// Deliberately NOT `widget_board_page_…`: that name sits under the
    /// `widget_board_` prefix, so any prefix scan would read it as a station
    /// whose id begins "page_". Nothing scans by prefix today, and the point is
    /// that nothing should be able to start.
    static func page(_ stationId: String) -> String { "widget_page_\(stationId)" }
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
