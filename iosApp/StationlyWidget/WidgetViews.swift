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
        case .systemSmall:
            SmallWidgetView(data: entry.widgetData)
        case .systemMedium:
            MediumWidgetView(data: entry.widgetData)
        case .systemLarge:
            LargeWidgetView(data: entry.widgetData)
        @unknown default:
            MediumWidgetView(data: entry.widgetData)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Small widget  (2×2)
// Station name + next 2 departures + line name footer
// ─────────────────────────────────────────────────────────────────────────────

struct SmallWidgetView: View {
    let data: WidgetData

    var body: some View {
        ZStack {
            WidgetTheme.background

            if data.isEmpty {
                EmptyWidgetView(size: .small)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    // ── Header ──────────────────────────────────────────────
                    HStack(spacing: 5) {
                        Circle()
                            .fill(WidgetTheme.amber)
                            .frame(width: 7, height: 7)
                        Text(data.stationName)
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(WidgetTheme.amber)
                            .lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 12)
                    .padding(.bottom, 7)

                    // ── Amber rule ───────────────────────────────────────────
                    Rectangle()
                        .fill(WidgetTheme.amber.opacity(0.35))
                        .frame(height: 1)
                        .padding(.horizontal, 12)

                    // ── Departure rows ───────────────────────────────────────
                    VStack(spacing: 0) {
                        if data.departures.isEmpty {
                            Text("No departures")
                                .font(.system(size: 11))
                                .foregroundColor(WidgetTheme.textMuted)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 10)
                        } else {
                            ForEach(data.departures.prefix(2)) { dep in
                                SmallDepartureRow(departure: dep)
                            }
                        }
                    }
                    .padding(.top, 4)

                    Spacer(minLength: 0)

                    // ── Line name footer ────────────────────────────────────
                    Text(data.lineName.capitalized)
                        .font(.system(size: 9, weight: .medium))
                        .foregroundColor(WidgetTheme.textMuted)
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
                .foregroundColor(WidgetTheme.textPrimary)
                .lineLimit(1)
            Spacer(minLength: 4)
            Text(departure.isDue ? "Due" : departure.eta)
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundColor(WidgetTheme.etaColor(eta: departure.eta, isDue: departure.isDue))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
        .background(WidgetTheme.rowSurface.opacity(0.45))
        .padding(.vertical, 1)   // hairline gap between rows
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Dot-matrix board (shared by medium + large)
// Matches the Android dot-matrix widget: TfL roundel + station header, platform-
// grouped sections (centered amber), all-amber dest/eta rows, status + "ago".
// ─────────────────────────────────────────────────────────────────────────────

private let DueRed = Color(red: 1.0, green: 0.32, blue: 0.32)

/// Group a flat departures list by platform, preserving first-seen order.
private func groupedByPlatform(_ deps: [DepartureRow]) -> [(title: String, rows: [DepartureRow])] {
    var order: [String] = []
    var map: [String: [DepartureRow]] = [:]
    for d in deps {
        let key = (d.platform.isEmpty || d.platform.lowercased() == "unknown") ? "" : "Platform \(d.platform)"
        if map[key] == nil { map[key] = []; order.append(key) }
        map[key]?.append(d)
    }
    return order.map { (title: $0, rows: map[$0] ?? []) }
}

struct DotMatrixHeader: View {
    let data: WidgetData
    var body: some View {
        HStack(spacing: 6) {
            // TfL roundel mark
            ZStack {
                Circle().stroke(WidgetTheme.amber, lineWidth: 2.5).frame(width: 13, height: 13)
                Rectangle().fill(WidgetTheme.amber).frame(width: 16, height: 2.5)
            }
            Text(data.stationName)
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(WidgetTheme.amber)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .padding(.horizontal, 10)
        .background(WidgetTheme.rowSurface)
    }
}

struct DotMatrixSectionHeader: View {
    let title: String
    var body: some View {
        Text(title)
            .font(.system(size: 12, weight: .bold))
            .foregroundColor(WidgetTheme.amber)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 2)
            .background(WidgetTheme.rowSurface)
    }
}

struct DotMatrixRow: View {
    let dep: DepartureRow
    var body: some View {
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
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(WidgetTheme.rowSurface)
    }
}

struct DotMatrixFooter: View {
    let data: WidgetData
    var body: some View {
        HStack(spacing: 6) {
            Text(data.status.isEmpty ? "Good Service" : data.status)
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(WidgetTheme.amber)
                .lineLimit(1)
            Spacer(minLength: 4)
            Text(relativeAgo(from: data.lastUpdated))
                .font(.system(size: 10).italic())
                .foregroundColor(WidgetTheme.amber.opacity(0.85))
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(WidgetTheme.rowSurface)
    }
    private func relativeAgo(from date: Date) -> String {
        let s = max(0, Int(-date.timeIntervalSinceNow))
        return "\(s / 60):" + String(format: "%02d", s % 60) + " ago"
    }
}

/// The dot-matrix board panel — station header, grouped platform sections, footer.
struct DotMatrixBoard: View {
    let data: WidgetData
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
                        if !group.title.isEmpty {
                            DotMatrixSectionHeader(title: group.title)
                        }
                        ForEach(group.rows) { dep in
                            DotMatrixRow(dep: dep)
                        }
                    }
                }
                Spacer(minLength: 0)
            }

            DotMatrixFooter(data: data)
        }
        .padding(6)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Medium widget  (4×2)
// ─────────────────────────────────────────────────────────────────────────────

struct MediumWidgetView: View {
    let data: WidgetData
    var body: some View {
        ZStack {
            WidgetTheme.background
            if data.isEmpty {
                EmptyWidgetView(size: .medium)
            } else {
                DotMatrixBoard(data: data, maxRows: 4)
            }
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Large widget  (4×4)
// ─────────────────────────────────────────────────────────────────────────────

struct LargeWidgetView: View {
    let data: WidgetData
    var body: some View {
        ZStack {
            WidgetTheme.background
            if data.isEmpty {
                EmptyWidgetView(size: .large)
            } else {
                DotMatrixBoard(data: data, maxRows: 9)
            }
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Shared sub-components
// ─────────────────────────────────────────────────────────────────────────────

// MARK: PlatformBadge

enum BadgeSize { case medium, large }

/// Renders a circular amber badge for a bus stop letter, a plain platform
/// number in muted text, or an empty spacer when the platform is unknown.
struct PlatformBadge: View {
    let platform: String
    let stopLetter: String?
    let size: BadgeSize

    private var diameter: CGFloat   { size == .large ? 18 : 20 }
    private var fontSize: CGFloat   { size == .large ?  9 : 10 }
    private var numberSize: CGFloat { size == .large ?  9 :  9 }

    var body: some View {
        Group {
            if let stop = stopLetter, !stop.isEmpty {
                // Bus stop letter — filled amber circle
                Text(stop)
                    .font(.system(size: fontSize, weight: .bold))
                    .foregroundColor(.black)
                    .frame(width: diameter, height: diameter)
                    .background(WidgetTheme.amber)
                    .clipShape(Circle())
            } else if !platform.isEmpty && platform.lowercased() != "unknown" {
                // Platform number — muted text, fixed width for alignment
                Text(platform)
                    .font(.system(size: numberSize, weight: .semibold))
                    .foregroundColor(WidgetTheme.textMuted)
                    .frame(width: diameter, alignment: .center)
            } else {
                // Nothing to show — preserve alignment spacing
                Color.clear
                    .frame(width: diameter, height: diameter)
            }
        }
    }
}

// MARK: ETALabel

/// Monospaced ETA countdown, coloured by urgency.
struct ETALabel: View {
    let eta: String
    let isDue: Bool
    let fontSize: CGFloat

    var body: some View {
        Text(isDue ? "Due" : eta)
            .font(.system(size: fontSize, weight: .bold, design: .monospaced))
            .foregroundColor(WidgetTheme.etaColor(eta: eta, isDue: isDue))
            .frame(minWidth: 50, alignment: .trailing)
    }
}

// MARK: StatusBar

/// Single-line service status row with coloured indicator dot.
/// Used at the bottom of the medium widget.
struct StatusBar: View {
    let status: String

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(WidgetTheme.statusColor(status: status))
                .frame(width: 6, height: 6)
            Text(status)
                .font(.system(size: 10, weight: .medium))
                .foregroundColor(WidgetTheme.textSecondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white.opacity(0.04))
    }
}

// MARK: WidgetFooter

/// Status dot + label on the left, relative timestamp on the right.
/// Used at the bottom of the large widget.
struct WidgetFooter: View {
    let data: WidgetData

    var body: some View {
        HStack(spacing: 0) {
            if !data.status.isEmpty {
                HStack(spacing: 5) {
                    Circle()
                        .fill(WidgetTheme.statusColor(status: data.status))
                        .frame(width: 5, height: 5)
                    Text(data.status)
                        .font(.system(size: 9))
                        .foregroundColor(WidgetTheme.textSecondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 4)

            Text(relativeTime(from: data.lastUpdated))
                .font(.system(size: 9))
                .foregroundColor(WidgetTheme.textMuted)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Color.white.opacity(0.03))
    }

    private func relativeTime(from date: Date) -> String {
        let seconds = Int(-date.timeIntervalSinceNow)
        guard seconds >= 0 else { return "Just now" }
        if seconds < 60   { return "Just now" }
        if seconds < 3600 { return "\(seconds / 60)m ago" }
        return "\(seconds / 3600)h ago"
    }
}

// MARK: NoDeparturesRow

struct NoDeparturesRow: View {
    var body: some View {
        Text("No departures available")
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
            // Stationly roundel
            ZStack {
                Circle()
                    .fill(WidgetTheme.amber.opacity(0.15))
                    .frame(width: 44, height: 44)
                Text("S")
                    .font(.system(size: 22, weight: .black))
                    .foregroundColor(WidgetTheme.amber)
            }

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
#Preview("Small — live data", as: .systemSmall) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

@available(iOS 17.0, *)
#Preview("Small — empty", as: .systemSmall) {
    StationlyDepartureBoardWidget()
} timeline: { emptyEntry }

@available(iOS 17.0, *)
#Preview("Medium — live data", as: .systemMedium) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

@available(iOS 17.0, *)
#Preview("Medium — empty", as: .systemMedium) {
    StationlyDepartureBoardWidget()
} timeline: { emptyEntry }

@available(iOS 17.0, *)
#Preview("Large — live data", as: .systemLarge) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

@available(iOS 17.0, *)
#Preview("Large — empty", as: .systemLarge) {
    StationlyDepartureBoardWidget()
} timeline: { emptyEntry }
#endif
