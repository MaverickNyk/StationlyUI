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

    /// `#RRGGBB` to a `Color`, or nil when it is not that.
    ///
    /// The palette crosses the App Group as hex strings because that is the one
    /// shape Kotlin and Swift already agree about — see `LinePalette`. Returning
    /// nil rather than a guess keeps a malformed value from painting something
    /// arbitrary; the callers fall back to a known colour.
    static func color(hex: String) -> Color? {
        var s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        return Color(red: Double((v >> 16) & 0xFF) / 255.0,
                     green: Double((v >> 8) & 0xFF) / 255.0,
                     blue: Double(v & 0xFF) / 255.0)
    }

    /// Roundel tint per transport mode.
    ///
    /// This used to be a hardcoded switch — the fourth copy of the same five
    /// values, beside three in Kotlin and one on the backend. It now reads the
    /// palette the app publishes into the App Group, so the widget's roundel and
    /// the in-app station strip cannot be two shades of the same thing.
    ///
    /// The default argument is the compiled palette, so a preview or a call site
    /// with no published table still renders.
    static func modeColor(_ mode: String, palette: ModePalette = .compiled) -> Color {
        // Two fallbacks deep on purpose: a malformed served hex falls to the
        // palette's own fallback, and a malformed fallback falls to the compiled
        // red. A roundel is the widget's most recognisable mark — it must never
        // render as nothing because someone typed a colour wrong.
        color(hex: palette.hex(for: mode))
            ?? color(hex: ModePalette.compiled.fallback)
            ?? Color(red: 0.863, green: 0.141, blue: 0.122)
    }
}
