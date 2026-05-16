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

    /// Good Service / Normal: #4CAF50
    static let goodService   = Color(red: 0.298, green: 0.686, blue: 0.314)
    /// Disruption / Delay: TfL disruption orange #F16130
    static let disruption    = Color(red: 0.945, green: 0.384, blue: 0.169)

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

    /// Colour for the service status indicator dot / label.
    static func statusColor(status: String) -> Color {
        let lower = status.lowercased()
        if lower.contains("good") || lower.contains("normal") || lower.contains("no issues") {
            return goodService
        }
        if lower.contains("suspend") || lower.contains("close") || lower.contains("no service") {
            return Color.red
        }
        return disruption
    }
}
