import Foundation

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
