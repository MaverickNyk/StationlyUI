import AppIntents
import Foundation
import WidgetKit

/// The medium board's platform-group page.
///
/// WidgetKit forbids scrollable content (widgets are static snapshots), so
/// Android's scrollable `rows_list` cannot be ported literally. The
/// WidgetKit-sanctioned equivalent is an interactive `Button(intent:)`
/// (iOS 17+): tapping the platform header advances this page and the widget
/// re-renders with the next platform group — paging instead of scrolling,
/// with zero extra rows spent on chrome.
///
/// The raw counter only ever increments; readers normalise with
/// `% groupCount`, so a stale page auto-wraps when the station's platform
/// set changes (no reset bookkeeping, no invalid states).
enum WidgetBoardPage {
    private static let key = "widget_board_page"
    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: AppGroupID.value)
    }

    static var current: Int {
        max(0, defaults?.integer(forKey: key) ?? 0)
    }

    static func advance() {
        let d = defaults
        d?.set((d?.integer(forKey: key) ?? 0) &+ 1, forKey: key)
    }
}

@available(iOS 17.0, *)
struct NextPlatformPageIntent: AppIntent {
    static var title: LocalizedStringResource = "Next platform"
    /// Widget-internal plumbing — keep it out of Shortcuts/Spotlight.
    static var isDiscoverable: Bool = false

    func perform() async throws -> some IntentResult {
        WidgetBoardPage.advance()
        // WidgetKit reloads the tapped widget automatically after an
        // interactive intent; the explicit reload covers multiple instances
        // of the widget showing the same board.
        WidgetCenter.shared.reloadTimelines(ofKind: StationlyDepartureBoardWidget.kind)
        return .result()
    }
}

/// The widget's refresh button — parity with Android's `btn_refresh`
/// PendingIntent firing ACTION_MANUAL_REFRESH.
///
/// `perform()` is awaited by the system, so the network round trip happens
/// before WidgetKit re-renders and the user sees fresh rows rather than a
/// flash of the old ones. Debounce and fetch live in WidgetRefreshService.
@available(iOS 17.0, *)
struct RefreshBoardIntent: AppIntent {
    static var title: LocalizedStringResource = "Refresh departures"
    static var isDiscoverable: Bool = false

    func perform() async throws -> some IntentResult {
        _ = await WidgetRefreshService.refresh()
        WidgetCenter.shared.reloadTimelines(ofKind: StationlyDepartureBoardWidget.kind)
        return .result()
    }
}
