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
        case .systemMedium: MediumWidgetView(data: entry.widgetData, clock: entry.date)
        case .systemLarge:  LargeWidgetView(data: entry.widgetData, clock: entry.date)
        @unknown default:   MediumWidgetView(data: entry.widgetData, clock: entry.date)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Dot-matrix tokens + shared marks
// Mirrors the Android dot-matrix departure board (widget_departure_board.xml):
// TfL amber on near-black, "lit cell" row backgrounds, roundel + station header,
// line-prefixed platform sections, amber rows / red Due, status strip, footer
// roundel + live "ago".
// ─────────────────────────────────────────────────────────────────────────────

private let DueRed = Color(red: 1.0, green: 0.32, blue: 0.32)

/// HH:mm:ss formatter for the footer clock (24h, like Android's TextClock).
private let clockFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    return f
}()

/// A "lit cell" strip — the active row background from the Android board.
/// SQUARE corners: the Android original is a tiled pixel bitmap with no
/// per-row radius (only the widget container is rounded), plus a faint
/// unlit-dot grid so the rows read as LED matrix cells.
private struct LitCell<Content: View>: View {
    var vPad: CGFloat = 3
    var hPad: CGFloat = 8
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(.horizontal, hPad)
            .padding(.vertical, vPad)
            .frame(maxWidth: .infinity)
            .background(WidgetTheme.rowSurface)
            .overlay(DotGrid().allowsHitTesting(false))
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

/// TfL roundel: amber ring crossed by a horizontal bar. Pure SwiftUI so it scales
/// crisply at any size (matches the Compose `TflRoundel` in the in-app board).
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
    var fontSize: CGFloat = 10
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
// MARK: - Dot-matrix board (shared by medium + large)
// ─────────────────────────────────────────────────────────────────────────────

/// Centered station lockup: mode-tinted roundel + station name (the constant
/// strip shown on every Stationly surface — the roundel colour carries the
/// transport-mode identity, exactly like Android's tinted mode_icon).
struct DotMatrixHeader: View {
    let data: WidgetData
    var body: some View {
        LitCell(vPad: 5) {
            HStack(spacing: 7) {
                TflRoundelMark(color: WidgetTheme.modeColor(data.mode), diameter: 15)
                Text(data.stationName)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
    }
}

/// "Piccadilly: Platform 1 (Eastbound)" — centered amber section header.
struct DotMatrixSectionHeader: View {
    let title: String
    var body: some View {
        LitCell(vPad: 2) {
            Text(title)
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(WidgetTheme.amber)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }
}

/// Destination (left) + ETA (right). All amber; "Due" in red — matches the board.
struct DotMatrixRow: View {
    let dep: DepartureRow
    var body: some View {
        LitCell {
            HStack(spacing: 8) {
                Text(dep.destination)
                    .font(.system(size: 13))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(dep.isDue ? "Due" : dep.eta)
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundColor(dep.isDue ? DueRed : WidgetTheme.amber)
            }
        }
    }
}

/// "Severity : reason" — the line-status strip. iOS widgets can't marquee, so the
/// reason truncates with a fading tail rather than scrolling (Android marquees it).
struct DotMatrixStatusStrip: View {
    let data: WidgetData
    private var parts: (severity: String, reason: String) {
        let raw = data.status.isEmpty ? "Good Service" : data.status
        if let r = raw.range(of: ":") {
            return (String(raw[..<r.lowerBound]).trimmingCharacters(in: .whitespaces),
                    String(raw[r.upperBound...]).trimmingCharacters(in: .whitespaces))
        }
        return (raw, "")
    }
    var body: some View {
        LitCell(vPad: 2) {
            HStack(spacing: 0) {
                Text(parts.severity)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                if !parts.reason.isEmpty {
                    Text(" : \(parts.reason)")
                        .font(.system(size: 11))
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
/// (left) + wall clock (CENTER) + live "M:SS ago" together (right).
/// The clock is minute-accurate: the timeline pre-renders one entry per
/// minute (free — local renders don't consume the refresh budget), and the
/// "ago" ticks per second via the `.timer` style.
struct DotMatrixFooter: View {
    let data: WidgetData
    let clock: Date
    var body: some View {
        LitCell(vPad: 3) {
            ZStack {
                HStack {
                    StationlyMark(diameter: 14)
                    Spacer(minLength: 0)
                    LiveAgo(data: data, fontSize: 10)
                }
                Text(clockFormatter.string(from: clock))
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundColor(WidgetTheme.amber)
            }
        }
    }
}

/// The Stationly brand disc (red circle + white S) — Android's footer
/// stationly_logo ImageView.
struct StationlyMark: View {
    var diameter: CGFloat = 14
    var body: some View {
        ZStack {
            Circle().fill(WidgetTheme.stationlyRed)
            Text("S")
                .font(.system(size: diameter * 0.62, weight: .black))
                .foregroundColor(.white)
        }
        .frame(width: diameter, height: diameter)
    }
}

/// The dot-matrix board panel — station header, grouped platform sections,
/// status strip, footer. Shared by medium + large; `maxRows` caps departures.
struct DotMatrixBoard: View {
    let data: WidgetData
    let clock: Date
    let maxRows: Int

    var body: some View {
        VStack(spacing: 2) {
            DotMatrixHeader(data: data)

            if data.departures.isEmpty {
                NoDeparturesRow()
                Spacer(minLength: 0)
            } else {
                let groups = groupedByPlatform(Array(data.departures.prefix(maxRows)))
                VStack(spacing: 2) {
                    ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                        let header = data.platformHeader(platform: group.platform)
                        if !header.isEmpty {
                            DotMatrixSectionHeader(title: header)
                        }
                        ForEach(group.rows) { dep in
                            DotMatrixRow(dep: dep)
                        }
                    }
                }
                Spacer(minLength: 0)
            }

            DotMatrixStatusStrip(data: data)
            DotMatrixFooter(data: data, clock: clock)
        }
        .padding(6)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Small widget (2×2): station + next 2 departures + live "ago"
// ─────────────────────────────────────────────────────────────────────────────

struct SmallWidgetView: View {
    let data: WidgetData
    let clock: Date

    var body: some View {
        ZStack {
            WidgetTheme.background
            if data.isEmpty {
                EmptyWidgetView(size: .small)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    // Header: mode-tinted roundel + station
                    HStack(spacing: 5) {
                        TflRoundelMark(color: WidgetTheme.modeColor(data.mode), diameter: 12)
                        Text(data.stationName)
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(WidgetTheme.amber)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 12)
                    .padding(.bottom, 7)

                    Rectangle()
                        .fill(WidgetTheme.amber.opacity(0.35))
                        .frame(height: 1)
                        .padding(.horizontal, 12)

                    VStack(spacing: 2) {
                        if data.departures.isEmpty {
                            Text("No departures")
                                .font(.system(size: 11))
                                .foregroundColor(WidgetTheme.textMuted)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 10)
                        } else {
                            ForEach(data.departures.prefix(3)) { dep in
                                SmallDepartureRow(departure: dep)
                            }
                        }
                    }
                    .padding(.top, 5)

                    Spacer(minLength: 0)

                    // Footer: line name (left) + live "ago" (right)
                    HStack(spacing: 4) {
                        Text(data.lineName.capitalized)
                            .font(.system(size: 9, weight: .medium))
                            .foregroundColor(WidgetTheme.textMuted)
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        LiveAgo(data: data, fontSize: 9)
                    }
                    .padding(.horizontal, 12)
                    .padding(.bottom, 10)
                }
            }
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }
}

struct SmallDepartureRow: View {
    let departure: DepartureRow

    var body: some View {
        HStack(spacing: 4) {
            Text(departure.destination)
                .font(.system(size: 11))
                .foregroundColor(WidgetTheme.amber)
                .lineLimit(1)
            Spacer(minLength: 4)
            Text(departure.isDue ? "Due" : departure.eta)
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundColor(departure.isDue ? DueRed : WidgetTheme.amber)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .background(WidgetTheme.rowSurface.opacity(0.55))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Medium widget (4×2)
// ─────────────────────────────────────────────────────────────────────────────

struct MediumWidgetView: View {
    let data: WidgetData
    let clock: Date
    var body: some View {
        ZStack {
            WidgetTheme.background
            if data.isEmpty {
                EmptyWidgetView(size: .medium)
            } else {
                DotMatrixBoard(data: data, clock: clock, maxRows: 4)
            }
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Large widget (4×4)
// ─────────────────────────────────────────────────────────────────────────────

struct LargeWidgetView: View {
    let data: WidgetData
    let clock: Date
    var body: some View {
        ZStack {
            WidgetTheme.background
            if data.isEmpty {
                EmptyWidgetView(size: .large)
            } else {
                DotMatrixBoard(data: data, clock: clock, maxRows: 9)
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
            TflRoundelMark(diameter: 40)
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
