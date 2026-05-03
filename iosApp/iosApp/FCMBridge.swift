import Foundation
import FirebaseMessaging

/// FCMBridge processes two things:
/// 1. Topic subscribe/unsubscribe requests queued by KMP in NSUserDefaults.standard
///    (keys: fcm_subscribe_pending, fcm_unsubscribe_pending)
/// 2. Raw FCM payload JSON stored by AppDelegate under "pending_fcm_payload",
///    forwarded to KMP FcmPayloadBridge once the framework is integrated.
///
/// Called on: FCM token receipt, app foreground.
class FCMBridge {
    static let shared = FCMBridge()
    private init() {}

    // MARK: - Topic subscriptions

    func processPendingSubscriptions() {
        let ud = UserDefaults.standard

        // Subscribe
        if let subscribePending = ud.array(forKey: "fcm_subscribe_pending") as? [String],
           !subscribePending.isEmpty {
            subscribePending.forEach { topic in
                Messaging.messaging().subscribe(toTopic: topic) { error in
                    if let error { print("[FCMBridge] Subscribe \(topic) failed: \(error)") }
                }
            }
            ud.removeObject(forKey: "fcm_subscribe_pending")
        }

        // Unsubscribe
        if let unsubscribePending = ud.array(forKey: "fcm_unsubscribe_pending") as? [String],
           !unsubscribePending.isEmpty {
            unsubscribePending.forEach { topic in
                Messaging.messaging().unsubscribe(fromTopic: topic) { error in
                    if let error { print("[FCMBridge] Unsubscribe \(topic) failed: \(error)") }
                }
            }
            ud.removeObject(forKey: "fcm_unsubscribe_pending")
        }

        ud.synchronize()

        // Also forward any buffered FCM payload to KMP
        processPendingPayload()
    }

    // MARK: - Buffered FCM payload forwarding

    /// AppDelegate stores raw FCM userInfo JSON under "pending_fcm_payload".
    /// Once the ComposeApp framework is linked, forward it to KMP and clear the key.
    private func processPendingPayload() {
        let ud = UserDefaults.standard
        guard let json = ud.string(forKey: "pending_fcm_payload") else { return }
        ud.removeObject(forKey: "pending_fcm_payload")
        ud.synchronize()

        // After Xcode framework integration uncomment:
        // FcmPayloadBridgeKt.FcmPayloadBridge.processPayload(jsonString: json)
        _ = json  // suppress unused-variable warning until framework is linked
    }

    // MARK: - Helpers for direct Swift-side enqueue (if needed)

    func enqueueSubscription(topic: String) {
        var pending = (UserDefaults.standard.array(forKey: "fcm_subscribe_pending") as? [String]) ?? []
        if !pending.contains(topic) { pending.append(topic) }
        UserDefaults.standard.set(pending, forKey: "fcm_subscribe_pending")
        UserDefaults.standard.synchronize()
    }

    func enqueueUnsubscription(topic: String) {
        var pending = (UserDefaults.standard.array(forKey: "fcm_unsubscribe_pending") as? [String]) ?? []
        if !pending.contains(topic) { pending.append(topic) }
        UserDefaults.standard.set(pending, forKey: "fcm_unsubscribe_pending")
        UserDefaults.standard.synchronize()
    }
}
