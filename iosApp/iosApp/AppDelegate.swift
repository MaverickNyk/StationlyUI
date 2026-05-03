import UIKit
import FirebaseCore
import FirebaseMessaging
import GoogleSignIn
import WidgetKit
// import ComposeApp  // Uncomment after Xcode framework integration

class AppDelegate: NSObject, UIApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        FirebaseApp.configure()
        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
        application.registerForRemoteNotifications()

        if let clientID = FirebaseApp.app()?.options.clientID {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        }

        // Wire KMP ↔ Swift auth command protocol
        AuthBridge.shared.wireToKMP()

        // Poll App Group UserDefaults every 5 s; reload widget when KMP bumps signal
        WidgetReloadObserver.shared.start()

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
        // Process any FCM topic subscriptions that KMP queued before the token was ready
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

    func applicationDidBecomeActive(_ application: UIApplication) {
        // Refresh Firebase token so KMP always has a fresh one
        Task { await AuthBridge.shared.refreshTokenIfNeeded() }
        // Flush any queued FCM subscriptions
        FCMBridge.shared.processPendingSubscriptions()
        // Force widget refresh when user returns to the app
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

    // MARK: - FCM payload → KMP

    private func processFcmPayload(_ userInfo: [AnyHashable: Any]) {
        guard let jsonData = try? JSONSerialization.data(withJSONObject: userInfo),
              let jsonString = String(data: jsonData, encoding: .utf8) else { return }
        // FcmPayloadBridge is a KMP object compiled into the ComposeApp framework.
        // After Xcode integration uncomment:
        // FcmPayloadBridgeKt.FcmPayloadBridge.processPayload(jsonString: jsonString)
        //
        // Until then, store the raw JSON so SummaryViewModel picks it up on next poll.
        UserDefaults.standard.set(jsonString, forKey: "pending_fcm_payload")
        UserDefaults.standard.synchronize()
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
