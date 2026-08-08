import AppIntents
import Foundation
import WidgetKit

/// Which platform the medium board is showing, per station.
///
/// WidgetKit forbids scrollable content (widgets are static snapshots), so
/// Android's scrollable `rows_list` cannot be ported literally. The
/// WidgetKit-sanctioned equivalent is an interactive `Button(intent:)`
/// (iOS 17+): tapping advances the page and the widget re-renders with the next
/// platform group — paging instead of scrolling.
///
/// ## Keyed per station, not per widget
/// Two widgets can now show two different stations, so one global counter would
/// have paged both of them at once. There is no supported per-INSTANCE
/// identifier in WidgetKit — `AppIntentTimelineProvider` is handed a
/// configuration, not an instance id — so the station is the finest key
/// available. Two widgets pinned to the SAME station therefore page together,
/// which is the one case this cannot separate and is a fair trade for the case
/// that actually happens.
///
/// ## Clamped, not wrapped
/// It used to be a counter that only ever incremented, normalised with
/// `% groupCount`. That works for a control that cycles, and is wrong for
/// arrows: an arrow says whether there is anything that way, so the page has to
/// be a real position with a first and a last. [clamped] is what every reader
/// goes through, so a stale page left behind by a station whose platforms have
/// changed still lands somewhere valid.
enum WidgetBoardPage {
    /// Shared rather than opened per call: one page render asks [page],
    /// [lastMoveAt] and [lastMoveWasForward], which used to open the suite three
    /// times to answer three questions about the same widget. See
    /// `AppGroupDefaults`.
    private static var defaults: UserDefaults? { AppGroupDefaults.shared }

    /// Which way the last move went, so the render after it can push the board
    /// in the direction the finger asked for. `true` = forwards.
    ///
    /// Stored rather than derived: the view is rebuilt from scratch after the
    /// intent runs and has no memory of what the previous page was, and a
    /// transition that always pushes one way makes going back feel like going
    /// on.
    static func lastMoveWasForward(_ stationId: String) -> Bool {
        defaults?.object(forKey: AppGroupKeys.pageDirection(key(stationId))) as? Bool ?? true
    }

    /// When the last page move happened, so a render can tell WHY it is
    /// happening.
    ///
    /// A view is stateless — it sees the current page and the current data and
    /// has no memory of the previous render — but the board wants two different
    /// transitions: a directional push when the user pressed an arrow, and a
    /// relight when new departures landed. Both re-key the rows, so the cause
    /// cannot be inferred from the keys themselves. Comparing this against
    /// `lastRefreshOk` is what separates them, and both are timestamps the code
    /// was already writing one way or another.
    static func lastMoveAt(_ stationId: String) -> TimeInterval {
        defaults?.double(forKey: AppGroupKeys.pageMovedAt(key(stationId))) ?? 0
    }

    /// The raw stored index, unclamped — read once when a timeline is built and
    /// carried on the entry, so no view body has to touch the App Group. The
    /// clamp is arithmetic and stays at render time, because the valid range is
    /// a property of the ticked entry rather than of the store. See
    /// [BoardRenderState].
    static func storedPage(_ stationId: String) -> Int {
        defaults?.integer(forKey: AppGroupKeys.page(key(stationId))) ?? 0
    }

    /// Clamp a stored page against a group count — see [clamped].
    static func clamp(_ page: Int, groupCount: Int) -> Int { clamped(page, groupCount) }

    /// Move one platform, staying inside the board. Returns the page landed on.
    ///
    /// A move that would leave the board is a no-op, INCLUDING the stored
    /// direction: writing "forwards" after a tap on a dimmed right arrow would
    /// make the next genuine leftward move push the wrong way.
    @discardableResult
    static func move(_ stationId: String, forward: Bool, groupCount: Int) -> Int {
        let d = defaults
        let current = clamped(d?.integer(forKey: AppGroupKeys.page(key(stationId))) ?? 0, groupCount)
        let next = clamped(current + (forward ? 1 : -1), groupCount)
        guard next != current else { return current }
        d?.set(next, forKey: AppGroupKeys.page(key(stationId)))
        d?.set(forward, forKey: AppGroupKeys.pageDirection(key(stationId)))
        // Stamped with the move so the render that follows knows an ARROW
        // caused it and pushes rather than relighting — see [lastMoveAt].
        d?.set(Date().timeIntervalSince1970, forKey: AppGroupKeys.pageMovedAt(key(stationId)))
        return next
    }

    /// The page is an INDEX, and platforms are not stable positions: a station
    /// whose Platform 4 goes quiet drops from three groups to two, and every
    /// index after it shifts. Clamping is the whole answer — a widget parked on
    /// the third page lands on the last platform there is rather than on
    /// nothing, and one tap puts it where the user wants. Keying the page on
    /// the platform LABEL was the alternative and buys nothing: the arrows have
    /// to walk an ordered list either way, and a label that vanishes overnight
    /// leaves the same problem with more bookkeeping.
    private static func clamped(_ page: Int, _ groupCount: Int) -> Int {
        guard groupCount > 0 else { return 0 }
        return min(max(0, page), groupCount - 1)
    }

    /// An empty station id (the legacy single-board path) still needs a key of
    /// its own rather than sharing one with a real station.
    private static func key(_ stationId: String) -> String {
        stationId.isEmpty ? "_primary" : stationId
    }
}

/// One step through the configured station's platforms.
///
/// Two buttons rather than one cycling header, because a cycling control cannot
/// say where it is: at "3/3" the only way back to the first platform was three
/// more taps forward, and nothing on screen told you that. Arrows say which
/// directions exist and dim when there is nothing that way.
@available(iOS 17.0, *)
struct MovePlatformPageIntent: AppIntent {
    static var title: LocalizedStringResource = "Show another platform"
    /// Widget-internal plumbing — keep it out of Shortcuts/Spotlight.
    static var isDiscoverable: Bool = false

    @Parameter(title: "Station")
    var stationId: String

    @Parameter(title: "Forward")
    var forward: Bool

    /// How many platform groups the board had when this button was drawn.
    ///
    /// Passed in rather than recomputed: the intent runs in the extension with
    /// no access to the rendered board, and re-deriving the count from the App
    /// Group would use rows ticked to a different minute than the ones the user
    /// is looking at — which is exactly when the count can differ by one.
    @Parameter(title: "Groups")
    var groupCount: Int

    init() {}

    init(stationId: String, forward: Bool, groupCount: Int) {
        self.stationId = stationId
        self.forward = forward
        self.groupCount = groupCount
    }

    /// Two `UserDefaults` writes and nothing else, on purpose.
    ///
    /// WidgetKit re-renders the tapped widget by itself once `perform()`
    /// returns, and it does so from the timeline it already holds — which is
    /// fast, because nothing has to be regenerated. This used to also call
    /// `reloadTimelines(ofKind:)`, which threw that timeline away and rebuilt
    /// all 61 entries (each one re-ticking every departure) BEFORE the new page
    /// could be drawn. That rebuild was the lag between the tap and the board
    /// moving; the page number is already in the App Group and every entry
    /// reads it at render time, so the reload bought nothing.
    ///
    /// The one thing it did buy: a SECOND widget pinned to the same station
    /// shares this page counter and now waits for its own next reload to catch
    /// up. That is a rare arrangement and a cheap price for the tap being
    /// immediate in the normal one.
    func perform() async throws -> some IntentResult {
        WidgetBoardPage.move(stationId, forward: forward, groupCount: groupCount)
        return .result()
    }
}

/// The widget's refresh button — parity with Android's `btn_refresh`
/// PendingIntent firing ACTION_MANUAL_REFRESH.
///
/// `perform()` is awaited by the system, so the network round trip happens
/// before WidgetKit re-renders and the user sees fresh rows rather than a
/// flash of the old ones. Debounce and fetch live in WidgetRefreshService.
///
/// Carries the station id for the same reason paging does: with several widgets
/// on screen, refreshing "the widget" would refresh whichever board the legacy
/// keys happen to hold rather than the one under the finger.
@available(iOS 17.0, *)
struct RefreshBoardIntent: AppIntent {
    static var title: LocalizedStringResource = "Refresh departures"
    static var isDiscoverable: Bool = false

    @Parameter(title: "Station")
    var stationId: String

    init() {}

    init(stationId: String) {
        self.stationId = stationId
    }

    /// The reload is [WidgetRefreshService.refresh]'s to make, not this one's.
    ///
    /// This used to reload unconditionally on top of the one the service
    /// already performs, which meant a tap absorbed by the in-flight guard —
    /// one that deliberately did no work — still asked WidgetKit to regenerate
    /// every entry of every widget's timeline. The service reloads exactly when
    /// something changed, and staying silent otherwise is the whole point.
    func perform() async throws -> some IntentResult {
        _ = await WidgetRefreshService.refresh(stationId: stationId)
        return .result()
    }
}
