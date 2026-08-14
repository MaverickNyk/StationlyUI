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
        return DepartureEntry(
            date: Date(),
            widgetData: data,
            // No link from the GALLERY preview — its board is invented, so a tap
            // would deep-link to a station the user may not even track. A real
            // snapshot is a live board and gets the same link a timeline entry
            // would.
            render: BoardRenderState(
                deepLink: context.isPreview
                    ? nil
                    : StationlyDeepLink.board(stationId: data.stationId))
        )
    }

    // Called when WidgetKit needs a fresh set of entries to display.
    //
    // ## Four ways a board gets newer, and only one of them is metered
    //  1. The entries themselves. Every entry's rows are RE-DERIVED for that
    //     entry's wall-clock minute (`ticked(at:)` — Android tickPredictions
    //     parity): ETAs count down, departed trains drop, the queue shifts,
    //     all without the app running and WITHOUT spending any budget. This is
    //     the iOS analog of Android's ACTION_ETA_TICK watchdog, and it is why a
    //     widget on a 45-minute refresh still looks live.
    //  2. `.after(next)` at the bottom — the scheduled reload, and the one
    //     Apple meters at ~40–70/day. Its interval comes from the tier in force;
    //     see `RefreshScheduleStore`.
    //  3. The app, when it runs: a background task or a push writes the App
    //     Group and calls `reloadTimelines`.
    //  4. A WidgetKit push straight to this extension on iOS 26+, which does
    //     not wake the app at all — see `WidgetPushHandler`.
    //
    // The footer clock ticks per second on its own (`.timer` Text, see
    // LiveClock); the per-minute entries re-anchor it across midnight.
    func timeline(for configuration: SelectStationIntent, in context: Context) async -> Timeline<DepartureEntry> {
        let tStart = Date()
        var data = AppGroupStorage.shared.readWidgetData(stationId: configuration.station?.id)

        // Tell the APP which station this widget is on. It cannot find out for
        // itself — the answer lives in an AppIntent type compiled into this
        // target — and `Board.widget` is the only per-board fact the app has no
        // other source for. Written before the fetch below, so a widget whose
        // refresh fails is still counted as placed.
        //
        // ── The CONFIGURED station, not the rendered one ──
        //
        // These two differ for exactly one widget: one whose station has been
        // deleted and which `readWidgetData` has repointed. Stamping the
        // configured id keeps that widget from claiming the station it borrowed
        // — if it claimed it, the next build would find it taken and repoint
        // somewhere else, and the board would walk down the user's station list
        // a few minutes at a time. The `?? data.stationId` arm is for a widget
        // with no configuration at all, which is showing what it stamps.
        AppGroupStorage.shared.notePlacement(
            station: configuration.station?.id ?? data.stationId,
            family: String(describing: context.family)
        )

        // ── Fetch when what we hold is stale ──
        //
        // A reload re-runs this provider against whatever is in the App Group;
        // it does NOT fetch. That is fine when the app just wrote the board, and
        // wrong in the two cases that matter most:
        //
        //   - a WIDGETKIT PUSH deliberately does not wake the app, so nothing
        //     else can fetch. Without this the push repaints identical numbers
        //     and the "ago" timer visibly does not move — measured on device.
        //   - a SCHEDULED reload after a long quiet stretch. Overnight the next
        //     build is ~9 hours out, and the app may not have run in between, so
        //     the board would render departures that old.
        //
        // `WidgetRefreshService` is the same REST path the header's refresh
        // button uses: it fans out over every installed board, coalesces
        // concurrent callers, and writes the App Group. Awaiting it here costs
        // roughly one round trip (~150ms measured) and only on builds that are
        // actually stale — an app-driven reload writes the board first, so its
        // data is seconds old and this is skipped.
        if Date().timeIntervalSince(data.lastUpdated) > Self.staleAfterSeconds {
            let outcome = await Self.refreshBounded(stationId: data.stationId)
            if outcome == .refreshed {
                // Re-read: the refresh wrote the App Group, and the whole point
                // is to render what it just fetched rather than what we opened
                // this function holding.
                data = AppGroupStorage.shared.readWidgetData(stationId: configuration.station?.id)
            }
            WidgetRefreshService.note("timeline stale → refresh outcome=\(outcome)")
        }
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
        // holds its own last few departures when it has fewer upcoming.
        //
        // It must not EXCEED the slots a block actually displays. Retained rows
        // sort to the top of their block, so holding more of them than the
        // renderer's `prefix` will show would hide the live trains underneath —
        // the precise opposite of what retention is for. Under-shooting is
        // harmless (a block simply renders short), which is why the large family
        // is left at 3 rather than tracking its own 6-cell budget.
        //
        // `rowCap` is the station's own setting, so a user who asked for two
        // rows per platform gets two held rather than three.
        let slotsPerPlatform = min(data.rowCap, 3)

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
        // returns [INTERACTION_HORIZON] minutes of DENSE entries. The sparse
        // tail still carries the timeline to the same horizon, so buying tap
        // latency here costs no refresh budget — and the scheduled reload is
        // `.after(next)` regardless of which path built the entries, so a burst
        // of taps cannot pull the next refresh forward.
        //
        // ── Every App Group read the render needs, done ONCE here ──
        //
        // These four answers are identical for every entry in the batch, and
        // the views used to look each one up from inside `body` — so a tap paid
        // for them per entry, per widget, inside WidgetKit's archiving pass.
        // See `BoardRenderState`.
        let movedAt = WidgetBoardPage.lastMoveAt(data.stationId)
        let refreshedAt = AppGroupDefaults.shared?.double(forKey: AppGroupKeys.lastRefreshOk) ?? 0
        let render = BoardRenderState(
            pages: WidgetBoardPage.storedPages(data.stationId),
            // An arrow press wins only when it is more recent than both the last
            // refresh and the payload itself — compared as absolute times
            // because a page move does not rebuild the data, so "recent" cannot
            // be judged against this entry's date.
            isPageMove: movedAt > max(refreshedAt, data.lastUpdated.timeIntervalSince1970),
            moveForward: WidgetBoardPage.lastMoveWasForward(data.stationId),
            refreshFailed: AppGroupDefaults.shared?
                .bool(forKey: AppGroupKeys.refreshFailed(data.stationId)) ?? false,
            // Built from the station actually being RENDERED, which for a widget
            // whose station was deleted is the repointed one rather than
            // `configuration.station`. Once per timeline, not once per entry.
            deepLink: StationlyDeepLink.board(stationId: data.stationId)
        )

        let interactionAt = max(movedAt, refreshedAt)
        let isInteraction = now.timeIntervalSince1970 - interactionAt < 3

        // ── The cadence, decided by KMP and looked up here ──
        //
        // Everything that used to be a `static let` below is now the tier's to
        // say, because the right answer genuinely differs between 08:00 and
        // 03:00 and neither one can be compiled in. `RefreshScheduleStore`
        // explains why this arrives as a schedule rather than a single
        // decision. Recording the reload is what lets the governor on the
        // Kotlin side see what this process has actually spent — the app is not
        // running for most of these, so nothing else can count them.
        let cadence = RefreshScheduleStore.cadence(at: now)
        // Read before recording: `recordReload` uses the same answer to decide
        // whether this build costs anything, and the trace has to be able to
        // explain a count that did not move.
        let isFree = RefreshScheduleStore.isAppForeground(at: now)
        let spent = RefreshScheduleStore.recordReload(at: now)
        let horizon = Self.horizonMinutes(for: data, from: now, cadence: cadence)

        // ── Dense near, sparse far ──
        //
        // Entry count is what drives tap latency: WidgetKit renders and archives
        // every entry returned here BEFORE the widget visibly updates, so a
        // uniform per-minute hour is 60 SwiftUI trees between the tap and the
        // paint.
        //
        // So the spacing is not uniform. The first `denseMinutes` carry an entry
        // each, because that is the window a countdown is actually read in.
        // Past it, entries step every `sparseStepMinutes` — enough to keep the
        // board honest at a fraction of the archive cost.
        //
        // Both numbers come from the tier now, which is the point: overnight a
        // board is glanced at rarely and can taper hard, while at 08:15 every
        // minute of the next twenty matters. The cost of the taper is that a
        // label beyond the dense window can sit up to one step stale — affordable
        // exactly where it applies, since a payload that old is already being
        // held by the retention layer.
        let dense = isInteraction ? Self.INTERACTION_HORIZON : cadence.denseMinutes
        var offsets: [Int] = Array(1...min(dense, horizon))
        if horizon > dense {
            offsets.append(contentsOf: stride(from: dense + cadence.sparseStepMinutes,
                                              through: horizon,
                                              by: cadence.sparseStepMinutes))
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

        // ── `.after`, not `.atEnd` ──
        //
        // `.atEnd` tied the next reload to how far the ENTRIES happened to
        // reach, which made the refresh cadence a side effect of how many
        // trains a station had left. That is the wrong lever twice over: a quiet
        // station expired sooner than a busy one for no reason a user would
        // recognise, and the interval could not be tuned without also changing
        // how the board renders.
        //
        // `.after` separates them. Entry SHAPE stays a display question (dense
        // near, sparse far, cut at the last departure); WHEN to come back is the
        // policy's answer, which is the one thing the daily budget is actually
        // spent on. The interval is already governed and floored on the Kotlin
        // side, so it is used as given.
        let nextRefresh = now.addingTimeInterval(TimeInterval(cadence.intervalMinutes * 60))
        // Remembered so the NEXT build can tell whether it was this schedule
        // firing or an external trigger (push / app reload), which Apple meters
        // separately and which must not be charged here.
        RefreshScheduleStore.recordScheduledNext(nextRefresh)

        let ageHours = RefreshScheduleStore.scheduleAgeHours(at: now)
        let boostNote = cadence.boostActive ? " boost" : ""
        let staleNote = cadence.isFallback ? " FALLBACK" : ""
        WidgetRefreshService.note(
            "timeline \(cause) read=\(readMs)ms tick=\(tickMs)ms entries=\(entries.count) " +
            "tier=\(cadence.tierId)\(boostNote)\(staleNote) next=\(cadence.intervalMinutes)m " +
            "spent=\(spent)\(isFree ? " FREE" : "") schedAge=\(ageHours)h")
        providerLog.notice("timeline returning \(entries.count) entries (\(cause, privacy: .public)) read=\(readMs)ms tick=\(tickMs)ms tier=\(cadence.tierId, privacy: .public) next=\(cadence.intervalMinutes)m spent=\(spent)")
        return Timeline(entries: entries, policy: .after(nextRefresh))
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
    /// ## The floor is no longer a budget decision
    /// It used to be: under `.atEnd` the horizon WAS the refresh interval, so
    /// this number silently controlled how much quota the widget spent. Now that
    /// the timeline carries an explicit `.after(next)`, the two are independent
    /// — this decides only how far the board can count down before it needs new
    /// data, and [RefreshScheduleStore] decides when that data arrives.
    ///
    /// The floor survives as a display guarantee: a board with no known
    /// departures still renders and still ticks its clock for half an hour.
    static func horizonMinutes(
        for data: WidgetData,
        from now: Date,
        cadence: RefreshScheduleStore.Cadence
    ) -> Int {
        // The tier's ceiling: overnight there is no point building an hour of
        // entries nobody will see, and the archive cost is paid on every build.
        let maxHorizon = max(cadence.horizonMinutes, minHorizon)
        var last: Double?
        for group in data.groups {
            for row in group.rows {
                guard let t = row.targetEpochMs else { continue }
                if last == nil || t > last! { last = t }
            }
        }
        guard let last else { return min(minHorizon, maxHorizon) }
        // Plus a tail so the board does not expire the instant its last train
        // does — the retention layer still has something to hold, and a reload
        // landing a couple of minutes later is cheaper than one landing exactly
        // as the user looks.
        let minutesOut = Int((last / 1000 - now.timeIntervalSince1970) / 60) + 3
        return min(maxHorizon, max(minHorizon, minutesOut))
    }

    private static let minHorizon = 30

    /// Per-minute entries returned while the user is WAITING on a tap.
    ///
    /// Smaller than [denseMinutes] because every entry is another board
    /// WidgetKit archives before the tap paints, and this is the only path where
    /// someone is watching that happen. It no longer shortens the TIMELINE —
    /// the sparse tail carries it to the same horizon — so buying tap latency
    /// here costs no refresh budget.
    /// Applies on the interaction path ONLY, and stays a constant on purpose:
    /// it is a latency budget for a person waiting on a tap, not a freshness
    /// setting, so it does not vary with the hour the way the tier's
    /// `denseMinutes` and `sparseStepMinutes` do. Those now come from
    /// [RefreshScheduleStore.Cadence].
    static let INTERACTION_HORIZON = 8

    /// Hard wall-clock bound on the fetch a timeline build is willing to wait for.
    ///
    /// `DepartureRepository` allows 15 s per stop and `WidgetRefreshService`
    /// gives URLSession 10 s with `waitsForConnectivity` on — fine for a tap,
    /// dangerous here. A widget extension gets a short slice before the system
    /// reclaims it, and one measured build spent **15.3 s** in fetch alone,
    /// which is close enough to being killed mid-build to matter: the result is
    /// a widget stuck on its previous render with no diagnostic anywhere.
    ///
    /// Six seconds covers a healthy fetch several times over (a warm one
    /// measured 22–108 ms). Overrunning simply renders the data we already
    /// hold — the board stays live via the tick layer, and the next build
    /// tries again — which is a far better outcome than being terminated.
    private static let refreshBudgetSeconds: TimeInterval = 6

    /// [WidgetRefreshService.refresh] with [refreshBudgetSeconds] enforced.
    ///
    /// A racing sleep rather than a URLSession timeout, deliberately: the
    /// refresh fans out over several stations and its own per-request timeouts
    /// compose to more than any single one of them. This bounds the whole
    /// operation as the caller experiences it.
    private static func refreshBounded(stationId: String) async -> WidgetRefreshService.RefreshOutcome {
        await withTaskGroup(of: WidgetRefreshService.RefreshOutcome?.self) { group in
            group.addTask { await WidgetRefreshService.refresh(stationId: stationId) }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(refreshBudgetSeconds * 1_000_000_000))
                return nil
            }
            // Whichever finishes first decides. `nil` is the timer winning.
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? .failed
        }
    }

    /// How old the stored board may be before a timeline build fetches rather
    /// than rendering what it found.
    ///
    /// Two minutes: comfortably longer than the gap between the app writing a
    /// board and WidgetKit asking for a timeline (so app-driven reloads never
    /// pay for a fetch), and far shorter than any tier interval (so every
    /// scheduled reload and every push does). Departures move on roughly a
    /// minute, so anything older than this is worth a round trip.
    static let staleAfterSeconds: TimeInterval = 120

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

/// The widget, as it exists on iOS 17–25.
///
/// ## Why there are two of these
/// `.pushHandler` is iOS 26+, and this extension deploys to 17. Normally a
/// version-gated modifier is applied with `if #available` inside `body` — but
/// `body` is `some WidgetConfiguration`, an opaque type that must be ONE
/// concrete type, and the modifier returns a different one. SwiftUI ships no
/// `AnyWidgetConfiguration` to erase the difference (checked against the iOS
/// 26.5 SDK), so the branch cannot live inside a configuration at all.
///
/// It has to happen where a result builder can handle it, which is the widget
/// BUNDLE — `WidgetBundleBuilder` supports `buildLimitedAvailability`. Hence
/// two `Widget` types selected by `StationlyWidgetBundle`.
///
/// The duplication is only the wrapper: [makeConfiguration] holds the entire
/// definition and both share it, so the two can never drift on anything a user
/// would see. Both also declare the same [kind], which is what lets an existing
/// widget survive the user upgrading to iOS 26 — WidgetKit matches on that
/// string, so the same home-screen widget is simply served by the other type.
struct StationlyDepartureBoardWidget: Widget {
    static let kind = "StationlyDepartureBoardWidget"

    var body: some WidgetConfiguration { Self.makeConfiguration() }

    /// The whole widget definition, shared with the push-enabled variant.
    static func makeConfiguration() -> some WidgetConfiguration {
        AppIntentConfiguration(
            kind: Self.kind,
            intent: SelectStationIntent.self,
            provider: DepartureBoardProvider()
        ) { entry in
            DepartureBoardEntryView(entry: entry)
        }
        // ── What the gallery and the editor call this ──
        //
        // "Station Boards", plural, because the unit a user adds is ONE board
        // and the expected shape is several of them. A singular name invites
        // the reading this widget spent a release being wrong about: that one
        // widget covers everything you track.
        //
        // The description is the only sentence between someone in the gallery
        // and a board on their home screen, so it says what to DO ("choose one
        // of your stations") rather than what the product is. "Live TfL
        // departures" described a category; nobody adding this doubts it shows
        // departures.
        .configurationDisplayName("Station Boards")
        .description("Choose one of your stations to show live departures. Add a board for each station you follow.")
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

/// The same widget on iOS 26+, with a push token attached.
///
/// Identical in every visible respect — it reuses
/// [StationlyDepartureBoardWidget.makeConfiguration] verbatim and declares the
/// same `kind`. The only difference is `.pushHandler`, which asks WidgetKit for
/// a push token so the backend can reload this widget directly, without waking
/// the app and without spending the timeline quota.
@available(iOS 26.0, *)
struct StationlyDepartureBoardWidgetPushable: Widget {
    var body: some WidgetConfiguration {
        StationlyDepartureBoardWidget.makeConfiguration()
            .pushHandler(StationlyWidgetPushHandler.self)
    }
}
