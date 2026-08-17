import Foundation
import UIKit
import WidgetKit
import composeApp

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
            let configurations: [WidgetInfo]
            switch result {
            case .success(let found):
                configurations = found
            case .failure:
                // Probe failed (the extension can be momentarily unavailable).
                // Leave whatever the last known answer was rather than
                // asserting "no widget" and nagging someone who has one — and
                // report NOTHING to the activity trail, or a transient failure
                // would be recorded as the user removing every widget at once.
                return
            }
            UserDefaults(suiteName: AppGroupID.value)?
                .set(configurations.isEmpty ? "false" : "true", forKey: widgetInstalledKey)

            // The same snapshot, handed to KMP so it can derive add/remove by
            // diffing against the previous one, and so each BOARD knows whether
            // it is on the home screen. This probe is the only source WidgetKit
            // offers — there is no placement callback — so it does double duty.
            // See `ActivityBridge.widgetsObserved`.
            let descriptors = describe(configurations)
            ActivityBridge.shared.widgetsObserved(descriptors: descriptors)
            publishObserved(descriptors, raw: configurations)
        }
    }

    /// Turn the host's answer into `family|stationId` descriptors.
    ///
    /// ## Why the station comes from the WIDGET and not from here
    /// `WidgetInfo` carries the family but not the station: the station lives in
    /// an AppIntent configuration whose type is compiled into the widget target,
    /// and `WidgetInfo.configuration` is an `INIntent`, so it is not reachable
    /// even in principle. Making it reachable would mean compiling
    /// `StationConfiguration` — and with it `AppGroupStorage` and the roundel
    /// artwork — into the app target as well.
    ///
    /// So the widget writes its own station down on every timeline build, and
    /// this reconciles those stamps against the one thing only the host knows:
    /// how many widgets are actually placed, and at what sizes.
    ///
    /// ## The reconciliation, and what it can and cannot get right
    /// The host's list is authoritative on COUNT and on FAMILIES; the stamps are
    /// authoritative on STATION. Matching them by family and taking the most
    /// recent stamp per slot is exact whenever the placed widgets show distinct
    /// stations — the normal case, since one widget is one station. Two widgets
    /// of the same size on the same station are indistinguishable from one, and
    /// nothing available can tell them apart.
    ///
    /// A widget that has just been added has no stamp until its first timeline
    /// build, seconds later; it is reported with an empty station until then,
    /// which counts toward the total but attaches to no board. That is the
    /// honest answer rather than a guess at which board it might be.
    private static func describe(_ configurations: [WidgetInfo]) -> [String] {
        var unclaimed = readPlacements().sorted { $0.at > $1.at }
        var descriptors: [String] = []
        for info in configurations {
            let family = String(describing: info.family)
            if let index = unclaimed.firstIndex(where: { $0.family == family }) {
                let claimed = unclaimed.remove(at: index)
                descriptors.append("\(family)|\(claimed.station)")
            } else {
                descriptors.append("\(family)|")
            }
        }
        // Whatever is left over describes widgets that are no longer placed —
        // the removals this probe exists to notice. Dropping them is what keeps
        // a stale stamp from claiming a board is on the home screen for days
        // after the user took it off.
        if !unclaimed.isEmpty {
            let stale = Set(unclaimed.map { "\($0.station)|\($0.family)" })
            writePlacements(readPlacements().filter { !stale.contains("\($0.station)|\($0.family)") })
        }
        return descriptors
    }

    /// Hand the widget extension the one fact only this side can learn: which
    /// widgets are ACTUALLY on the home screen right now.
    ///
    /// ## Why the extension needs it
    /// `RefreshScheduleStore` keeps a refresh ledger per widget, and nothing in
    /// WidgetKit tells that process a widget was removed — there is no deletion
    /// callback, and `getCurrentConfigurations` returns an empty list when
    /// called from inside `timeline(for:in:)`. A removed widget simply stops
    /// asking for timelines, so on its own the extension can only infer death
    /// from silence, which takes hours to be safe about.
    ///
    /// This is the same reconciliation [describe] already performs for the
    /// activity trail, published so the ledger can use it too: entries with no
    /// matching placed widget are reaped the next time the app is opened
    /// instead of waiting out a timeout.
    ///
    /// Stamped with the observation time because the answer expires. A widget
    /// added after this was written must not be reaped for being absent from a
    /// list that predates it, so the extension compares the two timestamps.
    private static func publishObserved(_ descriptors: [String], raw: [WidgetInfo]) {
        guard let d = UserDefaults(suiteName: AppGroupID.value) else { return }
        d.set(descriptors, forKey: observedKey)
        // The host's answer with NOTHING inferred from it.
        //
        // [describe] above attaches a station to each widget by matching
        // placement stamps by family, because `WidgetInfo` carries no station.
        // That inference is fine for the activity trail, which only needs to
        // know which boards are on screen, and misleading for the question
        // "how many widgets does this person actually have" — a question it
        // was read as answering once, wrongly. The `kind` is included because
        // nothing else records whether these are all even the same widget.
        d.set(raw.map { "\($0.kind)|\(String(describing: $0.family))" }, forKey: observedRawKey)
        d.set(Date().timeIntervalSince1970, forKey: observedAtKey)
        // Same reason as the foreground heartbeat: cfprefsd can hold the write
        // in this process's cache, and the reader is a different process
        // launched fresh. Without this the extension reads the previous answer.
        d.synchronize()
    }

    /// Keep these two literals in lockstep with the widget target's
    /// `AppGroupKeys.observedWidgets` / `observedWidgetsAt`.
    private static let observedKey = "widget_observed"
    private static let observedAtKey = "widget_observed_at"
    /// Diagnostic only, and read by nothing — see [publishObserved].
    private static let observedRawKey = "widget_observed_raw"

    /// One placed widget as the EXTENSION recorded it — mirrors
    /// `AppGroupStorage.WidgetPlacementStamp`, which lives in the widget target
    /// and is not visible here. Kept minimal so the two cannot drift in any way
    /// that matters: the field names are the wire format.
    private struct Placement: Codable {
        let station: String
        let family: String
        let at: TimeInterval
    }

    /// Keep this literal in lockstep with `AppGroupKeys.placements`.
    private static let placementsKey = "widget_placements"

    private static func readPlacements() -> [Placement] {
        guard let raw = UserDefaults(suiteName: AppGroupID.value)?.string(forKey: placementsKey),
              let data = raw.data(using: .utf8),
              let stamps = try? JSONDecoder().decode([Placement].self, from: data)
        else { return [] }
        return stamps
    }

    private static func writePlacements(_ stamps: [Placement]) {
        guard let encoded = try? JSONEncoder().encode(stamps),
              let raw = String(data: encoded, encoding: .utf8) else { return }
        UserDefaults(suiteName: AppGroupID.value)?.set(raw, forKey: placementsKey)
    }
}
