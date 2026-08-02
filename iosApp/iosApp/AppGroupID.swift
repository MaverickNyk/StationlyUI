import Foundation

/// The App Group both binaries share, in one place for the **app** target.
///
/// This literal is the single point of contact between four separate
/// compilation units — the app, the widget extension, `core/iosMain` and
/// `composeApp/iosMain` — none of which can import a constant from the
/// others, so it is necessarily repeated once per unit. The rename from
/// `group.com.stationly.mobile` on 2026-07-25 had to touch every copy, and a
/// missed one doesn't fail the build: it silently reads an empty suite, which
/// looks exactly like "the data was never written".
///
/// Keep in lockstep with:
///  - `StationlyWidget/AppGroupID.swift` (widget extension)
///  - `IosAppGroup.ID` in `core/src/iosMain/kotlin/platform/Platform.ios.kt`
///    (all Kotlin, app + shared code, now reads that one constant)
///  - the `application-groups` entitlement in both `project.yml` targets
enum AppGroupID {
    static let value = "group.com.stationly.shared"
}
