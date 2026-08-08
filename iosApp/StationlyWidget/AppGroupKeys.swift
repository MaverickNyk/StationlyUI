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
    static let lineName      = "widget_line_name"
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

    /// Refresh debounce and outcome — see `WidgetRefreshService`.
    ///
    /// The debounce is global (one finger, one TfL); the OUTCOME is per station,
    /// because one tap now refreshes every installed board and a single flag
    /// would put a failure warning on the widgets that refreshed perfectly well.
    static let lastManualRefresh = "widget_last_manual_refresh"
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
    static let refreshTrace      = "widget_refresh_trace"
}
