import BackgroundTasks
import UIKit
import composeApp

/// Uploads the user's queued activity trail, once a night.
///
/// ## Why this is a separate task from the widget refresh
/// `BackgroundRefreshScheduler` uses a `BGAppRefreshTask`, which iOS grants in
/// short slices and schedules from observed app usage — it is tuned for "keep
/// this fresh", runs often, and is cancelled aggressively. Uploading a day of
/// events is the opposite shape of work: it needs to happen once, it does not
/// matter when, and it should never compete with the thing keeping the widget
/// current. A `BGProcessingTask` is the right instrument — the system runs it
/// when the device is idle and usually charging, with a far longer slice.
///
/// Sharing the refresh task instead would have been worse in both directions:
/// every widget wake would carry a network upload it did not need, and the
/// upload would inherit a budget measured in seconds.
///
/// ## Nightly is an intent, never a promise
/// `earliestBeginDate` is a floor. iOS decides when — or whether — to run this,
/// and a phone in Low Power Mode, or one whose owner force-quits the app, may
/// get no processing task for days. Nothing here treats a run as guaranteed:
/// the queue is durable, capped, and drained by whichever wake arrives first,
/// and `ActivityBridge.uploadActivityIfStale` on foreground is the safety net
/// for a device that genuinely never gets one.
enum ActivityUploadScheduler {

    /// Must match `BGTaskSchedulerPermittedIdentifiers` in project.yml exactly.
    /// A mismatch is an exception at `register`, i.e. a launch-time crash.
    /// Composed from the bundle id — see `BGTaskIdentifier` for why.
    static let taskIdentifier = BGTaskIdentifier.make("activityupload")

    /// Registered from `didFinishLaunchingWithOptions`. **Must** happen before
    /// the app finishes launching — `BGTaskScheduler` refuses registrations
    /// after that, silently leaving the upload dead for the whole session.
    static func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: taskIdentifier, using: nil
        ) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(processingTask)
        }
    }

    /// Ask for the next run, at the next quiet hour.
    static func schedule() {
        // Cancel first: submitting over a pending request of the same
        // identifier throws.
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)

        let request = BGProcessingTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = nextQuietHour()
        // Network is the whole job, so requiring it is honest — a run without
        // it can do nothing but fail and reschedule.
        request.requiresNetworkConnectivity = true
        // Requiring power is what makes "nightly" realistic rather than
        // aspirational: a phone on a charger overnight is the single most
        // predictable window iOS has, and this is work with no deadline that
        // should never cost a user battery they are awake to notice.
        request.requiresExternalPower = true

        do {
            try BGTaskScheduler.shared.submit(request)
            PushTraceSwift.log("activity upload scheduled for \(request.earliestBeginDate?.description ?? "?")")
        } catch {
            // The common cause is Background App Refresh being switched off for
            // the app, which is a user setting and not a bug — but it is also
            // invisible without this line, and it looks exactly like a broken
            // scheduler. The foreground fallback covers this device.
            PushTraceSwift.log("activity upload submit failed: \(error.localizedDescription)")
        }
    }

    private static func handle(_ task: BGProcessingTask) {
        // Reschedule FIRST. Only one request per identifier may be pending, and
        // if this run is killed on expiry or throws, anything after the work
        // would never execute — leaving the app with no pending task and the
        // nightly upload silently over until the next launch.
        schedule()

        let work = Task { @MainActor in
            let uploaded = (try? await ActivityBridge.shared.uploadActivity())?.boolValue ?? false
            PushTraceSwift.log("activity upload ran, uploaded=\(uploaded)")
            task.setTaskCompleted(success: true)
        }

        task.expirationHandler = {
            PushTraceSwift.log("activity upload expired")
            work.cancel()
        }
    }

    /// The next 03:00 local time.
    ///
    /// A fixed hour rather than "24 hours from now", so a user's uploads settle
    /// on one time of day instead of drifting an hour later on each run — which
    /// would eventually put every upload in the middle of their commute, the
    /// one window the device is busy doing the thing the app is for.
    ///
    /// Falls back to a plain 12-hour offset if the calendar cannot produce a
    /// date, which it should never fail to do; a scheduled-but-odd time is
    /// better than no upload at all.
    private static func nextQuietHour() -> Date {
        let calendar = Calendar.current
        let target = DateComponents(hour: 3, minute: 0)
        return calendar.nextDate(
            after: Date(),
            matching: target,
            matchingPolicy: .nextTime
        ) ?? Date(timeIntervalSinceNow: 12 * 60 * 60)
    }
}
