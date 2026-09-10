import BackgroundTasks
import UIKit
import WidgetKit
import composeApp

/// Wakes the app periodically to refresh the widget's data.
///
/// ## Why this layer exists at all
/// The widget's own timeline reloads are metered by WidgetKit at roughly 40–70
/// a day. A 15-minute peak cadence is 96 a day on its own, so it is simply not
/// reachable from the timeline — and a widget that overspends is *throttled*,
/// which on a home screen is indistinguishable from a widget that is broken.
///
/// `BGAppRefreshTask` draws on a **different budget**: the system's own
/// scheduling of app background work, allocated per app from observed usage. So
/// the peak tiers are paid for here, and the timeline's `.after(next)` becomes
/// the floor rather than the ceiling. The two together are what make rush hour
/// dense without putting the widget at risk.
///
/// ## What the system will and will not promise
/// `earliestBeginDate` is a floor, never a schedule. iOS decides when — or
/// whether — to run this, learning from when the user actually opens the app;
/// a 07:40 commuter tends to get generous morning wakes and a phone in Low
/// Power Mode gets none. Nothing here treats a run as guaranteed: every layer
/// below (the ticking timeline, the scheduled reload) stands on its own, and
/// this only ever makes the board *fresher* than it would otherwise be.
///
/// One task may be pending at a time, so every run reschedules its successor —
/// including the failure paths, or a single bad wake ends background refresh
/// until the next launch.
enum BackgroundRefreshScheduler {

    /// Must match `BGTaskSchedulerPermittedIdentifiers` in project.yml exactly.
    /// A mismatch is an exception at `register`, i.e. a launch-time crash.
    ///
    /// Derived from the bundle id rather than hardcoded, because that entry in
    /// project.yml is now itself derived
    /// (`$(STATIONLY_BUNDLE_BASE)$(STATIONLY_BUNDLE_SUFFIX).widgetrefresh`) so
    /// that staging and production register distinct identifiers. Composing it
    /// the same way here is what keeps the two in step by construction — a
    /// literal would have had to be edited in lockstep with a build setting,
    /// and getting that wrong crashes the app on launch.
    static let taskIdentifier = BGTaskIdentifier.make("widgetrefresh")

    /// Used when the policy cannot be read (first launch, before KMP has
    /// published). Deliberately the slow end: guessing high would spend the
    /// user's battery on exactly the devices that have not yet been told
    /// otherwise.
    private static let fallbackMinutes = 60

    /// Registered from `didFinishLaunchingWithOptions`. **Must** happen before
    /// the app finishes launching — `BGTaskScheduler` refuses registrations
    /// after that, silently leaving background refresh dead.
    static func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: taskIdentifier, using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(refreshTask)
        }
    }

    /// Ask for the next wake, at the cadence the tier in force calls for.
    ///
    /// A tier of 0 means "no background wake in this band" — overnight, where
    /// waking a phone at 03:00 to fetch a board nobody will look at spends
    /// battery to change nothing. In that case any pending request is cancelled
    /// rather than left to fire.
    static func schedule() {
        // K/N requires exported suspend functions to be CALLED from the main
        // thread (the default launch-thread restriction); the Kotlin side hops
        // to a background dispatcher immediately, so nothing heavy runs here.
        Task { @MainActor in
            let minutes = await withCheckedContinuation { continuation in
                RefreshScheduleBridge.shared.backgroundTaskMinutes { value, error in
                    continuation.resume(returning: error == nil ? value?.intValue : nil)
                }
            }
            submit(minutes: minutes ?? fallbackMinutes)
        }
    }

    private static func submit(minutes: Int) {
        guard minutes > 0 else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
            PushTraceSwift.log("bgtask disabled for this tier")
            return
        }
        // Cancel first: submitting over a pending request of the same
        // identifier throws, and a tier change is exactly when the pending one
        // is for the wrong interval.
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)

        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(minutes * 60))
        do {
            try BGTaskScheduler.shared.submit(request)
            PushTraceSwift.log("bgtask scheduled in \(minutes)m")
        } catch {
            // The common cause is Background App Refresh being switched off for
            // the app, which is a user setting and not a bug — but it is also
            // invisible without this line, and it looks exactly like a broken
            // scheduler.
            PushTraceSwift.log("bgtask submit failed: \(error.localizedDescription)")
        }
    }

    private static func handle(_ task: BGAppRefreshTask) {
        // Reschedule FIRST. Only one request per identifier may be pending, and
        // if this run is killed on expiry or throws, anything after the work
        // would never execute — leaving the app with no pending task and
        // background refresh silently over until the next launch.
        schedule()

        let work = Task {
            await refreshNow(reason: "bgtask")
            task.setTaskCompleted(success: true)
        }

        // iOS gives a background task a short slice and then wants it back. The
        // handler cancels the work rather than letting the system kill the
        // process, which would count against future scheduling.
        task.expirationHandler = {
            PushTraceSwift.log("bgtask expired")
            work.cancel()
        }
    }

    /// Fetch every tracked board and republish the widget.
    ///
    /// Goes through KMP rather than through the widget extension's own
    /// `WidgetRefreshService`, for two reasons. The extension's copy is a
    /// documented fallback that writes ONLY the App Group because it cannot
    /// open SQLite — when the app is awake there is no reason to accept that
    /// gap, and the board the user next opens in the app should be as fresh as
    /// the one on their home screen. It is also in a different target and not
    /// linked here at all.
    ///
    /// Shared with the push path, which wants exactly the same work done.
    @discardableResult
    static func refreshNow(reason: String) async -> Bool {
        let refreshed = await onKotlinMain { continuation in
            RefreshScheduleBridge.shared.refreshAllBoards { value, error in
                if let error {
                    PushTraceSwift.log("refresh(\(reason)) failed: \(error.localizedDescription)")
                }
                continuation.resume(returning: value?.boolValue ?? false)
            }
        }
        PushTraceSwift.log("refresh(\(reason)) refreshed=\(refreshed)")

        // KMP bumps the reload signal only when a board actually CHANGED, which
        // is what keeps a quiet station from spending budget. Reloading here
        // regardless covers the other case: the data stood still but the
        // cadence did not, and a new timeline is the only way that takes effect.
        WidgetCenter.shared.reloadAllTimelines()
        return refreshed
    }

    /// Refetch the policy if stale and republish the schedule KMP owns.
    @discardableResult
    static func publishSchedule() async -> Bool {
        await onKotlinMain { continuation in
            RefreshScheduleBridge.shared.syncAndPublish { changed, error in
                if let error {
                    PushTraceSwift.log("schedule publish failed: \(error.localizedDescription)")
                }
                continuation.resume(returning: changed?.boolValue ?? false)
            }
        }
    }

    /// Await a KMP suspend function from anywhere.
    ///
    /// Kotlin/Native requires exported suspend functions to be CALLED from the
    /// main thread — the default launch-thread restriction — and these are
    /// invoked from background-task handlers and push callbacks that are not on
    /// it. The Kotlin side hops to a background dispatcher immediately, so this
    /// only borrows main for the call itself.
    static func onKotlinMain(
        _ body: @escaping (CheckedContinuation<Bool, Never>) -> Void
    ) async -> Bool {
        await withCheckedContinuation { continuation in
            Task { @MainActor in body(continuation) }
        }
    }
}
