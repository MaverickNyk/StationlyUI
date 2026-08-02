import UIKit
import FirebaseCore
import FirebaseMessaging
import GoogleSignIn
import WidgetKit
import composeApp

class AppDelegate: NSObject, UIApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let firebaseReady = configureFirebaseIfAvailable()
        if firebaseReady {
            Messaging.messaging().delegate = self
            UNUserNotificationCenter.current().delegate = self
            // The permission PROMPT is deliberately NOT fired here. Android asks
            // from the first authenticated screen (see the placement note in
            // `NotificationPermissionEffect`) because asking before the user has
            // seen what notifications buy them wastes the single chance iOS
            // gives us — a denial is permanent until the user digs into
            // Settings. `composeApp`'s NotificationPermissionEffect now drives
            // it from SummaryScreen instead, and registers for APNs on grant.
            //
            // Already-authorized users still need the APNs token every launch
            // (it can rotate), so re-register without prompting.
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                guard settings.authorizationStatus == .authorized ||
                      settings.authorizationStatus == .provisional else { return }
                DispatchQueue.main.async { application.registerForRemoteNotifications() }
            }
            if let clientID = FirebaseApp.app()?.options.clientID {
                GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
            }
        }

        // Wire KMP ↔ Swift auth command protocol
        AuthBridge.shared.wireToKMP()

        // Poll App Group UserDefaults every 5 s; reload widget when KMP bumps signal
        WidgetReloadObserver.shared.start()

        // Tell shared code whether a widget is actually installed (WidgetKit is
        // Swift-only, so Kotlin cannot look for itself) — drives the home
        // "add a widget" promo, Android parity.
        HomeStateProbe.start()

        // KMP queues topic (un)subscriptions in UserDefaults. Previously they
        // were only flushed on token receipt / app foreground, so adding a
        // station mid-session didn't take effect until the next app switch.
        // React to the defaults write itself (debounced — the flush also
        // mutates defaults, but a second pass sees empty queues and no-ops).
        NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification, object: nil, queue: .main
        ) { _ in
            NSObject.cancelPreviousPerformRequests(withTarget: FCMBridge.shared)
            FCMBridge.shared.perform(#selector(FCMBridge.flushPendingFromDefaultsChange),
                                     with: nil, afterDelay: 0.5)
        }

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

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("[AppDelegate] APNs registration failed: \(error.localizedDescription)")
    }

    // MARK: - FCM token

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken else { return }
        Self.persistFcmToken(token)
        // Token may have ROTATED: re-subscribe everything in the fcm_topics
        // ledger (idempotent), then flush anything KMP queued before the token
        // was ready — Android does the same in onNewToken.
        FCMBridge.shared.resubscribeAllTopics()
        FCMBridge.shared.processPendingSubscriptions()
    }

    /// Persist the FCM token where KMP's `registerDevice()` reads it.
    ///
    /// ⚠️ Written to the **APP GROUP** suite, not just the standard domain.
    /// Sign-out runs `storageManager.clearAll()`, which is
    /// `removePersistentDomainForName(bundleId)` — it nukes the entire
    /// standard domain, the token included. The old code stored the token
    /// there only, so after any sign-out the next login read an empty token,
    /// hit `if (fcmToken.isNotBlank())` and skipped backend registration
    /// **silently**. The backend then reported "No registered tokens for uid"
    /// and every push for that user went nowhere. Same reasoning that already
    /// puts the device identity and dream prefs in the group suite.
    ///
    /// Still mirrored into the standard domain so an older KMP build (which
    /// reads only there) keeps working.
    static func persistFcmToken(_ token: String) {
        UserDefaults(suiteName: AppGroupID.value)?
            .set(token, forKey: "fcm_token")
        UserDefaults.standard.set(token, forKey: "fcm_token")
    }

    /// Re-fetch the current token from Firebase and re-persist it.
    ///
    /// `didReceiveRegistrationToken` only fires on issue/rotation, so after a
    /// sign-out wipe nothing would restore the token until FCM next rotated it
    /// — potentially weeks. Called on every foreground; Firebase serves this
    /// from its own cache, so it costs nothing when unchanged.
    static func refreshFcmToken() {
        Messaging.messaging().token { token, error in
            guard let token, error == nil else { return }
            persistFcmToken(token)
        }
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
            FCMBridge.shared.processPendingSubscriptions()
            // Restore the token if a sign-out wiped the standard domain, so
            // the next login/foreground can actually register it.
            Self.refreshFcmToken()
        }
        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 willPresent notification: UNNotification,
                                 withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // Process FCM payload through KMP so the widget and SQLite cache update immediately
        PushTraceSwift.log("apns:fg-willPresent from=\(notification.request.content.userInfo["from"] as? String ?? "-")")
        processFcmPayload(notification.request.content.userInfo)
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 didReceive response: UNNotificationResponse,
                                 withCompletionHandler completionHandler: @escaping () -> Void) {
        // Also process on tap (covers background delivery)
        processFcmPayload(response.notification.request.content.userInfo)
        completionHandler()
    }

    // MARK: - Background FCM (data messages / content-available:1)

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        PushTraceSwift.log("apns:bg from=\(userInfo["from"] as? String ?? "-") state=\(application.applicationState.rawValue)")
        Messaging.messaging().appDidReceiveMessage(userInfo)
        guard let jsonData = try? JSONSerialization.data(withJSONObject: userInfo),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            PushTraceSwift.log("apns:bg SERIALISE-FAILED")
            completionHandler(.noData)
            return
        }
        // Await KMP so SQLite + the App Group are fully written before the
        // widget reloads and iOS is told we're done — completing early lets
        // iOS suspend the process mid-write. K/N exported suspend functions
        // must be CALLED from the main thread (default launch-thread
        // restriction); the Kotlin wrapper hops to a background dispatcher
        // immediately, so nothing heavy runs on main.
        Task { @MainActor in
            try? await FcmPayloadBridge.shared.processPayloadAndWait(jsonString: jsonString)
            WidgetCenter.shared.reloadAllTimelines()
            completionHandler(.newData)
        }
    }

    // MARK: - FCM payload → KMP

    private func processFcmPayload(_ userInfo: [AnyHashable: Any]) {
        guard let jsonData = try? JSONSerialization.data(withJSONObject: userInfo),
              let jsonString = String(data: jsonData, encoding: .utf8) else { return }
        // Await the KMP write, then reload the widget — covers deliveries where
        // the foreground WidgetReloadObserver timer isn't running. (The
        // observer still handles in-session immediacy via the reload signal.)
        Task { @MainActor in
            try? await FcmPayloadBridge.shared.processPayloadAndWait(jsonString: jsonString)
            WidgetCenter.shared.reloadAllTimelines()
        }
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
    private let signalKey  = "widget_reload_signal"
    private var lastSignal: Int = -1
    private var timer: Timer?

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
            self?.checkSignal()
        }
    }

    private func checkSignal() {
        guard let defaults = UserDefaults(suiteName: appGroupID) else { return }
        let current = defaults.integer(forKey: signalKey)
        if current != lastSignal {
            lastSignal = current
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    func stop() { timer?.invalidate(); timer = nil }
}
