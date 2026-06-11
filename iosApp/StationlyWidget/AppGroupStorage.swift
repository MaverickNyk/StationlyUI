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

    // Memberwise init (used for previews / placeholders)
    init(destination: String, platform: String, eta: String, isDue: Bool, stopLetter: String?) {
        self.id          = UUID()
        self.destination = destination
        self.platform    = platform
        self.eta         = eta
        self.isDue       = isDue
        self.stopLetter  = stopLetter
    }

    // Only JSON-encode the fields that actually appear in the KMP output.
    // `id` is synthesised locally and is not part of the wire format.
    private enum CodingKeys: String, CodingKey {
        case destination, platform, eta, isDue, stopLetter
    }

    init(from decoder: Decoder) throws {
        let container    = try decoder.container(keyedBy: CodingKeys.self)
        self.id          = UUID()
        self.destination = try container.decode(String.self, forKey: .destination)
        self.platform    = try container.decode(String.self, forKey: .platform)
        self.eta         = try container.decode(String.self, forKey: .eta)
        self.isDue       = try container.decode(Bool.self,   forKey: .isDue)
        self.stopLetter  = try container.decodeIfPresent(String.self, forKey: .stopLetter)
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
