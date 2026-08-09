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

    // An in-flight "loading" treatment was tried here (dimmed board) and
    // REMOVED after device testing: WidgetKit never rasterises a reload
    // requested from inside an AppIntent's `perform()`, so the state was
    // written and cleared without a single frame ever reaching the screen —
    // all cost, no signal. Refresh feedback is therefore the result itself:
    // the rows change and the "ago" timer snaps back to zero.
    var body: some View {
        if entry.isSkeleton {
            SkeletonBoardView(metrics: family == .systemLarge ? .large : .medium,
                              compact: family == .systemSmall)
        } else {
            switch family {
            case .systemSmall:
                SmallWidgetView(data: entry.widgetData, clock: entry.date)
            case .systemMedium:
                BoardWidgetView(data: entry.widgetData, clock: entry.date,
                                metrics: .medium, render: entry.render)
            case .systemLarge:
                BoardWidgetView(data: entry.widgetData, clock: entry.date,
                                metrics: .large, render: entry.render)
            @unknown default:
                BoardWidgetView(data: entry.widgetData, clock: entry.date,
                                metrics: .medium, render: entry.render)
            }
        }
    }
}

/// The board before it has anything to say: the real cell layout with a dim bar
/// where each piece of text will land.
///
/// ## Where this actually appears
/// `placeholder(in:)` only — a widget just added, or the home screen rebuilding
/// snapshots before any entry exists. It is NOT a refresh state and cannot
/// become one: WidgetKit rasterises nothing between a button tap and the
/// intent returning, so a skeleton drawn for that would land after the finished
/// board had already replaced it. See the note in `BoardWidgetView`.
///
/// ## Bars, not fake departures
/// The placeholder used to be four invented departures at a real station, which
/// is a small lie told at the one moment the user is deciding whether the widget
/// works. Bars say "this is the shape of what is coming" and cannot be misread
/// as a train that is not running.
///
/// Amber at low opacity rather than the grey of a typical app skeleton: this
/// panel is black with amber text, and grey bars would read as a foreign
/// component pasted over it rather than as the board's own lamps warming up.
struct SkeletonBoardView: View {
    let metrics: BoardMetrics
    var compact: Bool = false

    /// Bar heights track the type they stand in for, so the skeleton has the
    /// board's rhythm — a loud station line, a quieter header, even rows —
    /// rather than a stack of identical stripes.
    private func bar(_ height: CGFloat, width: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: height / 2, style: .continuous)
            .fill(WidgetTheme.amber.opacity(0.14))
            .frame(width: width, height: height)
    }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            VStack(spacing: 2) {
                LitCell(vPad: 4, hPad: 14, radius: metrics.cellRadius) {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(WidgetTheme.amber.opacity(0.14))
                            .frame(width: metrics.icon, height: metrics.icon)
                        bar(metrics.station * 0.8, width: w * 0.42)
                    }
                }
                .frame(minHeight: metrics.station + 14)

                if !compact {
                    LitCell(vPad: 2, radius: metrics.cellRadius) {
                        bar(metrics.platform * 0.8, width: w * 0.5)
                    }
                    .frame(minHeight: metrics.platform + 8)
                }

                // Destination bar left, ETA bar right — the row's real anatomy,
                // and the reason the skeleton reads as a departure board rather
                // than as generic loading furniture.
                ForEach(0..<max(1, metrics.maxRows - (compact ? 1 : 2)), id: \.self) { i in
                    LitCell(radius: metrics.cellRadius) {
                        HStack(spacing: 6) {
                            bar(metrics.row * 0.78, width: w * (i.isMultiple(of: 2) ? 0.46 : 0.38))
                            Spacer(minLength: 0)
                            bar(metrics.row * 0.78, width: w * 0.14)
                        }
                    }
                    .frame(minHeight: metrics.row + 10)
                }

                if geo.size.height >= 150 {
                    LitCell(vPad: 4, hPad: 20, radius: metrics.cellRadius) {
                        HStack(spacing: 6) {
                            bar(metrics.logo * 0.7, width: w * 0.12)
                            Spacer(minLength: 0)
                            bar(metrics.clock * 0.7, width: w * 0.18)
                            Spacer(minLength: 0)
                            bar(metrics.ago * 0.9, width: w * 0.14)
                        }
                    }
                    .frame(minHeight: metrics.clock + 12)
                }
            }
            .overlay(DotGrid().allowsHitTesting(false))
        }
        .containerBackground(WidgetTheme.background, for: .widget)
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
    // Large carries 6 departure rows. The old cap of 10 was never reachable —
    // it exceeded what the canvas can show once platform headers, the status
    // strip and the footer take their cells, so rows past ~6 were either
    // compressed or clipped. 6 is what actually fits at this type scale, and
    // it's also the retention target that keeps the board full of "Departed"
    // rows rather than emptying out.
    static let large = BoardMetrics(
        station: 19, platform: 15, row: 14.5, status: 12.5,
        clock: 18, ago: 10, icon: 22, logo: 22, cellRadius: 0, maxRows: 6,
        singlePlatform: false)

    /// Rows one BLOCK may show here, given the station's own setting.
    ///
    /// Two limits meeting, and they answer different questions. [maxRows] is
    /// what this canvas can physically draw — a medium widget has room for three
    /// rows and no preference changes that. `WidgetData.rowCap` is how deep the
    /// user asked their board to go, and it is the reason this exists at all:
    /// the widget used to hardcode three and therefore contradicted the home
    /// board for anyone who moved the slider off its default.
    ///
    /// The smaller of the two wins, which means the setting can make a widget
    /// SHALLOWER but never taller than its canvas.
    func rows(for rowCap: Int) -> Int { min(maxRows, rowCap) }
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
    /// The timeline entry's render date — drives the staleness colour below.
    let entryDate: Date
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
        .foregroundColor(staleColor)
        .lineLimit(1)
        .frame(maxWidth: 72, alignment: .trailing)
    }

    /// Freshness palette shared with the home board + dream (core
    /// `StaleColor`, same thresholds as the Android widget's AlarmManager
    /// colour fades): amber < 60s, grey < 180s, red beyond — anchored to the
    /// data's true age at THIS entry's date, so the per-minute timeline
    /// entries walk amber → grey → red exactly like Android.
    private var staleColor: Color {
        guard data.hasTimestamp else { return WidgetTheme.amber.opacity(0.85) }
        let age = entryDate.timeIntervalSince(data.lastUpdated)
        if age < 60  { return Color(red: 1.000, green: 0.702, blue: 0.000) } // #FFB300 amber
        if age < 180 { return Color(red: 0.533, green: 0.533, blue: 0.533) } // #888888 grey
        return         Color(red: 1.000, green: 0.231, blue: 0.188)          // #FF3B30 red
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Board cells
// ─────────────────────────────────────────────────────────────────────────────

/// Centered station lockup: real mode roundel + station name — the board's
/// loudest line, exactly like Android's station_lockup.
struct DotMatrixHeader: View {
    let data: WidgetData
    let m: BoardMetrics
    /// Forwarded to the refresh control so its retry glyph comes from the entry
    /// rather than from an App Group read inside `body`.
    var refreshFailed: Bool = false
    /// Entry time, forwarded to the refresh control so its "just updated"
    /// tick expires with the timeline entry rather than on a wall clock.
    var clock: Date = .distantPast

    /// Width reserved on BOTH sides: the button lives in the trailing one and
    /// an empty spacer balances the leading one, so the name stays centred.
    ///
    /// Sized as a TAP TARGET, not as the glyph. It was `icon * 0.72 + 6`
    /// (~19pt), and the button's hit area inside it was the glyph's own frame —
    /// ~13pt square. On device that is a target users miss most of the time,
    /// and every miss lands on the widget's own tap target, which opens the
    /// app: the worst possible outcome for a missed REFRESH. ~30pt square is
    /// still under Apple's 44pt ideal, but it is what this header row can give
    /// without costing the station name its legibility — the name yields ~22pt
    /// and it already scales and truncates.
    private var refreshSlot: CGFloat { m.icon + 12 }

    var body: some View {
        // Top cell lives in the widget's rounded-corner zone — deeper content
        // inset than mid-board rows so nothing clips against the corner mask.
        LitCell(vPad: 4, hPad: 14, radius: m.cellRadius) {
            // Three columns, not an overlay. An overlaid button sits OUTSIDE
            // the layout, so a long station name (large widget especially)
            // expanded straight underneath it and the two collided. Equal
            // side columns keep the name optically centred while physically
            // reserving the button's width, so overlap is impossible at any
            // family or name length.
            HStack(spacing: 8) {
                Color.clear
                    .frame(width: refreshSlot, height: 1)
                HStack(spacing: 8) {
                    ModeIconView(mode: data.mode, size: m.icon)
                    Text(data.stationName)
                        .font(.system(size: m.station, weight: .bold))
                        .foregroundColor(WidgetTheme.amber)
                        .lineLimit(1)
                        .minimumScaleFactor(0.65)
                        .truncationMode(.tail)
                }
                .frame(maxWidth: .infinity)
                // Interactive widgets need iOS 17, which is also this
                // extension's deployment target — so the availability check
                // never actually fails and is here only to satisfy the
                // compiler for `Button(intent:)`. The slot is reserved
                // unconditionally regardless, so centring can't shift.
                Group {
                    if #available(iOS 17.0, *) {
                        RefreshButton(size: m.icon * 0.72, clock: clock,
                                      stationId: data.stationId,
                                      lastRefreshFailed: refreshFailed)
                    }
                }
                .frame(width: refreshSlot)
            }
        }
        .widgetAccentable()
    }
}

/// Header refresh control — the iOS analog of Android's `btn_refresh`.
///
/// There is no "loading" variant, and that's a platform limit rather than an
/// omission: a widget is a sequence of static snapshots with no animation
/// loop, and WidgetKit won't render at all while the intent is running, so
/// neither a spinner nor a transient dim can ever appear (both were built and
/// verified dead on device).
///
/// What it CAN show is the last outcome, because that outlives `perform()`.
/// A failed refresh would otherwise be indistinguishable from a successful
/// one — the board just silently keeps its old rows.
@available(iOS 17.0, *)
private struct RefreshButton: View {
    let size: CGFloat
    /// The entry's wall-clock minute — NOT `Date()`. Comparing against the
    /// entry is what makes the success tick self-expiring: entry[0] (rendered
    /// straight after the tap) falls inside the window, entry[1] a minute
    /// later doesn't, so the tick reverts with no timer and no extra reload.
    let clock: Date
    /// Which board to refresh. With several widgets on the home screen this is
    /// the only thing distinguishing "refresh this one" from "refresh whichever
    /// board the legacy keys happen to hold".
    let stationId: String

    /// Carried from the entry, not read here.
    ///
    /// Keyed on THIS widget's station when the timeline was built: one tap
    /// refreshes every installed board and they do not all succeed together, so
    /// a station that timed out must ask for its retry on its own widget rather
    /// than on the one next to it showing perfectly fresh rows.
    ///
    /// A timeline is rebuilt whenever this flag changes — the refresh that sets
    /// it ends by reloading — so baking it into the entry cannot go stale in a
    /// way a live read would have caught.
    let lastRefreshFailed: Bool

    // A success tick was tried here and removed: swapping the arrow out on
    // the HAPPY path destroys the affordance — a checkmark where a control
    // used to be reads as un-tappable status, and success is the normal case
    // so it shouldn't alter the control at all. Confirmation of a successful
    // refresh belongs where it already lives: the rows change and the "ago"
    // timer snaps back to zero. Only the FAILURE case changes the glyph,
    // because that genuinely needs surfacing and still says "tap to retry".
    private var symbol: String {
        lastRefreshFailed ? "exclamationmark.arrow.circlepath" : "arrow.clockwise"
    }

    private var label: String {
        lastRefreshFailed ? "Refresh failed, tap to retry" : "Refresh departures"
    }

    var body: some View {
        Button(intent: RefreshBoardIntent(stationId: stationId)) {
            // The FRAME comes before the contentShape, and that ordering is the
            // fix for "hard to click": with the shape on the bare Image, the
            // hit area was the glyph's ~13pt square inside a ~30pt slot, and
            // most taps fell through to the widget's own target and opened the
            // app. Expanding to fill the reserved slot first makes the whole
            // slot the button — the same trick the pager arrows use, for the
            // same reason.
            Image(systemName: symbol)
                .font(.system(size: size, weight: .bold))
                .foregroundColor(lastRefreshFailed ? WidgetTheme.amberDim : WidgetTheme.amber)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// "Piccadilly: Platform 1 (Eastbound)" — centered amber section header.
struct DotMatrixSectionHeader: View {
    /// KMP's header ladder, widest first — see `HeaderLadder`.
    let variants: [String]
    let m: BoardMetrics
    var body: some View {
        LitCell(vPad: 2, radius: m.cellRadius) {
            HeaderLadder(variants: variants, size: m.platform)
        }
    }
}

/// A platform header that shrinks by REWORDING rather than by shrinking its
/// type — the widget's half of `MultiLineBoardProcessor.headerVariants`.
///
/// ## Why the widget needs this at all
/// A header carries several facts — lines, platform number, direction — and the
/// pager spends its width on two arrow slots and a "3/6" marker before the title
/// gets any. The two things SwiftUI does by default are both wrong here:
/// `minimumScaleFactor` makes the board's loudest row its smallest, and
/// truncation eats the right-hand end, which is where the platform number and
/// the direction live — the header would lose exactly what it exists to say.
///
/// So KMP sends "Northern Platform 1 (Westbound)" together with its shorter
/// forms, down to "Nor. Plat. 1", and this takes the first that fits. Every
/// rung's WORDING is decided in core, next to the home board that uses the same
/// ladder; the only judgement made here is measurement, which is the one thing
/// core cannot do.
///
/// `ViewThatFits` is the measurement: it proposes the available width to each
/// child in order and renders the first that does not overflow. Scaling stays on
/// as the floor beneath the last rung, for a canvas so narrow that even the
/// shortest form overflows.
private struct HeaderLadder: View {
    let variants: [String]
    let size: CGFloat

    var body: some View {
        // Written out rather than looped. `ViewThatFits` picks among its child
        // views, and whether a `ForEach` inside it resolves to N candidate
        // children or to ONE child containing N stacked Texts is a detail of
        // variadic-view flattening — if it went the second way the header would
        // render every rung at once, and a widget shows that to the user before
        // anyone finds out. Four explicit slots match the four rungs
        // `MultiLineBoardProcessor.headerVariants` produces.
        ViewThatFits(in: .horizontal) {
            fixedRung(0)
            fixedRung(1)
            fixedRung(2)
            fixedRung(3)
            // Last resort, deliberately outside the ladder: the shortest rung
            // allowed to scale and then ellipsise. Without a final child that
            // can compress, ViewThatFits would settle on the last fixed rung and
            // let it overflow the cell.
            rung(variants.last ?? "")
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    /// Rung `index`, or the shortest one available if the ladder is shorter than
    /// that — a group with a single rung (a locally-derived header, or a block a
    /// refresh invented) then offers that one string in every slot, and the
    /// scaling fallback below still catches it. Should core ever add a fifth
    /// rung, the extra is simply never tried: less shrinking, never wrong text.
    private func fixedRung(_ index: Int) -> some View {
        rung(variants.indices.contains(index) ? variants[index] : (variants.last ?? ""))
            .lineLimit(1)
            .fixedSize()
    }

    private func rung(_ text: String) -> some View {
        Text(text)
            .font(.system(size: size, weight: .bold))
            .foregroundColor(WidgetTheme.amber)
    }
}

/// The platform header with a step arrow at each end — the medium board's
/// answer to "this station has more than one platform".
///
/// ## Why arrows replaced a tappable header
/// The whole header used to be one button that cycled forwards. It worked, and
/// it could not say two things a user needs: which directions are available,
/// and how to get BACK. From the last platform the only route to the first was
/// to keep going forwards, and nothing on the widget hinted that this was
/// possible at all — a header that happens to be tappable looks exactly like a
/// header that is not.
///
/// ## The ends are not wrapped
/// An arrow that is present but dim means "nothing that way", which is the
/// entire reason to have two of them. Wrapping would make both arrows always
/// live and leave the user with no way to tell a three-platform board from a
/// two-platform one without tapping through it.
///
/// ## A dimmed arrow is still a Button, and it has to be
/// The first version drew it as plain content, reasoning that a control which
/// cannot act should not look tappable. On device that was actively wrong:
/// every non-interactive pixel of a widget belongs to the widget's own tap
/// target, so tapping the dim arrow LAUNCHED THE APP — the one thing a
/// disabled control must never do. It is a Button in both states now, and at
/// an end `WidgetBoardPage.move` clamps to the page it is already on, so the
/// tap is swallowed and nothing happens. "Disabled" is the dimming; the Button
/// is what makes it inert.
///
/// ## Layout
/// Both arrow slots are reserved unconditionally, so the title sits optically
/// centred and does not shift by half an arrow when it reaches an end. Same
/// device-proven trick as the station header's refresh slot: an overlaid button
/// sits outside the layout and a long platform name expands straight underneath
/// it.
struct PlatformPagerHeader: View {
    /// KMP's header ladder, widest first. This header is the tightest text on
    /// the widget — see `HeaderLadder` for why scaling and truncation are both
    /// the wrong answer to that.
    let variants: [String]
    let page: Int
    let groupCount: Int
    let stationId: String
    let m: BoardMetrics
    /// The transition the whole board is moving on, so the title travels with
    /// its rows instead of cross-fading while they slide.
    let slide: AnyTransition

    /// Sized as a TAP TARGET, not around the chevron.
    ///
    /// Was `platform + 10` (~23pt). The glyph already expands to fill whatever
    /// this is, so the slot IS the hit area — it was simply too small, the same
    /// problem the refresh button had. `platform + 24` puts it at ~37pt, close
    /// to Apple's 44pt guidance and comfortably thumb-sized.
    ///
    /// The width comes out of the title, which can afford it: `HeaderLadder`
    /// reworders rather than truncating, so a squeezed header steps down to
    /// "Nor. Plat. 1" instead of losing the platform number off the end.
    private var arrowSlot: CGFloat { m.platform + 24 }
    private var canGoBack: Bool { page > 0 }
    private var canGoForward: Bool { page < groupCount - 1 }

    var body: some View {
        // A single-platform board keeps the section header's original inset —
        // it is the common case and must render exactly as it always has. The
        // tighter one only applies where arrows need the width.
        LitCell(vPad: 2, hPad: groupCount > 1 ? 6 : 10, radius: m.cellRadius) {
            HStack(spacing: 4) {
                if groupCount > 1 {
                    arrow(back: true, enabled: canGoBack)
                        .frame(width: arrowSlot)
                }

                // The title and its page marker travel together as one label —
                // the marker is part of what changes, so it must not sit still
                // while the platform name slides out from under it.
                HStack(spacing: 6) {
                    HeaderLadder(variants: variants, size: m.platform)
                    if groupCount > 1 {
                        // Position, not an affordance. The arrows now say what
                        // can be tapped, so this is left to say only where you
                        // are — the "‣" that used to prefix it was the tap
                        // hint and has no job any more.
                        Text("\(page + 1)/\(groupCount)")
                            .font(.system(size: m.platform * 0.72, weight: .bold, design: .monospaced))
                            .foregroundColor(WidgetTheme.amberDim)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity)
                .id(page)
                .transition(slide)

                if groupCount > 1 {
                    arrow(back: false, enabled: canGoForward)
                        .frame(width: arrowSlot)
                }
            }
        }
    }

    @ViewBuilder
    private func arrow(back: Bool, enabled: Bool) -> some View {
        let glyph = Image(systemName: back ? "chevron.left" : "chevron.right")
            .font(.system(size: m.platform * 0.86, weight: .bold))
            .foregroundColor(enabled ? WidgetTheme.amber : WidgetTheme.amberDim.opacity(0.45))
            // The tap target is the whole slot, not the glyph: a chevron is
            // ~8pt of ink and a widget gets one chance at being hit.
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())

        if #available(iOS 17.0, *) {
            // A Button in BOTH states — see the note above. At an end the
            // intent clamps to the page it is already on, so the tap is
            // absorbed and the board does not move; drawn as plain content it
            // would fall through to the widget's own tap target and open the
            // app instead.
            Button(intent: MovePlatformPageIntent(
                stationId: stationId,
                forward: !back,
                groupCount: groupCount
            )) {
                glyph
            }
            .buttonStyle(.plain)
            .accessibilityLabel(back ? "Previous platform" : "Next platform")
            // Nothing to announce when there is nowhere to go, but the button
            // still has to exist to swallow the touch.
            .accessibilityHidden(!enabled)
        } else {
            glyph
        }
    }
}

/// Destination (left) + ETA (right). All amber; "Due" in red — matches the board.
///
/// [showLine] adds the bracketed line prefix the app's own board uses. It is
/// decided by the CALLER rather than read off the row, because the rule is a
/// property of the platform group and not of the departure: see
/// `groupMixesLines`.
struct DotMatrixRow: View {
    let dep: DepartureRow
    let m: BoardMetrics
    var showLine: Bool = false

    var body: some View {
        LitCell(radius: m.cellRadius) {
            // Three-step urgency ladder: DueRed = board now, amber = live,
            // amberDim = already gone. The WHOLE row dims, destination
            // included — a full-brightness destination beside a dim label
            // still reads as a live train, which defeats the point of
            // holding the row at all.
            let tint = dep.hasDeparted ? WidgetTheme.amberDim
                     : dep.isDue      ? DueRed
                     : WidgetTheme.amber
            let nameTint = dep.hasDeparted ? WidgetTheme.amberDim : WidgetTheme.amber
            HStack(spacing: 6) {
                // Bracketed short form — "(Cir.) Edgware Road", exactly as
                // `MultiLineBoardProcessor` renders it on the home board. The
                // brackets keep the line subordinate to the destination, which
                // is what the eye is actually scanning for; bold so one line can
                // still be picked out of a merged platform at a glance.
                if showLine, !dep.lineShort.isEmpty {
                    Text("(\(dep.lineShort))")
                        .font(.system(size: m.row, weight: .bold))
                        .foregroundColor(nameTint)
                        .lineLimit(1)
                        // Never squeezed: this is the row's most compressible
                        // text and also the one word that makes it legible.
                        .fixedSize()
                }
                Text(dep.destination)
                    .font(.system(size: m.row))
                    .foregroundColor(nameTint)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(dep.isDue ? "Due" : dep.eta)
                    .font(.system(size: m.row + 0.5, weight: .bold, design: .monospaced))
                    .foregroundColor(tint)
                    .lineLimit(1)
                    // Roll the digits instead of hard-cutting them. Every
                    // minute the timeline hands over a new entry with this
                    // label one lower, and a countdown that ticks reads as a
                    // live board where a jump cut reads as a redraw.
                    .contentTransition(.numericText(countsDown: true))
                    // The status label is the widest thing this column holds;
                    // fixedSize stops it wrapping or being squeezed, and the
                    // destination truncates to make room instead.
                    .fixedSize()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Board motion
// ─────────────────────────────────────────────────────────────────────────────

extension AnyTransition {
    /// New departures arriving — the board relighting rather than sliding.
    ///
    /// An LED panel does not scroll when its data changes; its lamps change
    /// state. Opacity is the honest expression of that, and the slight scale is
    /// what stops it reading as a dissolve: rows grow very slightly into place,
    /// which the eye registers as *arrival* rather than as a crossfade between
    /// two similar images. Asymmetric because the outgoing rows should simply
    /// go dark — scaling them out too makes the board appear to breathe.
    static var relight: AnyTransition {
        .asymmetric(
            insertion: .opacity.combined(with: .scale(scale: 0.96, anchor: .center)),
            removal: .opacity
        )
    }

    /// New departures landing — rows dropping in from above, split-flap style.
    ///
    /// ## Direction encodes CAUSE, and that is the whole point
    /// The board had one horizontal push for paging and this quiet opacity
    /// relight for new data, and on device the two were not distinguishable:
    /// both read as "the board changed", so a refresh looked like a page move
    /// that had gone somewhere unexpected.
    ///
    /// The rule now is a physical one a user learns without being told:
    ///  - **Horizontal = you moved.** You pressed an arrow; the board slides the
    ///    way you pointed.
    ///  - **Vertical = the world moved.** New times arrived; rows fall into
    ///    place and the old ones drop away beneath them.
    ///
    /// ## It does not contradict the "an LED panel does not scroll" rule
    /// [relight] argues that a dot-matrix panel changes its lamps rather than
    /// scrolling, and that still holds for an ambient tick. But this is the one
    /// moment the board is not a lamp array — it is a DEPARTURE board being
    /// re-set, and every real one on a concourse does that by flipping its rows
    /// over. The vertical motion is the mechanical memory of a Solari split-flap,
    /// which is the object this widget is pretending to be.
    ///
    /// Kept short and paired with opacity so it reads as a flip rather than as
    /// a list scrolling past.
    static var refreshFlip: AnyTransition {
        .asymmetric(
            insertion: .move(edge: .top).combined(with: .opacity),
            removal: .move(edge: .bottom).combined(with: .opacity)
        )
    }
}

/// Which transition the board is moving on, decided by WHAT changed.
///
/// A page move and a new payload both re-key the rows, and a stateless view
/// cannot tell which one it is being redrawn for. Three timestamps settle it:
/// the arrow press wins only when it is more recent than both the last refresh
/// and the data itself.
///
/// Compared as absolute times rather than against a window, because a page move
/// does NOT rebuild the timeline — WidgetKit re-renders the entry it already
/// holds, whose date can be up to a minute old. Anything phrased as "within N
/// seconds of this entry" would therefore mis-fire on exactly the interaction it
/// exists to catch.
private func boardTransition(_ render: BoardRenderState, updatedAt: Date, pageable: Bool) -> AnyTransition {
    if pageable, render.isPageMove {
        return .push(from: render.moveForward ? .trailing : .leading)
    }
    // A payload that landed in the last few seconds is a REFRESH the user is
    // watching — it gets the flip. An older timestamp means this render is an
    // ambient one (a per-minute tick, a snapshot rebuild) where rows should not
    // appear to fall: nothing arrived, the clock simply moved.
    let age = Date().timeIntervalSince(updatedAt)
    return age >= 0 && age < 6 ? .refreshFlip : .relight
}

/// How long the board takes to move, which is NOT one number.
///
/// A page move is direct manipulation: the user pressed an arrow and is waiting
/// on it, so every millisecond of animation is added to a latency they are
/// already feeling. WidgetKit's own wake-and-render sits in front of this and is
/// not ours to shorten — which makes the part that IS ours worth spending
/// carefully. Short and snappy, so the board arrives rather than glides.
///
/// New departures landing is ambient: nobody is waiting on it, it happens while
/// the user is reading, and an abrupt swap there reads as a glitch. That one
/// keeps the slower smooth curve.
private func boardAnimation(_ render: BoardRenderState, pageable: Bool) -> Animation {
    guard pageable, render.isPageMove else { return .smooth(duration: 0.28) }
    return .snappy(duration: 0.16)
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
            // Three real columns rather than a ZStack. The clock is a `.timer`
            // Text, which expands greedily — stacked on top of the mark/"ago"
            // row it could grow straight over them. Laying all three out as
            // siblings makes collision impossible, and equal-width outer
            // columns keep the clock optically centred as before.
            HStack(spacing: 6) {
                StationlyMark(diameter: m.logo)
                    .frame(maxWidth: .infinity, alignment: .leading)
                // CAPPED frame, never `fixedSize()`: a `.timer` Text has no
                // determinate ideal width (its digits are system-driven), so
                // asking for one collapses the layout and the widget renders
                // blank. A hard cap is the same tactic LiveAgo already uses.
                LiveClock(clock: clock, fontSize: m.clock)
                    .frame(maxWidth: m.clock * 6)
                LiveAgo(data: data, entryDate: clock, fontSize: m.ago)
                    .frame(maxWidth: .infinity, alignment: .trailing)
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
    /// Why this render is happening, resolved when the timeline was built —
    /// never looked up here. See `BoardRenderState`.
    var render: BoardRenderState = BoardRenderState()

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
                DotMatrixHeader(data: data, m: metrics, refreshFailed: render.refreshFailed,
                                clock: clock)
                    .frame(minHeight: metrics.station + 14)

                // ── `invalidatableContent` was tried here and REMOVED ──
                //
                // The idea was sound and the execution was not reachable. It
                // marks content as pending so the system shimmers it while a
                // button's intent runs — the sanctioned way to acknowledge a tap
                // that a hand-drawn spinner cannot achieve. On device it cost
                // more than it bought, in two ways worth recording so it is not
                // reintroduced:
                //
                //  1. **Applied to a `Group`, the modifier lands on EVERY child
                //     individually.** Each row, header and strip then shimmers on
                //     its own schedule, which reads as the board flashing
                //     repeatedly rather than as one panel loading.
                //  2. **Inside a `Button`'s label it broke the button.** Taps
                //     stopped reaching `RefreshBoardIntent` and fell through to
                //     the widget's own tap target, so the refresh control opened
                //     the app instead — the one thing it must never do.
                //
                // The honest position stands: a widget cannot show its own
                // in-flight state, because WidgetKit does not rasterise anything
                // between the tap and `perform()` returning. Making that window
                // SHORT is the only lever there is, which is why the board's
                // animation below is tuned rather than decorated.
                if !data.hasDepartures {
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
            // What makes the refresh READ as a refresh. Without an explicit
            // animation the row transitions below are applied instantly and the
            // board simply swaps its contents, which on device is the "flicker"
            // this replaces: the pixels change and nothing tells the eye that
            // anything was fetched.
            //
            // Keyed on page + payload timestamp, so it fires on an arrow press
            // and on new departures landing, and NOT on the per-minute entries —
            // all 61 of those are built from one payload and share the key, so
            // countdowns tick in place (the digits roll on their own, see
            // DotMatrixRow's contentTransition).
            .animation(
                boardAnimation(render, pageable: metrics.singlePlatform && data.groups.count > 1),
                value: motionKey
            )
            .overlay(DotGrid().allowsHitTesting(false))
        }
    }

    /// The single value every board animation is keyed on — see the modifier
    /// above for why these two things and nothing else.
    private var motionKey: String {
        // Only the paging board has a page; the large one renders every group,
        // so including it there would be a constant that never changes anyway.
        let page = metrics.singlePlatform
            ? WidgetBoardPage.clamp(render.page, groupCount: data.groups.count)
            : 0
        return "\(page)-\(Int(data.lastUpdated.timeIntervalSince1970))"
    }

    /// Medium: ONE platform group per render — one section header, up to
    /// `maxRows` rows. A second group would cost a second header cell and
    /// blow the 6-cell budget. When the group can't fill all row slots the
    /// status strip backfills one, so a quiet board never shows dead space.
    ///
    /// Multi-platform stations: WidgetKit can't scroll (static snapshots), so
    /// where Android's widget scrolls a `rows_list` the medium board PAGES.
    /// The header carries an arrow at each end (iOS 17+ `Button(intent:)`),
    /// and the board slides in the direction the arrow points. On a
    /// single-platform station it renders exactly as it always did — no
    /// arrows, no indicator, nothing to say.
    @ViewBuilder
    private var primaryPlatformSection: some View {
        let groups = data.groups
        if !groups.isEmpty {
            let page = WidgetBoardPage.clamp(render.page, groupCount: groups.count)
            let group = groups[page]
            // KMP's own header text — "Northern Platform 1 Westbound", "Bus 39,
            // 34 Stop N". Never assembled here: `MultiLineBoardProcessor.headerFor`
            // is the one implementation, and this widget showing a different
            // string from the home board is what that rule exists to prevent.
            let header = group.header

            // The board moves as ONE thing. Header and rows carry the same
            // transition and the same identity inputs, so they enter together
            // rather than each animating on its own schedule — the difference
            // between a board sliding across and a stack of strips arriving one
            // after another.
            //
            // Identity is `page` plus the data's TIMESTAMP, deliberately NOT the
            // row's own id: a DepartureRow's id is a fresh UUID on every decode,
            // so keying on it would re-insert every row on every minute tick and
            // animate a countdown as though the platform had changed. The
            // timestamp only moves when genuinely new departures land, so the 61
            // pre-rendered per-minute entries — all built from one payload —
            // share it and tick in place.
            let stamp = Int(data.lastUpdated.timeIntervalSince1970)
            let slide = boardTransition(render, updatedAt: data.lastUpdated,
                                        pageable: groups.count > 1)
            // One prefix decision for the whole group, taken by KMP from every
            // row the block HAS rather than the handful that fit — otherwise a
            // platform would gain and lose its prefixes as trains tick off the
            // bottom of it.
            let mixesLines = group.mixesLines

            if !header.isEmpty || groups.count > 1 {
                PlatformPagerHeader(
                    variants: group.headerVariants,
                    page: page,
                    groupCount: groups.count,
                    stationId: data.stationId,
                    m: metrics,
                    slide: slide
                )
                .frame(minHeight: metrics.platform + 8)
            }
            // The station's own depth, clamped to the canvas — see
            // `BoardMetrics.rows(for:)`. `group.rows` is RESERVES, so this is
            // where display depth is decided, exactly as the home board decides
            // it in `BoardTicker.tick`.
            let slots = metrics.rows(for: data.rowCap)
            ForEach(Array(group.rows.prefix(slots).enumerated()), id: \.offset) { index, dep in
                DotMatrixRow(dep: dep, m: metrics, showLine: mixesLines)
                    .frame(minHeight: metrics.row + 10)
                    .id("\(page)-\(index)-\(stamp)")
                    .transition(slide)
            }
            // Spare CANVAS cells, not spare slots: the strip exists to fill dead
            // space, and a board the user capped at two rows on a three-row
            // canvas has exactly as much of it as a quiet platform does.
            if min(group.rows.count, slots) < metrics.maxRows {
                statusStrip
            }
        }
    }

    /// Large: every platform group, Android-style.
    @ViewBuilder
    private var allPlatformsSection: some View {
        let groups = budgetedGroups
        let stamp = Int(data.lastUpdated.timeIntervalSince1970)
        ForEach(Array(groups.enumerated()), id: \.offset) { index, group in
            // KMP's header, same as the medium board — see there.
            let header = group.header
            if !header.isEmpty {
                DotMatrixSectionHeader(variants: group.headerVariants, m: metrics)
                    .frame(minHeight: metrics.platform + 8)
            }
            // Decided per group by KMP: this is the case the prefix exists for,
            // since large shows several platforms at once and a mixed one is
            // otherwise unreadable.
            let mixesLines = group.mixesLines
            ForEach(Array(group.rows.enumerated()), id: \.offset) { row, dep in
                DotMatrixRow(dep: dep, m: metrics, showLine: mixesLines)
                    .frame(minHeight: metrics.row + 10)
                    // Same rule as the medium board — keyed on the payload so
                    // new departures animate and the per-minute entries do not.
                    // There is no paging here, so this is only ever the
                    // data-landed case: a flip when it just landed, a quiet
                    // relight on an ambient redraw.
                    .id("\(index)-\(row)-\(stamp)")
                    .transition(boardTransition(render, updatedAt: data.lastUpdated,
                                                pageable: false))
            }
        }
    }

    /// The blocks the large board can fit, trimmed to a WHOLE-BOARD row budget.
    ///
    /// `metrics.maxRows` is the number of departure cells the canvas has, shared
    /// across every block — not a per-block cap, which is KMP's `rowCap` and has
    /// already been applied. Spending it block by block is what keeps the board
    /// filling top-down: platform one takes what it needs and the next one gets
    /// the remainder, rather than every platform being trimmed to the same depth
    /// and the bottom of the panel left empty.
    ///
    /// A block that would get zero rows is dropped rather than rendered as a
    /// lone header with nothing under it.
    private var budgetedGroups: [BoardGroup] {
        var remaining = metrics.maxRows
        // Per-block depth as the user set it, bounded by the whole-board budget.
        // Without it one deep platform ate all six cells and the platforms below
        // it never appeared — and a user who asked for two rows per platform got
        // six of the first one.
        let perBlock = metrics.rows(for: data.rowCap)
        var out: [BoardGroup] = []
        for group in data.groups {
            guard remaining > 0 else { break }
            let rows = Array(group.rows.prefix(min(remaining, perBlock)))
            guard !rows.isEmpty else { continue }
            remaining -= rows.count
            out.append(group.with(rows: rows))
        }
        return out
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

                    if !data.hasDepartures {
                        NoDeparturesRow()
                            .frame(maxHeight: .infinity)
                    } else {
                        ForEach(Array(data.firstDepartures(3).enumerated()), id: \.offset) { index, dep in
                            LitCell {
                                // Same three-step tint as the medium/large row.
                                HStack(spacing: 4) {
                                    Text(dep.destination)
                                        .font(.system(size: 11))
                                        .foregroundColor(dep.hasDeparted ? WidgetTheme.amberDim
                                                                         : WidgetTheme.amber)
                                        .lineLimit(1)
                                        .truncationMode(.tail)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Text(dep.isDue ? "Due" : dep.eta)
                                        .font(.system(size: 11.5, weight: .bold, design: .monospaced))
                                        .foregroundColor(dep.hasDeparted ? WidgetTheme.amberDim
                                                        : dep.isDue      ? DueRed
                                                                         : WidgetTheme.amber)
                                        .lineLimit(1)
                                        .contentTransition(.numericText(countsDown: true))
                                        .fixedSize()
                                }
                            }
                            .frame(minHeight: 20)
                            // No line prefix at this size, deliberately: the
                            // small family has no platform header, so there is
                            // no group for "does this platform mix lines" to be
                            // a question about — and at 11pt the prefix would
                            // take its width straight out of the destination.
                            .id("\(index)-\(Int(data.lastUpdated.timeIntervalSince1970))")
                            .transition(.relight)
                        }
                    }

                    LitCell(vPad: 3, hPad: 16) {
                        // Same three-column fix as the medium/large footer —
                        // most needed here, since this is the narrowest canvas
                        // and a greedy `.timer` clock had the least room to
                        // grow before running over the mark and the "ago".
                        HStack(spacing: 4) {
                            StationlyMark(diameter: 11)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            // Capped, not fixedSize — see the medium/large
                            // footer note; a `.timer` Text has no determinate
                            // ideal width and blanks the render if asked.
                            LiveClock(clock: clock, fontSize: 12)
                                .frame(maxWidth: 68)
                            LiveAgo(data: data, entryDate: clock, fontSize: 8)
                                .frame(maxWidth: .infinity, alignment: .trailing)
                        }
                    }
                    .frame(minHeight: 20)
                }
                // Same driver as the medium/large board — see BoardWidgetView.
                .animation(.smooth(duration: 0.28), value: data.lastUpdated)
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
