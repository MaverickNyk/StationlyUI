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
    // reload via WidgetReloadObserver in the main app process — that is the primary
    // real-time path. The 15-minute fallback here is just for the case where the
    // app is backgrounded and no push arrives. A 30-second interval would exhaust
    // Apple's daily refresh budget (~40–70 reloads) in under 35 minutes.
    func getTimeline(in context: Context, completion: @escaping (Timeline<DepartureEntry>) -> Void) {
        let data  = AppGroupStorage.shared.readWidgetData()
        let entry = DepartureEntry(date: Date(), widgetData: data)

        let nextRefresh = Calendar.current.date(byAdding: .minute, value: 15, to: Date())!
        let timeline    = Timeline(entries: [entry], policy: .after(nextRefresh))
        completion(timeline)
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
    }
}
