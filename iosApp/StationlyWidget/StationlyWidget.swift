import WidgetKit
import SwiftUI
import os

/// os_log (NOT print — print goes to stdout, invisible on a device) so the
/// provider's activity shows up in `idevicesyslog`: proof of whether chronod
/// launched the extension and what each family was given.
private let providerLog = Logger(subsystem: "com.stationly.mobile.StationlyWidget", category: "provider")

// MARK: - TimelineProvider

/// One widget, one station — chosen in the widget's own configuration.
///
/// This was a `TimelineProvider` reading a single set of flat App Group keys,
/// which meant every widget on the home screen showed the same board (the
/// app's primary station) and adding a second one was pointless. It is now an
/// `AppIntentTimelineProvider`: `configuration.station` says which board to
/// read, exactly as the Weather widget's configuration says which city.
///
/// An unconfigured widget resolves to the first station via
/// `StationEntityQuery.defaultResult()`, so nothing added before this build
/// changes what it shows.
struct DepartureBoardProvider: AppIntentTimelineProvider {

    // Called immediately when the widget is added to the home screen.
    // Returns hard-coded placeholder data so the widget renders without delay.
    /// The empty-bars skeleton — see `SkeletonBoardView`.
    ///
    /// This used to be four invented departures at a real station, which is a
    /// small lie told at exactly the moment a user is deciding whether the
    /// widget works. `snapshot(for:in:)` still uses that data for the GALLERY,
    /// where a representative board is the right answer and a skeleton would
    /// look broken.
    func placeholder(in context: Context) -> DepartureEntry {
        DepartureEntry(date: Date(), widgetData: .empty, isSkeleton: true)
    }

    // Called for the widget gallery preview. Uses placeholder in preview mode,
    // real App Group data otherwise.
    func snapshot(for configuration: SelectStationIntent, in context: Context) async -> DepartureEntry {
        let data = context.isPreview
            ? WidgetData.placeholder
            : AppGroupStorage.shared.readWidgetData(stationId: configuration.station?.id)
        return DepartureEntry(date: Date(), widgetData: data)
    }

    // Called when WidgetKit needs a fresh set of entries to display.
    // KMP bumps `widget_reload_signal` on every App-Group write (FCM push,
    // in-app refresh, selection change), triggering an immediate reload via
    // WidgetReloadObserver / AppDelegate — that is the real-time path.
    //
    // The timeline itself carries ONE ENTRY PER MINUTE for the next hour, and
    // every entry's rows are RE-DERIVED for that entry's wall-clock minute
    // (`ticked(at:)` — Android tickPredictions parity): ETAs count down,
    // departed trains drop, the queue shifts — all without the app running and
    // without consuming Apple's ~40–70/day refresh budget. This is the iOS
    // analog of Android's ACTION_ETA_TICK watchdog. The footer clock ticks per
    // second on its own (`.timer` Text, see LiveClock); the per-minute entries
    // re-anchor it across midnight. `.atEnd` re-reads the App Group once an
    // hour (~24 refreshes/day, comfortably inside budget).
    func timeline(for configuration: SelectStationIntent, in context: Context) async -> Timeline<DepartureEntry> {
        let tStart = Date()
        let data = AppGroupStorage.shared.readWidgetData(stationId: configuration.station?.id)
        let tRead = Date()
        // `departureCount`, not `departures.count`: the latter flattened every
        // block into a throwaway array to log a number. Instrumentation must
        // never be the most expensive thing on the path it measures.
        providerLog.notice("timeline family=\(String(describing: context.family), privacy: .public) station=\(data.stationName.isEmpty ? "<none>" : "set", privacy: .public) id=\(data.stationId, privacy: .public) deps=\(data.departureCount) statusLen=\(data.status.count)")

        // Align entries to minute boundaries so the clock flips exactly on the minute.
        let calendar = Calendar.current
        let now = Date()
        let currentMinute = calendar.date(bySetting: .second, value: 0, of: now).flatMap {
            $0 > now ? calendar.date(byAdding: .minute, value: -1, to: $0) : $0
        } ?? now

        // PER-PLATFORM retention target, not a whole-board one: each platform
        // holds its own last 3 departures when it has fewer than 3 upcoming.
        // 3 regardless of family — every family renders at most 3 rows for a
        // given platform group (medium/small show one group of 3; large shows
        // several groups), so a per-platform figure doesn't vary by size.
        let slotsPerPlatform = 3

        // ── A tap gets a SHORT timeline; only quiet rebuilds get the full one ──
        //
        // Measured on device, in order: our data work is 1ms (`tick=1ms`), the
        // refresh network is 145ms — and yet a tap took seconds to show
        // anything. The remaining term is WidgetKit itself: every entry
        // returned here is a SwiftUI tree the system renders and archives
        // BEFORE the widget visibly updates, and an interactive intent
        // invalidates the timeline, so every arrow press was paying for the
        // whole flipbook (the trace shows a full `getTimeline` per press, in
        // pairs). Fewer entries is the only lever that shortens tap-to-paint,
        // and it only matters on the builds a person is waiting for.
        //
        // So: a rebuild within a few seconds of a page move or a manual refresh
        // returns [INTERACTION_HORIZON] minutes. `.atEnd` then refills to the
        // full horizon when it runs out — by which point the interaction stamp
        // is stale, so the refill takes the quiet path below. In normal use the
        // app's own pushes replace the timeline for free long before that; the
        // refill is the backstop, and it costs at most one budgeted reload per
        // burst of taps, not per tap, because each tap replaces the same
        // timeline.
        // ── Every App Group read the render needs, done ONCE here ──
        //
        // These four answers are identical for every entry in the batch, and
        // the views used to look each one up from inside `body` — so a tap paid
        // for them per entry, per widget, inside WidgetKit's archiving pass.
        // See `BoardRenderState`.
        let movedAt = WidgetBoardPage.lastMoveAt(data.stationId)
        let refreshedAt = AppGroupDefaults.shared?.double(forKey: AppGroupKeys.lastRefreshOk) ?? 0
        let render = BoardRenderState(
            page: WidgetBoardPage.storedPage(data.stationId),
            // An arrow press wins only when it is more recent than both the last
            // refresh and the payload itself — compared as absolute times
            // because a page move does not rebuild the data, so "recent" cannot
            // be judged against this entry's date.
            isPageMove: movedAt > max(refreshedAt, data.lastUpdated.timeIntervalSince1970),
            moveForward: WidgetBoardPage.lastMoveWasForward(data.stationId),
            refreshFailed: AppGroupDefaults.shared?
                .bool(forKey: AppGroupKeys.refreshFailed(data.stationId)) ?? false
        )

        let interactionAt = max(movedAt, refreshedAt)
        let isInteraction = now.timeIntervalSince1970 - interactionAt < 3
        let horizon = Self.horizonMinutes(for: data, from: now)

        // ── Dense near, sparse far ──
        //
        // Entry count drives tap latency (WidgetKit archives every entry before
        // the tap paints) but timeline LENGTH drives the refresh budget, because
        // `.atEnd` asks for a new timeline when the entries run out and Apple
        // meters that at ~40–70/day. Those pull in opposite directions, and a
        // single uniform spacing has to lose one of them: a short per-minute
        // timeline paints fast and then expires every few minutes, which on a
        // home screen with three widgets is enough reloads to get the whole
        // kind THROTTLED — and a throttled widget looks exactly like one that
        // will not update until you touch it.
        //
        // So the spacing is not uniform. The first [denseMinutes] carry an entry
        // each, because that is the window a countdown is read in and the one
        // the user is watching after a tap. Past it, entries step every
        // [sparseStepMinutes] purely to keep the timeline ALIVE to the same
        // horizon as before — same expiry, same budget, a fraction of the
        // archive cost.
        //
        // The cost of the taper is that a label more than [denseMinutes] out can
        // sit up to [sparseStepMinutes] stale. That is affordable precisely
        // where it applies: a payload that old is already being held by the
        // retention layer, and in normal use the app has replaced the whole
        // timeline via a push long before the sparse tail is ever displayed.
        let dense = isInteraction ? Self.INTERACTION_HORIZON : Self.denseMinutes
        var offsets: [Int] = Array(1...min(dense, horizon))
        if horizon > dense {
            offsets.append(contentsOf: stride(from: dense + Self.sparseStepMinutes,
                                              through: horizon,
                                              by: Self.sparseStepMinutes))
        }

        var entries: [DepartureEntry] = [
            DepartureEntry(date: now,
                           widgetData: data.ticked(at: now, keepAtLeast: slotsPerPlatform),
                           render: render)
        ]
        entries.reserveCapacity(offsets.count + 1)
        for offset in offsets {
            if let date = calendar.date(byAdding: .minute, value: offset, to: currentMinute) {
                entries.append(DepartureEntry(
                    date: date,
                    widgetData: data.ticked(at: date, keepAtLeast: slotsPerPlatform),
                    render: render))
            }
        }
        // Written to the App Group as well as os_log: a widget extension has no
        // attachable console, so this is the only way to see where a slow tap
        // goes. `read` is JSON decode, `tick` is our row re-derivation, and
        // `entries` is the count WidgetKit then has to render and archive —
        // which the first measurement showed is the part that actually costs.
        let readMs = Int(tRead.timeIntervalSince(tStart) * 1000)
        let tickMs = Int(Date().timeIntervalSince(tRead) * 1000)
        let cause = isInteraction ? "tap" : "quiet"
        WidgetRefreshService.note("timeline \(cause) read=\(readMs)ms tick=\(tickMs)ms entries=\(entries.count)")
        providerLog.notice("timeline returning \(entries.count) entries (\(cause, privacy: .public)) read=\(readMs)ms tick=\(tickMs)ms")
        return Timeline(entries: entries, policy: .atEnd)
    }

    /// How many minutes of entries to build — and the single biggest lever on
    /// how a widget TAP feels.
    ///
    /// ## Why entry count is a latency question at all
    /// It looks like a data-freshness setting and it is not. Measured on device:
    /// re-deriving all 61 entries' rows costs **1ms**, so the work this code
    /// does is irrelevant. What matters is that WidgetKit renders and ARCHIVES
    /// every entry when the timeline is returned — 61 entries is 61 SwiftUI view
    /// trees evaluated and encoded — and the device trace shows a full
    /// `getTimeline` on **every arrow press** (15 rebuilds in 113 seconds of
    /// tapping). So the board was being rendered 61 times to move one page.
    ///
    /// ## Sized to what the data can actually say
    /// A fixed hour was always wrong in the same direction: once the last known
    /// departure has gone, every later entry renders the identical held-or-empty
    /// board. A station whose last train is twelve minutes out was paying for
    /// ~48 entries that differ in nothing.
    ///
    /// So the horizon runs to the last departure plus a small tail, which keeps
    /// per-minute countdown accuracy exactly — every minute still gets its own
    /// entry — while cutting the count to what carries information.
    ///
    /// ## The floor is a refresh-budget decision, not a display one
    /// `.atEnd` asks for a new timeline when the entries run out, and Apple
    /// meters that (~40–70/day). [minHorizon] of 30 minutes bounds it at ~48/day
    /// in the worst case of a permanently empty board. The app also reloads
    /// widgets directly whenever a push or stream frame lands, so in normal use
    /// the timeline is replaced long before it expires and this floor is the
    /// backstop rather than the schedule.
    static func horizonMinutes(for data: WidgetData, from now: Date) -> Int {
        var last: Double?
        for group in data.groups {
            for row in group.rows {
                guard let t = row.targetEpochMs else { continue }
                if last == nil || t > last! { last = t }
            }
        }
        guard let last else { return minHorizon }
        // Plus a tail so the board does not expire the instant its last train
        // does — the retention layer still has something to hold, and a reload
        // landing a couple of minutes later is cheaper than one landing exactly
        // as the user looks.
        let minutesOut = Int((last / 1000 - now.timeIntervalSince1970) / 60) + 3
        return min(maxHorizon, max(minHorizon, minutesOut))
    }

    private static let minHorizon = 30
    private static let maxHorizon = 60

    /// Per-minute entries returned while the user is WAITING on a tap.
    ///
    /// Smaller than [denseMinutes] because every entry is another board
    /// WidgetKit archives before the tap paints, and this is the only path where
    /// someone is watching that happen. It no longer shortens the TIMELINE —
    /// the sparse tail carries it to the same horizon — so buying tap latency
    /// here costs no refresh budget.
    static let INTERACTION_HORIZON = 8

    /// How far out entries are worth spending one-per-minute on.
    ///
    /// A countdown is read over the next few minutes; past that the number is
    /// context, not a decision. Fifteen keeps every label exact through the
    /// window anyone actually watches.
    static let denseMinutes = 15

    /// Spacing of the tail that keeps the timeline alive to its horizon.
    ///
    /// Five is the largest step that cannot make a label wrong by a whole
    /// "walk or run" decision, and it cuts a 60-minute tail from 45 entries to
    /// 9. The tail exists for `.atEnd` timing, not for legibility.
    static let sparseStepMinutes = 5

    /// Puts the station's name on the widget in the home-screen editor's
    /// carousel of configured widgets, so three Stationly widgets are told
    /// apart while being arranged.
    func recommendations() -> [AppIntentRecommendation<SelectStationIntent>] {
        AppGroupStorage.shared.readStations().map { station in
            AppIntentRecommendation(
                intent: SelectStationIntent(station: station),
                description: Text(station.name)
            )
        }
    }
}

// MARK: - Widget declaration

struct StationlyDepartureBoardWidget: Widget {
    static let kind = "StationlyDepartureBoardWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: Self.kind,
            intent: SelectStationIntent.self,
            provider: DepartureBoardProvider()
        ) { entry in
            DepartureBoardEntryView(entry: entry)
        }
        .configurationDisplayName("Stationly Departures")
        .description("Live TfL departures. Add one per station.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        // The black dot-matrix panel IS the product identity — keep it under
        // the amber text everywhere (StandBy, lock screen contexts) instead of
        // letting the system strip it to bare glyphs.
        .containerBackgroundRemovable(false)
        // Kill iOS 17's default ~16pt safe-area margins: the board's lit cells
        // run edge-to-edge so the widget reads as one LED panel, not a board
        // floating inside a black frame. The system's corner mask clips the
        // outer cells to the widget's continuous radius. (Cleared of suspicion
        // in the Code=2 bisect — see docs/IOS_WIDGET_DESIGN.md §3.4.)
        .contentMarginsDisabled()
    }
}
