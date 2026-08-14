import AppIntents
import Foundation
import WidgetKit

/// Which pager on the widget a control belongs to.
///
/// A small or medium board has exactly one, and its stored key must stay
/// byte-identical to the one that shipped before sections existed — hence the
/// empty raw value, which every key builder treats as "no suffix".
///
/// The large board stacks two (`WidgetData.sections`), each walking its own
/// platforms, so they cannot share a page: at a station tracking the Piccadilly
/// and the Victoria both ways, the top half is the Piccadilly's two platforms
/// and the bottom half is the Victoria's, and an arrow in one must leave the
/// other exactly where it was.
///
/// A raw `String` rather than an `Int` because this value travels through an
/// `AppIntent` parameter and lands in a `UserDefaults` key, and both read better
/// as "u"/"d" than as a magic number.
enum BoardSection: String {
    /// The whole board is one pager — small and medium.
    case single = ""
    /// The large board's top half.
    case upper = "u"
    /// The large board's bottom half.
    case lower = "d"

    /// Every section a board can hold, for the one read that hoists all of them
    /// onto the timeline entry.
    static let all: [BoardSection] = [.single, .upper, .lower]
}

/// Which platform each of a board's pagers is showing, per station.
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
/// that actually happens. The SECTION is part of the key for the same reason a
/// station is: two pagers on one large board are two positions.
///
/// ## Two different normalisations, and the difference matters
///  - [wrapped] is the STEP. Past the last platform the board comes round to the
///    first, and back from the first it lands on the last. Both arrows are
///    therefore always live, which is what a user asked for after living with
///    the alternative: an arrow that dims at an end reads as a broken control
///    long before it reads as "nothing that way", and on a two-platform board
///    half the arrows were dim half the time.
///  - [clamped] is what a READER does with a stored index it did not write. A
///    station whose Platform 4 goes quiet drops from four groups to three, and a
///    widget parked on the last page must land on the last platform there IS
///    rather than being flung back to the first — which is exactly what wrapping
///    a stale index would do.
///
/// Both exist, and [move] uses one of each: it clamps what it reads (so the step
/// starts from the page the user is looking at) and wraps what it writes.
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

    /// The raw stored index for one section, unclamped — read once when a
    /// timeline is built and carried on the entry, so no view body has to touch
    /// the App Group. The clamp is arithmetic and stays at render time, because
    /// the valid range is a property of the ticked entry rather than of the
    /// store. See [BoardRenderState].
    static func storedPage(_ stationId: String, _ section: BoardSection = .single) -> Int {
        defaults?.integer(forKey: AppGroupKeys.page(key(stationId), section.rawValue)) ?? 0
    }

    /// Every section's stored page, for the one hoist a timeline build does.
    ///
    /// All three are read whichever family is being built, which is three
    /// integer lookups against an already-open suite — cheaper than teaching the
    /// provider which sections the family it is building happens to use, and it
    /// keeps [BoardRenderState] a complete snapshot rather than a
    /// family-conditional one.
    static func storedPages(_ stationId: String) -> [String: Int] {
        var out: [String: Int] = [:]
        for section in BoardSection.all {
            out[section.rawValue] = storedPage(stationId, section)
        }
        return out
    }

    /// Normalise a stored page against a group count — see [clamped].
    static func clamp(_ page: Int, groupCount: Int) -> Int { clamped(page, groupCount) }

    /// Move one platform round the ring. Returns the page landed on.
    ///
    /// Reads through [clamped] and writes through [wrapped], which is not an
    /// inconsistency: the first is how a stale index is understood, the second
    /// is how a step behaves. See the type's note.
    ///
    /// The direction and timestamp stay keyed on the STATION rather than on the
    /// section, because they answer "did a finger just move something" — a
    /// question the whole board's transition asks once. The section that did not
    /// move keeps its row identities and therefore does not animate anyway.
    @discardableResult
    static func move(_ stationId: String, section: BoardSection = .single,
                     forward: Bool, groupCount: Int) -> Int {
        let d = defaults
        let pageKey = AppGroupKeys.page(key(stationId), section.rawValue)
        let current = clamped(d?.integer(forKey: pageKey) ?? 0, groupCount)
        let next = wrapped(current + (forward ? 1 : -1), groupCount)
        // A single-platform section is the only board that cannot move, and it
        // draws no arrows — so this guard is reached by nothing a user can tap.
        // It stays because writing an unchanged page would still stamp a move,
        // and the render after it would push a board that had not moved.
        guard next != current else { return current }
        d?.set(next, forKey: pageKey)
        d?.set(forward, forKey: AppGroupKeys.pageDirection(key(stationId)))
        // Stamped with the move so the render that follows knows an ARROW
        // caused it and pushes rather than relighting — see [lastMoveAt].
        d?.set(Date().timeIntervalSince1970, forKey: AppGroupKeys.pageMovedAt(key(stationId)))
        return next
    }

    /// The page is an INDEX, and platforms are not stable positions: a station
    /// whose Platform 4 goes quiet drops from three groups to two, and every
    /// index after it shifts. Clamping is the whole answer for a page nobody
    /// just pressed — a widget parked on the third page lands on the last
    /// platform there is rather than on nothing, and one tap puts it where the
    /// user wants. Keying the page on the platform LABEL was the alternative and
    /// buys nothing: the arrows have to walk an ordered list either way, and a
    /// label that vanishes overnight leaves the same problem with more
    /// bookkeeping.
    private static func clamped(_ page: Int, _ groupCount: Int) -> Int {
        guard groupCount > 0 else { return 0 }
        return min(max(0, page), groupCount - 1)
    }

    /// One step round the ring — the arrows' arithmetic.
    ///
    /// Written as a double modulo rather than as `if next < 0` because the
    /// negative case is the whole reason it exists: Swift's `%` keeps the sign
    /// of the dividend, so `-1 % 4` is `-1` and a left arrow on the first page
    /// would index off the front of the array.
    private static func wrapped(_ page: Int, _ groupCount: Int) -> Int {
        guard groupCount > 0 else { return 0 }
        return ((page % groupCount) + groupCount) % groupCount
    }

    /// An empty station id (the legacy single-board path) still needs a key of
    /// its own rather than sharing one with a real station.
    private static func key(_ stationId: String) -> String {
        stationId.isEmpty ? "_primary" : stationId
    }
}

/// One step through the platforms of one section of one station's board.
///
/// Two buttons rather than one cycling header, because a single control cannot
/// say where it is going: at "3/3" the only way back to the first platform was
/// three more taps forward, and nothing on screen told you that. Two arrows say
/// which way each tap goes, and the page marker beside them says where you are —
/// which is the whole position story now that the ring has no ends to dim.
struct MovePlatformPageIntent: AppIntent {
    static var title: LocalizedStringResource = "Show another platform"
    /// Widget-internal plumbing — keep it out of Shortcuts/Spotlight.
    static var isDiscoverable: Bool = false

    @Parameter(title: "Station")
    var stationId: String

    /// Which pager on the board this arrow belongs to — `BoardSection`'s raw
    /// value. Carried as a String because that is what an `AppIntent` parameter
    /// can hold; an unknown value degrades to the single pager, which is what
    /// every board except the large one has.
    @Parameter(title: "Section")
    var section: String

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

    init(stationId: String, section: String = "", forward: Bool, groupCount: Int) {
        self.stationId = stationId
        self.section = section
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
        WidgetBoardPage.move(stationId, section: BoardSection(rawValue: section) ?? .single,
                             forward: forward, groupCount: groupCount)
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
