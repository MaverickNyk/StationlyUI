import Foundation

/// Every App Group key the **app target** reads or writes, in one place.
///
/// ## Why this exists
/// The widget extension has had its own `AppGroupKeys` for exactly this reason:
/// a mistyped key does not fail the build, it reads `nil` — indistinguishable
/// from "the other side never wrote it" — and the symptom surfaces on the home
/// screen rather than in a compiler message.
///
/// The app target had no equivalent, so the device-push work spread raw string
/// literals across `DevicePushCoordinator` and `WidgetReloadObserver`
/// (`"widget_api_base_url"`, `"widget_push_token"`, `"widget_reload_signal"`,
/// …) — reintroducing the precise bug class the extension's file was written to
/// prevent, in the one target that did not have the guard.
///
/// ## Three copies, kept by hand
/// Swift cannot import Kotlin, and the two Swift targets are separate
/// compilation units, so these constants exist three times:
///
///   1. this file — the app target
///   2. `StationlyWidget/AppGroupKeys.swift` — the extension
///   3. `AppGroupKeys` in `core/iosMain/platform/Platform.ios.kt` — KMP
///
/// **Change all three in one commit.** The grouping below is by WRITER, because
/// that is what tells you whether a change here needs a matching change in one
/// of the others.
enum AppGroupKeys {

    // MARK: - Written by KMP, read here

    /// Where the extension and this target send REST requests. Mirrored out of
    /// KMP because the extension cannot reach `AppConfig`.
    static let apiBaseURL = "widget_api_base_url"
    static let apiKey     = "widget_api_key"

    /// Directory of every station the user tracks — id, name, mode, lines.
    /// Read here to tell the backend which stations and lines this device's
    /// widgets show, which is what scopes a disruption push.
    static let stations   = "widget_stations"

    /// Bumped by KMP on every board write; `WidgetReloadObserver` turns a
    /// change into `reloadAllTimelines()`.
    static let reloadSignal = "widget_reload_signal"

    // MARK: - Written by the widget extension, read here

    /// The push token WidgetKit issues to the EXTENSION (iOS 26+). Written
    /// there by `StationlyWidgetPushHandler`; uploaded from here, because the
    /// extension has no auth state and no idea which backend it points at.
    static let widgetPushToken = "widget_push_token"

    // MARK: - Written here, read by the widget extension

    /// Heartbeat proving the app is foregrounded, so the extension can tell a
    /// budget-exempt reload from a metered one. A timestamp rather than a flag
    /// so it cannot get stuck if the app is killed — see
    /// `RefreshScheduleStore.isAppForeground`.
    static let appForegroundHeartbeat = "widget_app_foreground_heartbeat"

    // MARK: - App-local

    /// Stable per-install id — the key of the backend device registry.
    ///
    /// Lives in the APP GROUP rather than the standard domain because sign-out
    /// wipes the standard domain wholesale (`removePersistentDomainForName`),
    /// which would silently orphan this device's backend registration and
    /// mint a duplicate on the next launch. Same reasoning that already puts
    /// the device identity and dream prefs in the group suite.
    static let deviceId = "stationly_device_id"

    /// Firebase ID token, written by `AuthBridge` into the STANDARD domain
    /// (not the group). Named here so the one cross-file reader —
    /// device registration, which sends it so the backend can verify the uid —
    /// is not spelling it by hand.
    static let firebaseAuthToken = "firebase_auth_token"
}
