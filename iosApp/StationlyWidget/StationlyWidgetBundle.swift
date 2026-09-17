import WidgetKit
import SwiftUI

/// Entry point for the widget extension. All Widget types declared in this
/// extension must be listed here.
///
/// ## Why this extension requires iOS 26
/// The push-enabled variant is used unconditionally, which is what forced the
/// target to 26.0 (see project.yml). It could not be selected any other way:
/// `.pushHandler` returns a different opaque type so it cannot be applied
/// inside an `if #available`, SwiftUI ships no `AnyWidgetConfiguration` to
/// erase the branch, and `WidgetBundleBuilder` has no `buildEither` — its
/// `buildOptional` is explicitly marked *"if statements in a
/// WidgetBundleBuilder can only be used with #available clauses"*. A bundle
/// can conditionally ADD a widget, never swap one for another, and the two
/// variants share a `kind` so registering both is not an option either.
///
/// ## Why it was worth requiring
/// A silent push wakes the APP, and iOS will not honour `reloadAllTimelines()`
/// from a background-woken app — measured on device: fresh data written at
/// 23:35:24, extension did not rebuild until the app was foregrounded three
/// minutes later, with the next scheduled rebuild 395 minutes out. Without
/// `.pushHandler` the widget simply cannot be repainted on demand, which is
/// the entire point of a disruption trigger.
@main
struct StationlyWidgetBundle: WidgetBundle {
    var body: some Widget {
        StationlyDepartureBoardWidgetPushable()
    }
}
