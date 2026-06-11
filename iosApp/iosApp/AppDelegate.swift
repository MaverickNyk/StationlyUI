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
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
            application.registerForRemoteNotifications()
            if let clientID = FirebaseApp.app()?.options.clientID {
                GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
            }
        }

        // Wire KMP ↔ Swift auth command protocol
        AuthBridge.shared.wireToKMP()

        // Poll App Group UserDefaults every 5 s; reload widget when KMP bumps signal
        WidgetReloadObserver.shared.start()

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
        // Store under "fcm_token" — read by KMP IosNotificationManager.registerDevice()
        UserDefaults.standard.set(token, forKey: "fcm_token")
        UserDefaults.standard.synchronize()
        // Token may have ROTATED: re-subscribe everything in the fcm_topics
        // ledger (idempotent), then flush anything KMP queued before the token
        // was ready — Android does the same in onNewToken.
        FCMBridge.shared.resubscribeAllTopics()
        FCMBridge.shared.processPendingSubscriptions()
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
        if FirebaseApp.app() != nil {
            Task { await AuthBridge.shared.refreshTokenIfNeeded() }
            FCMBridge.shared.processPendingSubscriptions()
        }
        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 willPresent notification: UNNotification,
                                 withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // Process FCM payload through KMP so the widget and SQLite cache update immediately
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
        Messaging.messaging().appDidReceiveMessage(userInfo)
        guard let jsonData = try? JSONSerialization.data(withJSONObject: userInfo),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            completionHandler(.noData)
            return
        }
        // KMP exposes a suspend processPayloadAndWait, but Kotlin/Native only
        // bridges suspend functions (completionHandler form) for the ROOT
        // framework module (composeApp) — core's suspend funcs are silently
        // omitted from the ObjC header. Until a composeApp-side wrapper is
        // added (next session), hold the background task open ~2.5 s after
        // the fire-and-forget call so the SQLite + App Group writes land
        // before iOS suspends us, then reload the widget and complete.
        FcmPayloadBridge.shared.processPayload(jsonString: jsonString)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            WidgetCenter.shared.reloadAllTimelines()
            completionHandler(.newData)
        }
    }

    // MARK: - FCM payload → KMP

    private func processFcmPayload(_ userInfo: [AnyHashable: Any]) {
        guard let jsonData = try? JSONSerialization.data(withJSONObject: userInfo),
              let jsonString = String(data: jsonData, encoding: .utf8) else { return }
        FcmPayloadBridge.shared.processPayload(jsonString: jsonString)
        // KMP writes the App Group asynchronously (GlobalScope). Reload the
        // widget shortly after so a background push refreshes it even when the
        // foreground WidgetReloadObserver timer isn't running. (The observer
        // still handles foreground immediacy via the reload signal.)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
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

    private let appGroupID = "group.com.stationly.mobile"
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
