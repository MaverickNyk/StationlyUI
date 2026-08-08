import WidgetKit

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
    /// The stored page index, UNCLAMPED.
    ///
    /// Clamping stays at render time because the group count is a property of
    /// the ticked entry — blocks that empty out are dropped — so the valid range
    /// genuinely differs between entries built from one payload. Clamping is
    /// arithmetic; reading the page was the part worth hoisting.
    var page: Int = 0
    /// Whether an ARROW caused this render, rather than new departures landing.
    /// Decides push-vs-flip; see `boardTransition`.
    var isPageMove: Bool = false
    var moveForward: Bool = true
    /// Whether this station's last refresh failed, so the header can offer a
    /// retry.
    var refreshFailed: Bool = false
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
}
