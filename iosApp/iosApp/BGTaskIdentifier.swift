import Foundation

/// Builds the `BGTaskScheduler` identifiers from the running bundle id.
///
/// ## Why these are not literals
/// Since the staging/production split the two apps have different bundle ids
/// and can be installed at the same time, so they must register different
/// background-task identifiers. `BGTaskSchedulerPermittedIdentifiers` in
/// project.yml is therefore composed as
/// `$(STATIONLY_BUNDLE_BASE)$(STATIONLY_BUNDLE_SUFFIX).<name>` — giving
/// `com.stationly.mobile.staging.widgetrefresh` in staging and leaving
/// production's identifiers byte-for-byte as they were.
///
/// The suffix goes in the MIDDLE for a reason: Apple expects a BGTask
/// identifier to be prefixed by the app's bundle id, and appending `.staging`
/// to the end would have yielded `com.stationly.mobile.widgetrefresh.staging`,
/// which is not a prefix match for `com.stationly.mobile.staging`.
///
/// Composing the runtime value the same way — from `Bundle.main`, which IS the
/// bundle id — makes the code and the plist agree by construction. Keeping
/// literals here instead would mean a Swift constant that has to be edited in
/// lockstep with a build setting, and the penalty for getting that wrong is
/// not a warning: `BGTaskScheduler.register` throws for an identifier missing
/// from the plist, and it is called from `didFinishLaunchingWithOptions`, so
/// the app crashes on launch.
enum BGTaskIdentifier {

    /// - Parameter name: the trailing component, e.g. `"widgetrefresh"`.
    static func make(_ name: String) -> String {
        // Force-unwrapped deliberately: an app bundle without
        // CFBundleIdentifier cannot be installed by iOS in the first place, so
        // a nil here is not a state a shipped build can reach.
        let bundleID = Bundle.main.bundleIdentifier!
        return "\(bundleID).\(name)"
    }
}
