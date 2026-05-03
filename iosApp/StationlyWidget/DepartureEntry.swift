import WidgetKit

/// TimelineEntry that carries a full snapshot of departure board data for a
/// single point in time. WidgetKit calls getTimeline() to request a batch of
/// these; we supply one entry and ask to be refreshed every 30 seconds.
struct DepartureEntry: TimelineEntry {
    /// The wall-clock time this entry represents (required by TimelineEntry).
    let date: Date
    /// The departure board data to render at `date`.
    let widgetData: WidgetData
}
