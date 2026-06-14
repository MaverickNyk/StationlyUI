import SwiftUI
import UIKit
import WidgetKit

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Entry view router
// ─────────────────────────────────────────────────────────────────────────────

/// Top-level view that WidgetKit renders. Routes to the correct size layout.
struct DepartureBoardEntryView: View {
    let entry: DepartureEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        switch family {
        case .systemSmall:  SmallWidgetView(data: entry.widgetData, clock: entry.date)
        case .systemMedium: BoardWidgetView(data: entry.widgetData, clock: entry.date, metrics: .medium)
        case .systemLarge:  BoardWidgetView(data: entry.widgetData, clock: entry.date, metrics: .large)
        @unknown default:   BoardWidgetView(data: entry.widgetData, clock: entry.date, metrics: .medium)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Type scale
// The user-specified hierarchy, top to bottom:
//   station (biggest) > platform header > departure rows > status strip;
//   footer clock ≈ station, "ago" timer smallest.
// One struct per family so the large widget breathes while medium stays dense.
// ─────────────────────────────────────────────────────────────────────────────

struct BoardMetrics {
    let station: CGFloat      // station name — biggest
    let platform: CGFloat     // platform section header — 2nd
    let row: CGFloat          // departure destination/eta — 3rd
    let status: CGFloat       // line status — slightly under rows
    let clock: CGFloat        // footer wall clock — near-station size
    let ago: CGFloat          // live "ago" — smallest
    let icon: CGFloat         // mode roundel beside the station name
    let logo: CGFloat         // Stationly maker mark in the footer
    let cellRadius: CGFloat   // LED cell corner radius
    let maxRows: Int
    /// Medium budgets exactly one platform group (one section header + up to
    /// `maxRows` rows); large renders every group like Android's 5×3 board.
    let singlePlatform: Bool

    // Medium's content budget: station + platform + 3 rows + footer is
    // ~156pt of cell minimums — the most a fixed ~155–170pt medium canvas
    // can carry without compressing cells below their minimums (the
    // "crumbled" look). The status strip only appears when a departure
    // slot is spare; on <150pt canvases the footer is shed too.
    static let medium = BoardMetrics(
        station: 16, platform: 13, row: 12.5, status: 10.5,
        clock: 15, ago: 8.5, icon: 18, logo: 22, cellRadius: 0, maxRows: 3,
        singlePlatform: true)
    static let large = BoardMetrics(
        station: 19, platform: 15, row: 14.5, status: 12.5,
        clock: 18, ago: 10, icon: 22, logo: 22, cellRadius: 0, maxRows: 10,
        singlePlatform: false)
}

private let DueRed = Color(red: 1.0, green: 0.32, blue: 0.32)

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Shared marks
// ─────────────────────────────────────────────────────────────────────────────

/// A "lit cell" strip — the active row background from the Android board.
/// Cells are square-cornered and run the full widget width (content margins are
/// disabled), so the board IS the widget: the system's corner mask rounds the
/// four outer corners and the 2pt black gaps between cells read as the bezel.
///
/// NO per-cell texture here: the unlit-dot lattice is ONE `DotGrid` overlay on
/// the whole board (one image per entry instead of ~16 × 61 ≈ 1,000 refs in
/// the archive — and an LED panel's unlit dots span the full panel anyway,
/// gaps included). Archive-poisoning history of this board:
/// docs/IOS_WIDGET_DESIGN.md §3.4.
private struct LitCell<Content: View>: View {
    var vPad: CGFloat = 3
    var hPad: CGFloat = 10
    var radius: CGFloat = 0
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(.horizontal, hPad)
            .padding(.vertical, vPad)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(WidgetTheme.rowSurface)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// The unlit-LED texture: a sparse dot lattice at very low alpha, tiled from a
/// tiny bitmap rendered once per process. Applied ONCE over the whole board
/// (covering lit cells and the black gaps alike — an LED panel's unlit dots
/// span the full panel) — see LitCell's comment for why per-cell texturing
/// broke the WidgetKit archiver. Earlier this was a live `Canvas` per cell,
/// which additionally forced thousands of rasterisations per timeline render
/// burst in the system's WidgetRenderer process.
private struct DotGrid: View {
    static let tile: UIImage = {
        let pitch: CGFloat = 3, r: CGFloat = 0.55, side: CGFloat = 9
        let format = UIGraphicsImageRendererFormat()
        format.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { ctx in
            UIColor.white.withAlphaComponent(0.030).setFill()
            var y = pitch / 2
            while y < side {
                var x = pitch / 2
                while x < side {
                    ctx.cgContext.fillEllipse(in: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2))
                    x += pitch
                }
                y += pitch
            }
        }
    }()
    var body: some View {
        Image(uiImage: Self.tile)
            .resizable(resizingMode: .tile)
    }
}

/// TfL roundel: ring crossed by a horizontal bar. Drawn fallback for when the
/// backend mode icon hasn't been cached yet.
struct TflRoundelMark: View {
    var color: Color = WidgetTheme.amber
    var diameter: CGFloat = 14
    var body: some View {
        ZStack {
            Circle()
                .stroke(color, lineWidth: diameter * 0.19)
                .frame(width: diameter * 0.92, height: diameter * 0.92)
            Rectangle()
                .fill(color)
                .frame(width: diameter * 1.16, height: diameter * 0.19)
        }
        .frame(width: diameter * 1.16, height: diameter)
    }
}

/// The real per-mode roundel from the backend `/modes` endpoint, cached in the
/// App Group by KMP ModeIconStore (same chain as Android's ModeIconCache):
/// cached PNG → backend tint on the drawn roundel → hardcoded mode colour.
struct ModeIconView: View {
    let mode: String
    var size: CGFloat = 18
    var body: some View {
        if let ui = ModeIconProvider.icon(mode) {
            // Height-anchored like Android's fitCenter lockup: the TfL
            // roundel is wider than tall (~1.22:1), so a square frame either
            // squashed it or left it undersized. Full height + natural width
            // (clamped against pathological assets) lets it fill out.
            let aspect = ui.size.height > 0 ? ui.size.width / ui.size.height : 1
            Image(uiImage: ui)
                .resizable()
                .scaledToFit()
                .frame(width: size * min(max(aspect, 0.6), 1.6), height: size)
        } else {
            TflRoundelMark(color: tint, diameter: size)
        }
    }
    private var tint: Color {
        if let t = ModeIconProvider.tint(mode) { return Color(t) }
        return WidgetTheme.modeColor(mode)
    }
}

/// The Stationly maker mark — the real brand logo (bundled imageset, copied
/// from android/res/drawable/stationly_logo.png).
struct StationlyMark: View {
    var diameter: CGFloat = 16
    var body: some View {
        Image("StationlyLogo")
            .resizable()
            .scaledToFit()
            .frame(width: diameter, height: diameter)
    }
}

/// Live "M:SS ago" — WidgetKit renders the `.timer` style as a self-updating
/// element (ticks every second with no timeline reload), the iOS analog of
/// Android's Chronometer. Falls back to a static dash before any data has landed.
///
/// Gotcha that broke the footer layout on device: a `.timer` Text greedily
/// expands to fill its container and left-aligns, which shoved the digits to
/// the far LEFT while the "ago" label stayed right ("0:03 …… ago").
/// Concatenating into a single Text and trailing-aligning inside a capped
/// frame keeps "M:SS ago" together as one unit.
private struct LiveAgo: View {
    let data: WidgetData
    var fontSize: CGFloat = 8.5
    var body: some View {
        Group {
            if data.hasTimestamp {
                (Text(data.lastUpdated, style: .timer).monospacedDigit() + Text(" ago"))
                    .multilineTextAlignment(.trailing)
            } else {
                Text("—")
            }
        }
        .font(.system(size: fontSize).italic())
        .foregroundColor(WidgetTheme.amber.opacity(0.85))
        .lineLimit(1)
        .frame(maxWidth: 72, alignment: .trailing)
    }
}

/// Group a flat departures list by platform, preserving first-seen order.
private func groupedByPlatform(_ deps: [DepartureRow]) -> [(platform: String, rows: [DepartureRow])] {
    var order: [String] = []
    var map: [String: [DepartureRow]] = [:]
    for d in deps {
        let key = (d.platform.isEmpty || d.platform.lowercased() == "unknown") ? "" : d.platform
        if map[key] == nil { map[key] = []; order.append(key) }
        map[key]?.append(d)
    }
    return order.map { (platform: $0, rows: map[$0] ?? []) }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Board cells
// ─────────────────────────────────────────────────────────────────────────────

/// Centered station lockup: real mode roundel + station name — the board's
/// loudest line, exactly like Android's station_lockup.
struct DotMatrixHeader: View {
    let data: WidgetData
    let m: BoardMetrics
    var body: some View {
        // Top cell lives in the widget's rounded-corner zone — deeper content
        // inset than mid-board rows so nothing clips against the corner mask.
        LitCell(vPad: 4, hPad: 14, radius: m.cellRadius) {
            HStack(spacing: 8) {
                ModeIconView(mode: data.mode, size: m.icon)
                Text(data.stationName)
                    .font(.system(size: m.station, weight: .bold))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                    .minimumScaleFactor(0.65)
            }
        }
        .widgetAccentable()
    }
}

/// "Piccadilly: Platform 1 (Eastbound)" — centered amber section header.
struct DotMatrixSectionHeader: View {
    let title: String
    let m: BoardMetrics
    var body: some View {
        LitCell(vPad: 2, radius: m.cellRadius) {
            Text(title)
                .font(.system(size: m.platform, weight: .bold))
                .foregroundColor(WidgetTheme.amber)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }
}

/// Destination (left) + ETA (right). All amber; "Due" in red — matches the board.
struct DotMatrixRow: View {
    let dep: DepartureRow
    let m: BoardMetrics
    var body: some View {
        LitCell(radius: m.cellRadius) {
            HStack(spacing: 8) {
                Text(dep.destination)
                    .font(.system(size: m.row))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(dep.isDue ? "Due" : dep.eta)
                    .font(.system(size: m.row + 0.5, weight: .bold, design: .monospaced))
                    .foregroundColor(dep.isDue ? DueRed : WidgetTheme.amber)
            }
        }
    }
}

/// "Severity : reason" — the line-status strip. Board-amber everywhere (no
/// green/orange severity tinting); the reason is STATIC, truncating with a
/// tail. Android's continuously-scrolling marquee is impossible in WidgetKit
/// (static snapshots, no animation API; best possible was a once-per-minute
/// stepped window, which read as broken) — so by product decision the iOS
/// widget doesn't marquee at all. History: docs/IOS_WIDGET_DESIGN.md §3.3.
struct DotMatrixStatusStrip: View {
    let data: WidgetData
    let m: BoardMetrics
    private var parts: (severity: String, reason: String) {
        let raw = data.status.isEmpty ? "Good Service" : data.status
        if let r = raw.range(of: ":") {
            return (String(raw[..<r.lowerBound]).trimmingCharacters(in: .whitespaces),
                    String(raw[r.upperBound...]).trimmingCharacters(in: .whitespaces))
        }
        return (raw, "")
    }
    var body: some View {
        LitCell(vPad: 2, radius: m.cellRadius) {
            HStack(spacing: 0) {
                Text(parts.severity)
                    .font(.system(size: m.status, weight: .bold))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                    .fixedSize()
                if !parts.reason.isEmpty {
                    Text(" : \(parts.reason)")
                        .font(.system(size: m.status))
                        .foregroundColor(WidgetTheme.amber)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Spacer(minLength: 0)
            }
        }
    }
}

/// Live HH:MM:SS wall clock, ticking every second with NO timeline reloads:
/// a `.timer`-style Text anchored at local midnight counts the time elapsed
/// since 00:00:00, which IS the time of day — the same trick the system Clock
/// widget uses (the only per-second self-updating element WidgetKit allows).
/// Each per-minute timeline entry re-anchors it, so the midnight rollover is
/// picked up within a minute. Known limitation of the timer style: a
/// single-digit hour renders without the leading zero ("8:05:09").
private struct LiveClock: View {
    let clock: Date
    let fontSize: CGFloat
    var body: some View {
        Text(Calendar.current.startOfDay(for: clock), style: .timer)
            .font(.system(size: fontSize, weight: .bold, design: .monospaced))
            .foregroundColor(WidgetTheme.amber)
            .lineLimit(1)
            // Timer Texts greedily expand and left-align; center the digits
            // inside that expanded frame so the clock stays mid-board.
            .multilineTextAlignment(.center)
    }
}

/// Footer, matching the Android board's bottom row: Stationly maker mark
/// (left) + live HH:MM:SS clock (CENTER, near-station size) + live "M:SS ago"
/// (right, the smallest type on the board). Both clock and "ago" tick per
/// second via `.timer`.
struct DotMatrixFooter: View {
    let data: WidgetData
    let clock: Date
    let m: BoardMetrics
    var body: some View {
        // Bottom cell: the corner mask intrudes ~9–12pt at the logo/ago height
        // (iOS 26 corner radii are generous) — 20pt of side breathing keeps the
        // maker mark and the "ago" timer comfortably clear of the curve.
        LitCell(vPad: 4, hPad: 20, radius: m.cellRadius) {
            ZStack {
                HStack {
                    StationlyMark(diameter: m.logo)
                    Spacer(minLength: 0)
                    LiveAgo(data: data, fontSize: m.ago)
                }
                LiveClock(clock: clock, fontSize: m.clock)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - The board (medium + large)
// Every cell is height-flexible (`maxHeight: .infinity` inside LitCell) at
// EQUAL layout priority, so surplus height is shared evenly across all cells
// and the board always fills the whole canvas. (An earlier layoutPriority
// 2/1/0 ladder made ONLY the header and footer balloon on sparse boards —
// 1–2 departures left the mid rows pinned at minimum height. The minHeights
// remain as compression floors; priorities had no other job once the medium
// board was budgeted to fit.)
//
// Medium vs large content budget (see BoardMetrics): medium carries station +
// one platform header + 3 rows + footer; the status strip rides along only
// when a departure slot is spare, and the footer is shed on SE-class canvases
// (<150pt). Large keeps full Android parity: every platform group + status +
// footer, always.
// ─────────────────────────────────────────────────────────────────────────────

struct BoardWidgetView: View {
    let data: WidgetData
    let clock: Date
    let metrics: BoardMetrics

    var body: some View {
        Group {
            if data.isEmpty {
                EmptyWidgetView(size: metrics.maxRows > 4 ? .large : .medium)
            } else {
                board
            }
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }

    private var board: some View {
        GeometryReader { geo in
            VStack(spacing: 2) {
                DotMatrixHeader(data: data, m: metrics)
                    .frame(minHeight: metrics.station + 14)

                if data.departures.isEmpty {
                    NoDeparturesRow()
                        .frame(maxHeight: .infinity)
                    // On medium the status strip is the empty board's one shot
                    // at saying WHY ("Service Closed : …"); large adds it
                    // unconditionally below.
                    if metrics.singlePlatform {
                        statusStrip
                    }
                } else if metrics.singlePlatform {
                    primaryPlatformSection
                } else {
                    allPlatformsSection
                }

                if !metrics.singlePlatform {
                    statusStrip
                }
                // "If it doesn't fit, drop the last row" — only SE-class
                // mediums (321×148) fall under 150pt; every other family
                // keeps the live clock/ago footer.
                if geo.size.height >= 150 {
                    DotMatrixFooter(data: data, clock: clock, m: metrics)
                        .frame(minHeight: metrics.clock + 12)
                }
            }
            .overlay(DotGrid().allowsHitTesting(false))
        }
    }

    /// Medium: the first platform group only — one section header, up to
    /// `maxRows` rows. A second group would cost a second header cell and
    /// blow the 6-cell budget. When the group can't fill all row slots the
    /// status strip backfills one, so a quiet board never shows dead space.
    @ViewBuilder
    private var primaryPlatformSection: some View {
        if let group = groupedByPlatform(data.departures).first {
            let header = data.platformHeader(platform: group.platform)
            if !header.isEmpty {
                DotMatrixSectionHeader(title: header, m: metrics)
                    .frame(minHeight: metrics.platform + 8)
            }
            ForEach(group.rows.prefix(metrics.maxRows)) { dep in
                DotMatrixRow(dep: dep, m: metrics)
                    .frame(minHeight: metrics.row + 10)
            }
            if group.rows.count < metrics.maxRows {
                statusStrip
            }
        }
    }

    /// Large: every platform group, Android-style.
    @ViewBuilder
    private var allPlatformsSection: some View {
        let groups = groupedByPlatform(Array(data.departures.prefix(metrics.maxRows)))
        ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
            let header = data.platformHeader(platform: group.platform)
            if !header.isEmpty {
                DotMatrixSectionHeader(title: header, m: metrics)
                    .frame(minHeight: metrics.platform + 8)
            }
            ForEach(group.rows) { dep in
                DotMatrixRow(dep: dep, m: metrics)
                    .frame(minHeight: metrics.row + 10)
            }
        }
    }

    private var statusStrip: some View {
        DotMatrixStatusStrip(data: data, m: metrics)
            .frame(minHeight: metrics.status + 8)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Small widget (2×2): station + next departures + footer clock/ago
// Same hierarchy at small scale: station boldest, rows mid, ago smallest.
// ─────────────────────────────────────────────────────────────────────────────

struct SmallWidgetView: View {
    let data: WidgetData
    let clock: Date

    var body: some View {
        Group {
            if data.isEmpty {
                EmptyWidgetView(size: .small)
            } else {
                VStack(spacing: 2) {
                    // Corner-zone cells (first/last) get the deeper inset —
                    // same reasoning as the medium/large header/footer.
                    LitCell(vPad: 3, hPad: 14) {
                        HStack(spacing: 6) {
                            ModeIconView(mode: data.mode, size: 14)
                            Text(data.stationName)
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(WidgetTheme.amber)
                                .lineLimit(1)
                                .minimumScaleFactor(0.6)
                        }
                    }
                    .frame(minHeight: 24)
                    .widgetAccentable()

                    if data.departures.isEmpty {
                        NoDeparturesRow()
                            .frame(maxHeight: .infinity)
                    } else {
                        ForEach(data.departures.prefix(3)) { dep in
                            LitCell {
                                HStack(spacing: 4) {
                                    Text(dep.destination)
                                        .font(.system(size: 11))
                                        .foregroundColor(WidgetTheme.amber)
                                        .lineLimit(1)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Text(dep.isDue ? "Due" : dep.eta)
                                        .font(.system(size: 11.5, weight: .bold, design: .monospaced))
                                        .foregroundColor(dep.isDue ? DueRed : WidgetTheme.amber)
                                }
                            }
                            .frame(minHeight: 20)
                        }
                    }

                    LitCell(vPad: 3, hPad: 16) {
                        ZStack {
                            HStack {
                                StationlyMark(diameter: 11)
                                Spacer(minLength: 0)
                                LiveAgo(data: data, fontSize: 8)
                            }
                            LiveClock(clock: clock, fontSize: 12)
                        }
                    }
                    .frame(minHeight: 20)
                }
                .overlay(DotGrid().allowsHitTesting(false))
            }
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Shared sub-components
// ─────────────────────────────────────────────────────────────────────────────

struct NoDeparturesRow: View {
    var body: some View {
        Text("No departures right now")
            .font(.system(size: 12))
            .foregroundColor(WidgetTheme.textMuted)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 18)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Empty state (no station configured)
// ─────────────────────────────────────────────────────────────────────────────

enum WidgetSize { case small, medium, large }

struct EmptyWidgetView: View {
    let size: WidgetSize

    var body: some View {
        VStack(spacing: 10) {
            StationlyMark(diameter: 40)
            if size != .small {
                Text("Stationly")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(WidgetTheme.textPrimary)
                Text("Open the app to add a station")
                    .font(.system(size: 11))
                    .foregroundColor(WidgetTheme.textMuted)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Xcode Previews
// ─────────────────────────────────────────────────────────────────────────────

#if DEBUG
@available(iOS 17.0, *)
private let previewEntry = DepartureEntry(date: Date(), widgetData: .placeholder)
@available(iOS 17.0, *)
private let emptyEntry   = DepartureEntry(date: Date(), widgetData: .empty)

@available(iOS 17.0, *)
#Preview("Small — live", as: .systemSmall) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

@available(iOS 17.0, *)
#Preview("Medium — live", as: .systemMedium) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

@available(iOS 17.0, *)
#Preview("Large — live", as: .systemLarge) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

@available(iOS 17.0, *)
#Preview("Medium — empty", as: .systemMedium) {
    StationlyDepartureBoardWidget()
} timeline: { emptyEntry }
#endif
