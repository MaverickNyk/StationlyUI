import Foundation
import WidgetKit

/// The deep link a board tap opens.
///
/// One builder, because the app parses the other end of it
/// (`ContentView.onOpenURL`) and two string formats obliged to agree is one too
/// many. `home` rather than `board` matches the shape Android's notifications
/// already use, so both platforms share one deep-link vocabulary.
///
/// Percent-encoded defensively — TfL grouping ids are alphanumeric today
/// (`940GZZLUKSX`, `490G00008805`), and a naptan that ever isn't would
/// otherwise produce a URL that silently fails to parse.
///
/// ## Why the scheme is not a literal
/// `stationly` in production, `stationly-staging` in staging. Since the split
/// the two apps can be installed at the same time, and when two apps claim one
/// URL scheme iOS picks between them arbitrarily — a tap on the staging widget
/// could open the production app. The value comes from the `StationlyUrlScheme`
/// Info.plist key, which `project.yml` writes into both targets' plists from
/// the same `STATIONLY_URL_SCHEME` build setting that populates
/// `CFBundleURLSchemes`, so the scheme the widget emits and the scheme the app
/// registers cannot drift apart.
enum StationlyDeepLink {

    /// Read from the EXTENSION's bundle — `Bundle.main` in an app extension is
    /// the extension, not the containing app.
    private static let scheme: String = {
        guard let s = Bundle.main.object(forInfoDictionaryKey: "StationlyUrlScheme") as? String,
              !s.isEmpty else {
            fatalError("StationlyUrlScheme missing from the widget Info.plist — check STATIONLY_URL_SCHEME in Config/*.xcconfig")
        }
        return s
    }()

    static func board(stationId: String) -> URL? {
        guard !stationId.isEmpty,
              let escaped = stationId.addingPercentEncoding(
                  withAllowedCharacters: .urlQueryAllowed)
        else { return nil }
        return URL(string: "\(scheme)://home?station=\(escaped)")
    }
}

/// TimelineEntry that carries a full snapshot of departure board data for a
/// single point in time. WidgetKit calls getTimeline() to request a batch of
/// these; we supply one entry and ask to be refreshed every 30 seconds.
/// Everything the board needs to know about WHY it is being drawn, resolved
/// once when the timeline is built.
///
/// ## Why this is not read in the views
/// All four of these live in the App Group, and the views used to read them
/// from inside `body` — which meant `UserDefaults` lookups during WidgetKit's
/// archiving pass, repeated for every entry in the timeline and every widget on
/// the screen. One arrow press was doing a couple of hundred of them to answer
/// four questions whose answers were identical across the whole batch.
///
/// A `TimelineEntry` is meant to be a complete snapshot of what to render.
/// Going back to disk mid-render breaks that: it puts I/O on the one path that
/// stands between the tap and the pixels, and it makes an entry's appearance
/// depend on when it happened to be rasterised rather than on what it holds.
struct BoardRenderState {
    /// The stored page index of every pager on the board, keyed by
    /// `BoardSection`'s raw value, UNCLAMPED.
    ///
    /// A map rather than one index because the large family stacks two pagers
    /// and they hold two independent positions — see `BoardSection`. Every
    /// section is read whichever family is being built, so one entry type
    /// describes any board it might be handed.
    ///
    /// Clamping stays at render time because the group count is a property of
    /// the ticked entry — blocks that empty out are dropped — so the valid range
    /// genuinely differs between entries built from one payload. Clamping is
    /// arithmetic; reading the pages was the part worth hoisting.
    var pages: [String: Int] = [:]

    /// This section's stored page, or the first page for a section the board
    /// does not use.
    func page(_ section: BoardSection = .single) -> Int { pages[section.rawValue] ?? 0 }
    /// Whether an ARROW caused this render, rather than new departures landing.
    /// Decides push-vs-flip; see `boardTransition`.
    var isPageMove: Bool = false
    var moveForward: Bool = true
    /// Whether this station's last refresh failed, so the header can offer a
    /// retry.
    var refreshFailed: Bool = false

    /// Where tapping the board goes — see [StationlyDeepLink].
    ///
    /// Here rather than built in the view for the reason this whole type exists:
    /// `body` runs once per ENTRY during WidgetKit's archiving pass, so building
    /// it there meant a percent-encode and a `URL` parse ~20 times per timeline
    /// per widget, to produce the identical URL every time. The station is the
    /// same for every entry in a batch, so the URL is too.
    ///
    /// Optional because two entries legitimately have no station to link to: the
    /// skeleton placeholder, and the gallery preview. Both fall back to
    /// `widgetURL`'s default, which opens the app.
    var deepLink: URL? = nil
}

struct DepartureEntry: TimelineEntry {
    /// The wall-clock time this entry represents (required by TimelineEntry).
    let date: Date
    /// The departure board data to render at `date`.
    let widgetData: WidgetData
    /// Why this render is happening — resolved once per timeline, never in a
    /// view body. See [BoardRenderState].
    var render: BoardRenderState = BoardRenderState()
    /// Render the empty-bars skeleton instead of a board.
    ///
    /// Set ONLY by `placeholder(in:)` — the moment WidgetKit has no entry yet
    /// (a widget just added, the home screen rebuilding its snapshots). It is
    /// deliberately NOT set for the gallery preview, which wants a board that
    /// looks like the product rather than one that looks like it is loading.
    ///
    /// Not reachable from a refresh or an arrow press: nothing of ours renders
    /// between the tap and the intent returning, so a skeleton asked for there
    /// would only appear AFTER the real board was already on screen.
    var isSkeleton: Bool = false

    /// What a board with no departures says, and whether its "ago" timer should
    /// still colour itself by age. Nil when the board HAS departures.
    ///
    /// ## The one thing on this entry that is genuinely per-entry
    /// Everything else in `BoardRenderState` is identical across a batch, which
    /// is exactly why it was hoisted out of the views. This is the opposite
    /// case: the answer MOVES inside a single timeline. A board becomes "Live
    /// updates paused" six minutes after its payload lands, and "Service ended
    /// for tonight" at midnight, both on entries archived long before either
    /// moment.
    ///
    /// So it is resolved per entry in the provider, where each entry's own date
    /// is already in hand, rather than in `body` — which would put the App Group
    /// read and a `Calendar` lookup back on the archiving path, per entry, per
    /// widget, for a table that only needs reading once per build.
    var fallback: BoardFallbackResult? = nil

    /// What to say when this widget has no board at all — signed out, no
    /// stations, never configured, or configured for a station since removed.
    ///
    /// Carried on the entry rather than read in the view, because the view runs
    /// inside WidgetKit's archiving pass: reading the App Group and decoding the
    /// table there would do it once per entry per widget to produce the identical
    /// four sentences. Resolved once per timeline build alongside `fallback`,
    /// which is read from the same table for the same reason.
    ///
    /// Unlike `fallback` this is NOT per-entry — a widget that has no station at
    /// 08:00 still has none at 08:40 — so one value covers the batch.
    var configCopy: [String: BoardFallbackCopy] = BoardFallbackTable.configDefaults

    /// Mode roundel tints, resolved once per timeline build alongside
    /// `configCopy` and for the same reason — the view runs inside WidgetKit's
    /// archiving pass, where an App Group read per entry would decode the same
    /// eight colours twenty times over.
    var palette: ModePalette = .compiled
}
