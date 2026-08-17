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
    /// One board, three type scales. The small family used to have a view of its
    /// own here, and that separation is what let it quietly lose features the
    /// others had — the refresh control and the platform pager were written once,
    /// in the shared board, and the small widget simply did not call it. It now
    /// renders the same view at its own metrics: whatever the board learns to do,
    /// every family gets.
    var body: some View {
        let metrics = BoardMetrics.forFamily(family)
        Group {
            if entry.isSkeleton {
                SkeletonBoardView(metrics: metrics)
            } else {
                BoardWidgetView(data: entry.widgetData, clock: entry.date,
                                metrics: metrics, render: entry.render,
                                fallback: entry.fallback)
            }
        }
        // ── Where a tap on the board goes ──
        //
        // Every widget used to open the app to whatever it was last showing, so
        // a home screen with three stations on it had three buttons that did the
        // same thing. This carries the station and the app pages or scrolls to
        // it.
        //
        // It does not fight the buttons: the pager arrows and the refresh
        // control are `Button(intent:)` and keep their own hit regions,
        // `widgetURL` claims everything else. That is the same rule §6 of the
        // design doc records from device testing — only now the leftover pixels
        // lead somewhere specific rather than merely opening the app.
        //
        // Resolved when the TIMELINE was built, never here — see
        // `BoardRenderState.deepLink`. It is the RENDERED station, which since
        // `StationResolver` landed is always the configured one too: a widget
        // never borrows another station's board, so the two cannot differ.
        //
        // A widget in the removed state carries no station id, so
        // `StationlyDeepLink.board` returns nil and a tap simply opens the app.
        // That is the right answer for it — the station it names is gone, so
        // there is nothing to open, and the fix is a touch-and-hold rather than
        // a tap.
        .widgetURL(entry.render.deepLink)
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
                LitCell(vPad: BoardMetrics.cornerCellVPad, hPad: metrics.headerPad) {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(WidgetTheme.amber.opacity(0.14))
                            .frame(width: metrics.icon, height: metrics.icon)
                        bar(metrics.station * 0.8, width: w * 0.42)
                    }
                }
                .frame(minHeight: metrics.headerMinHeight)

                // The SAME cell count the real board draws — a header and its
                // rows per section, every family. The skeleton is the thing on
                // screen immediately before the board replaces it, so a
                // different number of cells here means the widget visibly
                // re-lays itself out the moment its first data lands.
                ForEach(0..<metrics.sectionCount, id: \.self) { _ in
                    LitCell(vPad: 2, hPad: metrics.rowPad) {
                        bar(metrics.platform * 0.8, width: w * 0.5)
                    }
                    .frame(minHeight: metrics.platform + 8)

                    // Destination bar left, ETA bar right — the row's real
                    // anatomy, and the reason the skeleton reads as a departure
                    // board rather than as generic loading furniture.
                    ForEach(0..<metrics.rowsPerSection, id: \.self) { i in
                        LitCell(hPad: metrics.rowPad) {
                            HStack(spacing: 6) {
                                bar(metrics.row * 0.78, width: w * (i.isMultiple(of: 2) ? 0.46 : 0.38))
                                Spacer(minLength: 0)
                                bar(metrics.row * 0.78, width: w * 0.14)
                            }
                        }
                        .frame(minHeight: metrics.row + 10)
                    }
                }

                if metrics.statusPolicy == .always {
                    LitCell(vPad: 2, hPad: metrics.rowPad) {
                        HStack(spacing: 0) {
                            bar(metrics.status * 0.8, width: w * 0.34)
                            Spacer(minLength: 0)
                        }
                    }
                    .frame(minHeight: metrics.status + 8)
                }

                // Measured on the FULL canvas, not on the inset content — the
                // threshold separates SE-class widgets (141/148pt) from every
                // other family, and that is a property of the device rather
                // than of how much of it this board chooses to use.
                if geo.size.height >= 150 {
                    // Mirrors `DotMatrixFooter` column for column, including
                    // the empty trailing one on small — the skeleton is what is
                    // on screen immediately before the real footer replaces it,
                    // so a different arrangement here is a visible jump.
                    LitCell(vPad: BoardMetrics.cornerCellVPad, hPad: metrics.footerPad) {
                        HStack(spacing: 6) {
                            bar(metrics.logo * 0.7, width: w * 0.12)
                            Spacer(minLength: 0)
                            if metrics.showsClock {
                                bar(metrics.clock * 0.7, width: w * 0.18)
                                Spacer(minLength: 0)
                            }
                            bar(metrics.ago * 0.9, width: w * 0.14)
                            if !metrics.showsClock { Spacer(minLength: 0) }
                        }
                    }
                    .frame(minHeight: metrics.footerMinHeight,
                           maxHeight: metrics.footerFlexible
                               ? nil : metrics.footerMinHeight)
                }
            }
            .overlay(DotGrid().allowsHitTesting(false))
            // Full-bleed, like the board it stands in for — see the note at the
            // bottom of `BoardWidgetView.board`.
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Type scale
//
// The hierarchy, top to bottom:
//   station > platform header > departure rows > status strip > "ago".
//
// **The station is exactly ONE STEP above the platform header** — 15/14 on
// medium, 17/16 on large — not the three-and-a-half point gap it had. The
// ladder's job is to say which line outranks which, and it does that at one
// step per rung; anything wider just makes the top of a small canvas expensive.
// Owner's words: *"make sure the station font is not too big, it should just be
// 1 size bigger than the platform rows."*
//
// ## The footer clock is NOT on that ladder, and that is deliberate
// It sits at the STATION's size, bold — which makes it far larger than the
// departure row above it, and that is the entire point. An attempt to fold it
// into the "everything else" band (row size, 12.5 on medium) made the footer
// indistinguishable from the last departure: same face, same size, same lit
// surface, so the two cells read as one and the 2pt bezel gap between them
// stopped registering as a gap at all.
//
// The in-app board is the reference and it has always done this: `BoardFooter`
// in `Board.kt` puts its clock at **19sp bold against 15sp rows**, a clear step
// up, on its own lit chip. A widget footer that reads quieter than the app's is
// the same board disagreeing with itself. It does not outrank the station name,
// which is where it stops.
//
// One struct per family so the large widget breathes while medium stays dense.
// ─────────────────────────────────────────────────────────────────────────────

/// How a family spends its platform blocks.
enum BoardLayout {
    /// One pager for the whole board: one block on screen, arrows to the rest.
    /// Small and medium — neither has the height for a second header cell.
    case paged
    /// Two stacked pagers, each with its own blocks — see `WidgetData.sections`.
    /// Large only, because it is the only canvas that can pay for two headers
    /// and still show a useful number of rows under each.
    case split
}

/// When the line-status strip earns a cell.
enum StatusPolicy {
    /// It takes the last departure cell whenever the platform on screen cannot
    /// fill it — so a quiet board says something useful in the space instead of
    /// resizing itself around a hole, and a busy one is never pushed out by it.
    case backfill
    /// Always, as a row of its own. Large only: it is the one canvas with the
    /// height to keep a cell reserved for a line that is running perfectly.
    case always
}

struct BoardMetrics {
    let station: CGFloat      // station name — biggest
    let platform: CGFloat     // platform section header — 2nd
    let row: CGFloat          // departure destination/eta — 3rd
    let status: CGFloat       // line status — slightly under rows
    /// Footer wall clock — a ROW-sized element, not a headline. It also sizes
    /// the footer cell on the small family, which no longer draws a clock at all
    /// (see [showsClock]).
    let clock: CGFloat
    let ago: CGFloat          // live "ago" — smallest
    let icon: CGFloat         // mode roundel beside the station name
    let logo: CGFloat         // Stationly maker mark in the footer
    let maxRows: Int
    /// How many pagers this canvas can carry — see [BoardLayout].
    let layout: BoardLayout
    /// See [StatusPolicy].
    let statusPolicy: StatusPolicy
    /// Content inset of the corner-zone cells, where the widget's own corner
    /// mask intrudes on the content: header and footer are deeper than the
    /// mid-board rows, and the small family's are shallower than the others'
    /// because it has a third of the width to spend.
    let headerPad: CGFloat
    let footerPad: CGFloat
    /// Width reserved for the refresh control in the station header — and, when
    /// [centresStationName], reserved a second time on the opposite side so the
    /// name stays optically centred.
    ///
    /// **A TAP TARGET, not a glyph.** It was `icon * 0.72 + 6` (~19pt) with the
    /// button's hit area inside it being the glyph's own ~13pt square. On device
    /// that is a target users miss most of the time, and every miss lands on the
    /// widget's own tap target, which OPENS THE APP — the worst possible outcome
    /// for a missed refresh. ~30pt is still under Apple's 44pt ideal, but it is
    /// what the header can give without costing the station name its legibility.
    let refreshSlot: CGFloat

    /// Width of one pager chevron's tap target — same reasoning and same device
    /// finding as [refreshSlot]; it was `platform + 10` (~23pt) on medium. The
    /// glyph expands to fill whatever this is, so the slot IS the hit area.
    ///
    /// The width comes out of the title, which can afford it: `HeaderLadder`
    /// rewords rather than truncating, so a squeezed header steps down to
    /// "Nor. Plat. 1" instead of losing the platform number off the end.
    let arrowSlot: CGFloat

    /// Content inset of the MID-BOARD cells — departures, the status strip, the
    /// empty and message cells.
    ///
    /// ## This is where "breathing room" lives, and it took two goes to find out
    /// The first attempt at the owner's "more breathing room from the edges" put
    /// a margin AROUND the panel (a `boardInset` + `ContainerRelativeShape`
    /// clip). Wrong reading, corrected on device: **the dot-matrix board must
    /// cover the widget end to end.** The panel is the widget; a margin round it
    /// makes it a card in a frame, which is exactly what §3.1 removed. What
    /// needed room was the TEXT inside the cells, which was sitting on a flat
    /// 10pt inset at every family — comfortable on a 2×2, mean on a 360pt-wide
    /// board where the destination had 340pt of cell and used all of it.
    ///
    /// Small keeps 10: it has a third of the width and the destination needs
    /// every point of it.
    let rowPad: CGFloat

    /// Whether the station name is optically CENTRED by reserving a matching
    /// empty column opposite the refresh button.
    ///
    /// False on small, and that is the one place the reservation cost more than
    /// centring is worth: 22pt of slot plus 8pt of spacing is ~30pt of a ~130pt
    /// header, held empty, while the name it is balancing gets cut to three
    /// characters and an ellipsis. On a 2×2 the name is the whole point of the
    /// header, so it takes the left-hand space and reads from the edge — the
    /// roundel anchors that edge, so it does not look adrift.
    let centresStationName: Bool

    /// Whether the footer carries the live HH:MM:SS wall clock.
    ///
    /// False on small, by product decision: a 2×2 footer has to fit the maker
    /// mark, a wall clock and the "ago" timer across a third of the width, and
    /// the clock is the one of the three the user can read off the phone's own
    /// status bar without the widget's help. Dropping it gives the "ago" timer —
    /// which nothing else on the device says — the middle of the footer to
    /// itself. See `DotMatrixFooter`.
    let showsClock: Bool

    /// Vertical padding inside the header and footer cells (`LitCell(vPad:)`).
    ///
    /// Named because the height floors below are DERIVED from it. It used to be
    /// a literal 4 at the two call sites and a hand-picked constant in the
    /// floors, which is how the two drifted apart — see [footerMinHeight].
    static let cornerCellVPad: CGFloat = 4

    /// The footer cell's height floor.
    ///
    /// ## It has to include the cell's own padding, and it did not
    /// `LitCell` pads 4pt top and bottom, so a floor of H offers its tenants
    /// H − 8. The floors were written as "tallest thing + a bit" with the
    /// padding forgotten: small asked for `max(ago, logo) + 7` = 18pt, which
    /// hands an 11pt maker mark a 10pt box. `StationlyMark` demanded a hard
    /// frame, so it overflowed and the cell's clip took the top and bottom off
    /// it — a quietly cropped logo, on the family where it is most visible
    /// because it is one of only two things in the footer.
    ///
    /// Derived now, so the arithmetic cannot drift again: the tallest tenant,
    /// plus the padding that will be put around it, plus a point of air. The
    /// tenants are the maker mark and whichever of the clock / "ago" this family
    /// draws — text sits a little above its point size, hence the extra.
    var footerMinHeight: CGFloat {
        max(showsClock ? clock : ago, logo) + 2 * Self.cornerCellVPad + 2
    }

    /// The station header cell's height floor.
    ///
    /// `station + 14` is the tuned number and every family still resolves to
    /// exactly it — the header was NOT overflowing, unlike the footer, and
    /// re-deriving it from its tenants would have grown it by 3–5pt on the wider
    /// families for no reason and taken that straight out of the departure rows.
    ///
    /// The `max` is a guard rather than a change: it trips only if the roundel is
    /// ever sized past what the tuned floor can hold, which is the failure the
    /// footer actually had. 27 / 29 / 31 today, against roundel minimums of
    /// 22 / 26 / 30.
    var headerMinHeight: CGFloat {
        max(station + 14, icon + 2 * Self.cornerCellVPad)
    }

    /// How much width the "ago" timer may occupy in the footer.
    ///
    /// Tight (72pt) when it is one of three tenants; generous when it is centred
    /// and alone on small, so a two-digit-minute reading — "12:07 ago" — has
    /// somewhere to go. A cap rather than `fixedSize()`, for the reason
    /// `LiveClock` documents: a `.timer` Text has no determinate ideal width, so
    /// asking for one collapses the layout and the widget renders blank.
    var agoWidth: CGFloat { showsClock ? 72 : ago * 9 }

    /// Whether the footer takes a share of the board's surplus height.
    ///
    /// **Lowering the floor alone does almost nothing**, and that is worth
    /// spelling out because it looks like it should. Every cell here is
    /// `maxHeight: .infinity` at equal priority (§3.6), so leftover height is
    /// split EQUALLY: cutting the small footer's floor by 5pt returns 5pt to a
    /// pool of six cells and buys the departure rows less than a point each. The
    /// footer simply grows back into most of what it gave up.
    ///
    /// So on small the footer is PINNED to its floor rather than floored at it,
    /// and the whole surplus goes to the cells above. It stays a constant, so
    /// this does not reintroduce the reflow §6.2 removed — the cell count and
    /// every height is still fixed for the family, whatever the data does.
    ///
    /// Medium and large stay flexible: their footer holds a wall clock at the
    /// station's size and has earned the room.
    var footerFlexible: Bool { showsClock }

    // Small carries the same board as its bigger siblings and pays for it in
    // type size: station + platform header + 3 rows + footer is ~140pt of cell
    // minimums against a 155–170pt canvas (and the footer sheds below 150pt,
    // which is what keeps SE-class 141pt canvases honest). The slots are
    // narrower than medium's because two chevrons and a refresh button have to
    // come out of a third of the width — see `HeaderLadder` for how the header
    // text survives that.
    //
    // The platform header used to be SMALLER than the departures here (10
    // against 11) — the ladder inverted on the one family with the least room to
    // spare, so the 2×2 board had no visible hierarchy at all. Rows are
    // unchanged; the two header rungs moved up around them.
    static let small = BoardMetrics(
        station: 13, platform: 12, row: 11, status: 9.5,
        clock: 11, ago: 9, icon: 14, logo: 10, maxRows: 3,
        layout: .paged, statusPolicy: .backfill,
        headerPad: 10, footerPad: 12, refreshSlot: 22, arrowSlot: 24,
        rowPad: 10, centresStationName: false, showsClock: false)
    // Medium's content budget: station + platform + 3 rows + footer is
    // ~156pt of cell minimums — the most a fixed ~155–170pt medium canvas
    // can carry without compressing cells below their minimums (the
    // "crumbled" look). The status strip only appears when a departure
    // slot is spare; on <150pt canvases the footer is shed too.
    //
    static let medium = BoardMetrics(
        station: 15, platform: 14, row: 12.5, status: 10.5,
        clock: 15, ago: 8.5, icon: 18, logo: 18, maxRows: 3,
        layout: .paged, statusPolicy: .backfill,
        headerPad: 14, footerPad: 20, refreshSlot: 30, arrowSlot: 37,
        rowPad: 14, centresStationName: true, showsClock: true)
    // Large carries 6 departure rows. The old cap of 10 was never reachable —
    // it exceeded what the canvas can show once platform headers, the status
    // strip and the footer take their cells, so rows past ~6 were either
    // compressed or clipped. 6 is what actually fits at this type scale, and
    // it's also the retention target that keeps the board full of "Departed"
    // rows rather than emptying out. Two sections split them 3 and 3, which is
    // the same shape the family already drew for a two-platform station.
    static let large = BoardMetrics(
        station: 17, platform: 16, row: 14.5, status: 12.5,
        clock: 17, ago: 10, icon: 22, logo: 18, maxRows: 6,
        layout: .split, statusPolicy: .always,
        headerPad: 14, footerPad: 20, refreshSlot: 34, arrowSlot: 39,
        rowPad: 16, centresStationName: true, showsClock: true)

    /// The scale for a family. Unknown families take medium: it is the one shape
    /// that works on any canvas WidgetKit is likely to invent, since it neither
    /// assumes the height for two sections nor the narrowness of small's slots.
    static func forFamily(_ family: WidgetFamily) -> BoardMetrics {
        switch family {
        case .systemSmall: return .small
        case .systemLarge: return .large
        default:           return .medium
        }
    }

    /// How many platform sections this canvas stacks — see [BoardLayout]. The
    /// board draws exactly this many headers whether or not it has platforms to
    /// put in them; see `BoardWidgetView.platformSections`.
    var sectionCount: Int { layout == .split ? 2 : 1 }

    /// Departure cells one section has before the user's own depth is applied.
    /// The canvas maximum, which is what a board with no station to read a
    /// setting from (the skeleton) draws.
    var rowsPerSection: Int { max(1, maxRows / sectionCount) }

    /// Rows one SECTION may show, given the station's own setting.
    ///
    /// Three limits meeting, and they answer different questions. [maxRows] is
    /// what this canvas can physically draw — a medium widget has room for three
    /// rows and no preference changes that. [sectionCount] divides that between
    /// the stacked pagers, so a large board showing two platforms gives each
    /// three cells instead of letting the first take all six and starve the
    /// second. `WidgetData.rowCap` is how deep the user asked their board to go,
    /// and it is the reason this exists at all: the widget used to hardcode three
    /// and therefore contradicted the home board for anyone who moved the slider
    /// off its default.
    ///
    /// The smallest wins, which means the setting can make a widget SHALLOWER
    /// but never taller than its canvas.
    func rows(for rowCap: Int) -> Int { max(1, min(rowsPerSection, rowCap)) }
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
/// `hPad` is the cell's own content inset and is the ONLY breathing room this
/// board has — there is no margin outside the panel and there must not be one
/// (see the note at the foot of `BoardWidgetView.board`). Mid-board cells take
/// `BoardMetrics.rowPad`; the header and footer take their deeper corner-zone
/// pads, because those two sit where the widget's corner mask intrudes.
///
/// NO per-cell texture here: the unlit-dot lattice is ONE `DotGrid` overlay on
/// the whole board (one image per entry instead of ~16 × 61 ≈ 1,000 refs in
/// the archive — and an LED panel's unlit dots span the full panel anyway,
/// gaps included). Archive-poisoning history of this board:
/// docs/IOS_WIDGET_DESIGN.md §3.4.
private struct LitCell<Content: View>: View {
    /// The header and footer override this — see `BoardMetrics.cornerCellVPad`,
    /// which is the number their height floors are derived from.
    var vPad: CGFloat = 3
    var hPad: CGFloat = 10
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(.horizontal, hPad)
            .padding(.vertical, vPad)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(WidgetTheme.rowSurface)
            // A plain `Rectangle`, and there is no corner-radius knob any more.
            // `BoardMetrics.cellRadius` existed to feed a `RoundedRectangle`
            // here and was 0 in all three families — §3.1 requires squared
            // cells, so it was a setting that could only ever be wrong — while
            // costing a `RoundedRectangle` built per cell per timeline entry.
            //
            // The clip itself STAYS. It is not decoration: a cell whose content
            // is taller than the height the board can give it would otherwise
            // spill into the 2pt bezel gap and touch its neighbour. Nothing
            // should rely on that (the height floors are sized so it cannot
            // happen — see `footerMinHeight`), but the panel's cells must read
            // as clean rectangles even when a future tenant misbehaves.
            .clipShape(Rectangle())
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
            // `maxWidth/maxHeight`, not a hard `width/height`. A fixed frame
            // DEMANDS its size: in a footer cell shorter than `diameter` plus
            // the cell's padding it simply overflowed and was cropped by the
            // cell's clip, which is what was happening to the 22pt mark in a
            // ~19pt content box. The floors are sized so this cannot arise (see
            // `BoardMetrics.footerMinHeight`), and a mark that shrinks to fit is
            // the belt to that braces — a slightly small logo is a cosmetic
            // nothing, a logo with its top and bottom sliced off is not.
            .frame(maxWidth: diameter, maxHeight: diameter)
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
    /// Where the element sits in its capped frame. Trailing in the three-column
    /// footer; CENTRE on the small family, where it is the footer's only text —
    /// see `DotMatrixFooter`.
    var alignment: Alignment = .trailing
    /// The frame this is allowed to occupy. 72pt in the three-column footer,
    /// where it is one of three tenants; WIDER when it is centred and alone on
    /// small, so a two-digit-minute reading ("12:07 ago") has somewhere to go
    /// rather than being clipped in the one place there is room to spare.
    var maxWidth: CGFloat = 72
    /// Whether the freshness ladder applies — see [staleColor]. False while the
    /// network is closed, where an ageing reading is correct rather than wrong.
    var freshnessMatters: Bool = true
    var body: some View {
        Group {
            if data.hasTimestamp {
                // No `monospacedDigit()`. The in-app board's own "ago"
                // (`Board.kt`, `BoardFooter`) is plain system type, and the two
                // are read within seconds of each other — see `LiveClock`.
                (Text(data.lastUpdated, style: .timer) + Text(" ago"))
                    // Derived from `alignment`, not a two-way test against
                    // `.center`. Written as `alignment == .center ? .center :
                    // .trailing` it silently right-aligned anything else — so a
                    // future `.leading` call site would have laid out leading
                    // and drawn trailing, which is the kind of disagreement that
                    // only shows up as "the footer looks wrong on one family".
                    .multilineTextAlignment(Self.textAlignment(for: alignment))
            } else {
                Text("—")
            }
        }
        // Board face, upright. This was `.italic()`, the only slanted text on
        // the panel — a signage board has one machine cutting every glyph, and
        // an italic "ago" read as a caption pasted onto it. Size already says
        // this is the quietest thing here; it is the smallest type on the board.
        .font(WidgetTheme.font(fontSize))
        .foregroundColor(staleColor)
        .lineLimit(1)
        .frame(maxWidth: maxWidth, alignment: alignment)
    }

    /// The text alignment matching a frame alignment. Both are needed: the frame
    /// places the (greedily expanded) Text, and this places the digits inside it.
    private static func textAlignment(for alignment: Alignment) -> TextAlignment {
        switch alignment {
        case .leading:  return .leading
        case .center:   return .center
        default:        return .trailing
        }
    }

    /// Freshness palette shared with the home board + dream (core
    /// `StaleColor`, same thresholds as the Android widget's AlarmManager
    /// colour fades): amber < 60s, grey < 180s, red beyond — anchored to the
    /// data's true age at THIS entry's date, so the per-minute timeline
    /// entries walk amber → grey → red exactly like Android.
    ///
    /// ## ⚠️ Suppressed while the network is closed (2026-08-17)
    /// After the last train nothing fetches, because there is nothing to fetch:
    /// the app is shut, the stream has nothing to push, and the widget's own
    /// schedule tapers overnight by design. So by 04:00 the last check really is
    /// hours old, this ladder really does reach red, and the footer spends the
    /// night flagging a fault on a widget that is working perfectly.
    ///
    /// Reported exactly that way, off the device:
    ///
    ///     "when the service ended for night the text also says the widget
    ///      hasn't updated since last update ... but the fact is we did check
    ///      with the backend so our update is recent but the trains are not
    ///      there"
    ///
    /// The READING is kept — it is true, and the one thing on the panel that
    /// says how old this is — it just stops being drawn as an alarm. The colour
    /// answers "can I trust these times?", and during a closed window there are
    /// no times to distrust.
    ///
    /// Note the timestamp itself was never the bug and is not touched: it
    /// measures the last CHECK, not the last train (`SqlStorage.saveSyncTimestamp`
    /// stamps every sync including a zero-row one, and the extension's own REST
    /// path writes `now` unconditionally in `writeBack`).
    private var staleColor: Color {
        guard data.hasTimestamp else { return WidgetTheme.amber.opacity(0.85) }
        guard freshnessMatters else { return WidgetTheme.amber }
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

    var body: some View {
        // Top cell lives in the widget's rounded-corner zone — deeper content
        // inset than mid-board rows so nothing clips against the corner mask.
        LitCell(vPad: BoardMetrics.cornerCellVPad, hPad: m.headerPad) {
            // Three columns, not an overlay. An overlaid button sits OUTSIDE
            // the layout, so a long station name (large widget especially)
            // expanded straight underneath it and the two collided. Equal
            // side columns keep the name optically centred while physically
            // reserving the button's width, so overlap is impossible at any
            // family or name length.
            HStack(spacing: 8) {
                // The balancing column, and the SMALL family does without it.
                //
                // On medium and large it is what keeps the name optically
                // centred against the refresh button opposite. On a 2×2 it was
                // 22pt of slot plus 8pt of spacing held permanently empty — call
                // it a quarter of the header — so that a name which had already
                // been cut to three characters and an ellipsis could sit in the
                // middle of what was left. Centring is not worth a quarter of
                // the row it centres. Small reads from the left edge instead,
                // with the roundel anchoring it, and the name gets the 30pt.
                if m.centresStationName {
                    Color.clear
                        .frame(width: m.refreshSlot, height: 1)
                }
                HStack(spacing: 8) {
                    ModeIconView(mode: data.mode, size: m.icon)
                    // ── Truncates; never shrinks ──
                    //
                    // This was `minimumScaleFactor(0.65)`, which meant the
                    // board's LOUDEST line was also the only one that changed
                    // size with its content: "Bank" rendered at full height and
                    // "Highbury & Islington" at two thirds of it, so the widget
                    // appeared to use a different type scale per station and the
                    // top of the panel never sat at a consistent weight.
                    //
                    // A station name is the one string on this board the user
                    // already knows — they chose it — so the tail is the
                    // cheapest thing on the panel to lose. Hold the size, cut
                    // the end, and the hierarchy is the same at every station.
                    Text(data.stationName)
                        .font(WidgetTheme.font(m.station, .bold))
                        .foregroundColor(WidgetTheme.amber)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                // Leading on small, where there is no column opposite to centre
                // against — a centred group in a frame that starts at the edge
                // would just float the roundel away from it.
                .frame(maxWidth: .infinity,
                       alignment: m.centresStationName ? .center : .leading)
                // No availability gate. This extension deploys to **iOS 26**
                // (project.yml — the push-enabled bundle forced it, see
                // `StationlyWidgetBundle`), so `if #available(iOS 17.0, *)` here
                // was a branch that could not fail, wrapped in a `Group` that
                // existed only to hold it. Worse than noise: had it ever failed
                // it would have left the reserved slot EMPTY, giving the board a
                // refresh control that silently isn't there.
                RefreshButton(size: m.icon * 0.72, clock: clock,
                              stationId: data.stationId,
                              lastRefreshFailed: refreshFailed)
                    .frame(width: m.refreshSlot)
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
                .font(WidgetTheme.font(size, .bold))
                .foregroundColor(lastRefreshFailed ? WidgetTheme.amberDim : WidgetTheme.amber)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

// A plain `DotMatrixSectionHeader` used to live here for the large board's
// non-paging blocks, and it is gone rather than kept for that case:
// `PlatformPagerHeader` with a single group renders the identical cell (same
// inset, same centred ladder, no arrows), so keeping both meant two headers
// that had to be changed together and would eventually not be.

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
        // A ladder with nothing to climb is not a ladder. `ViewThatFits`
        // measures its children in order until one fits, and with a single
        // variant all five candidates are the SAME STRING — so a header with no
        // shorter wording to fall back to (a locally-derived block, or one a
        // refresh invented) paid up to five text layouts to arrive at the answer
        // it had at the first. That cost lands inside WidgetKit's archiving
        // pass, once per header, per entry, per widget.
        //
        // One variant means there is only ever the compressing fallback to
        // render, so render it directly.
        if variants.count <= 1 {
            lastResortRung
        } else {
            ViewThatFits(in: .horizontal) {
                fixedRung(0)
                fixedRung(1)
                fixedRung(2)
                fixedRung(3)
                // Last resort, deliberately outside the ladder: the shortest
                // rung allowed to scale and then ellipsise. Without a final
                // child that can compress, ViewThatFits would settle on the last
                // fixed rung and let it overflow the cell.
                lastResortRung
            }
        }
    }

    private var lastResortRung: some View {
        rung(variants.last ?? "")
            .lineLimit(1)
            .minimumScaleFactor(0.7)
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
            .font(WidgetTheme.font(size, .bold))
            .foregroundColor(WidgetTheme.amber)
    }
}

/// The platform header with a step arrow at each end — the board's answer to
/// "this station has more than one platform", on every family.
///
/// ## Why arrows replaced a tappable header
/// The whole header used to be one button that cycled forwards. It worked, and
/// it could not say two things a user needs: which directions are available,
/// and how to get BACK. From the last platform the only route to the first was
/// to keep going forwards, and nothing on the widget hinted that this was
/// possible at all — a header that happens to be tappable looks exactly like a
/// header that is not.
///
/// ## The platforms are a RING
/// They used to be a line with two ends, and at each end the arrow pointing past
/// it went dim. The theory was that a dim arrow says "nothing that way"; in use
/// it says "this widget's controls are broken", because on a two-platform board
/// one of the two arrows is always dead and a user reaching for the same place
/// twice gets a response once. Stepping past the last platform now comes round
/// to the first, and back from the first lands on the last, so both arrows are
/// always live and either one reaches everything.
///
/// The page marker carries the whole position story as a result — "2/4" is what
/// tells a three-platform board from a two-platform one, and it is the reason
/// the marker is `fixedSize()`: it must never be the text that gets squeezed out
/// on a narrow canvas.
///
/// ## An arrow must always be a Button
/// Not a style choice: every non-interactive pixel of a widget belongs to the
/// widget's own tap target, so an arrow drawn as plain content LAUNCHES THE APP
/// when tapped. That was found on device when the disabled state was drawn
/// inertly, and it is why nothing arrow-shaped here is ever anything but a
/// `Button`.
///
/// ## Layout
/// Both arrow slots are reserved unconditionally, so the title sits optically
/// centred and does not shift by half an arrow as it pages. Same device-proven
/// trick as the station header's refresh slot: an overlaid button sits outside
/// the layout and a long platform name expands straight underneath it.
struct PlatformPagerHeader: View {
    /// KMP's header ladder, widest first. This header is the tightest text on
    /// the widget — see `HeaderLadder` for why scaling and truncation are both
    /// the wrong answer to that.
    let variants: [String]
    let page: Int
    let groupCount: Int
    let stationId: String
    /// Which pager this is. The large board stacks two and they hold separate
    /// positions, so an arrow has to name the one it belongs to — see
    /// `BoardSection`.
    var section: BoardSection = .single
    let m: BoardMetrics
    /// The transition the whole board is moving on, so the title travels with
    /// its rows instead of cross-fading while they slide.
    let slide: AnyTransition

    /// Whether this section has anywhere to go. A lone block draws no arrows
    /// at all — there is no second platform to reach, and a live control that
    /// changes nothing is worse than no control.
    private var pageable: Bool { groupCount > 1 }

    var body: some View {
        // A single-platform board keeps the section header's original inset —
        // it is the common case and must render exactly as it always has. The
        // tighter one only applies where arrows need the width.
        LitCell(vPad: 2, hPad: pageable ? 6 : m.rowPad) {
            HStack(spacing: 4) {
                if pageable {
                    arrow(back: true).frame(width: m.arrowSlot)
                }

                // The title and its page marker travel together as one label —
                // the marker is part of what changes, so it must not sit still
                // while the platform name slides out from under it.
                HStack(spacing: 6) {
                    HeaderLadder(variants: variants, size: m.platform)
                    if pageable {
                        // Position, and now the ONLY thing saying it: with a
                        // ring there are no ends for a dim arrow to mark. The
                        // "‣" that used to prefix this was the tap hint, and
                        // the arrows took that job.
                        // The board's face, at the header's weight, one notch
                        // down in size — "the indexing of the platform is fine
                        // to be smaller", but it is part of the header and reads
                        // as part of it. It was SF Mono, which made the one
                        // element sitting hard against the platform name the one
                        // element in a different typeface.
                        Text("\(page + 1)/\(groupCount)")
                            .font(WidgetTheme.font(m.platform * 0.72, .bold))
                            .foregroundColor(WidgetTheme.amberDim)
                            .lineLimit(1)
                            // Never squeezed or ellipsised: "1/…" is worse than
                            // no marker, and the ladder beside it is built to
                            // give up width instead.
                            .fixedSize()
                    }
                }
                .frame(maxWidth: .infinity)
                .id(page)
                .transition(slide)

                if pageable {
                    arrow(back: false).frame(width: m.arrowSlot)
                }
            }
        }
    }

    /// An arrow, and it is ALWAYS a `Button`.
    ///
    /// There used to be an `if #available(iOS 17.0, *) { Button } else { glyph }`
    /// here. The `else` was unreachable — this extension deploys to iOS 26 (see
    /// `StationlyWidgetBundle`) — and it was unreachable code that did the one
    /// thing the class comment above forbids: drawing an arrow as plain content.
    /// Every non-interactive pixel of a widget belongs to the widget's own tap
    /// target, so that branch shipped an arrow which OPENS THE APP when tapped.
    /// It was found on device once already and written up; leaving a copy of it
    /// behind a condition that happens to be false is leaving it loaded.
    private func arrow(back: Bool) -> some View {
        Button(intent: MovePlatformPageIntent(
            stationId: stationId,
            section: section.rawValue,
            forward: !back,
            groupCount: groupCount
        )) {
            Image(systemName: back ? "chevron.left" : "chevron.right")
                .font(WidgetTheme.font(m.platform * 0.86, .bold))
                // Full brightness, always: every arrow drawn now leads somewhere.
                .foregroundColor(WidgetTheme.amber)
                // The tap target is the whole slot, not the glyph: a chevron is
                // ~8pt of ink and a widget gets one chance at being hit.
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(back ? "Previous platform" : "Next platform")
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
        LitCell(hPad: m.rowPad) {
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
                        .font(WidgetTheme.font(m.row, .bold))
                        .foregroundColor(nameTint)
                        .lineLimit(1)
                        // Never squeezed: this is the row's most compressible
                        // text and also the one word that makes it legible.
                        .fixedSize()
                }
                Text(dep.destination)
                    .font(WidgetTheme.font(m.row))
                    .foregroundColor(nameTint)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                // Same face and same size as the destination beside it; BOLD is
                // what makes it the number you scan for. It was SF Mono half a
                // point larger, which is two ways of saying the same thing and
                // put a second typeface on the busiest row of the board.
                //
                // Plain figures, matching the in-app board's ETA exactly
                // (`Board.kt`: 15sp bold, no family, no digit modifier) — the
                // two boards show the same train and must not disagree about
                // how a number is drawn.
                Text(dep.isDue ? "Due" : dep.eta)
                    .font(WidgetTheme.font(m.row, .bold))
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
private func boardTransition(_ render: BoardRenderState, updatedAt: Date,
                             entryDate: Date, pageable: Bool) -> AnyTransition {
    if pageable, render.isPageMove {
        return .push(from: render.moveForward ? .trailing : .leading)
    }
    // A payload that landed in the last few seconds is a REFRESH the user is
    // watching — it gets the flip. An older timestamp means this render is an
    // ambient one (a per-minute tick, a snapshot rebuild) where rows should not
    // appear to fall: nothing arrived, the clock simply moved.
    //
    // Measured against THIS ENTRY's date, never `Date()`. A widget's body is
    // evaluated while WidgetKit archives the whole timeline, so `Date()` here
    // returns the build time for all ~20 entries at once and every one of them
    // is told the payload is seconds old — including the entry that will be on
    // screen forty minutes later. Nothing visibly broke, because the animation
    // is keyed on a value all entries in a batch share and so fires at most once
    // per timeline; but a view whose output depends on when it happened to be
    // archived is not a view you can reason about, and this one is asking a
    // question ("is the user watching this land?") that the entry's own date
    // answers exactly.
    let age = entryDate.timeIntervalSince(updatedAt)
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
    var body: some View {
        // Resolved ONCE, and now by `StatusParts` rather than inline — the
        // fallback resolver needs the same split to tell a disrupted line from
        // a healthy one, and two implementations of "where does the colon go"
        // is one more than a board should have. This body used to read a
        // computed property three times, so every strip split its string three
        // times, per entry, per widget.
        let parts = StatusParts(data.status)

        // ── ⚠️ No status means NO STRIP, not "Good Service" ──
        //
        // This read `data.status.isEmpty ? "Good Service" : data.status`, so a
        // board with no status record told the user their line was running
        // fine. Nothing had checked. It is the same defect as the old "No
        // departures right now" one cell above it — static text asserting an
        // unverified fact — and it arrived through the one place that looked
        // like a formatting nicety rather than a claim.
        //
        // It shows up most on exactly the board least able to afford it: a
        // station the app has not written yet (`StationResolver.waiting`)
        // carries `status: ""`, so a widget that knew nothing announced good
        // service on a line it had never asked about.
        //
        // The CELL stays, dark and holding its place. Dropping it would change
        // the cell count, which is what §6.2 exists to prevent — the strip
        // comes and goes with the departure count already, and every other cell
        // would resize around it.
        return Group {
            if parts.isKnown {
                strip(parts)
            } else {
                EmptyRowCell(m: m)
            }
        }
    }

    private func strip(_ parts: StatusParts) -> some View {
        LitCell(vPad: 2, hPad: m.rowPad) {
            HStack(spacing: 0) {
                Text(parts.severity)
                    .font(WidgetTheme.font(m.status, .bold))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                    // Scales rather than truncates, and takes its width before
                    // the reason does. This was `fixedSize()`, which guaranteed
                    // the same priority and could not give any of it back — fine
                    // while the label was "Minor Delays", overflow once KMP
                    // started naming the line ("Circle, District Minor Delays")
                    // and the small family had 149pt of cell to put it in.
                    // The rule it protects is unchanged: the truncation eats the
                    // reason, never the label.
                    .minimumScaleFactor(0.75)
                    .layoutPriority(1)
                if !parts.reason.isEmpty {
                    Text(" : \(parts.reason)")
                        .font(WidgetTheme.font(m.status))
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
            // ── Exactly the in-app board's clock, and NOT tabular ──
            //
            // `Board.kt`'s `BoardFooter` renders its clock as plain bold system
            // type with no digit-width modifier at all, and it has ticked that
            // way for as long as there has been a board. This carried
            // `monospacedDigit()` for a while on the reasoning that a per-second
            // clock needs a fixed advance or the footer twitches — true in
            // isolation, and it made the widget's clock visibly a different
            // thing from the app's, which is the comparison a user actually
            // makes. Tabular figures are wider and more evenly spaced; side by
            // side with the app the two clocks did not look like one product.
            //
            // The app lives with whatever jitter proportional figures cost, so
            // the widget does too. If it ever becomes a problem, it is a problem
            // on BOTH surfaces and gets fixed on both.
            .font(WidgetTheme.font(fontSize, .bold))
            .foregroundColor(WidgetTheme.amber)
            .lineLimit(1)
            // Timer Texts greedily expand and left-align; center the digits
            // inside that expanded frame so the clock stays mid-board.
            .multilineTextAlignment(.center)
    }
}

/// Footer, matching the Android board's bottom row: Stationly maker mark
/// (left) + live HH:MM:SS clock (CENTER) + live "M:SS ago" (right, the smallest
/// type on the board). Both clock and "ago" tick per second via `.timer`.
///
/// ## The small family has TWO columns, not three (2026-08-14)
/// A 2×2 footer is about 145pt wide with the maker mark and both insets taken
/// out of it, and three elements in that space is not a layout, it is a queue.
/// The wall clock is the one of the three that is redundant on this device —
/// the phone's own status bar is a centimetre above the widget and says the same
/// thing — so it comes out, and the "ago" timer takes the middle. That one has
/// no other source: it is the only thing on the panel that says whether these
/// departures are seconds or minutes old, which is the whole question a glance
/// at a departure board is asking.
///
/// Medium and large keep all three; they have the width, and there the clock is
/// part of the concourse-board lockup rather than a passenger competing for it.
struct DotMatrixFooter: View {
    let data: WidgetData
    let clock: Date
    let m: BoardMetrics
    /// Forwarded to the "ago" timer — see `LiveAgo.staleColor`.
    var freshnessMatters: Bool = true
    var body: some View {
        // Bottom cell: the corner mask intrudes ~9–12pt at the logo/ago height
        // (iOS 26 corner radii are generous) — 20pt of side breathing keeps the
        // maker mark and the "ago" timer comfortably clear of the curve. The
        // small family gives back a few points of that, because at a third of
        // the width the columns need it more than the curve does.
        LitCell(vPad: BoardMetrics.cornerCellVPad, hPad: m.footerPad) {
            // Real columns rather than a ZStack. The clock and the "ago" are
            // both `.timer` Texts, which expand greedily — stacked on top of the
            // mark they could grow straight over it. Laying them out as siblings
            // makes collision impossible, and the equal-width outer columns are
            // what keep the CENTRE element optically centred: on small the
            // trailing column holds nothing at all and exists only to balance
            // the maker mark opposite it.
            HStack(spacing: 6) {
                StationlyMark(diameter: m.logo)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if m.showsClock {
                    // CAPPED frame, never `fixedSize()`: a `.timer` Text has no
                    // determinate ideal width (its digits are system-driven), so
                    // asking for one collapses the layout and the widget renders
                    // blank. A hard cap is the same tactic LiveAgo already uses.
                    LiveClock(clock: clock, fontSize: m.clock)
                        .frame(maxWidth: m.clock * 6)
                }
                // ONE construction, whichever family this is. The two arms used
                // to build their own `LiveAgo` with different arguments, so a
                // change to the element that exists on every board had two
                // places to be made and one of them would eventually be missed.
                // What genuinely differs is where it sits and how much room it
                // may take, and both of those are now `BoardMetrics`' answer.
                LiveAgo(data: data, entryDate: clock, fontSize: m.ago,
                        alignment: m.showsClock ? .trailing : .center,
                        maxWidth: m.agoWidth, freshnessMatters: freshnessMatters)
                    .frame(maxWidth: m.showsClock ? .infinity : nil,
                           alignment: .trailing)
                if !m.showsClock {
                    // The empty trailing column, and it is load-bearing: it is
                    // the only thing balancing the maker mark opposite, and
                    // without it the "ago" sits right of centre rather than in
                    // the middle of the footer.
                    Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - The board (every family)
// Every cell is height-flexible (`maxHeight: .infinity` inside LitCell) at
// EQUAL layout priority, so surplus height is shared evenly across all cells
// and the board always fills the whole canvas. (An earlier layoutPriority
// 2/1/0 ladder made ONLY the header and footer balloon on sparse boards —
// 1–2 departures left the mid rows pinned at minimum height. The minHeights
// remain as compression floors; priorities had no other job once the medium
// board was budgeted to fit.)
//
// The content budget per family (see BoardMetrics): small and medium carry
// station + one platform header + 3 rows + footer, medium adding the status
// strip when a departure slot is spare; the footer is shed on canvases under
// 150pt. Large carries station + TWO platform headers + 3 rows each + status +
// footer — one board divided into two, so a station with more platforms than
// the canvas can show at once is reachable rather than truncated.
// ─────────────────────────────────────────────────────────────────────────────

struct BoardWidgetView: View {
    let data: WidgetData
    let clock: Date
    let metrics: BoardMetrics
    /// Why this render is happening, resolved when the timeline was built —
    /// never looked up here. See `BoardRenderState`.
    var render: BoardRenderState = BoardRenderState()
    /// What this board says when it has no departures, chosen on THIS entry's
    /// clock when the timeline was built. Nil when there are departures to show.
    /// See `BoardFallbackResolver`.
    var fallback: BoardFallbackResult? = nil

    var body: some View {
        Group {
            if let reason = data.emptyReason {
                EmptyWidgetView(m: metrics, reason: reason)
            } else {
                board
            }
        }
        .containerBackground(WidgetTheme.background, for: .widget)
    }

    private var board: some View {
        // The blocks each pager on this canvas walks, decided ONCE and threaded
        // through everything below: the layout, the row budget and the animation
        // key all have to be answering the same question about the same board.
        let sections = sectionRenders
        let slots = metrics.rows(for: data.rowCap)
        let cells = rowCells(sections.first, slots: slots)
        return GeometryReader { geo in
            VStack(spacing: 2) {
                DotMatrixHeader(data: data, m: metrics, refreshFailed: render.refreshFailed,
                                clock: clock)
                    .frame(minHeight: metrics.headerMinHeight)

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
                platformSections(sections, slots: slots, cells: cells)

                // The paged board's strip rides in a departure cell instead
                // (see `platformSections`), so this is the large family's own
                // always-on row — the one canvas that can reserve a cell for a
                // line that is running perfectly.
                if metrics.statusPolicy == .always {
                    statusStrip
                }
                // "If it doesn't fit, drop the last row" — only SE-class
                // mediums (321×148) fall under 150pt; every other family
                // keeps the live clock/ago footer.
                //
                // Measured on the FULL canvas rather than on the inset content,
                // deliberately: the threshold is about which DEVICE this is, and
                // moving it by the board's own margin would newly shed the
                // footer on 155pt canvases that keep it today.
                if geo.size.height >= 150 {
                    // Pinned on small, flexible elsewhere — see
                    // `BoardMetrics.footerFlexible` for why a floor alone
                    // doesn't hand the departure rows anything.
                    DotMatrixFooter(data: data, clock: clock, m: metrics,
                                    freshnessMatters: fallback?.freshnessMatters ?? true)
                        .frame(minHeight: metrics.footerMinHeight,
                               maxHeight: metrics.footerFlexible
                                   ? nil : metrics.footerMinHeight)
                }
            }
            // What makes the refresh READ as a refresh. Without an explicit
            // animation the row transitions below are applied instantly and the
            // board simply swaps its contents, which on device is the "flicker"
            // this replaces: the pixels change and nothing tells the eye that
            // anything was fetched.
            //
            // Keyed on every pager's page plus the payload timestamp, so it
            // fires on an arrow press and on new departures landing, and NOT on
            // the per-minute entries — all 61 of those are built from one
            // payload and share the key, so countdowns tick in place (the digits
            // roll on their own, see DotMatrixRow's contentTransition).
            .animation(
                boardAnimation(render, pageable: isPageable(sections)),
                value: motionKey(sections)
            )
            .overlay(DotGrid().allowsHitTesting(false))
            // ── NO outer margin here. Do not add one. ──
            //
            // A `boardInset` + `ContainerRelativeShape` clip was tried here and
            // removed the same day, on the owner's correction: **the dot-matrix
            // board covers the widget end to end.** The reasoning was that an
            // inset would give the corners somewhere to breathe, and the margin
            // was even the same black as the inter-cell gaps — but a panel with
            // a border round it stops being the widget and starts being a card
            // inside it, which is the exact thing §3.1 of the design doc removed
            // in the first place. Breathing room is `rowPad`, inside the cells,
            // where the text is.
        }
    }

    /// One section as THIS render sees it: the blocks it holds, the pager key it
    /// answers to, the page it is showing, and the block on that page.
    ///
    /// Resolved once and passed down, because three separate questions used to
    /// ask it independently — how many cells the status strip leaves for
    /// departures, what the animation is keyed on, and what to draw — and each
    /// one re-read the stored page and re-clamped it against the block count.
    /// Three answers to one question is how they drift apart.
    private struct SectionRender {
        /// Which stored page this section reads and writes. Only the split
        /// layout has more than one, so every other family keeps the single key
        /// it has always used — see `BoardSection`.
        let token: BoardSection
        let groups: [BoardGroup]
        /// Already clamped against `groups` — see `WidgetBoardPage`.
        let page: Int
        /// The block on screen, or nil for a section holding a place with
        /// nothing to put in it (a large board at a one-platform station).
        var group: BoardGroup? { groups.indices.contains(page) ? groups[page] : nil }
        /// Whether this section has somewhere to page to.
        var isPageable: Bool { groups.count > 1 }
    }

    /// How this canvas divides its blocks between pagers — one section on small
    /// and medium, two on large. See `WidgetData.sections` for what decides
    /// which blocks land together.
    private var sectionRenders: [SectionRender] {
        let split = metrics.layout == .split
        return data.sections(metrics.sectionCount).enumerated().map { index, groups in
            let token: BoardSection = split ? (index == 0 ? .upper : .lower) : .single
            return SectionRender(
                token: token,
                groups: groups,
                page: WidgetBoardPage.clamp(render.page(token), groupCount: groups.count))
        }
    }

    /// The payload's own timestamp, which is what row identity is keyed on —
    /// see `platformSections`.
    private var stamp: Int { Int(data.lastUpdated.timeIntervalSince1970) }

    /// Whether any section has somewhere to page to, which is what decides
    /// between a directional push and a relight.
    private func isPageable(_ sections: [SectionRender]) -> Bool {
        sections.contains { $0.isPageable }
    }

    /// The single value every board animation is keyed on — see the modifier
    /// above for why these things and nothing else. Every section's page is in
    /// it, so an arrow in the lower half animates as surely as one in the upper.
    ///
    /// An array of `Int`, not a formatted `String`. It was
    /// `map { String($0.page) }.joined(separator: ".") + "-\(stamp)"` — two
    /// string allocations plus interpolation to produce a value whose only job
    /// is to be compared with `==`.
    private func motionKey(_ sections: [SectionRender]) -> [Int] {
        sections.map(\.page) + [stamp]
    }

    /// Identity of one departure cell — see `platformSection` for why it is the
    /// page and the payload's timestamp rather than the row's own id.
    ///
    /// A `Hashable` struct rather than `"\(token)-\(page)-\(index)-\(stamp)"`.
    /// `.id()` takes any `Hashable` and only ever hashes and compares it, so the
    /// string form was formatting ~11 of these per entry, per widget, inside
    /// WidgetKit's archiving pass — the one path this board is measured on.
    private struct RowID: Hashable {
        /// The section itself, not its `rawValue` — a `String`-backed enum is
        /// `Hashable` already, so going through the raw value only put a string
        /// back into the identity this struct exists to keep out of.
        let section: BoardSection
        let page: Int
        let index: Int
        let stamp: Int
    }

    /// The board's platform blocks, one pager per section, at a CELL COUNT that
    /// does not depend on the data.
    ///
    /// WidgetKit can't scroll (static snapshots), so where Android's widget
    /// scrolls a `rows_list` this PAGES: each section shows one block, with an
    /// arrow at each end of its header (`Button(intent:)`), and the rows
    /// slide in the direction the arrow points. A section holding a single block
    /// renders exactly as a plain header always did — no arrows, no marker,
    /// nothing to say.
    ///
    /// ## Why the empty cells ARE the feature (2026-08-10)
    /// Every cell on this board is height-flexible at equal priority, so the
    /// NUMBER of cells is what decides how tall each one is. The board used to
    /// draw a cell per departure it happened to have, which meant it re-laid
    /// itself out every time a train left: four rows at 09:00, three at 09:03,
    /// each row a different height, the type appearing to grow and shrink on its
    /// own. A panel that reflows while you are reading it looks broken, not live.
    ///
    /// So the skeleton is fixed and the DATA moves inside it:
    ///
    /// | | small / medium | large |
    /// |---|---|---|
    /// | station | 1 | 1 |
    /// | platform header | 1 (always, even unlabelled) | 1 per section = 2 |
    /// | departures | 3 | 3 per section = 6 |
    /// | status | takes the 3rd departure cell when the platform can't fill it | 1 of its own |
    /// | clock | 1 | 1 |
    ///
    /// A platform with two trains draws two rows and the strip; with one, one row
    /// and a dark cell holding the third's place; with none, a message in the
    /// first cell and dark cells under it. The count never changes, so no cell
    /// ever changes height.
    @ViewBuilder
    private func platformSections(_ sections: [SectionRender], slots: Int, cells: Int) -> some View {
        let slide = boardTransition(render, updatedAt: data.lastUpdated,
                                    entryDate: clock, pageable: isPageable(sections))
        ForEach(Array(sections.enumerated()), id: \.offset) { index, section in
            platformSection(section, slots: slots, cells: cells, slide: slide,
                            // The board's one message goes in the FIRST section,
                            // and only when the whole board has nothing — an
                            // empty lower section on the large family means
                            // "there is no second platform", which is not news.
                            speaks: index == 0 && !data.hasDepartures)
        }
        // The strip takes the cell the departures did not need. `cells` is
        // already the answer — see `rowCells`.
        if metrics.statusPolicy == .backfill, cells < metrics.maxRows {
            statusStrip
        }
    }

    /// One section: its platform header, then exactly [cells] row cells — real
    /// departures first, dark ones holding the rest of the places.
    @ViewBuilder
    private func platformSection(_ section: SectionRender, slots: Int, cells: Int,
                                 slide: AnyTransition, speaks: Bool) -> some View {
        // ── ⚠️ The blank goes at the BOTTOM, never under the station name ──
        //
        // This was unconditional, on the reasoning that a cell which comes and
        // goes takes every other cell's height with it (§6.2). The count rule is
        // right and is kept; drawing the cell HERE when it has nothing to say
        // was not.
        //
        // With no platform to name, `section.group` is nil, the variants are
        // `[""]`, and this rendered a LIT STRIP WITH NOTHING IN IT between the
        // station name and the board's message. On an empty board that is the
        // first thing under the header, so the panel opened with a gap and the
        // message it was meant to introduce started one cell late. Owner's
        // words: *"it's okay to leave the line at the bottom rather than leaving
        // something from top"*, and that is exactly right — trailing dark cells
        // read as a board with room left, a leading one reads as a fault.
        //
        // So the cell MOVES rather than disappearing: no header means one extra
        // dark cell at the end (see `cellCount`), the count is identical, and
        // nothing resizes. The floors differ by half a point across the three
        // families (`platform + 8` against `row + 10`), which is inside the
        // rounding of an equal-share layout.
        //
        // NOT keyed on `speaks`: a station whose platform block exists but has
        // emptied out still has a real name to show, and that header is useful
        // precisely then. The test is whether there is anything to write.
        //
        // KMP's own header text — "Northern Platform 1 Westbound", "Bus 39, 34
        // Stop N". Never assembled here: `MultiLineBoardProcessor.headerFor` is
        // the one implementation, and this widget showing a different string
        // from the home board is what that rule exists to prevent.
        if let group = section.group {
            PlatformPagerHeader(
                variants: group.headerVariants,
                page: section.page,
                groupCount: section.groups.count,
                stationId: data.stationId,
                section: section.token,
                m: metrics,
                slide: slide
            )
            .frame(minHeight: metrics.platform + 8)
        }

        // `group.rows` is RESERVES, so this is where display depth is decided,
        // exactly as the home board decides it in `BoardTicker.tick` — see
        // `BoardMetrics.rows(for:)`. `slots` is the user's own depth and can be
        // shallower than the cells available, which is why both bound the take.
        // An `ArraySlice`, not `Array(...)`. It is only ever read by index and
        // counted, so copying it into a fresh array per section, per entry, per
        // widget bought nothing. (Slices index off the PARENT's positions, which
        // is why `rowCell` takes the index relative to `startIndex` below.)
        let rows = (section.group?.rows ?? []).prefix(min(cells, slots))
        // Hoisted: `stamp` is a computed property and every cell below read it.
        let stamp = self.stamp
        // `Array(0..<cells)` rather than the bare range: SwiftUI treats a
        // `ForEach` over a Range as CONSTANT data, and this count does move —
        // the status strip takes the third cell on a quiet platform and gives it
        // back on a busy one.
        //
        // The board moves as ONE thing, so the height, identity and transition
        // are applied to every cell alike — a departure, a dark cell and a
        // message all travel with the header when the section pages, rather than
        // the rows sliding while the empty places sit still.
        //
        // Identity is the section and page plus the data's TIMESTAMP,
        // deliberately NOT the row's own id: a DepartureRow's id is a fresh UUID
        // on every decode, so keying on it would re-insert every row on every
        // minute tick and animate a countdown as though the platform had
        // changed. The timestamp only moves when genuinely new departures land,
        // so the 61 pre-rendered per-minute entries — all built from one payload
        // — share it and tick in place. Including the SECTION is what keeps the
        // half nobody touched from re-animating when the other half pages.
        // One MORE cell when this section drew no platform header, so the board
        // keeps exactly the cell count §6.2 fixes it at. The header did not
        // vanish; it moved to the bottom and went dark. See the note above it.
        let cellCount = section.group == nil ? cells + 1 : cells
        ForEach(Array(0..<cellCount), id: \.self) { index in
            rowCell(index, in: section, rows: rows, speaks: speaks)
                .frame(minHeight: metrics.row + 10)
                .id(RowID(section: section.token, page: section.page,
                          index: index, stamp: stamp))
                .transition(slide)
        }
    }

    /// What goes in one departure cell: a train, the board's one message, or
    /// nothing at all.
    ///
    /// `mixesLines` is one prefix decision for the whole block, taken by KMP
    /// from every row the block HAS rather than the handful that fit — otherwise
    /// a platform would gain and lose its prefixes as trains tick off the bottom
    /// of it.
    @ViewBuilder
    private func rowCell(_ index: Int, in section: SectionRender,
                         rows: ArraySlice<DepartureRow>, speaks: Bool) -> some View {
        if index < rows.count {
            // Offset from `startIndex`, not a bare subscript: a slice keeps its
            // parent's indices, and although `prefix` on an `Array` always
            // starts at 0 today, a slice indexed as though it were an array is
            // the classic Swift out-of-bounds crash waiting for the day someone
            // changes where the slice comes from.
            DotMatrixRow(dep: rows[rows.startIndex + index], m: metrics,
                         showLine: section.group?.mixesLines ?? false)
        // The board's message, across TWO cells — the title where the first
        // train would be, the detail in the dark cell under it.
        //
        // Two cells rather than one line, because the app's copy is a pair
        // ("Service ended for tonight" / "Back in the morning") and the half
        // that says what happens NEXT is the half that stops an empty board
        // reading as a broken one. The cell COUNT is untouched: the detail lands
        // in a place that was already being held for a train that is not
        // coming, so §6.2's fixed skeleton is intact.
        //
        // Both arms require a resolved fallback rather than defaulting to an
        // empty string. An entry built without one (the gallery snapshot, an
        // Xcode preview) would otherwise draw a LIT cell with nothing in it,
        // which is neither a message nor the dark placeholder the board uses
        // for "no train here" — just a gap.
        } else if speaks, let fallback, index == rows.count, !fallback.title.isEmpty {
            BoardMessageCell(text: fallback.title, m: metrics, emphasis: true)
        } else if speaks, let fallback, index == rows.count + 1, !fallback.detail.isEmpty {
            BoardMessageCell(text: fallback.detail, m: metrics)
        } else {
            EmptyRowCell(m: metrics)
        }
    }

    /// How many departure CELLS each section draws — the number that must not
    /// move with the data.
    ///
    /// Constant per family on the split board. On the paged one it is the whole
    /// status-strip rule in a single expression: a platform that fills every
    /// cell keeps them all, and one that cannot gives the LAST cell to the
    /// strip. It never drops below `maxRows - 1`, which is what makes a
    /// one-train platform draw a train, a dark cell and a strip rather than
    /// stretching one row across the panel.
    private func rowCells(_ first: SectionRender?, slots: Int) -> Int {
        guard metrics.layout == .paged else { return metrics.rowsPerSection }
        let shown = min(first?.group?.rows.count ?? 0, slots)
        return shown >= metrics.maxRows ? metrics.maxRows : metrics.maxRows - 1
    }

    /// The strip, at a DEPARTURE cell's height on the paged board.
    ///
    /// It is standing in for a departure row there, so it has to be the same
    /// size as one — given its own smaller minimum it would resize its two
    /// neighbours every time it appeared, which is the exact flicker the fixed
    /// cell count exists to remove. On the large board it is a row in its own
    /// right and keeps its own height.
    private var statusStrip: some View {
        DotMatrixStatusStrip(data: data, m: metrics)
            .frame(minHeight: metrics.layout == .paged ? metrics.row + 10 : metrics.status + 8)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Small widget: NOT a view of its own any more
//
// A `SmallWidgetView` used to live here: station lockup, the first three
// departures of whatever block came first, footer. It is gone because that
// independence is exactly what made the small family fall behind — the refresh
// control and the platform pager were built in the shared board, and this view
// simply never called them, so a user with a 2×2 widget had no way to refresh it
// and no way to see a second platform. It now renders `BoardWidgetView` at
// `BoardMetrics.small`, which is a type scale and four narrower slots rather
// than a separate design.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Shared sub-components
// ─────────────────────────────────────────────────────────────────────────────

/// A departure cell with nothing in it — a lit row holding its place.
///
/// It replaced a board that simply drew fewer cells, and the difference is the
/// whole point: cells share the canvas evenly, so a row that vanishes when a
/// train departs makes every remaining row taller. A dark cell costs the same
/// pixels a train did and keeps the panel still. It is LIT rather than black
/// because the black gaps between cells read as the bezel — an unlit gap two
/// rows deep would look like a hole in the panel rather than an empty row on it.
struct EmptyRowCell: View {
    let m: BoardMetrics
    var body: some View {
        // Height comes from the call site, like a real row's — see
        // `BoardWidgetView.platformSection`. A cell standing in for a departure
        // has to be sized by the same hand that sizes departures.
        LitCell(hPad: m.rowPad) {
            Color.clear.frame(height: m.row)
        }
    }
}

/// A departure cell saying why there are no departures.
///
/// Takes the place of one row rather than floating in the middle of the board
/// (which is what `NoDeparturesRow` did, at whatever height was left over), so
/// an empty board is the same board with its rows dark — not a different layout.
/// The reason, when there is one, is in the status strip directly beneath.
///
/// ## It is a departure row in every respect but the words
/// Same cell, same inset, same face, same size, same amber. It was
/// `WidgetTheme.textMuted` — a 0.40 grey that appeared nowhere else on the
/// panel and read at about 3:1 against the row surface, so the one line a board
/// shows when it has nothing else was also the hardest line on it to read.
/// Centred rather than left-aligned is the only difference, and that is enough:
/// it only ever appears when there are no departures to confuse it with.
struct BoardMessageCell: View {
    let text: String
    let m: BoardMetrics
    /// Whether this is the TITLE of a two-cell message rather than its detail.
    ///
    /// Bold, matching the app's own fallback rows (`BoardFallbackCopy` is a bold
    /// title over normal-weight detail lines, and `BoardFallbackRows` draws it
    /// that way). Weight rather than colour, per `WidgetTheme` — both cells are
    /// board amber, and the one the eye should land on is the heavier one.
    var emphasis: Bool = false
    var body: some View {
        LitCell(hPad: m.rowPad) {
            Text(text)
                .font(WidgetTheme.font(m.row, emphasis ? .bold : .regular))
                .foregroundColor(WidgetTheme.amber)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .frame(maxWidth: .infinity, alignment: .center)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Empty state (no station behind the widget)
//
// ## A centred mark and a sentence, NOT the board
//
// The line, and it is the owner's:
//
//     The board layout is for a widget that HAS a station. It can be empty —
//     nothing has arrived, every train has gone, nothing is coming — and it is
//     still that station's board. A widget with no station behind it is not a
//     board with nothing on it; it is not a board.
//
// So the four `EmptyReason` states get a mark centred over a sentence, and the
// board's cells, pager, footer and dot lattice stay out of it. Drawing the
// header cell here was tried and reverted the same day: a lit header with the
// maker mark where the roundel goes is a *departure board for a station*, and
// three of these four states have no station to be a board for. It made the
// panel look like it was reporting on somewhere, when what it needs to say is
// that it has nowhere to report on yet.
//
// `.removed` sits on this side of the line too, and it is the interesting one:
// there IS a name, but the station is gone from the user's list, so there is no
// board to draw and the ask is the same as `.needsStation` — pick one. The name
// goes in the title, where it does the one job it can still do: tell the user
// WHICH of their widgets needs attention.
//
// ## What did change, and stays changed
// The layout is the original. The paint is not:
//
//  - **Amber, not white and grey.** These four sentences were the only text on
//    the widget that was not board-amber, and the grey ran at about 3:1. See the
//    note in `WidgetTheme`.
//  - **Type from `BoardMetrics`.** It was a hardcoded 13pt title and 11pt body on
//    every family, so a 4×4 got the same small print as a 2×2 on more than twice
//    the canvas. Now `m.station` / `m.row`, the same ladder the board uses.
//  - **The mark scales too**, at twice the mode roundel's size, instead of a flat
//    40pt that crowded a 2×2 and looked lost on a 4×4.
//  - **One copy table.** Small used to draw a shorter message and NO title, so a
//    deleted-station widget could say "Station removed" and never which one.
// ─────────────────────────────────────────────────────────────────────────────

struct EmptyWidgetView: View {
    /// The family's type scale, so this panel is measured by the same hand as
    /// the board it stands in for. It used to take a three-case `WidgetSize`
    /// enum that existed solely to feed this view, and `BoardMetrics` carried a
    /// `size` field solely to produce it — a whole parallel notion of "how big is
    /// this widget" running beside the one that does the work.
    let m: BoardMetrics
    /// Which of the four empty states this is. One value rather than a flag per
    /// state: the copy below is a total switch over it, so a state added later
    /// cannot silently inherit another one's wording. See
    /// `WidgetData.EmptyReason`, which is also where their precedence lives.
    let reason: WidgetData.EmptyReason

    /// The header line, where the station name goes on a live board: WHAT this
    /// state is, in three words or fewer.
    ///
    /// A removed station's own NAME is what tells the user which of their widgets
    /// needs attention without opening any of them, so it takes the slot it would
    /// have had anyway. The other three name the state rather than the app: this
    /// line sits in the station's place, and a user reading a panel headed
    /// "Stationly" over an instruction learns nothing they could not see from the
    /// icon. The brand is the mark beside it.
    ///
    /// The removed case still falls back, because iOS can hand over a
    /// configuration with an id and no entity around it, and a blank header reads
    /// as a rendering fault.
    private var title: String {
        switch reason {
        case .removed(let station): return station.isEmpty ? "Station removed" : station
        case .needsStation:         return "Choose a station"
        case .signedOut:            return "Signed out"
        case .noStations:           return "No stations yet"
        }
    }

    /// What to do about it. Plain sentences, no dashes, nothing that reads as
    /// the app's fault.
    ///
    /// Every one of these four states is one the user can end, and three of them
    /// they end with a single tap, so the copy's whole job is to point at that
    /// tap. No exclamation marks and no apology: `.needsStation` in particular is
    /// a setup step, not an error, and it can appear on several widgets at once
    /// (adding the first station after a spell with none un-masks every stale
    /// configuration at the same moment), which is exactly when alarmed wording
    /// would read as the widget being broken.
    ///
    /// Both configuration lines name the whole gesture — touch and hold, THEN
    /// tap Edit Widget — because the touch-and-hold alone only opens the jiggle
    /// menu, and a user who has never configured a widget has no reason to know
    /// the second step is there. "Touch and hold" rather than "long press", and
    /// "Edit Widget" exactly as the menu spells it, because those are the words
    /// on the phone.
    ///
    /// ## One table, every family
    /// There was a second one for the small family — four shorter strings, and a
    /// small widget drew ONLY those, with no title at all. Two tables is two
    /// places to change a sentence and one of them gets missed, and the short set
    /// had quietly become the worse copy: "Choose a station" as the whole message
    /// says nothing about how, on the family least likely to be understood
    /// without it. The header carries the state at every size now, so the small
    /// panel says the same thing as the large one and simply wraps it.
    private var message: String {
        switch reason {
        case .signedOut:    return "Open Stationly to sign in"
        case .noStations:   return "Open Stationly to add one"
        case .needsStation: return "Touch and hold, then tap Edit Widget"
        case .removed:      return "Not in your stations. Touch and hold, then tap Edit Widget"
        }
    }

    /// The maker mark, at twice the mode roundel this family draws: 28 / 36 / 44.
    ///
    /// Derived rather than picked, so it moves with the type around it. A flat
    /// 40pt sat here for every family, which is a quarter of a 2×2's height
    /// competing with the sentence under it, and small change on a 4×4.
    private var markSize: CGFloat { m.icon * 2 }

    var body: some View {
        // Centred, and the whole panel is the message. No lit cells, no lattice,
        // no footer — see the note above for why this is deliberately not the
        // board.
        VStack(spacing: m.row * 0.6) {
            StationlyMark(diameter: markSize)

            VStack(spacing: m.row * 0.35) {
                // Truncates rather than shrinks, the same rule the board's
                // station line follows: this is a station NAME in the removed
                // state, and a name that resizes itself makes the panel look
                // like it uses a different type scale per station.
                Text(title)
                    .font(WidgetTheme.font(m.station, .bold))
                    .foregroundColor(WidgetTheme.amber)
                    .lineLimit(1)
                    .truncationMode(.tail)

                // Wraps rather than truncates, because unlike a station name
                // this is a sentence the user has NOT seen before — losing its
                // tail loses the instruction. The floor is only for a canvas
                // narrower than any that ships.
                Text(message)
                    .font(WidgetTheme.font(m.row))
                    .foregroundColor(WidgetTheme.amber)
                    .multilineTextAlignment(.center)
                    .lineLimit(4)
                    .minimumScaleFactor(0.8)
            }
            // Room for the sentence to wrap without touching the widget's
            // corner mask. Scaled off the family's own row inset rather than a
            // flat 16, which on a 2×2 was taking a fifth of the width from the
            // one string that needed it most.
            .padding(.horizontal, m.rowPad)
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

/// Two lines, both directions — the board sectioning exists for exactly this
/// shape, and it is the one a two-platform placeholder cannot show. The large
/// family should put the Piccadilly's two platforms in the top section and the
/// Victoria's two in the bottom, each paging on its own arrows.
private let fourPlatformEntry: DepartureEntry = {
    func block(_ key: String, _ header: String, _ line: String,
               _ destinations: [String]) -> BoardGroup {
        BoardGroup(
            key: key, header: header,
            headerVariants: [header, header.replacingOccurrences(of: "Platform", with: "Plat.")],
            label: "Platform \(key)", mixesLines: false,
            rows: destinations.enumerated().map { i, name in
                DepartureRow(destination: name, platform: key,
                             eta: i == 0 ? "Due" : "\(i * 3) min", isDue: i == 0,
                             stopLetter: nil,
                             targetEpochMs: Date().addingTimeInterval(Double(i) * 180).timeIntervalSince1970 * 1000,
                             lineShort: line)
            })
    }
    let data = WidgetData(
        stationName: "Finsbury Park", lineName: "", lineDisplay: "",
        direction: "", mode: "tube",
        groups: [
            block("1", "Piccadilly Platform 1 Southbound", "Pic.", ["Heathrow T5", "Uxbridge", "Rayners Lane"]),
            block("2", "Piccadilly Platform 2 Northbound", "Pic.", ["Cockfosters", "Arnos Grove", "Oakwood"]),
            block("3", "Victoria Platform 3 Southbound", "Vic.", ["Brixton", "Victoria", "Brixton"]),
            block("4", "Victoria Platform 4 Northbound", "Vic.", ["Walthamstow Central", "Seven Sisters"]),
        ],
        status: "Good Service", lastUpdated: Date(), isEmpty: false,
        stationId: "940GZZLUFPK", rowCap: 3)
    return DepartureEntry(date: Date(), widgetData: data)
}()

#Preview("Small — live", as: .systemSmall) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

#Preview("Small — four platforms", as: .systemSmall) {
    StationlyDepartureBoardWidget()
} timeline: { fourPlatformEntry }

#Preview("Medium — live", as: .systemMedium) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

#Preview("Large — live", as: .systemLarge) {
    StationlyDepartureBoardWidget()
} timeline: { previewEntry }

#Preview("Large — four platforms", as: .systemLarge) {
    StationlyDepartureBoardWidget()
} timeline: { fourPlatformEntry }

#Preview("Medium — empty", as: .systemMedium) {
    StationlyDepartureBoardWidget()
} timeline: { emptyEntry }

// ── The empty states, at every family ──
//
// There was ONE of these (medium, `.noStations`), which is how the small
// family's empty panel kept its own layout long after the board stopped having
// one: the family with the least room was the family nobody was looking at.
// Four states times three canvases is too many previews to be useful, so these
// are the ones that can go wrong — the longest copy on the narrowest canvas,
// and the state that renders a station NAME rather than a fixed string.
// Built from the SAME factories the resolver returns (`WidgetData.needsStation`
// and friends), never from a hand-made `WidgetData` — a preview that constructs
// its own is a preview of a state the app cannot produce.
private func emptyPreview(_ data: WidgetData) -> DepartureEntry {
    DepartureEntry(date: Date(), widgetData: data)
}
private let removedPreview = WidgetData.removed(station: "Highbury & Islington")

#Preview("Small — choose a station", as: .systemSmall) {
    StationlyDepartureBoardWidget()
} timeline: { emptyPreview(.needsStation) }

#Preview("Small — removed", as: .systemSmall) {
    StationlyDepartureBoardWidget()
} timeline: { emptyPreview(removedPreview) }

#Preview("Medium — removed", as: .systemMedium) {
    StationlyDepartureBoardWidget()
} timeline: { emptyPreview(removedPreview) }

#Preview("Large — signed out", as: .systemLarge) {
    StationlyDepartureBoardWidget()
} timeline: { emptyPreview(.signedOut) }
#endif
