import Foundation
import FirebaseMessaging

/// FCMBridge processes FCM topic subscribe/unsubscribe requests that were
/// enqueued by the KMP layer in NSUserDefaults while an FCM token was not yet
/// available. Called both on FCM token receipt and on app foreground.
class FCMBridge {
    static let shared = FCMBridge()
    private init() {}

    private let subscribeKey   = "fcm_subscribe_pending"
    private let unsubscribeKey = "fcm_unsubscribe_pending"

    func processPendingSubscriptions() {
        let defaults = UserDefaults.standard

        // -- Subscribe --
        if let topics = defaults.array(forKey: subscribeKey) as? [String], !topics.isEmpty {
            topics.forEach { topic in
                Messaging.messaging().subscribe(toTopic: topic) { error in
                    if let error = error {
                        print("[FCMBridge] Subscribe to '\(topic)' failed: \(error.localizedDescription)")
                    }
                }
            }
            defaults.removeObject(forKey: subscribeKey)
        }

        // -- Unsubscribe --
        if let topics = defaults.array(forKey: unsubscribeKey) as? [String], !topics.isEmpty {
            topics.forEach { topic in
                Messaging.messaging().unsubscribe(fromTopic: topic) { error in
                    if let error = error {
                        print("[FCMBridge] Unsubscribe from '\(topic)' failed: \(error.localizedDescription)")
                    }
                }
            }
            defaults.removeObject(forKey: unsubscribeKey)
        }

        defaults.synchronize()
    }

    /// Enqueue a topic subscription to be processed once an FCM token is
    /// available. The KMP layer calls this directly via the shared UserDefaults
    /// key; this helper is exposed for any Swift-side callers.
    func enqueueSubscription(topic: String) {
        var pending = (UserDefaults.standard.array(forKey: subscribeKey) as? [String]) ?? []
        if !pending.contains(topic) {
            pending.append(topic)
            UserDefaults.standard.set(pending, forKey: subscribeKey)
        }
    }

    /// Enqueue a topic unsubscription to be processed once an FCM token is
    /// available.
    func enqueueUnsubscription(topic: String) {
        var pending = (UserDefaults.standard.array(forKey: unsubscribeKey) as? [String]) ?? []
        if !pending.contains(topic) {
            pending.append(topic)
            UserDefaults.standard.set(pending, forKey: unsubscribeKey)
        }
    }
}
