import UIKit
import FirebaseCore
import FirebaseMessaging
import GoogleSignIn
import WidgetKit

class AppDelegate: NSObject, UIApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        FirebaseApp.configure()
        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self

        // Request notification permissions
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
        application.registerForRemoteNotifications()

        // Set up GoogleSignIn
        if let clientID = FirebaseApp.app()?.options.clientID {
            let config = GIDConfiguration(clientID: clientID)
            GIDSignIn.sharedInstance.configuration = config
        }

        // Wire up KMP AuthCallbackBridge
        AuthBridge.shared.wireToKMP()

        // Observe widget reload signals written by KMP
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

    // MARK: - FCM

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken else { return }
        UserDefaults.standard.set(token, forKey: "apns_token")
        // Process any pending FCM topic subscriptions queued by KMP
        FCMBridge.shared.processPendingSubscriptions()
    }

    // MARK: - URL handling (Google Sign-In deep links)

    func application(_ application: UIApplication,
                     open url: URL,
                     options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        return GIDSignIn.sharedInstance.handle(url)
    }

    // MARK: - Foreground

    func applicationDidBecomeActive(_ application: UIApplication) {
        FCMBridge.shared.processPendingSubscriptions()
        // Reload widget so ETAs are fresh immediately when user returns to device
        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 willPresent notification: UNNotification,
                                 withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 didReceive response: UNNotificationResponse,
                                 withCompletionHandler completionHandler: @escaping () -> Void) {
        completionHandler()
    }
}

// MARK: - WidgetReloadObserver
// Watches the widget_reload_signal key in the App Group UserDefaults. Every time
// KMP bumps this integer (after receiving fresh prediction data via FCM), we
// tell WidgetKit to reload all timelines so the widget reflects the latest data
// without waiting for the 30-second polling interval.

class WidgetReloadObserver {
    static let shared = WidgetReloadObserver()
    private init() {}

    private let appGroupID = "group.com.stationly.mobile"
    private let signalKey  = "widget_reload_signal"
    private var lastSignal: Int = -1
    private var timer: Timer?

    func start() {
        // Poll the shared defaults every 5 seconds. A KVO observer on
        // UserDefaults across process boundaries is unreliable on iOS, so a
        // lightweight timer is the pragmatic approach.
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

    func stop() {
        timer?.invalidate()
        timer = nil
    }
}
