import Foundation
import UIKit

// MARK: - ModeIconProvider

/// Reads the per-mode roundel icons + tint map that KMP's ModeIconStore
/// downloads from the backend `/modes` endpoint into the App Group container —
/// the iOS analog of Android's ModeIconCache (same file layout:
/// `mode_icons/<mode>.png`, `mode_icons/tints.json`).
///
/// Fall-back chain at render time (same as Android):
///   1. cached PNG          → real backend roundel
///   2. tints.json colour   → drawn roundel tinted per backend
///   3. nil                 → caller's hardcoded mode colour
enum ModeIconProvider {
    private static let appGroupID = "group.com.stationly.mobile"

    private static var iconsDir: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupID)?
            .appendingPathComponent("mode_icons", isDirectory: true)
    }

    /// Lowercase, a-z/0-9/- only — must match KMP ModeIconStore.safeName.
    private static func safeName(_ mode: String) -> String {
        mode.lowercased().replacingOccurrences(
            of: "[^a-z0-9-]", with: "_", options: .regularExpression)
    }

    static func icon(_ mode: String) -> UIImage? {
        guard !mode.isEmpty, let dir = iconsDir else { return nil }
        let url = dir.appendingPathComponent("\(safeName(mode)).png")
        guard let raw = UIImage(contentsOfFile: url.path) else { return nil }
        return rerendered(raw)
    }

    /// Re-encode the backend PNG into a fresh, small, standard-format bitmap.
    /// The raw file is whatever the backend served — odd colour spaces, huge
    /// dimensions or subtly corrupt data still open as a UIImage but get
    /// embedded into EVERY timeline entry's view archive, where they can make
    /// chronod reject the whole collection (WidgetArchiver ArchivingError
    /// Code=2 → widget stuck on redacted placeholder). A 48pt re-render is
    /// visually identical at roundel sizes and guaranteed archive-safe.
    private static func rerendered(_ image: UIImage) -> UIImage {
        let side: CGFloat = 48
        let format = UIGraphicsImageRendererFormat()
        format.opaque = false
        format.scale = 3
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: side, height: side))
        }
    }

    static func tint(_ mode: String) -> UIColor? {
        guard !mode.isEmpty, let dir = iconsDir,
              let data = try? Data(contentsOf: dir.appendingPathComponent("tints.json")),
              let map = try? JSONSerialization.jsonObject(with: data) as? [String: String],
              let hex = map[mode.lowercased()] else { return nil }
        return UIColor(hex: hex)
    }
}

extension UIColor {
    /// "#RRGGBB" / "RRGGBB" parser for the tints.json values.
    convenience init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt64(s, radix: 16) else { return nil }
        self.init(
            red:   CGFloat((v >> 16) & 0xFF) / 255.0,
            green: CGFloat((v >> 8)  & 0xFF) / 255.0,
            blue:  CGFloat(v         & 0xFF) / 255.0,
            alpha: 1.0)
    }
}

// MARK: - DepartureRow
// Mirrors the JSON schema written by KMP AppGroupKeys / Platform.ios.kt:
// [{"destination":"Ealing Broadway","platform":"1","eta":"3 min","isDue":false,"stopLetter":null}]

struct DepartureRow: Codable, Identifiable {
    let id: UUID
    let destination: String
    let platform: String
    let eta: String
    let isDue: Bool
    let stopLetter: String?
    /// Absolute arrival time (epoch ms) — present in the KMP JSON
    /// (PredictionDisplay.targetEpochMs, encodeDefaults=true). Lets the
    /// timeline re-derive the eta label per minute entry instead of blitting
    /// the receipt-time string. Nil for defensive-fallback rows; those render
    /// their stored eta unchanged, same as Android.
    let targetEpochMs: Double?

    // Memberwise init (used for previews / placeholders)
    init(destination: String, platform: String, eta: String, isDue: Bool, stopLetter: String?,
         targetEpochMs: Double? = nil) {
        self.id            = UUID()
        self.destination   = destination
        self.platform      = platform
        self.eta           = eta
        self.isDue         = isDue
        self.stopLetter    = stopLetter
        self.targetEpochMs = targetEpochMs
    }

    // Only JSON-encode the fields that actually appear in the KMP output.
    // `id` is synthesised locally and is not part of the wire format.
    private enum CodingKeys: String, CodingKey {
        case destination, platform, eta, isDue, stopLetter, targetEpochMs
    }

    init(from decoder: Decoder) throws {
        let container      = try decoder.container(keyedBy: CodingKeys.self)
        self.id            = UUID()
        self.destination   = try container.decode(String.self, forKey: .destination)
        self.platform      = try container.decode(String.self, forKey: .platform)
        self.eta           = try container.decode(String.self, forKey: .eta)
        self.isDue         = try container.decode(Bool.self,   forKey: .isDue)
        self.stopLetter    = try container.decodeIfPresent(String.self, forKey: .stopLetter)
        self.targetEpochMs = try container.decodeIfPresent(Double.self, forKey: .targetEpochMs)
    }

    /// Copy with a re-derived label (id intentionally regenerated — it's
    /// local-only ForEach identity, not wire data).
    func relabelled(eta: String, isDue: Bool) -> DepartureRow {
        DepartureRow(destination: destination, platform: platform, eta: eta,
                     isDue: isDue, stopLetter: stopLetter, targetEpochMs: targetEpochMs)
    }
}

// MARK: - WidgetData

struct WidgetData {
    let stationName: String
    let lineName: String
    let direction: String
    let mode: String
    let departures: [DepartureRow]
    let status: String
    let lastUpdated: Date
    let isEmpty: Bool

    /// True once the App Group has a real `widget_last_updated` timestamp — lets
    /// the footer show a live ticking "ago" only when there's genuine data age
    /// (avoids a decades-long count from the epoch-0 sentinel).
    var hasTimestamp: Bool { lastUpdated.timeIntervalSince1970 > 0 }

    /// "Piccadilly: Platform 1 (Eastbound)" — the dot-matrix platform header,
    /// mirroring Android's StationlyFormatters.platformHeaderText + formatLinePrefix.
    /// Collapses cleanly when a part is missing ("Piccadilly", "Platform 1", "").
    ///
    /// The platform value KMP writes can already be fully formed (e.g.
    /// "Platform 2 (Westbound)") — never re-prefix "Platform" or re-append the
    /// direction in that case (it rendered as
    /// "Platform Platform 2 (Westbound) (Inbound)" on device).
    func platformHeader(platform: String) -> String {
        let rawPlat = platform.trimmingCharacters(in: .whitespaces)
        let line = lineName.isEmpty ? "" : lineName.capitalized

        var plat = ""
        if !rawPlat.isEmpty && rawPlat.lowercased() != "unknown" {
            let lower = rawPlat.lowercased()
            plat = (lower.hasPrefix("platform") || lower.hasPrefix("stop")) ? rawPlat : "Platform \(rawPlat)"
        }
        // Skip the selection direction when the platform string already carries
        // one ("… (Westbound)").
        let dir = (direction.isEmpty || plat.contains("(")) ? "" : " (\(direction.capitalized))"

        switch (line.isEmpty, plat.isEmpty) {
        case (false, false): return "\(line): \(plat)\(dir)"
        case (false, true):  return "\(line)\(dir)"
        case (true, false):  return "\(plat)\(dir)"
        default:             return ""
        }
    }

    // Used for Xcode Previews and widget placeholder state
    static var placeholder: WidgetData {
        WidgetData(
            stationName: "Arsenal",
            lineName: "Piccadilly",
            direction: "Eastbound",
            mode: "tube",
            departures: [
                DepartureRow(destination: "Cockfosters",  platform: "1", eta: "2 min", isDue: false, stopLetter: nil),
                DepartureRow(destination: "Heathrow T5",  platform: "2", eta: "5 min", isDue: false, stopLetter: nil),
                DepartureRow(destination: "Cockfosters",  platform: "1", eta: "8 min", isDue: false, stopLetter: nil),
                DepartureRow(destination: "Heathrow T123",platform: "2", eta: "12 min",isDue: false, stopLetter: nil),
            ],
            status: "Good Service",
            lastUpdated: Date(),
            isEmpty: false
        )
    }

    // Shown when no station has been selected in the main app yet
    static var empty: WidgetData {
        WidgetData(
            stationName: "No station set",
            lineName: "",
            direction: "",
            mode: "",
            departures: [],
            status: "",
            lastUpdated: Date(),
            isEmpty: true
        )
    }

    // MARK: Tick layer (Android consistency contract)
    //
    // Swift mirror of android ui/util/PredictionTicker.tickPredictions +
    // core StationlyFormatters.formatMinutesRemaining. The Android widget
    // NEVER blits the stored eta string — it was current at write time and is
    // stale a minute later. Each timeline entry re-derives every row against
    // that entry's wall-clock date, so the pre-rendered per-minute entries
    // count down ("5 min" → "4 min"), shed departed trains, and shift the
    // queue — with zero WidgetKit refresh-budget cost and no network.
    // Keep the three constants in lockstep with Android:
    //   departed grace 30 s (DEPARTED_GRACE_MS) · "Due" < 60 s · isDue < 30 s.

    /// Rows re-derived for the given instant. Platform order of first
    /// appearance is preserved (matches Kotlin groupBy+flatMap semantics).
    func ticked(at date: Date) -> WidgetData {
        guard !departures.isEmpty else { return self }
        let nowMs = date.timeIntervalSince1970 * 1000.0

        // Step 1: drop departed rows (>30 s past target). Null-target rows
        // pass through untouched — no target to tick from.
        let survivors = departures.filter { row in
            guard let target = row.targetEpochMs else { return true }
            return target >= nowMs - 30_000
        }

        // Step 2: per-platform monotonic bump — two trains on one platform
        // can't share a label; rounding collisions shift the later one up
        // ("Due, Due, Due" → "Due, 1 min, 2 min").
        let groups = Dictionary(grouping: survivors, by: { $0.platform })
        var seen = Set<String>()
        var result: [DepartureRow] = []
        for row in survivors where !seen.contains(row.platform) {
            seen.insert(row.platform)
            result.append(contentsOf: Self.bumpPlatformGroup(groups[row.platform] ?? [], nowMs: nowMs))
        }

        return WidgetData(
            stationName: stationName, lineName: lineName, direction: direction,
            mode: mode, departures: result, status: status,
            lastUpdated: lastUpdated, isEmpty: isEmpty
        )
    }

    private static func bumpPlatformGroup(_ group: [DepartureRow], nowMs: Double) -> [DepartureRow] {
        if group.count <= 1 {
            // Single row still re-derives against current now.
            return group.map { row in
                guard let target = row.targetEpochMs else { return row }
                let secs = (target - nowMs) / 1000.0
                let eta = secs < 60 ? "Due" : "\(Int(secs / 60)) min"
                return row.relabelled(eta: eta, isDue: secs < 30)
            }
        }
        let withTarget = group.filter { $0.targetEpochMs != nil }
            .sorted { $0.targetEpochMs! < $1.targetEpochMs! }
        let withoutTarget = group.filter { $0.targetEpochMs == nil }
        var prevMin = -1   // "Due" == 0; -1 means nothing taken yet
        let bumped = withTarget.map { row -> DepartureRow in
            let secs = (row.targetEpochMs! - nowMs) / 1000.0
            let raw = secs < 60 ? 0 : Int(secs / 60)   // floor, same as Kotlin Long division
            let effective = max(raw, prevMin + 1)
            prevMin = effective
            return row.relabelled(eta: effective == 0 ? "Due" : "\(effective) min",
                                  isDue: effective == 0)
        }
        // Null-target rows pin to the end of the group, unchanged.
        return bumped + withoutTarget
    }
}

// MARK: - AppGroupStorage

/// Reads departure data from the App Group NSUserDefaults container that is
/// shared between the main app (written by KMP Platform.ios.kt) and this
/// widget extension.
class AppGroupStorage {
    static let shared = AppGroupStorage()
    private init() {}

    private let appGroupID = "group.com.stationly.mobile"

    private var defaults: UserDefaults? {
        UserDefaults(suiteName: appGroupID)
    }

    func readWidgetData() -> WidgetData {
        guard let defaults = defaults else {
            print("[AppGroupStorage] Cannot open App Group suite: \(appGroupID)")
            return .empty
        }

        let stationName = defaults.string(forKey: "widget_station_name") ?? ""
        let lineName    = defaults.string(forKey: "widget_line_name")    ?? ""
        let direction   = defaults.string(forKey: "widget_direction")    ?? ""
        let mode        = defaults.string(forKey: "widget_mode")         ?? ""
        let status      = defaults.string(forKey: "widget_status")       ?? ""
        let tsRaw       = defaults.double(forKey: "widget_last_updated")
        let lastUpdated = tsRaw > 0 ? Date(timeIntervalSince1970: tsRaw) : Date(timeIntervalSince1970: 0)

        guard !stationName.isEmpty else { return .empty }

        let predictionsJson = defaults.string(forKey: "widget_predictions") ?? "[]"
        let departures: [DepartureRow]
        do {
            departures = try JSONDecoder().decode([DepartureRow].self, from: Data(predictionsJson.utf8))
        } catch {
            print("[AppGroupStorage] Failed to decode predictions: \(error)")
            departures = []
        }

        return WidgetData(
            stationName: stationName,
            lineName: lineName,
            direction: direction,
            mode: mode,
            departures: departures,
            status: status,
            lastUpdated: lastUpdated,
            isEmpty: false
        )
    }
}
