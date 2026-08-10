import UIKit
import FirebaseCore
import GoogleSignIn
import WidgetKit
import composeApp

/// ## No Firebase Messaging on iOS
/// This app talks to APNs directly. Firebase Cloud Messaging was removed
/// entirely — the SDK, the registration token, the topic ledger and the
/// subscribe/unsubscribe queues — because on iOS it was doing nothing useful
/// and standing in the way of the one thing that is genuinely better.
///
/// FCM is a relay to APNs. Routing through it cost a dependency, a second token
/// to keep alive, and a topic-subscription protocol bridged through
/// `UserDefaults` — and in exchange it could not send the push type that
/// matters most here: a WidgetKit push, which reloads a widget without waking
/// the app at all. Sending to APNs ourselves is fewer moving parts AND strictly
/// more capable.
///
/// What replaced it: `DevicePushCoordinator` (this device's tokens and what its
/// widgets show) plus the signal envelopes in `didReceiveRemoteNotification`.
/// Freshness while the app is open still comes from the live stream; freshness
/// while it is closed comes from the adaptive schedule in
/// `BackgroundRefreshScheduler` and `RefreshScheduleStore`.
///
/// `FirebaseAuth` and `FirebaseCore` stay — sign-in uses them.
///
/// **Android is unaffected** and still uses FCM throughout.
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let firebaseReady = configureFirebaseIfAvailable()
        if firebaseReady {
            UNUserNotificationCenter.current().delegate = self
            if let clientID = FirebaseApp.app()?.options.clientID {
                GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
            }
        }

        // ── APNs registration: unconditional, and OUTSIDE the Firebase block ──
        //
        // Two separate mistakes used to live here, and each one on its own is
        // enough to leave the device registry empty with nothing on screen or
        // in a log to say why.
        //
        // 1. It was gated on `authorizationStatus == .authorized`, which
        //    conflates two unrelated permissions. Notification authorization
        //    governs whether we may DISPLAY an alert. Silent pushes
        //    (`content-available: 1`) and WidgetKit pushes need only the
        //    `aps-environment` entitlement and the `remote-notification`
        //    background mode — both of which this target has. So a user who
        //    declined notifications got no device token and therefore no widget
        //    triggers, ever.
        //
        // 2. It sat inside `if firebaseReady`, a leftover from when the token
        //    existed to feed `Messaging.apnsToken`. With FCM gone that coupling
        //    is simply wrong: APNs is now our own transport and has nothing to
        //    do with whether Firebase configured.
        //
        // Both were measured on device — `devices: 0` in the registry, and a
        // trace showing neither delegate callback firing.
        //
        // The permission PROMPT is still deliberately not fired here: Android
        // asks from the first authenticated screen (see the placement note in
        // `NotificationPermissionEffect`) because asking before the user has
        // seen what notifications buy them wastes the single chance iOS gives
        // us. This call shows no UI. It is also needed on every launch, because
        // the token can rotate.
        application.registerForRemoteNotifications()
        PushTraceSwift.log("apns register called, isRegistered=\(application.isRegisteredForRemoteNotifications)")

        // Re-check shortly after. `isRegisteredForRemoteNotifications` is false
        // synchronously at the call above (registration is asynchronous), so the
        // interesting reading is a few seconds later:
        //   true  + no token callback → iOS considers us registered and simply
        //           is not re-delivering the token; we can stop suspecting the
        //           network and go looking at the delegate wiring.
        //   false + no callback       → registration never completed, which is
        //           a device/APNs-connection condition and not something the
        //           app can fix.
        // Splitting those two is the whole point; without it both look
        // identical from here.
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) {
            PushTraceSwift.log(
                "apns +8s isRegistered=\(application.isRegisteredForRemoteNotifications) " +
                "bgRefresh=\(application.backgroundRefreshStatus.rawValue)")
        }

        // Wire KMP ↔ Swift auth command protocol
        AuthBridge.shared.wireToKMP()

        // MUST happen before launch finishes — BGTaskScheduler refuses
        // registrations afterwards and background refresh is then silently dead
        // for the whole session. Scheduling the first request is separate and
        // happens on foreground, once KMP can say what the cadence should be.
        BackgroundRefreshScheduler.register()

        // Poll App Group UserDefaults every 5 s; reload widget when KMP bumps signal
        WidgetReloadObserver.shared.start()

        // Tell shared code whether a widget is actually installed (WidgetKit is
        // Swift-only, so Kotlin cannot look for itself) — drives the home
        // "add a widget" promo, Android parity.
        HomeStateProbe.start()

        return true
    }

    // MARK: - Firebase setup

    @discardableResult
    private func configureFirebaseIfAvailable() -> Bool {
        guard let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist"),
              let dict = NSDictionary(contentsOfFile: path),
              dict["GOOGLE_APP_ID"] != nil else {
            print("[AppDelegate] GoogleService-Info.plist missing or placeholder — Firebase not configured")
            return false
        }
        FirebaseApp.configure()
        return true
    }

    // MARK: - APNs

    /// The APNs device token, which now goes straight to our own backend.
    ///
    /// Previously this was handed to `Messaging.messaging().apnsToken` and the
    /// device was addressed via an FCM registration token derived from it — a
    /// second identifier, with its own rotation and its own failure modes, for
    /// a device APNs could already address.
    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushTraceSwift.log("apns token received bytes=\(deviceToken.count)")
        DevicePushCoordinator.setAppToken(deviceToken)
        DevicePushCoordinator.register()
    }

    /// Traced, not just printed. `print` goes to stdout, which is invisible on
    /// a device launched outside Xcode — so a registration that fails here used
    /// to be indistinguishable from one that simply never happened, and both
    /// present as an empty device registry on the backend.
    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        PushTraceSwift.log("apns registration FAILED: \(error.localizedDescription)")
    }

    // MARK: - URL handling (Google Sign-In + Firebase password reset deep links)
    // To enable password reset deep links, register a custom URL scheme in Info.plist:
    //   CFBundleURLSchemes: ["stationly"]
    // Firebase will then redirect password-reset emails to stationly://...?mode=resetPassword&oobCode=...

    func application(_ application: UIApplication,
                     open url: URL,
                     options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        // Google Sign-In handles its own redirect URLs
        if GIDSignIn.sharedInstance.handle(url) { return true }
        // Firebase email action link (password reset)
        handleFirebaseActionURL(url)
        return true
    }

    // MARK: - Firebase email action URL parsing

    private func handleFirebaseActionURL(_ url: URL) {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems  = components.queryItems else { return }
        let params = Dictionary(uniqueKeysWithValues: queryItems.compactMap { item -> (String, String)? in
            guard let value = item.value else { return nil }
            return (item.name, value)
        })
        guard params["mode"] == "resetPassword",
              let oobCode = params["oobCode"], !oobCode.isEmpty else { return }

        UserDefaults.standard.set(oobCode, forKey: "pending_reset_oob_code")
        UserDefaults.standard.synchronize()
        NotificationCenter.default.post(name: .passwordResetLink, object: oobCode)
    }

    // MARK: - Foreground

    // NOTE: in the SwiftUI scene lifecycle UIKit does NOT call this method —
    // it's kept only as belt-and-braces. The live path is scenePhase==.active
    // in StationlyApp, which calls handleDidBecomeActive() directly.
    func applicationDidBecomeActive(_ application: UIApplication) {
        handleDidBecomeActive()
    }

    func handleDidBecomeActive() {
        // Why this is traced: `content-available` (silent) pushes are gated on
        // the per-app Background App Refresh switch. When it's denied or
        // restricted, iOS drops EVERY silent push while still delivering
        // alerts — which is precisely the asymmetry seen on 2026-07-30, where
        // an alert arrived and the identical silent push never did. Without
        // this line that's indistinguishable from ordinary APNs throttling.
        let bg = UIApplication.shared.backgroundRefreshStatus
        let bgName = bg == .available ? "available"
                   : bg == .denied    ? "DENIED"
                   : bg == .restricted ? "RESTRICTED" : "unknown"
        PushTraceSwift.log("bgRefresh=\(bgName) lowPower=\(ProcessInfo.processInfo.isLowPowerModeEnabled)")

        if FirebaseApp.app() != nil {
            Task { await AuthBridge.shared.refreshTokenIfNeeded() }
        }

        // ── Ask for the APNs token on EVERY foreground, not just at launch ──
        //
        // Registration is asynchronous, and requesting it only from
        // `didFinishLaunching` loses the answer in two ordinary situations:
        //
        //   - a RESUME does not run `didFinishLaunching` at all, so a session
        //     that starts by returning to a backgrounded app never asks;
        //   - a cold launch that is short-lived can end before the callback
        //     lands, and the token is simply dropped.
        //
        // Both were observed on device: `isRegisteredForRemoteNotifications`
        // reported **true** — iOS had a token for us the whole time — while the
        // delegate callback never ran, because the only launches that asked
        // died within a second and the long-lived sessions were resumes that
        // never asked. The registry sat empty with nothing to explain it.
        //
        // Calling this repeatedly is cheap and shows no UI: when iOS already
        // holds a token it re-delivers the cached one immediately. It is also
        // the documented way to notice a ROTATED token, which is reason enough
        // on its own.
        UIApplication.shared.registerForRemoteNotifications()

        // Republish the cadence and queue the next background wake.
        //
        // Foreground is the natural place for both: the policy fetch is
        // network work a user is not waiting on, and the schedule the widget
        // reads only goes stale while the app is NOT running — so every launch
        // is a chance to repair it. Scheduling the BGTask here rather than at
        // registration also means the interval reflects the tier we are
        // actually in now.
        Task { @MainActor in
            await BackgroundRefreshScheduler.publishSchedule()
            BackgroundRefreshScheduler.schedule()
            // After the schedule, because the station list it reports is the
            // one KMP has just refreshed — and the widget push token may have
            // been written by the extension since we last looked.
            DevicePushCoordinator.register()
        }

        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// An alert arriving while the app is open.
    ///
    /// A trigger envelope can reach us here too — the backend sends `boost.*`
    /// and `policy.update` as silent pushes, but iOS routes a push differently
    /// depending on app state, and a state change is worth applying whichever
    /// door it comes through.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 willPresent notification: UNNotification,
                                 withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let userInfo = notification.request.content.userInfo
        PushTraceSwift.log("apns:fg-willPresent stationly=\(userInfo["stationly"] != nil)")
        if userInfo["stationly"] != nil {
            Task { _ = await DevicePushCoordinator.handle(userInfo) }
        }
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 didReceive response: UNNotificationResponse,
                                 withCompletionHandler completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo
        if userInfo["stationly"] != nil {
            Task { _ = await DevicePushCoordinator.handle(userInfo) }
        }
        completionHandler()
    }

    // MARK: - Silent pushes (content-available: 1)

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        PushTraceSwift.log("apns:bg stationly=\(userInfo["stationly"] != nil) state=\(application.applicationState.rawValue)")

        // ── Stationly widget triggers ──
        //
        // These carry a SIGNAL, never departure data: the envelope says what
        // changed and the client fetches for itself. That is what lets the same
        // trigger be delivered either to the app (here) or straight to the
        // widget extension on iOS 26+, where no process exists to parse a
        // payload into SQLite anyway.
        //
        // Awaited before `completionHandler` so SQLite and the App Group are
        // fully written before iOS is told we are done — completing early lets
        // the system suspend the process mid-write.
        if userInfo["stationly"] != nil {
            Task {
                let changed = await DevicePushCoordinator.handle(userInfo)
                completionHandler(changed ? .newData : .noData)
            }
            return
        }

        // Anything else is not ours. There is deliberately no fallback path
        // that parses an unrecognised payload as departures any more: that was
        // the FCM topic pipeline, iOS no longer subscribes to any topic, and
        // keeping a decoder alive for a message nobody sends is how a dead code
        // path quietly becomes a live one.
        PushTraceSwift.log("apns:bg unrecognised payload")
        completionHandler(.noData)
    }
}

// MARK: - Notification names

extension Notification.Name {
    static let passwordResetLink = Notification.Name("passwordResetLink")
}

// MARK: - WidgetReloadObserver

/// Watches `widget_reload_signal` in the App Group UserDefaults.
/// KMP bumps this integer on every widget data write; we react by telling
/// WidgetKit to reload all timelines immediately (no waiting for 30 s policy).
class WidgetReloadObserver {
    static let shared = WidgetReloadObserver()
    private init() {}

    private let appGroupID = AppGroupID.value
    private var lastSignal: Int = -1
    private var timer: Timer?

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
            self?.checkSignal()
        }
        // Beat immediately: the reload the app fires on foreground arrives well
        // inside the first 5-second tick, and without this it would be counted
        // against the budget despite being exempt.
        beat()
    }

    private func checkSignal() {
        guard let defaults = UserDefaults(suiteName: appGroupID) else { return }
        beat()
        let current = defaults.integer(forKey: AppGroupKeys.reloadSignal)
        if current != lastSignal {
            lastSignal = current
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    /// Prove the app is on screen, so the extension can tell a free reload from
    /// a metered one. A timer tick is the right carrier: it stops on its own
    /// when the app is backgrounded or killed, so the signal cannot get stuck
    /// on the way a boolean flag would.
    private func beat() {
        guard let d = UserDefaults(suiteName: appGroupID) else { return }
        d.set(Date().timeIntervalSince1970, forKey: AppGroupKeys.appForegroundHeartbeat)
        // MUST flush. cfprefsd can hold the write in this process's cache, and
        // the reader is a DIFFERENT process launched fresh — measured on device,
        // an unflushed heartbeat was invisible to the extension and every
        // foreground build was still being charged. Same reasoning as the
        // synchronize before `reloadTimelines` in WidgetRefreshService.
        d.synchronize()
    }

    /// Called on `scenePhase == .background`. The heartbeat would go stale on
    /// its own within 15 s, but clearing it makes the very next build after a
    /// backgrounding count properly rather than riding out the grace window.
    func markBackgrounded() {
        guard let d = UserDefaults(suiteName: appGroupID) else { return }
        d.set(0.0, forKey: AppGroupKeys.appForegroundHeartbeat)
        d.synchronize()
    }

    func stop() { timer?.invalidate(); timer = nil }
}
