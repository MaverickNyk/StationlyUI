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

// MARK: - BoardGroup

/// One block of the board — a platform on rail, a pole on bus — mirroring
/// `WidgetGroup` in `core/iosMain/platform/WidgetAppGroup.kt`.
///
/// ## The extension no longer decides what a group is
/// It used to group the flat departures list by `platform` itself, which is
/// wrong for buses and cannot be made right on this side of the wire: TfL
/// letters stops only at multi-stop interchanges, so at an ordinary hub every
/// pole reports `platform = ""` and they all collapse into one block with both
/// directions interleaved. The pole a departure was FETCHED from is the bus
/// group key, and that association exists only in the app, before the merge.
///
/// So KMP sends the blocks, and this renders them. That also brings the header
/// text ("Northern Platform 1 Westbound" rather than a locally-assembled
/// "Platform 1 (Westbound)") and every ordering rule the extension had no way to
/// apply — unassigned platforms last, the user's pin, their chosen sort.
struct BoardGroup: Codable, Identifiable {
    /// Platform string on rail, pole naptan on bus. Stable identity, and what a
    /// refresh re-associates its freshly-fetched rows against.
    let key: String
    /// KMP's own header text, rendered verbatim.
    let header: String
    /// [header] and its shorter forms, widest first — see `headerLadder`.
    let headerVariants: [String]
    /// The backend's platform label ("Platform 8", "Stop C", "").
    let label: String
    /// Whether rows here should carry their line prefix — decided by KMP from
    /// every departure the block HAS, not the handful that fit on screen.
    let mixesLines: Bool
    let rows: [DepartureRow]

    /// Identity for `ForEach`. The key is unique within a board by construction
    /// (it is what the block was grouped BY), so it is stable across the
    /// per-minute entries in a way a row's regenerated UUID is not.
    var id: String { key }

    /// `rows` is `predictions` on the wire — the Kotlin field name. Renamed here
    /// because a block's contents read as rows at every use site in the views.
    private enum CodingKeys: String, CodingKey {
        case key, header, headerVariants, label, mixesLines
        case rows = "predictions"
    }

    init(key: String, header: String, headerVariants: [String] = [],
         label: String, mixesLines: Bool, rows: [DepartureRow]) {
        self.key = key
        self.header = header
        // The full header is always the widest rung, so a group given no ladder
        // (an older payload, or a block a refresh invented) still has one — of
        // exactly one rung, which behaves as the old unconditional render did.
        self.headerVariants = headerVariants.isEmpty ? [header] : headerVariants
        self.label = label
        self.mixesLines = mixesLines
        self.rows = rows
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let header = try c.decode(String.self, forKey: .header)
        let variants = try c.decodeIfPresent([String].self, forKey: .headerVariants) ?? []
        self.key = try c.decode(String.self, forKey: .key)
        self.header = header
        self.headerVariants = variants.isEmpty ? [header] : variants
        self.label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
        self.mixesLines = try c.decodeIfPresent(Bool.self, forKey: .mixesLines) ?? false
        self.rows = try c.decodeIfPresent([DepartureRow].self, forKey: .rows) ?? []
    }

    func with(rows: [DepartureRow]) -> BoardGroup {
        BoardGroup(key: key, header: header, headerVariants: headerVariants,
                   label: label, mixesLines: mixesLines, rows: rows)
    }

    // MARK: Block rules the extension has to restate
    //
    // KMP owns both of these — `MultiLineBoardProcessor.isBus` and
    // `Group.mixesLines` — and every group that arrives over the wire already
    // carries the answers. They exist here only for the two paths that must
    // build a block WITHOUT KMP: the legacy flat keys, and the extension's own
    // REST refresh. Both had their own copy until they were pulled together
    // here; two copies of a rule KMP already owns is one more than the one
    // that should not exist at all.

    /// Whether a station groups by POLE rather than by platform.
    /// Mirror of `MultiLineBoardProcessor.isBus`.
    static func isBus(mode: String) -> Bool {
        mode.trimmingCharacters(in: .whitespaces).lowercased() == "bus"
    }

    /// Whether a block carries more than one line, which is the ONLY case a row
    /// draws its line prefix. Mirror of `MultiLineBoardProcessor.Group.mixesLines`.
    ///
    /// Buses never take one: the backend appends the route to the destination
    /// itself ("53 Nags Head"), so a prefix would print it twice.
    static func mixesLines(_ rows: [DepartureRow], isBus: Bool) -> Bool {
        guard !isBus else { return false }
        var seen = Set<String>()
        for row in rows where !row.lineShort.isEmpty {
            seen.insert(row.lineShort)
            if seen.count > 1 { return true }
        }
        return false
    }
}

// MARK: - WidgetData

struct WidgetData {
    let stationName: String
    /// CANONICAL line id ("hammersmith-city") — an identity, never shown. The
    /// legacy refresh path keys the predictions payload on it.
    let lineName: String
    /// [lineName] as a person reads it, resolved by KMP. Only the fallback
    /// header uses it; a board with real groups renders KMP's header text.
    let lineDisplay: String
    let direction: String
    let mode: String
    /// The board as blocks — the unit the views render and page between.
    let groups: [BoardGroup]
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
    /// How many departures this station shows under each header — the user's
    /// own setting, mirroring `WidgetBoard.rowCap` in
    /// `core/iosMain/platform/WidgetAppGroup.kt`.
    ///
    /// The extension cannot read the preference itself (it lives in the app's
    /// NSUserDefaults suite, not the App Group), which is why this board used to
    /// show three rows whatever the home screen was showing. `group.rows` is
    /// RESERVES, so this is applied at render — always clamped by what the
    /// family can physically draw, since a medium widget has room for three rows
    /// however high the setting goes. See `BoardMetrics.rows(for:)`.
    var rowCap: Int = 3

    // A `departures` property that flattened every block used to live here, and
    // it is deliberately gone: the render path only ever asked it three
    // questions — is it empty, give me the first few, how many — and answering
    // any of them by materialising the whole board is work paid on a process
    // iOS cold-launched to service one tap. The three accessors below answer
    // them without allocating, and none of them can be written past.

    /// Whether the board has any row at all, without building the list.
    ///
    /// Short-circuits on the first non-empty block — one comparison in the
    /// common case, against a full flatMap before.
    var hasDepartures: Bool { groups.contains { !$0.rows.isEmpty } }

    /// The first [limit] rows across all blocks, stopping as soon as it has
    /// them. The small family shows three; it was flattening a board of forty
    /// to find them.
    func firstDepartures(_ limit: Int) -> [DepartureRow] {
        var out: [DepartureRow] = []
        out.reserveCapacity(limit)
        for group in groups {
            for row in group.rows {
                out.append(row)
                if out.count == limit { return out }
            }
        }
        return out
    }

    /// Row count without building the list — for logging, which must never be
    /// the most expensive thing on a code path.
    var departureCount: Int { groups.reduce(0) { $0 + $1.rows.count } }

    /// True once the App Group has a real `widget_last_updated` timestamp — lets
    /// the footer show a live ticking "ago" only when there's genuine data age
    /// (avoids a decades-long count from the epoch-0 sentinel).
    var hasTimestamp: Bool { lastUpdated.timeIntervalSince1970 > 0 }

    /// How old this payload is at a given entry's date. Negative clock skew is
    /// clamped away — a future timestamp means the clocks disagree, not that the
    /// data is fresher than fresh.
    func ageMs(at date: Date) -> Double {
        guard hasTimestamp else { return .greatestFiniteMagnitude }
        return max(0, date.timeIntervalSince(lastUpdated) * 1000)
    }

    // MARK: Fallback grouping (no groups on the wire)

    /// Blocks derived locally from a flat departures list.
    ///
    /// The ONLY caller is a board that arrived without groups: the legacy
    /// single-station keys, and the window between installing a build that sends
    /// them and the first push that does. It groups by `platform`, which is the
    /// rule this file exists to stop relying on — correct for rail, and on bus it
    /// collapses unlettered poles exactly as described in `BoardGroup`.
    ///
    /// That is acceptable HERE and nowhere else, because the legacy keys describe
    /// a single (line, direction) board, which is one pole by definition — there
    /// is nothing to collapse. Any board that can have two poles has groups.
    static func fallbackGroups(_ rows: [DepartureRow],
                               lineDisplay: String,
                               mode: String) -> [BoardGroup] {
        var order: [String] = []
        var map: [String: [DepartureRow]] = [:]
        for row in rows {
            let key = (row.platform.isEmpty || row.platform.lowercased() == "unknown")
                ? "" : row.platform
            if map[key] == nil { map[key] = []; order.append(key) }
            map[key]?.append(row)
        }
        let isBus = BoardGroup.isBus(mode: mode)
        return order.map { key in
            let rows = map[key] ?? []
            // No ladder: this header was assembled here and the rules for
            // shortening it live in KMP. One rung renders it as-is.
            return BoardGroup(
                key: key,
                header: fallbackHeader(platform: key, lineDisplay: lineDisplay),
                label: key,
                mixesLines: BoardGroup.mixesLines(rows, isBus: isBus),
                rows: rows
            )
        }
    }

    /// "Piccadilly: Platform 1" — the header for a locally-derived block.
    ///
    /// A deliberately poorer string than KMP's, and it should stay that way
    /// rather than growing toward parity: matching `headerFor` here would mean a
    /// second implementation of the board's header rules in a language that
    /// cannot be tested against the first. Boards with real groups never reach
    /// it.
    ///
    /// Android parity note (`StationlyFormatters.platformHeaderText` +
    /// `formatLinePrefix`): no travel-direction suffix is appended. A past
    /// iOS-only session added " (Eastbound)"/" (Inbound)" that Android never
    /// shows; removed. A backend platform string that already carries one
    /// ("Platform 2 (Westbound)") passes through untouched — that is
    /// backend-owned content, not client-invented text.
    private static func fallbackHeader(platform: String, lineDisplay: String) -> String {
        let rawPlat = platform.trimmingCharacters(in: .whitespaces)
        let line = lineDisplay.trimmingCharacters(in: .whitespaces)

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

    /// Convenience init for the paths that only have a flat list — the legacy
    /// keys, previews, placeholders. Derives blocks via [fallbackGroups].
    init(stationName: String, lineName: String, lineDisplay: String = "",
         direction: String, mode: String, departures: [DepartureRow],
         status: String, lastUpdated: Date, isEmpty: Bool,
         stationId: String = "", feeds: [BoardFeed] = [], rowCap: Int = 3) {
        // The pre-`lineDisplay` fallback: capitalising a canonical id is what
        // rendered "Hammersmith-City", so it is last resort only — KMP writes
        // both keys on every board write, making this a window of one push.
        let display = lineDisplay.isEmpty ? lineName.capitalized : lineDisplay
        self.init(
            stationName: stationName, lineName: lineName, lineDisplay: display,
            direction: direction, mode: mode,
            groups: Self.fallbackGroups(departures, lineDisplay: display, mode: mode),
            status: status, lastUpdated: lastUpdated, isEmpty: isEmpty,
            stationId: stationId, feeds: feeds, rowCap: rowCap
        )
    }

    init(stationName: String, lineName: String, lineDisplay: String,
         direction: String, mode: String, groups: [BoardGroup],
         status: String, lastUpdated: Date, isEmpty: Bool,
         stationId: String = "", feeds: [BoardFeed] = [], rowCap: Int = 3) {
        self.stationName = stationName
        self.lineName = lineName
        self.lineDisplay = lineDisplay
        self.direction = direction
        self.mode = mode
        self.groups = groups
        self.status = status
        self.lastUpdated = lastUpdated
        self.isEmpty = isEmpty
        self.stationId = stationId
        self.feeds = feeds
        self.rowCap = rowCap
    }

    // Used for Xcode Previews and widget placeholder state
    static var placeholder: WidgetData {
        WidgetData(
            stationName: "Arsenal",
            lineName: "piccadilly",
            lineDisplay: "Piccadilly",
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

    /// How old a payload must be before a departed row is worth holding.
    ///
    /// ## Why retention has to be conditional at all
    /// "Gone" means "this board is stale, refresh me". Backfilling
    /// unconditionally made it mean "this platform is quiet", which is a
    /// different and much less useful statement — and it produced the reported
    /// bug: rows reading "Gone" immediately after a successful refresh. A
    /// payload can legitimately arrive containing trains that have already left
    /// (TfL reports them, and SQL holds the previous fetch's rows), so a fresh
    /// board with a shortfall would resurrect them and label the newest data the
    /// app has as departed.
    ///
    /// Fresh payload, no backfill: the platform showing two trains instead of
    /// three is the truth, and the board says so by being short rather than by
    /// lying. Old payload, hold the last known departures: there the label is
    /// accurate and it is the only signal the user gets that the widget is
    /// showing history.
    ///
    /// 60s because that is already what "fresh" means on this board — it is the
    /// footer's amber threshold in `LiveAgo.staleColor`. A second definition of
    /// freshness with a different number is the kind of drift that leaves two
    /// parts of one widget disagreeing about the same payload.
    static let retentionMinAgeMs: Double = 60_000

    /// The board re-derived for the given instant: ETAs counted down, departed
    /// trains shed, the queue shifted.
    ///
    /// Block order and identity are untouched — they are KMP's, and a board that
    /// re-ordered itself between timeline entries would move pages under the
    /// user's arrows. Blocks left with no rows at all ARE dropped: an empty page
    /// is nothing to arrow to, and a board whose blocks all empty renders the
    /// "No departures" row, exactly as before.
    ///
    /// `keepAtLeast` is the row slots the caller can display, and it only takes
    /// effect once the payload is at least [retentionMinAgeMs] old — see there.
    /// Pass 0 to never retain.
    func ticked(at date: Date, keepAtLeast minRows: Int = 0) -> WidgetData {
        guard !groups.isEmpty else { return self }
        let nowMs = date.timeIntervalSince1970 * 1000.0

        // Decided once for the whole board, because it is a property of the
        // PAYLOAD rather than of any block: every block here was written by the
        // same fetch and is therefore exactly as old as every other.
        let retain = minRows > 0 && ageMs(at: date) >= Self.retentionMinAgeMs

        // Retention is then a per-BLOCK question: a busy Platform 1 with six
        // upcoming trains says nothing about whether a quiet Platform 2 should
        // hold its last departures. Deciding it globally let one crowded
        // platform suppress "Gone" rows on every other one.
        let tickedGroups = groups.compactMap { group -> BoardGroup? in
            let rows = Self.tickGroup(group.rows, nowMs: nowMs, keepAtLeast: retain ? minRows : 0)
            return rows.isEmpty ? nil : group.with(rows: rows)
        }

        // stationId/feeds carried through: every timeline entry is a ticked
        // copy, so dropping them here would leave the rendered board with no
        // idea which station it is — and its paging and refresh buttons acting
        // on whatever the legacy keys happened to hold.
        return WidgetData(
            stationName: stationName, lineName: lineName, lineDisplay: lineDisplay,
            direction: direction, mode: mode, groups: tickedGroups, status: status,
            lastUpdated: lastUpdated, isEmpty: isEmpty,
            stationId: stationId, feeds: feeds, rowCap: rowCap
        )
    }

    /// One block's rows at `nowMs`, with its own shortfall backfilled.
    private static func tickGroup(_ rows: [DepartureRow],
                                  nowMs: Double,
                                  keepAtLeast minRows: Int) -> [DepartureRow] {
        let cutoff = nowMs - departedGraceMs

        // Split departed from live. Null-target rows can't be judged, so they
        // count as live and pass through untouched.
        var live: [DepartureRow] = []
        var departed: [DepartureRow] = []
        for row in rows {
            guard let target = row.targetEpochMs else { live.append(row); continue }
            if target >= cutoff { live.append(row) } else { departed.append(row) }
        }

        // Back-fill ONLY this block's shortfall. Where its live rows already
        // fill the slots, departed rows stay dropped — retaining them would
        // push genuine upcoming trains out of view, since their earlier
        // targets sort them to the top.
        let shortfall = max(0, minRows - live.count)
        let retained: [DepartureRow] = shortfall > 0
            ? Array(departed
                .sorted { ($0.targetEpochMs ?? 0) < ($1.targetEpochMs ?? 0) }
                .suffix(shortfall))      // keep the most recent departures
            : []

        // bumpGroup applies the monotonic label bump — two trains in one block
        // can't share a label ("Due, Due" → "Due, 1 min").
        return bumpGroup(retained + live, nowMs: nowMs)
    }

    private static func bumpGroup(_ group: [DepartureRow], nowMs: Double) -> [DepartureRow] {
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
            // Single row still re-derives against current now — through the SAME
            // labelling as a bumped one (a one-row block has nothing to collide
            // with, so the bump is a no-op). This used to be a parallel branch
            // flagging `isDue: secs < 30`, which labelled a lone 45-second train
            // "Due" while reporting isDue = false — and this view tints from the
            // flag, so that train rendered amber where the identical train on a
            // busier platform rendered red. Mirrors Kotlin `BoardTicker.label`.
            return departedRows + group.map { row in
                guard let minutes = Self.minutes(row, nowMs: nowMs) else { return row }
                return row.relabelled(eta: Self.label(minutes), isDue: minutes == 0)
            }
        }
        let withTarget = group.filter { $0.targetEpochMs != nil }
            .sorted { $0.targetEpochMs! < $1.targetEpochMs! }
        let withoutTarget = group.filter { $0.targetEpochMs == nil }
        var prevMin = -1   // "Due" == 0; -1 means nothing taken yet
        let bumped = withTarget.map { row -> DepartureRow in
            let effective = max(Self.minutes(row, nowMs: nowMs) ?? 0, prevMin + 1)
            prevMin = effective
            return row.relabelled(eta: Self.label(effective), isDue: effective == 0)
        }
        // Departed first (chronological), null-target rows pin to the end.
        return departedRows + bumped + withoutTarget
    }

    /// Whole minutes until this row arrives, or nil when it has no target.
    /// FLOOR rounding, matching Kotlin's `Long` division and the platform signs.
    private static func minutes(_ row: DepartureRow, nowMs: Double) -> Int? {
        guard let target = row.targetEpochMs else { return nil }
        let secs = (target - nowMs) / 1000.0
        return secs < 60 ? 0 : Int(secs / 60)
    }

    /// The board's word for a whole-minute countdown; zero is "Due".
    /// `isDue` means exactly "this row reads Due" — see Kotlin `BoardTicker.label`.
    private static func label(_ minutes: Int) -> String {
        minutes == 0 ? "Due" : "\(minutes) min"
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

    /// Shared — see `AppGroupDefaults`. Read on every timeline build and every
    /// render, in a process launched fresh for each.
    private var defaults: UserDefaults? { AppGroupDefaults.shared }

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

        let lineDisplay = board.lineDisplay?.isEmpty == false
            ? board.lineDisplay! : board.lineName.capitalized
        let lastUpdated = board.lastUpdated > 0
            ? Date(timeIntervalSince1970: TimeInterval(board.lastUpdated))
            : Date(timeIntervalSince1970: 0)

        // A board written by a build older than `groups` still has to render, so
        // its flat predictions are grouped locally — see `fallbackGroups` for
        // why that is safe only as a fallback. The window is one push long.
        guard !board.groups.isEmpty else {
            return WidgetData(
                stationName: board.stationName, lineName: board.lineName,
                lineDisplay: lineDisplay, direction: board.direction, mode: board.mode,
                departures: board.predictions, status: board.status ?? "",
                lastUpdated: lastUpdated, isEmpty: false,
                stationId: board.id, feeds: board.feeds, rowCap: board.rowCap
            )
        }

        return WidgetData(
            stationName: board.stationName,
            lineName: board.lineName,
            lineDisplay: lineDisplay,
            direction: board.direction,
            mode: board.mode,
            groups: board.groups,
            status: board.status ?? "",
            lastUpdated: lastUpdated,
            isEmpty: false,
            stationId: board.id,
            feeds: board.feeds,
            rowCap: board.rowCap
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
        /// Optional: absent on a board written before the key existed.
        let lineDisplay: String?
        let direction: String
        let mode: String
        let status: String?
        let lastUpdated: Int
        let feeds: [BoardFeed]
        /// The station's `rowsPerPlatform` setting — see `WidgetData.rowCap`.
        /// Absent on a board written before it was sent, which decodes to the
        /// depth that build hardcoded.
        let rowCap: Int
        /// The board as blocks. Empty on a pre-`groups` payload, which
        /// `readWidgetData` handles by grouping `predictions` locally.
        let groups: [BoardGroup]
        let predictions: [DepartureRow]

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            self.id = try c.decode(String.self, forKey: .id)
            self.stationId = try c.decode(String.self, forKey: .stationId)
            self.stationName = try c.decode(String.self, forKey: .stationName)
            self.lineName = try c.decode(String.self, forKey: .lineName)
            self.lineDisplay = try c.decodeIfPresent(String.self, forKey: .lineDisplay)
            self.direction = try c.decode(String.self, forKey: .direction)
            self.mode = try c.decode(String.self, forKey: .mode)
            self.status = try c.decodeIfPresent(String.self, forKey: .status)
            self.lastUpdated = try c.decodeIfPresent(Int.self, forKey: .lastUpdated) ?? 0
            self.feeds = try c.decodeIfPresent([BoardFeed].self, forKey: .feeds) ?? []
            // Normalised on the way IN, so no renderer has to defend against a
            // zero or negative depth arriving from a build that wrote one. A
            // board must always be able to draw at least one row.
            self.rowCap = max(1, try c.decodeIfPresent(Int.self, forKey: .rowCap) ?? 3)
            self.groups = try c.decodeIfPresent([BoardGroup].self, forKey: .groups) ?? []
            self.predictions = try c.decodeIfPresent([DepartureRow].self, forKey: .predictions) ?? []
        }

        private enum CodingKeys: String, CodingKey {
            case id, stationId, stationName, lineName, lineDisplay, direction, mode
            case status, lastUpdated, feeds, rowCap, groups, predictions
        }
    }

    // MARK: - Legacy single-station keys

    func readWidgetData() -> WidgetData {
        guard let defaults = defaults else {
            print("[AppGroupStorage] Cannot open App Group suite: \(appGroupID)")
            return .empty
        }

        let stationName = defaults.string(forKey: AppGroupKeys.stationName) ?? ""
        let lineName    = defaults.string(forKey: AppGroupKeys.lineName)    ?? ""
        let lineDisplay = defaults.string(forKey: AppGroupKeys.lineDisplay) ?? ""
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
            lineDisplay: lineDisplay,
            direction: direction,
            mode: mode,
            departures: departures,
            status: status,
            lastUpdated: lastUpdated,
            isEmpty: false
        )
    }
}
