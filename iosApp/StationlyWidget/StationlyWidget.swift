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
    func placeholder(in context: Context) -> DepartureEntry {
        DepartureEntry(date: Date(), widgetData: .placeholder)
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
        let data = AppGroupStorage.shared.readWidgetData(stationId: configuration.station?.id)
        providerLog.notice("timeline family=\(String(describing: context.family), privacy: .public) station=\(data.stationName.isEmpty ? "<none>" : "set", privacy: .public) id=\(data.stationId, privacy: .public) deps=\(data.departures.count) statusLen=\(data.status.count)")

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

        var entries: [DepartureEntry] = [
            DepartureEntry(date: now, widgetData: data.ticked(at: now, keepAtLeast: slotsPerPlatform))
        ]
        for offset in 1...60 {
            if let date = calendar.date(byAdding: .minute, value: offset, to: currentMinute) {
                entries.append(DepartureEntry(
                    date: date,
                    widgetData: data.ticked(at: date, keepAtLeast: slotsPerPlatform)))
            }
        }
        providerLog.notice("timeline returning \(entries.count) entries")
        return Timeline(entries: entries, policy: .atEnd)
    }

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
