import Foundation

/// The App Group both binaries share, read from this bundle's Info.plist.
///
/// ## Why this is no longer a literal
/// Until the staging/production split this was the hardcoded string
/// `group.com.stationly.shared`, repeated in four compilation units — the app,
/// the widget extension, `core/iosMain` and `composeApp/iosMain` — none of
/// which can import a constant from the others. The rename from
/// `group.com.stationly.mobile` on 2026-07-25 had to find every one, and a
/// missed copy does not fail the build: it silently opens an EMPTY suite,
/// which looks exactly like "the data was never written".
///
/// All four now read the `StationlyAppGroup` Info.plist key, expanded at build
/// time from `STATIONLY_APP_GROUP` in `Config/Staging.xcconfig` /
/// `Config/Production.xcconfig`. There is one definition per environment and
/// the lockstep hazard is gone rather than merely documented.
///
/// The environments need different containers now because they have different
/// bundle ids and can be installed side by side; one shared group would put
/// staging boards in the production widget.
///
/// ## Note for the extension
/// `Bundle.main` inside an app extension is the EXTENSION's bundle, so
/// `StationlyWidget/AppGroupID.swift` reads its own Info.plist, which
/// `project.yml` populates from the same build setting.
enum AppGroupID {

    /// Trapped rather than defaulted. A missing key means the Info.plist was
    /// built without `StationlyAppGroup` — a project.yml/xcconfig regression,
    /// not a runtime condition — and every alternative to crashing here
    /// degrades into the silent empty-suite failure this design exists to
    /// eliminate.
    static let value: String = {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "StationlyAppGroup") as? String,
              !id.isEmpty else {
            fatalError("StationlyAppGroup missing from Info.plist — check STATIONLY_APP_GROUP in Config/*.xcconfig")
        }
        return id
    }()
}
