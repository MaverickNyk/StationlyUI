import Foundation
import UIKit
import WidgetKit
import composeApp

/// Tells the backend how to reach this device's widgets, and decides what an
/// incoming trigger means.
///
/// ## Two tokens, two jobs
///  - The **widget push token** is issued by WidgetKit to the extension
///    (iOS 26+) and written to the App Group by `StationlyWidgetPushHandler`.
///    A push to it reloads the widget WITHOUT waking the app, on a budget
///    separate from the timeline quota. This is the path worth having.
///  - The **app APNs token** wakes the app silently. It works back to iOS 17,
///    so it is the fallback — and it is the only path that can change stored
///    state, because a widget reload cannot write anything.
///
/// The app owns registration for both: the extension has no auth state, no
/// knowledge of which backend it points at, and a very short execution window.
enum DevicePushCoordinator {

    /// This device's APNs token, captured in `didRegisterForRemoteNotifications`.
    /// Held in memory rather than persisted — iOS re-issues it on every launch,
    /// and a stored copy would only ever be a stale one to get confused by.
    private(set) static var appToken: String?

    

    static func setAppToken(_ data: Data) {
        appToken = data.map { String(format: "%02x", $0) }.joined()
    }

    /// Stable per-install id — the registry key.
    ///
    /// Keyed on this rather than on a token because TOKENS rotate and the
    /// device does not: keying by token would accumulate a new backend record
    /// on every rotation and leave the dead ones to be pushed at forever.
    /// Stored in the App Group so it survives the standard domain being wiped
    /// on sign-out (see the note on the FCM token in AppDelegate).
    private static func deviceId() -> String {
        let d = UserDefaults(suiteName: AppGroupID.value)
        if let existing = d?.string(forKey: AppGroupKeys.deviceId), !existing.isEmpty { return existing }
        let fresh = UUID().uuidString
        d?.set(fresh, forKey: AppGroupKeys.deviceId)
        d?.synchronize()
        return fresh
    }

    /// Which APNs environment this build's tokens belong to.
    ///
    /// A token is valid in exactly ONE environment: a development build's token
    /// is rejected by the production gateway with `BadDeviceToken` and vice
    /// versa. Derived from the same `aps-environment` entitlement that decided
    /// which gateway issued the token, so the two cannot disagree — reading a
    /// build flag instead is how this ends up wrong in a TestFlight build.
    private static func apnsEnvironment() -> String {
        if let cached = cachedEnvironment { return cached }
        let resolved: String = {
            guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
                  let raw = try? Data(contentsOf: url),
                  let text = String(data: raw, encoding: .isoLatin1),
                  let range = text.range(of: "<key>aps-environment</key>")
            else {
                // No profile means the App Store, which is always production.
                return "production"
            }
            let tail = text[range.upperBound...].prefix(200)
            return tail.contains("development") ? "sandbox" : "production"
        }()
        cachedEnvironment = resolved
        return resolved
    }

    /// Read once. The embedded profile cannot change under a running process,
    /// and `register()` runs on every foreground — re-reading and string-scanning
    /// a multi-kilobyte file each time bought nothing.
    private static var cachedEnvironment: String?

    /// Push whatever tokens we have to the backend.
    ///
    /// Idempotent and cheap, so it runs on every foreground: the widget token
    /// may have been written by the extension since we last looked, the app
    /// token is reissued each launch, and the station list changes whenever the
    /// user edits their boards.
    static func register() {
        let widgetToken = UserDefaults(suiteName: AppGroupID.value)?
            .string(forKey: AppGroupKeys.widgetPushToken)
        guard widgetToken != nil || appToken != nil else {
            // Traced because this is the silent failure mode: nothing to
            // register looks exactly like registration working, and the only
            // symptom is an empty registry on a server you may not be watching.
            PushTraceSwift.log("register skipped — no tokens yet")
            return
        }

        // Stations AND lines, from the same App Group directory the widget
        // configuration picker reads.
        //
        // The lines are not optional garnish: TfL reports disruption BY LINE
        // ("Victoria: Severe Delays"), so `DisruptionTriggerService` scopes its
        // pushes with `listForLines`. Registering stations alone left every
        // device with an empty `lines` array, which means the automatic
        // disruption trigger — the whole point of the feature — would have
        // resolved an audience of nobody and delivered nothing, silently.
        let tracked = trackedBoards()

        var body: [String: Any] = [
            "deviceId": deviceId(),
            "environment": apnsEnvironment(),
            "iosVersion": UIDevice.current.systemVersion,
            "stations": tracked.stations,
            "lines": tracked.lines,
        ]
        if let widgetToken { body["widgetToken"] = widgetToken }
        if let appToken { body["appToken"] = appToken }
        if let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String {
            body["appVersion"] = version
        }

        // Skip a POST that would change nothing.
        //
        // `register()` runs on every foreground, every APNs token callback and
        // after every account reconcile, but the payload only actually changes
        // when a token rotates or the user edits their boards — so the vast
        // majority were identical writes to Firestore. The signature covers the
        // whole body, so anything that genuinely changes still goes.
        //
        // In-memory only: a fresh process re-registers once, which is the
        // correct behaviour after an update or a crash.
        let signature = String(describing: body.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" })
        guard signature != lastRegisteredSignature else { return }

        guard let baseUrl = UserDefaults(suiteName: AppGroupID.value)?
                .string(forKey: AppGroupKeys.apiBaseURL),
              let apiKey = UserDefaults(suiteName: AppGroupID.value)?
                .string(forKey: AppGroupKeys.apiKey),
              // NOT under /user/* — that prefix is Firebase-auth gated, and this
              // request carries only the API key. Measured: the first on-device
              // registration 401'd for exactly that reason.
              let url = URL(string: "\(baseUrl)/api/v1/device/register"),
              let payload = try? JSONSerialization.data(withJSONObject: body)
        else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "X-Stationly-Key")
        // Sent when we have one, so the backend can VERIFY the uid and record
        // it against this device. Without it the registry has no account, and
        // `user.sync` — which targets by uid — reaches no iOS device at all.
        //
        // The endpoint is not auth-gated (a signed-out device still runs
        // widgets and still wants disruption pushes), so this is additive: no
        // token simply means no uid, not a rejected registration. The backend
        // verifies rather than trusting a body field, so this cannot be used to
        // claim someone else's account.
        if let idToken = UserDefaults.standard.string(forKey: AppGroupKeys.firebaseAuthToken),
           !idToken.isEmpty {
            request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = payload

        URLSession.shared.dataTask(with: request) { _, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            // Remembered only on success, so a failed attempt is retried on the
            // next foreground rather than being suppressed by its own signature.
            if status == 200 { lastRegisteredSignature = signature }
            PushTraceSwift.log(
                "widgetpush register status=\(status) widget=\(widgetToken != nil) " +
                "app=\(appToken != nil) stations=\(tracked.stations.count) lines=\(tracked.lines.count)" +
                (error.map { " err=\($0.localizedDescription)" } ?? ""))
        }.resume()
    }

    /// Body of the last registration the backend accepted. See `register()`.
    private static var lastRegisteredSignature: String?

    /// Stations and lines this device's widgets can display, from the directory
    /// KMP maintains for the widget configuration picker.
    ///
    /// Lines are lower-cased and de-duplicated here so the backend's
    /// `array-contains-any` query matches regardless of how KMP happened to
    /// capitalise a display name.
    private static func trackedBoards() -> (stations: [String], lines: [String]) {
        struct Ref: Decodable {
            let id: String
            /// Canonical ids, NOT the `lines` display names sitting beside them
            /// in the same payload — matching on "Hammersmith & City" would
            /// never hit a `hammersmith-city` incident.
            let lineIds: [String]?
        }
        guard let raw = UserDefaults(suiteName: AppGroupID.value)?.string(forKey: AppGroupKeys.stations),
              let data = raw.data(using: .utf8),
              let refs = try? JSONDecoder().decode([Ref].self, from: data)
        else { return ([], []) }

        let lines = Set(refs.flatMap { $0.lineIds ?? [] }
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .filter { !$0.isEmpty })
        return (refs.map(\.id), Array(lines).sorted())
    }

    // MARK: - Incoming triggers

    /// What the backend asked us to do.
    ///
    /// Mirrors `PushSignalKind` on the server and `PushSignal` in commonMain —
    /// one vocabulary, three implementations, so a new signal is added in one
    /// place and spelled the same everywhere.
    enum Trigger: String {
        /// The account changed on another device. **Not a widget concern** —
        /// this is the cross-device consistency signal, and the only one here
        /// that also exists on Android.
        case userSync = "user.sync"
        case refresh = "widget.refresh"
        case policyUpdate = "policy.update"
        case boostStart = "boost.start"
        case boostStop = "boost.stop"
    }

    /// Act on a silent push.
    ///
    /// Returns whether anything changed, which `didReceiveRemoteNotification`
    /// reports to iOS — a background wake that honestly reports `.noData` is
    /// scheduled more generously in future than one that always claims new data.
    ///
    /// Unknown or missing types are treated as a plain refresh rather than
    /// ignored: a push we do not recognise still means the backend thought
    /// something was worth telling us about, and refreshing is both harmless and
    /// the most likely intent.
    static func handle(_ userInfo: [AnyHashable: Any]) async -> Bool {
        let envelope = userInfo["stationly"] as? [String: Any]
        let kind = (envelope?["type"] as? String).flatMap(Trigger.init(rawValue:)) ?? .refresh
        let reason = envelope?["reason"] as? String ?? ""
        PushTraceSwift.log("widgetpush recv kind=\(kind.rawValue) reason=\(reason)")

        switch kind {
        case .userSync:
            // The uid check, the station diff and the profile reconcile all live
            // in shared Kotlin (`UserSyncBridge`), so iOS and Android cannot
            // drift on what "the cloud is the truth" means.
            let pushUid = envelope?["uid"] as? String
            if UserSyncBridge.shared.isAccountDeleted(reason: reason) {
                // Forcing a sign-out touches the keychain, the Firebase session
                // and the UI — none of which shared code owns, and a
                // half-applied logout is worse than a late one. Android routes
                // this to its own `forceLogout` for the same reason.
                PushTraceSwift.log("user.sync deleted → forcing sign-out")
                await AuthBridge.shared.signOutForAccountDeletion()
                return true
            }
            let changed = await BackgroundRefreshScheduler.onKotlinMain { continuation in
                UserSyncBridge.shared.handle(reason: reason, pushUid: pushUid) { value, _ in
                    continuation.resume(returning: value?.boolValue ?? false)
                }
            }
            if changed {
                // The station list may have changed, so the widget's boards and
                // the backend's idea of what this device shows both need
                // updating.
                _ = await BackgroundRefreshScheduler.refreshNow(reason: "user.sync")
                register()
            }
            return changed

        case .refresh:
            return await BackgroundRefreshScheduler.refreshNow(reason: "push:\(reason)")

        case .policyUpdate:
            // Force a refetch regardless of TTL — the backend is telling us the
            // cached copy is stale ahead of when we would have asked.
            let changed = await BackgroundRefreshScheduler.onKotlinMain { continuation in
                RefreshScheduleBridge.shared.forcePolicyRefresh { value, _ in
                    continuation.resume(returning: value?.boolValue ?? false)
                }
            }
            // The cadence changing only takes effect on a new timeline, and the
            // background task interval only on a resubmission.
            if changed { reloadAndReschedule() }
            return changed

        case .boostStart:
            let tierId = envelope?["tierId"] as? String ?? ""
            let minutes = envelope?["minutes"] as? Int ?? 0
            let changed = await BackgroundRefreshScheduler.onKotlinMain { continuation in
                RefreshScheduleBridge.shared.startBoost(
                    tierId: tierId, requestedMinutes: Int32(minutes), reason: reason
                ) { value, _ in continuation.resume(returning: value?.boolValue ?? false) }
            }
            // A boost is worth fetching for immediately — its whole purpose is
            // that the next few minutes matter more than usual.
            _ = await BackgroundRefreshScheduler.refreshNow(reason: "boost:\(reason)")
            reloadAndReschedule()
            return changed

        case .boostStop:
            let changed = await BackgroundRefreshScheduler.onKotlinMain { continuation in
                RefreshScheduleBridge.shared.stopBoost { value, _ in
                    continuation.resume(returning: value?.boolValue ?? false)
                }
            }
            if changed { reloadAndReschedule() }
            return changed
        }
    }

    /// A cadence change is inert until the widget rebuilds its timeline and the
    /// background task is resubmitted at the new interval.
    private static func reloadAndReschedule() {
        WidgetCenter.shared.reloadAllTimelines()
        BackgroundRefreshScheduler.schedule()
    }
}
