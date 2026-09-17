import Foundation
import Security

/// Keeps this install's `deviceId` alive across a delete-and-reinstall.
///
/// ## The problem this fixes
/// Kotlin's `DeviceIdentity.deviceId()` stores a UUID in the App Group, which
/// is the right place for it to survive a LOGOUT (logout wipes the standard
/// NSUserDefaults domain). It does not survive the app being DELETED: iOS
/// destroys the App Group container with the last app in the group.
///
/// Firebase's session does not have that problem — it lives in the Keychain,
/// which outlives the app. So a reinstall produces a user who is silently still
/// signed in, carrying a **brand new device id**. The backend has no way to
/// know the old one is gone, so it keeps that session in `users/{uid}.sessions`
/// for the full 90-day TTL.
///
/// Measured on the test account: nine sessions for one phone, every one with
/// `firstSeen == lastSeen`, six of them from a single day of debugging.
///
/// ## Why the ghosts matter beyond being untidy
/// `loggedIn` means "≥1 active session", and `/user/logout` removes only the
/// device that called it. With ghosts present the count never reaches zero, so
/// the logged-in → logged-out transition never fires and the user's saved
/// stations are **never released from the subscription registry** — the Syncer
/// keeps polling TfL for an account that has signed out everywhere.
///
/// ## Why Swift, and why the App Group stays the primary
/// The Keychain is the only store with the right lifetime, and it is far less
/// error-prone here than the equivalent Kotlin/Native `Security` interop.
///
/// The App Group remains the value Kotlin reads. This only ever *restores* it:
///
///   1. App Group has an id  → keep it, and make sure the Keychain has a copy.
///   2. App Group empty, Keychain has one → put it back (the reinstall case).
///   3. Neither → do nothing; Kotlin mints one and case 1 saves it next time.
///
/// That ordering is deliberate: if anything in here fails, the App Group is
/// simply left as it was and behaviour is exactly what it is today. A bug here
/// can never make the churn worse than not having it.
///
/// **Android is untouched by all of this** — it has its own `DeviceIdProvider`
/// backed by a dedicated SharedPreferences file, and shares no code with this.
enum DeviceIdentityStore {

    /// Must match `DeviceIdentity.deviceId()` in `composeApp/iosMain`.
    /// Renaming there without renaming here silently reverts to per-install ids.
    private static let defaultsKey = "stationly_device_id"

    private static let service = "com.stationly.mobile.deviceid"
    private static let account = "stationly_device_id"

    /// Reconcile the App Group and the Keychain, in both directions.
    ///
    /// Called at launch (before any Kotlin code reads the id) and again on
    /// foreground — by which point Kotlin may have minted one that needs
    /// saving.
    static func sync() {
        guard let defaults = UserDefaults(suiteName: AppGroupID.value) else { return }

        let current = defaults.string(forKey: defaultsKey)?.trimmingCharacters(in: .whitespaces)
        if let current, !current.isEmpty {
            // Case 1 — the app has an id. Make sure it outlives the install.
            if keychainRead() != current { keychainWrite(current) }
            return
        }

        // Case 2 — a reinstall (or a first run after this shipped) with a
        // surviving Keychain entry. Put the id back before Kotlin generates one.
        if let restored = keychainRead(), !restored.isEmpty {
            defaults.set(restored, forKey: defaultsKey)
            // Flush: the reader is Kotlin in this same process but cfprefsd can
            // hold the write, and the very next thing that happens is a read.
            defaults.synchronize()
            PushTraceSwift.log("deviceId restored from keychain")
        }
        // Case 3 — nothing anywhere. Kotlin mints one; the next `sync()` saves it.
    }

    // MARK: - Keychain

    private static func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func keychainRead() -> String? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8) else { return nil }
        return value
    }

    private static func keychainWrite(_ value: String) {
        guard let data = value.data(using: .utf8) else { return }

        // Delete-then-add rather than SecItemUpdate: an update on a missing item
        // fails, and branching on which case we are in is more code than simply
        // making the write unconditional.
        SecItemDelete(baseQuery() as CFDictionary)

        var attributes = baseQuery()
        attributes[kSecValueData as String] = data
        // AfterFirstUnlock, not WhenUnlocked: background refresh and push
        // handlers run with the phone locked and need to read this to report
        // which device they are. ThisDeviceOnly keeps it off iCloud Keychain —
        // a device id restored onto a DIFFERENT phone would merge two devices
        // into one session, which is worse than minting a new one.
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status != errSecSuccess {
            PushTraceSwift.log("deviceId keychain write failed: \(status)")
        }
    }
}
