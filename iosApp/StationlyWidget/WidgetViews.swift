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
// MARK: - Medium widget  (4×2)
// Station header + amber separator + up to 4 departure rows + status bar
// ─────────────────────────────────────────────────────────────────────────────

struct MediumWidgetView: View {
    let data: WidgetData

    var body: some View {
        ZStack {
            WidgetTheme.background

            if data.isEmpty {
                EmptyWidgetView(size: .medium)
            } else {
                VStack(alignment: .leading, spacing: 0) {

                    // ── Header panel ─────────────────────────────────────────
                    MediumWidgetHeader(data: data)

                    // ── 2px amber separator ──────────────────────────────────
                    Rectangle()
                        .fill(WidgetTheme.amber)
                        .frame(height: 2)

                    // ── Departure rows ───────────────────────────────────────
                    if data.departures.isEmpty {
                        NoDeparturesRow()
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(data.departures.prefix(4).enumerated()), id: \.offset) { index, dep in
                                MediumDepartureRow(departure: dep, isFirst: index == 0)
                                if index < min(data.departures.count, 4) - 1 {
                                    Divider()
                                        .background(Color.white.opacity(0.04))
                                        .padding(.horizontal, 14)
                                }
                            }
                        }
                    }

                    Spacer(minLength: 0)

                    // ── Status bar ───────────────────────────────────────────
                    if !data.status.isEmpty {
                        StatusBar(status: data.status)
                    }
                }
            }
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }
}

struct MediumWidgetHeader: View {
    let data: WidgetData

    var body: some View {
        HStack(alignment: .center, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                Text(data.stationName)
                    .font(.system(size: 13, weight: .black))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                Text(data.lineName.capitalized + " Line")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(WidgetTheme.textSecondary)
            }

            Spacer()

            // Live indicator
            HStack(spacing: 4) {
                Circle()
                    .fill(WidgetTheme.goodService)
                    .frame(width: 6, height: 6)
                Text("LIVE")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundColor(WidgetTheme.textMuted)
                    .kerning(1.2)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(WidgetTheme.surface)
    }
}

struct MediumDepartureRow: View {
    let departure: DepartureRow
    let isFirst: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 8) {

            // ── Platform / stop-letter badge ─────────────────────────────────
            PlatformBadge(platform: departure.platform, stopLetter: departure.stopLetter, size: .medium)

            // ── Destination ──────────────────────────────────────────────────
            Text(departure.destination)
                .font(.system(size: 13, weight: isFirst ? .semibold : .regular))
                .foregroundColor(isFirst ? WidgetTheme.textPrimary : WidgetTheme.textSecondary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

            // ── ETA ──────────────────────────────────────────────────────────
            ETALabel(eta: departure.eta, isDue: departure.isDue, fontSize: 14)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(isFirst ? WidgetTheme.amber.opacity(0.06) : Color.clear)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Large widget  (4×4)
// Full departure board — up to 8 rows with column headers + footer
// ─────────────────────────────────────────────────────────────────────────────

struct LargeWidgetView: View {
    let data: WidgetData

    var body: some View {
        ZStack {
            WidgetTheme.background

            if data.isEmpty {
                EmptyWidgetView(size: .large)
            } else {
                VStack(alignment: .leading, spacing: 0) {

                    // ── Full header ──────────────────────────────────────────
                    LargeWidgetHeader(data: data)

                    // ── 2px amber separator ──────────────────────────────────
                    Rectangle()
                        .fill(WidgetTheme.amber)
                        .frame(height: 2)

                    // ── Column header row ────────────────────────────────────
                    DepartureBoardColumnHeader()

                    // ── Departure rows ───────────────────────────────────────
                    if data.departures.isEmpty {
                        NoDeparturesRow()
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(data.departures.prefix(8).enumerated()), id: \.offset) { index, dep in
                                LargeDepartureRow(departure: dep, rowIndex: index)
                                if index < min(data.departures.count, 8) - 1 {
                                    Divider()
                                        .background(Color.white.opacity(0.05))
                                        .padding(.horizontal, 14)
                                }
                            }
                        }
                    }

                    Spacer(minLength: 0)

                    // ── Footer: status + last-updated timestamp ───────────────
                    WidgetFooter(data: data)
                }
            }
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }
}

struct LargeWidgetHeader: View {
    let data: WidgetData

    var body: some View {
        HStack(alignment: .center, spacing: 10) {

            // Stationly "S" roundel
            ZStack {
                Circle()
                    .fill(WidgetTheme.amber)
                    .frame(width: 30, height: 30)
                Text("S")
                    .font(.system(size: 15, weight: .black))
                    .foregroundColor(.black)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(data.stationName)
                    .font(.system(size: 15, weight: .black))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                Text(data.lineName.capitalized + " Line")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(WidgetTheme.textSecondary)
            }

            Spacer()

            // Live indicator
            HStack(spacing: 4) {
                Circle()
                    .fill(WidgetTheme.goodService)
                    .frame(width: 6, height: 6)
                Text("LIVE")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundColor(WidgetTheme.textMuted)
                    .kerning(1.5)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(WidgetTheme.surface)
    }
}

struct DepartureBoardColumnHeader: View {
    var body: some View {
        HStack(spacing: 0) {
            // Matches the width of PlatformBadge
            Color.clear.frame(width: 22)

            Text("DESTINATION")
                .font(.system(size: 8, weight: .bold))
                .foregroundColor(WidgetTheme.textMuted)
                .kerning(1.0)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 8)

            Text("ETA")
                .font(.system(size: 8, weight: .bold))
                .foregroundColor(WidgetTheme.textMuted)
                .kerning(1.0)
                .frame(minWidth: 50, alignment: .trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .background(Color.white.opacity(0.03))
    }
}

struct LargeDepartureRow: View {
    let departure: DepartureRow
    let rowIndex: Int

    var isHighlighted: Bool { rowIndex == 0 }

    var body: some View {
        HStack(alignment: .center, spacing: 8) {

            // ── Platform / stop-letter badge ─────────────────────────────────
            PlatformBadge(platform: departure.platform, stopLetter: departure.stopLetter, size: .large)

            // ── Destination ──────────────────────────────────────────────────
            Text(departure.destination)
                .font(.system(size: 14, weight: isHighlighted ? .semibold : .regular))
                .foregroundColor(isHighlighted ? .white : WidgetTheme.textSecondary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

            // ── ETA ──────────────────────────────────────────────────────────
            ETALabel(eta: departure.eta, isDue: departure.isDue, fontSize: 15)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(isHighlighted ? WidgetTheme.amber.opacity(0.07) : Color.clear)
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
private let previewEntry = DepartureEntry(date: Date(), widgetData: .placeholder)
private let emptyEntry   = DepartureEntry(date: Date(), widgetData: .empty)

#Preview("Small — live data", as: .systemSmall) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

#Preview("Small — empty", as: .systemSmall) {
    StationlyDepartureBoardWidget()
} timeline: { emptyEntry }

#Preview("Medium — live data", as: .systemMedium) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

#Preview("Medium — empty", as: .systemMedium) {
    StationlyDepartureBoardWidget()
} timeline: { emptyEntry }

#Preview("Large — live data", as: .systemLarge) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

#Preview("Large — empty", as: .systemLarge) {
    StationlyDepartureBoardWidget()
} timeline: { emptyEntry }
#endif
