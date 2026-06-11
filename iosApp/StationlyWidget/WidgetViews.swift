import SwiftUI
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

    static let medium = BoardMetrics(
        station: 16, platform: 13, row: 12.5, status: 10.5,
        clock: 15, ago: 8.5, icon: 18, logo: 16, cellRadius: 5, maxRows: 4)
    static let large = BoardMetrics(
        station: 19, platform: 15, row: 14.5, status: 12.5,
        clock: 18, ago: 10, icon: 22, logo: 19, cellRadius: 6, maxRows: 10)
}

private let DueRed = Color(red: 1.0, green: 0.32, blue: 0.32)

/// HH:mm formatter for the footer clock (24h, like Android's TextClock).
private let clockFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    return f
}()

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Shared marks
// ─────────────────────────────────────────────────────────────────────────────

/// A "lit cell" strip — the active row background from the Android board.
/// Soft-radiused so the cells flow with the widget's continuous corners while
/// the faint unlit-dot lattice keeps the LED-matrix read.
private struct LitCell<Content: View>: View {
    var vPad: CGFloat = 3
    var hPad: CGFloat = 9
    var radius: CGFloat = 5
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(.horizontal, hPad)
            .padding(.vertical, vPad)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(WidgetTheme.rowSurface)
            .overlay(DotGrid().allowsHitTesting(false))
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// The unlit-LED texture: a sparse dot lattice at very low alpha.
private struct DotGrid: View {
    var body: some View {
        Canvas { context, size in
            let pitch: CGFloat = 3
            let r: CGFloat = 0.55
            var y = pitch / 2
            while y < size.height {
                var x = pitch / 2
                while x < size.width {
                    context.fill(
                        Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)),
                        with: .color(.white.opacity(0.030))
                    )
                    x += pitch
                }
                y += pitch
            }
        }
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
            Image(uiImage: ui)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
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
        LitCell(vPad: 4, radius: m.cellRadius) {
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

/// "Severity : reason" — the line-status strip. iOS widgets can't marquee, so the
/// reason truncates with a fading tail rather than scrolling (Android marquees it).
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
                    .foregroundColor(WidgetTheme.statusColor(status: parts.severity))
                    .lineLimit(1)
                if !parts.reason.isEmpty {
                    Text(" : \(parts.reason)")
                        .font(.system(size: m.status))
                        .foregroundColor(WidgetTheme.amber.opacity(0.9))
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Spacer(minLength: 0)
            }
        }
    }
}

/// Footer, matching the Android board's bottom row: Stationly maker mark
/// (left) + wall clock (CENTER, near-station size) + live "M:SS ago" (right,
/// the smallest type on the board). Clock is minute-accurate via the
/// per-minute timeline entries; "ago" ticks per second via `.timer`.
struct DotMatrixFooter: View {
    let data: WidgetData
    let clock: Date
    let m: BoardMetrics
    var body: some View {
        LitCell(vPad: 3, radius: m.cellRadius) {
            ZStack {
                HStack {
                    StationlyMark(diameter: m.logo)
                    Spacer(minLength: 0)
                    LiveAgo(data: data, fontSize: m.ago)
                }
                Text(clockFormatter.string(from: clock))
                    .font(.system(size: m.clock, weight: .bold, design: .monospaced))
                    .foregroundColor(WidgetTheme.amber)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - The board (medium + large)
// Every cell is height-flexible (`maxHeight: .infinity` inside LitCell) with
// layoutPriority steering the share-out, so the board always fills the whole
// widget canvas — no dead band under the footer, no clipped rectangle.
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
        VStack(spacing: 2) {
            DotMatrixHeader(data: data, m: metrics)
                .frame(minHeight: metrics.station + 14)
                .layoutPriority(2)

            if data.departures.isEmpty {
                NoDeparturesRow()
                    .frame(maxHeight: .infinity)
            } else {
                let groups = groupedByPlatform(Array(data.departures.prefix(metrics.maxRows)))
                ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                    let header = data.platformHeader(platform: group.platform)
                    if !header.isEmpty {
                        DotMatrixSectionHeader(title: header, m: metrics)
                            .frame(minHeight: metrics.platform + 8)
                            .layoutPriority(1)
                    }
                    ForEach(group.rows) { dep in
                        DotMatrixRow(dep: dep, m: metrics)
                            .frame(minHeight: metrics.row + 10)
                    }
                }
            }

            DotMatrixStatusStrip(data: data, m: metrics)
                .frame(minHeight: metrics.status + 8)
                .layoutPriority(1)
            DotMatrixFooter(data: data, clock: clock, m: metrics)
                .frame(minHeight: metrics.clock + 12)
                .layoutPriority(2)
        }
        .padding(5)
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
                    LitCell(vPad: 3, radius: 5) {
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
                    .layoutPriority(2)
                    .widgetAccentable()

                    if data.departures.isEmpty {
                        NoDeparturesRow()
                            .frame(maxHeight: .infinity)
                    } else {
                        ForEach(data.departures.prefix(3)) { dep in
                            LitCell(radius: 5) {
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

                    LitCell(vPad: 2, radius: 5) {
                        ZStack {
                            HStack {
                                StationlyMark(diameter: 11)
                                Spacer(minLength: 0)
                                LiveAgo(data: data, fontSize: 8)
                            }
                            Text(clockFormatter.string(from: clock))
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundColor(WidgetTheme.amber)
                        }
                    }
                    .frame(minHeight: 20)
                    .layoutPriority(2)
                }
                .padding(4)
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
