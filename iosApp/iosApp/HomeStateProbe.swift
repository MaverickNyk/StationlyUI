import Foundation
import UIKit
import WidgetKit

/// Answers one question Kotlin cannot ask for itself: **has the user actually
/// placed a Stationly widget?**
///
/// Android's `SummaryViewModel.checkWidgetPromo` reads `AppWidgetManager`
/// directly from shared code. WidgetKit has no Objective-C interface — it is
/// Swift-only — so Kotlin/Native cannot see `WidgetCenter` at all. The host
/// probes it here and drops the answer in the App Group, which is the same
/// Kotlin↔Swift channel the auth identity keys and the widget payload use.
/// `composeApp`'s `hasHomeScreenWidget()` reads exactly this key.
///
/// Runs on launch and on every foreground, so removing (or adding) a widget
/// from the Home Screen re-evaluates the promo on the next app switch —
/// matching Android, whose check re-runs on every `ON_RESUME`.
enum HomeStateProbe {

    /// Keep this literal in lockstep with `HomePromoPlatform.ios.kt`.
    private static let widgetInstalledKey = "home_widget_installed"

    /// Start probing: once now, then on every foreground.
    static func start() {
        refresh()
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { _ in refresh() }
    }

    static func refresh() {
        // Below iOS 14 there is no WidgetKit; the deployment target is 16, so
        // this is only ever the availability compiler's requirement.
        guard #available(iOS 14.0, *) else { return }
        WidgetCenter.shared.getCurrentConfigurations { result in
            let installed: Bool
            switch result {
            case .success(let configurations):
                installed = !configurations.isEmpty
            case .failure:
                // Probe failed (the extension can be momentarily unavailable).
                // Leave whatever the last known answer was rather than
                // asserting "no widget" and nagging someone who has one.
                return
            }
            UserDefaults(suiteName: AppGroupID.value)?
                .set(installed ? "true" : "false", forKey: widgetInstalledKey)
        }
    }
}
