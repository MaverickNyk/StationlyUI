import WidgetKit

/// Receives pushes addressed to the WIDGET rather than to the app.
///
/// ## Why this is the good path
/// Every other way of making a widget current costs something scarce. A
/// timeline reload spends the ~40–70/day quota that, once exhausted, gets the
/// widget throttled. A silent background push wakes the whole app, is gated on
/// the user's Background App Refresh switch, and is throttled by the system on
/// its own schedule.
///
/// A WidgetKit push (iOS 26+) reloads this extension's timeline **without
/// launching the app at all**, on a budget separate from both. For "there is a
/// closure on the Victoria line, show it now" that is exactly right: the thing
/// that needs to change is a board on the home screen, and nothing else has to
/// wake up for that to happen.
///
/// ## What it does NOT do
/// It cannot persist anything. A reload re-runs the timeline provider against
/// whatever is already in the App Group, so a widget push makes the board
/// *re-render* — the fetch that gives it newer numbers still has to happen.
/// State changes (a new refresh policy, a boost) therefore go to the app as
/// background pushes instead; see `WidgetPushService` on the server for which
/// kind is routed where.
///
/// ## Registration
/// Requires the Push Notifications capability on the **widget extension**
/// target, not just the app. Without it no token is ever issued — no error, no
/// log, just a widget that never gets pushed to.
@available(iOS 26.0, *)
struct StationlyWidgetPushHandler: WidgetPushHandler {

    init() {}

    /// Called when WidgetKit issues or rotates this widget's push token.
    ///
    /// The token is written to the App Group rather than uploaded from here:
    /// this extension has no auth state, no idea which backend environment it
    /// is pointed at, and a very short execution window. The app picks it up on
    /// its next foreground and registers it — see `WidgetPushRegistrar`.
    ///
    /// Tokens rotate, so this is an upsert and not one-time setup.
    func pushTokenDidChange(_ pushInfo: WidgetPushInfo, widgets: [WidgetInfo]) {
        guard let d = AppGroupDefaults.shared else { return }

        let hex = pushInfo.token.map { String(format: "%02x", $0) }.joined()
        guard !hex.isEmpty else {
            d.removeObject(forKey: AppGroupKeys.widgetPushToken)
            d.synchronize()
            WidgetRefreshService.note("widgetpush token cleared")
            return
        }

        // Only write on change: this fires on widget reconfiguration too, and
        // rewriting an identical token would make the app re-register with the
        // backend for nothing.
        guard d.string(forKey: AppGroupKeys.widgetPushToken) != hex else { return }
        d.set(hex, forKey: AppGroupKeys.widgetPushToken)
        // Cross-process write — the APP is the reader, and cfprefsd will
        // otherwise hold this in cache long enough for the next foreground to
        // miss it entirely. Measured: the same omission on the foreground
        // heartbeat made it invisible to this extension.
        d.synchronize()
        WidgetRefreshService.note("widgetpush token stored widgets=\(widgets.count)")
    }
}
