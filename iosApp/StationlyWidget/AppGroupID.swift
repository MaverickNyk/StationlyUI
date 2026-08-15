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
/// Still a separate declaration from `iosApp/AppGroupID.swift` — the extension
/// is its own compilation unit and cannot import the app's — but no longer a
/// duplicated *value*. Both read the `StationlyAppGroup` Info.plist key, which
/// `project.yml` expands into BOTH targets' plists from the one
/// `STATIONLY_APP_GROUP` build setting per environment. Two files, one source
/// of truth; the old hazard was that the two files held two independent
/// literals and a mismatch gave the widget an empty suite (blank board)
/// without failing the build.
///
/// `Bundle.main` here is the EXTENSION's bundle, not the containing app's,
/// which is exactly why the key has to be present in both plists.
enum AppGroupID {
    static let value: String = {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "StationlyAppGroup") as? String,
              !id.isEmpty else {
            fatalError("StationlyAppGroup missing from the widget Info.plist — check STATIONLY_APP_GROUP in Config/*.xcconfig")
        }
        return id
    }()
}
