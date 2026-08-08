import Foundation

/// The App Group's `UserDefaults`, opened once per process.
///
/// `UserDefaults(suiteName:)` builds a new instance on every call, and this
/// extension used to call it from five places — several of them on the render
/// path, which runs in a process that iOS cold-launches to answer a single
/// arrow tap. Opening the same suite three times to answer three questions
/// about the same widget is overhead in the one place there is none to spare.
///
/// Safe to hold for the life of the process: `UserDefaults` reads through to the
/// underlying store rather than snapshotting it, so a cached instance cannot
/// serve stale values — which is the property that matters here, because the
/// app writes these keys from a different process entirely.
enum AppGroupDefaults {
    static let shared: UserDefaults? = UserDefaults(suiteName: AppGroupID.value)
}

/// The App Group both binaries share, in one place for the **widget
/// extension** target.
///
/// A deliberate duplicate of `iosApp/AppGroupID.swift`: the extension is a
/// separate compilation unit and cannot import the app's declaration. See
/// that file for the full lockstep list — a mismatch here doesn't fail the
/// build, it silently gives the widget an empty suite (blank board).
enum AppGroupID {
    static let value = "group.com.stationly.shared"
}
