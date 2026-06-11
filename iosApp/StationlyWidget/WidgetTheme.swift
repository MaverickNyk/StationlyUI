import SwiftUI

/// Shared design tokens for the Stationly departure-board widget.
/// TfL amber on pure black, dark-mode only — mirrors the Android/KMP theme.
enum WidgetTheme {

    // MARK: - Palette

    /// TfL amber: #FFC819
    static let amber         = Color(red: 1.000, green: 0.784, blue: 0.098)
    /// Dimmed amber for secondary amber text / inactive elements
    static let amberDim      = Color(red: 0.720, green: 0.560, blue: 0.100)

    /// Pure black card / widget background
    static let background    = Color.black
    /// Slightly lifted surface (header panels)
    static let surface       = Color(white: 0.07)
    /// Row background — just enough lift to separate rows
    static let rowSurface    = Color(white: 0.10)

    // MARK: - Text

    static let textPrimary   = Color.white
    static let textSecondary = Color(white: 0.65)
    static let textMuted     = Color(white: 0.40)

    // MARK: - Status colours
    // (No per-severity tinting: the line-status strip is board-amber like every
    // other cell — severity is carried by the bold weight, not colour.)

    /// Stationly brand red (#E32017) — the footer maker mark.
    static let stationlyRed  = Color(red: 0.890, green: 0.125, blue: 0.090)

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

    // MARK: - Dynamic helpers

    /// Colour to display next to an ETA value:
    /// - Due / ≤2 min → amber (urgent)
    /// - ≤5 min       → white (near)
    /// - otherwise    → secondary grey
    static func etaColor(eta: String, isDue: Bool) -> Color {
        if isDue { return amber }
        // Strip " min" suffix and parse integer minutes
        let digits = eta.replacingOccurrences(of: " min", with: "")
            .trimmingCharacters(in: .whitespaces)
        if let mins = Int(digits) {
            if mins <= 2 { return amber }
            if mins <= 5 { return textPrimary }
        }
        return textSecondary
    }
}
