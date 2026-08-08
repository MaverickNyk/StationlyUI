import Foundation
import FirebaseCore
import FirebaseMessaging
import composeApp

/// FCMBridge processes two things:
/// 1. Topic subscribe/unsubscribe requests queued by KMP in NSUserDefaults.standard
///    (keys: fcm_subscribe_pending, fcm_unsubscribe_pending)
/// 2. Raw FCM payload JSON stored by AppDelegate under "pending_fcm_payload",
///    forwarded to KMP PushPayloadBridge once the framework is integrated.
///
/// Called on: FCM token receipt, app foreground, and (debounced) whenever KMP
/// writes to UserDefaults — so a station added mid-session subscribes its
/// topics immediately instead of waiting for the next foreground transition.
class FCMBridge: NSObject {
    static let shared = FCMBridge()
    private override init() {}

    /// Debounced UserDefaults-change entry point (see AppDelegate observer).
    /// NSObject + @objc so it can be scheduled via perform(_:with:afterDelay:).
    @objc func flushPendingFromDefaultsChange() {
        guard FirebaseApp.app() != nil else { return }
        processPendingSubscriptions()
    }

    /// Re-subscribe every topic this device should be on (KMP's `fcm_topics`
    /// ledger). FCM subscribe is idempotent; called on token receipt/rotation
    /// so a rotated token keeps receiving Station_*/LineStatus_* pushes —
    /// the iOS analog of Android FcmMessagingService.onNewToken.
    func resubscribeAllTopics() {
        guard FirebaseApp.app() != nil else { return }
        let topics = (UserDefaults.standard.array(forKey: "fcm_topics") as? [String]) ?? []
        topics.forEach { topic in
            Messaging.messaging().subscribe(toTopic: topic) { error in
                if let error { print("[FCMBridge] Re-subscribe \(topic) failed: \(error)") }
            }
        }
    }

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

        PushPayloadBridge.shared.processPayload(jsonString: json)
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
