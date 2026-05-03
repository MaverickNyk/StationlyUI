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
    // We supply a single entry and request a refresh every 30 seconds so that
    // the ETA countdown stays accurate. The KMP layer also bumps
    // `widget_reload_signal` on each FCM push, triggering an immediate refresh
    // via WidgetReloadObserver in the main app process.
    func getTimeline(in context: Context, completion: @escaping (Timeline<DepartureEntry>) -> Void) {
        let data  = AppGroupStorage.shared.readWidgetData()
        let entry = DepartureEntry(date: Date(), widgetData: data)

        let nextRefresh = Calendar.current.date(byAdding: .second, value: 30, to: Date())!
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
