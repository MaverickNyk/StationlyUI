import Foundation

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
    let departures: [DepartureRow]
    let status: String
    let lastUpdated: Date
    let isEmpty: Bool

    // Used for Xcode Previews and widget placeholder state
    static var placeholder: WidgetData {
        WidgetData(
            stationName: "Arsenal",
            lineName: "Piccadilly",
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
            departures: departures,
            status: status,
            lastUpdated: lastUpdated,
            isEmpty: false
        )
    }
}
