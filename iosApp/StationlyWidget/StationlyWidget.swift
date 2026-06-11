import WidgetKit
import SwiftUI

// MARK: - TimelineProvider

struct DepartureBoardProvider: TimelineProvider {

    // Called immediately when the widget is added to the home screen.
    // Returns hard-coded placeholder data so the widget renders without delay.
    func placeholder(in context: Context) -> DepartureEntry {
        DepartureEntry(date: Date(), widgetData: .placeholder)
    }

    // Called for the widget gallery preview. Uses placeholder in preview mode,
    // real App Group data otherwise.
    func getSnapshot(in context: Context, completion: @escaping (DepartureEntry) -> Void) {
        let data = context.isPreview ? WidgetData.placeholder : AppGroupStorage.shared.readWidgetData()
        completion(DepartureEntry(date: Date(), widgetData: data))
    }

    // Called when WidgetKit needs a fresh set of entries to display.
    // KMP bumps `widget_reload_signal` on every FCM push, triggering an immediate
    // reload via WidgetReloadObserver / AppDelegate — that is the real-time path.
    //
    // The timeline itself carries ONE ENTRY PER MINUTE for the next hour, all
    // with the same board data: pre-rendered local entries are free (they don't
    // consume Apple's ~40–70/day refresh budget), and they're what drives the
    // footer's minute-accurate wall clock — the same trick the system Clock
    // widget uses. `.atEnd` re-reads the App Group once an hour (~24
    // refreshes/day, comfortably inside budget).
    func getTimeline(in context: Context, completion: @escaping (Timeline<DepartureEntry>) -> Void) {
        let data = AppGroupStorage.shared.readWidgetData()

        // Align entries to minute boundaries so the clock flips exactly on the minute.
        let calendar = Calendar.current
        let now = Date()
        let currentMinute = calendar.date(bySetting: .second, value: 0, of: now).flatMap {
            $0 > now ? calendar.date(byAdding: .minute, value: -1, to: $0) : $0
        } ?? now

        var entries: [DepartureEntry] = [DepartureEntry(date: now, widgetData: data)]
        for offset in 1...60 {
            if let date = calendar.date(byAdding: .minute, value: offset, to: currentMinute) {
                entries.append(DepartureEntry(date: date, widgetData: data))
            }
        }
        completion(Timeline(entries: entries, policy: .atEnd))
    }
}

// MARK: - Widget declaration

struct StationlyDepartureBoardWidget: Widget {
    static let kind = "StationlyDepartureBoardWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: DepartureBoardProvider()) { entry in
            DepartureBoardEntryView(entry: entry)
        }
        .configurationDisplayName("Stationly Departures")
        .description("Live TfL departure board for your saved station.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        // The black dot-matrix panel IS the product identity — keep it under
        // the amber text everywhere (StandBy, lock screen contexts) instead of
        // letting the system strip it to bare glyphs.
        .containerBackgroundRemovable(false)
    }
}
