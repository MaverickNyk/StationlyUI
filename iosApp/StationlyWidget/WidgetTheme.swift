import SwiftUI

/// Shared design tokens for the Stationly departure-board widget.
/// TfL amber on pure black, dark-mode only — mirrors the Android/KMP theme.
enum WidgetTheme {

    // MARK: - Palette

    /// TfL amber: #FFC819
    static let amber         = Color(red: 1.000, green: 0.784, blue: 0.098)
    /// Dimmed amber for secondary amber text / inactive elements
    static let amberDim      = Color(red: 0.720, green: 0.560, blue: 0.100)

    /// Pure black card / widget background, and the colour of the 2pt gaps
    /// between cells, which is what reads as the panel's bezel.
    static let background    = Color.black
    /// Every lit cell on the board. There is ONE surface: a `surface` at 0.07
    /// sat here for "header panels" and was never used, because §3.1 settled
    /// that the header and footer are cells like any other.
    static let rowSurface    = Color(white: 0.10)

    // MARK: - Text
    //
    // There is no text palette, and that is the point.
    //
    // A white/grey set lived here — `textPrimary` (white), `textSecondary`
    // (0.65), `textMuted` (0.40) — and it was used in exactly four places, all
    // of them STATIC TEXT: the "no departures" cell and the three lines of the
    // empty state. So every word this board wrote itself was the only text on
    // the panel that wasn't board-amber, while every word it got from TfL was.
    // The grey was also close to unreadable where it landed: 0.40 white on the
    // 0.10 row surface is about 3:1, against amber's 11:1, at 11pt.
    //
    // Text on this board is [amber]. The hierarchy is size and weight, per the
    // note under `font` — a second colour would be a second voice, and the one
    // thing a signage panel does not have is two voices. [amberDim] is not a
    // quieter amber for that purpose either; it means SPENT (a departed row),
    // which an instruction the user is meant to act on is not.
    //
    // `stationlyRed` (the maker mark draws its own colours) and
    // `etaColor(eta:isDue:)` went with them. The latter is worth naming because
    // it was a trap rather than merely dead: it returned amber / white / grey by
    // parsed minutes, which is a DIFFERENT colour policy from the one the board
    // actually applies in `DotMatrixRow` (red when due, amber when live,
    // amberDim once departed). Two rules for one thing, with the unused one
    // looking authoritative because it sat in the theme.

    // MARK: - Type
    //
    // ONE typeface for the whole board, and this is the only place it is named.
    //
    // The board used to mix three faces without meaning to: SF Pro for names and
    // headers, SF **Mono** wherever a number appeared (the ETA, the "2/4" page
    // marker, the wall clock) and SF Pro **italic** for the "ago" timer. Each of
    // those was a locally sensible decision — mono for digits, italic for a
    // secondary note — and together they read as a panel assembled from parts.
    // A departure board is signage: every glyph on it comes off the same
    // machine, and the hierarchy is carried by SIZE and WEIGHT alone.
    //
    // SF Pro is the face, matching Android's board (`widget_departure_row.xml`
    // and friends set no `fontFamily` — see docs/BOARD_DOTMATRIX_FONT.md for the
    // DotGothic16 experiment that was tried on iOS and reverted for exactly this
    // parity reason).
    //
    // **Not even tabular figures.** `monospacedDigit()` was applied for a while
    // at the three call sites that tick (clock, "ago", ETA), on the reasoning
    // that a per-second number needs a fixed advance or its column twitches.
    // Sound in isolation, and wrong here: the in-app board sets NO digit
    // modifier on any of the three, tabular figures are visibly wider and more
    // evenly spaced, and the comparison a user actually makes is between this
    // widget and the app's own board seconds later. Two clocks that do not look
    // like one product is a worse defect than a digit that shifts a point.
    // If the jitter ever needs fixing it is a defect on BOTH surfaces.
    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight)
    }

    // MARK: - Status colours
    // (No per-severity tinting: the line-status strip is board-amber like every
    // other cell — severity is carried by the bold weight, not colour.)

    // MARK: - Mode roundel tints (mirror the in-app board's modeRoundelColor)

    static func modeColor(_ mode: String) -> Color {
        switch mode.lowercased() {
        case "dlr":                          return Color(red: 0.000, green: 0.643, blue: 0.655) // #00A4A7
        case "overground":                   return Color(red: 0.933, green: 0.486, blue: 0.055) // #EE7C0E
        case "elizabeth", "elizabeth-line":  return Color(red: 0.412, green: 0.314, blue: 0.631) // #6950A1
        case "tram":                         return Color(red: 0.518, green: 0.722, blue: 0.090) // #84B817
        default:                             return Color(red: 0.863, green: 0.141, blue: 0.122) // #DC241F tube/bus
        }
    }
}
