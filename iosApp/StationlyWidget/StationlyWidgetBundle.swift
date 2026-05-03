import WidgetKit
import SwiftUI

/// Entry point for the widget extension. All Widget types declared in this
/// extension must be listed here.
@main
struct StationlyWidgetBundle: WidgetBundle {
    var body: some Widget {
        StationlyDepartureBoardWidget()
    }
}
