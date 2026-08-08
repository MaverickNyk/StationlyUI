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
    private static let appGroupID = AppGroupID.value

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
    ///
    /// The canvas keeps the source's aspect ratio (longest side 48pt) — an
    /// earlier fixed 48×48 square stretched the wider-than-tall TfL roundel
    /// into a visibly squashed blob next to the station name. Android never
    /// had the problem because its ImageView uses fitCenter.
    private static func rerendered(_ image: UIImage) -> UIImage {
        let maxSide: CGFloat = 48
        let w = max(image.size.width, 1), h = max(image.size.height, 1)
        let scale = maxSide / max(w, h)
        let size = CGSize(width: w * scale, height: h * scale)
        let format = UIGraphicsImageRendererFormat()
        format.opaque = false
        format.scale = 3
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
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
    /// Which line this train is on, in short display form ("Cir.", "H&C"),
    /// resolved by KMP — mirrors `PredictionDisplay.lineShort`.
    ///
    /// Empty on rows written before this build and on any board whose station
    /// tracks a single line, which the renderer treats identically: no prefix.
    /// See `DotMatrixRow` for when it is actually drawn — a prefix on every row
    /// of a single-line platform would be the same word repeated.
    let lineShort: String

    // Memberwise init (used for previews / placeholders)
    init(destination: String, platform: String, eta: String, isDue: Bool, stopLetter: String?,
         targetEpochMs: Double? = nil, lineShort: String = "") {
        self.id            = UUID()
        self.destination   = destination
        self.platform      = platform
        self.eta           = eta
        self.isDue         = isDue
        self.stopLetter    = stopLetter
        self.targetEpochMs = targetEpochMs
        self.lineShort     = lineShort
    }

    // Only JSON-encode the fields that actually appear in the KMP output.
    // `id` is synthesised locally and is not part of the wire format.
    private enum CodingKeys: String, CodingKey {
        case destination, platform, eta, isDue, stopLetter, targetEpochMs, lineShort
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
        // Absent on a board written by an older build, which is a normal state
        // for the window between installing this one and the next push.
        self.lineShort     = try container.decodeIfPresent(String.self, forKey: .lineShort) ?? ""
    }

    /// Label for a train that has already left but is being held on the board
    /// (see `WidgetData.ticked`). Single source of truth so the renderer can
    /// recognise the state without duplicating the string.
    static let departedLabel = "Gone"

    /// True when this row is a retained already-departed train, so the view
    /// can dim it. Derived from the label rather than re-deriving from
    /// `targetEpochMs`, which keeps the "is it departed" decision in ONE
    /// place (`ticked`) — the grace period and retention rules live there,
    /// and a second copy in the view would inevitably drift.
    var hasDeparted: Bool { eta == Self.departedLabel }

    /// Copy with a re-derived label (id intentionally regenerated — it's
    /// local-only ForEach identity, not wire data).
    func relabelled(eta: String, isDue: Bool) -> DepartureRow {
        DepartureRow(destination: destination, platform: platform, eta: eta,
                     isDue: isDue, stopLetter: stopLetter, targetEpochMs: targetEpochMs,
                     lineShort: lineShort)
    }
}

// MARK: - BoardFeed

/// One (line, direction) the configured station tracks, plus the naptan it is
/// fetched from — mirrors `WidgetFeed` in `core/iosMain/platform/WidgetAppGroup.kt`.
///
/// Only the refresh path uses it: the predictions endpoint answers per naptan
/// with every line calling there, so rebuilding THIS board means keeping these
/// pairs and dropping the rest.
struct BoardFeed: Codable, Hashable {
    let station: String
    let line: String
    let direction: String
    /// [line] in short display form, resolved by KMP — the extension has no
    /// line vocabulary of its own and deliberately keeps none. This is what
    /// lets a refresh re-label the rows it rebuilds, so prefixes written by a
    /// push survive a refresh tap instead of disappearing.
    var lineShort: String = ""

    private enum CodingKeys: String, CodingKey {
        case station, line, direction, lineShort
    }

    init(station: String, line: String, direction: String, lineShort: String = "") {
        self.station = station
        self.line = line
        self.direction = direction
        self.lineShort = lineShort
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.station = try c.decode(String.self, forKey: .station)
        self.line = try c.decode(String.self, forKey: .line)
        self.direction = try c.decode(String.self, forKey: .direction)
        self.lineShort = try c.decodeIfPresent(String.self, forKey: .lineShort) ?? ""
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
    /// The GROUPING id of the station on screen, or "" for a board read from
    /// the legacy single-station keys.
    ///
    /// Carried on the data rather than looked up in the views because every
    /// interactive control on this widget now has to name its station: paging
    /// and refresh are per-station once two widgets can show two different
    /// boards, and a control that acted on "the widget" would move the wrong
    /// one.
    var stationId: String = ""
    /// The naptan the extension's own refresh calls, and which (line,
    /// direction) pairs to keep out of the reply. Empty on the legacy path,
    /// where `WidgetRefreshService` falls back to the flat keys.
    var feeds: [BoardFeed] = []

    /// True once the App Group has a real `widget_last_updated` timestamp — lets
    /// the footer show a live ticking "ago" only when there's genuine data age
    /// (avoids a decades-long count from the epoch-0 sentinel).
    var hasTimestamp: Bool { lastUpdated.timeIntervalSince1970 > 0 }

    /// "Piccadilly: Platform 1" — the dot-matrix platform header. EXACT
    /// parity with Android's `StationlyFormatters.platformHeaderText` +
    /// `formatLinePrefix`, which never append the selection's travel
    /// direction here — a past iOS-only session added a client-side
    /// " (Eastbound)"/" (Inbound)" suffix that Android never shows and the
    /// owner never asked for; removed. If the backend's platform string is
    /// already fully formed with a direction in parens (e.g. bus stops:
    /// "Platform 2 (Westbound)") that passes through untouched — it's
    /// backend-owned SDUI content, not client-invented text.
    /// Collapses cleanly when a part is missing ("Piccadilly", "Platform 1", "").
    func platformHeader(platform: String) -> String {
        let rawPlat = platform.trimmingCharacters(in: .whitespaces)
        let line = lineName.isEmpty ? "" : lineName.capitalized

        var plat = ""
        if !rawPlat.isEmpty && rawPlat.lowercased() != "unknown" {
            let lower = rawPlat.lowercased()
            plat = (lower.hasPrefix("platform") || lower.hasPrefix("stop")) ? rawPlat : "Platform \(rawPlat)"
        }

        switch (line.isEmpty, plat.isEmpty) {
        case (false, false): return "\(line): \(plat)"
        case (false, true):  return line
        case (true, false):  return plat
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

    static let departedGraceMs: Double = 30_000

    /// Rows re-derived for the given instant. Platform order of first
    /// appearance is preserved (matches Kotlin groupBy+flatMap semantics).
    ///
    /// `keepAtLeast` is the number of row slots the caller can actually
    /// display. When there aren't enough live trains to fill them, the most
    /// recently departed rows are RETAINED and labelled "Departed" instead of
    /// vanishing — a board that empties itself looks broken and gives the user
    /// nothing to react to, whereas a stale-but-labelled board reads as "this
    /// needs a refresh". Pass 0 to keep the old drop-everything behaviour.
    func ticked(at date: Date, keepAtLeast minRows: Int = 0) -> WidgetData {
        guard !departures.isEmpty else { return self }
        let nowMs = date.timeIntervalSince1970 * 1000.0
        let cutoff = nowMs - Self.departedGraceMs

        // Group FIRST, then decide retention inside each platform. Retention
        // is a per-platform question: a busy Platform 1 with six upcoming
        // trains says nothing about whether a quiet Platform 2 should hold its
        // last departures. Deciding globally let one crowded platform suppress
        // "Gone" rows on every other one.
        var order: [String] = []
        var byPlatform: [String: [DepartureRow]] = [:]
        for row in departures {
            if byPlatform[row.platform] == nil {
                byPlatform[row.platform] = []
                order.append(row.platform)
            }
            byPlatform[row.platform]?.append(row)
        }

        var result: [DepartureRow] = []
        for platform in order {
            // Split departed from live. Null-target rows can't be judged, so
            // they count as live and pass through untouched.
            var live: [DepartureRow] = []
            var departed: [DepartureRow] = []
            for row in byPlatform[platform] ?? [] {
                guard let target = row.targetEpochMs else { live.append(row); continue }
                if target >= cutoff { live.append(row) } else { departed.append(row) }
            }

            // Back-fill ONLY this platform's shortfall. Where its live rows
            // already fill the slots, departed rows stay dropped — retaining
            // them would push genuine upcoming trains out of view, since their
            // earlier targets sort them to the top.
            let shortfall = max(0, minRows - live.count)
            let retained: [DepartureRow] = shortfall > 0
                ? Array(departed
                    .sorted { ($0.targetEpochMs ?? 0) < ($1.targetEpochMs ?? 0) }
                    .suffix(shortfall))      // keep the most recent departures
                : []

            // bumpPlatformGroup applies the monotonic label bump — two trains
            // on one platform can't share a label ("Due, Due" → "Due, 1 min").
            result.append(contentsOf: Self.bumpPlatformGroup(retained + live, nowMs: nowMs))
        }

        // stationId/feeds carried through: every timeline entry is a ticked
        // copy, so dropping them here would leave the rendered board with no
        // idea which station it is — and its paging and refresh buttons acting
        // on whatever the legacy keys happened to hold.
        return WidgetData(
            stationName: stationName, lineName: lineName, direction: direction,
            mode: mode, departures: result, status: status,
            lastUpdated: lastUpdated, isEmpty: isEmpty,
            stationId: stationId, feeds: feeds
        )
    }

    private static func bumpPlatformGroup(_ group: [DepartureRow], nowMs: Double) -> [DepartureRow] {
        let cutoff = nowMs - departedGraceMs
        func isDeparted(_ row: DepartureRow) -> Bool {
            guard let t = row.targetEpochMs else { return false }
            return t < cutoff
        }

        // Retained departed rows are labelled, never ticked: counting them
        // down past zero ("Due" forever, or a negative minute) is exactly the
        // confusion this feature exists to remove. They also sit OUT of the
        // monotonic bump below, so they can't shift a real train's label.
        //
        // "Gone" over "Departed" on purpose: this column is monospaced and the
        // widest label sets the destination's truncation point, so 4 chars
        // keeps long station names intact where 8 would clip them. It also
        // matches the rhythm of "Due" — both are states, not durations.
        let departedRows = group.filter(isDeparted).map {
            $0.relabelled(eta: DepartureRow.departedLabel, isDue: false)
        }
        let group = group.filter { !isDeparted($0) }

        if group.count <= 1 {
            // Single row still re-derives against current now.
            return departedRows + group.map { row in
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
        // Departed first (chronological), null-target rows pin to the end.
        return departedRows + bumped + withoutTarget
    }
}

// MARK: - AppGroupStorage

/// Reads departure data from the App Group NSUserDefaults container that is
/// shared between the main app (written by KMP Platform.ios.kt) and this
/// widget extension.
class AppGroupStorage {
    static let shared = AppGroupStorage()
    private init() {}

    private let appGroupID = AppGroupID.value

    private var defaults: UserDefaults? {
        UserDefaults(suiteName: appGroupID)
    }

    // MARK: - Multi-station

    /// Every station the user tracks, in the app's own order — the list the
    /// widget's configuration picker offers.
    ///
    /// Written by KMP on every board change (`AppGroupKeys.WIDGET_STATIONS`).
    /// Empty is a legitimate answer, not an error: a signed-out user, or one
    /// who has not added a board yet, genuinely has nothing to pick from.
    func readStations() -> [StationEntity] {
        guard let raw = defaults?.string(forKey: AppGroupKeys.stations),
              let data = raw.data(using: .utf8),
              let refs = try? JSONDecoder().decode([StationRef].self, from: data)
        else { return [] }
        return refs.map { StationEntity(id: $0.id, name: $0.name, mode: $0.mode, lines: $0.lines) }
    }

    /// The board for one configured station.
    ///
    /// Falls back to the legacy single-station keys when the id is unknown —
    /// which covers a widget added before multi-station shipped (no
    /// configuration at all) and one whose station has since been deleted. The
    /// primary board is a better answer than an empty one in both cases: the
    /// user still gets a working widget and can re-point it whenever they
    /// notice.
    func readWidgetData(stationId: String?) -> WidgetData {
        guard let stationId, !stationId.isEmpty,
              let raw = defaults?.string(forKey: AppGroupKeys.board(stationId)),
              let data = raw.data(using: .utf8),
              let board = try? JSONDecoder().decode(StoredBoard.self, from: data),
              !board.stationName.isEmpty
        else { return readWidgetData() }

        return WidgetData(
            stationName: board.stationName,
            lineName: board.lineName,
            direction: board.direction,
            mode: board.mode,
            departures: board.predictions,
            status: board.status ?? "",
            lastUpdated: board.lastUpdated > 0
                ? Date(timeIntervalSince1970: TimeInterval(board.lastUpdated))
                : Date(timeIntervalSince1970: 0),
            isEmpty: false,
            stationId: board.id,
            feeds: board.feeds
        )
    }

    /// Mirrors `core/iosMain/platform/WidgetAppGroup.kt`. Both sides change together.
    private struct StationRef: Decodable {
        let id: String
        let name: String
        let mode: String
        let lines: [String]
    }

    private struct StoredBoard: Decodable {
        let id: String
        let stationId: String
        let stationName: String
        let lineName: String
        let direction: String
        let mode: String
        let status: String?
        let lastUpdated: Int
        let feeds: [BoardFeed]
        let predictions: [DepartureRow]
    }

    // MARK: - Legacy single-station keys

    func readWidgetData() -> WidgetData {
        guard let defaults = defaults else {
            print("[AppGroupStorage] Cannot open App Group suite: \(appGroupID)")
            return .empty
        }

        let stationName = defaults.string(forKey: AppGroupKeys.stationName) ?? ""
        let lineName    = defaults.string(forKey: AppGroupKeys.lineName)    ?? ""
        let direction   = defaults.string(forKey: AppGroupKeys.direction)    ?? ""
        let mode        = defaults.string(forKey: AppGroupKeys.mode)         ?? ""
        let status      = defaults.string(forKey: AppGroupKeys.status)       ?? ""
        let tsRaw       = defaults.double(forKey: AppGroupKeys.lastUpdated)
        let lastUpdated = tsRaw > 0 ? Date(timeIntervalSince1970: tsRaw) : Date(timeIntervalSince1970: 0)

        guard !stationName.isEmpty else { return .empty }

        let predictionsJson = defaults.string(forKey: AppGroupKeys.predictions) ?? "[]"
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
