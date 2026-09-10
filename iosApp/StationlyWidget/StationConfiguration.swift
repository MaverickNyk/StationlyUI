import AppIntents
import Foundation
import SwiftUI
import UIKit
import WidgetKit

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - The station a widget is pinned to
//
// The iOS Weather model: one widget shows one place, and a user who wants three
// places adds three widgets. Long-pressing a widget → Edit Widget offers this
// list, exactly where anyone who has ever configured a Weather widget expects
// to find it.
//
// The alternative — one widget cycling through every station — was not built,
// and it is worth saying why: a widget is a GLANCE. Something whose meaning
// depends on which page it happens to be showing has to be read before it can
// be understood, which is the opposite of the job. Paging within a STATION
// (between its platforms) is a different matter: there the station is fixed and
// the pages are all answers to the same question.
// ─────────────────────────────────────────────────────────────────────────────

/// One of the user's stations, as offered in the widget's configuration.
///
/// `id` is the app's GROUPING id — the hub — which is the same thing one card
/// on the home screen is, so "a widget" and "a card" mean the same station and
/// nobody has to explain the difference. A bus hub's several poles are one
/// entry, not one per pole.
struct StationEntity: AppEntity, Identifiable, Hashable {
    let id: String
    let name: String
    let mode: String
    let lines: [String]

    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: "Station")
    }

    static var defaultQuery = StationEntityQuery()

    /// Name on top, what is tracked there underneath, the real TfL roundel
    /// alongside.
    ///
    /// The subtitle is the LINES rather than the mode, because at the moment of
    /// choosing, two entries called "Paddington" are told apart by what runs
    /// there and not by both being tube. The mode is carried by the roundel,
    /// where it costs no width.
    ///
    /// ## The picker's chrome is Apple's; the row's contents are ours
    /// The sheet, its dark background, its type and its checkmark are all
    /// system-drawn and cannot be themed — this is the same list the Weather
    /// widget's Location picker uses. The three things we DO control are the
    /// title, the subtitle and the image, so the image is worth getting right:
    /// a real roundel is the difference between "a system list" and "a system
    /// list of Stationly stations".
    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(
            title: "\(name)",
            subtitle: "\(subtitle)",
            image: RoundelImage.forMode(mode)
        )
    }

    /// The lines, already in DISPLAY form.
    ///
    /// KMP resolves them through `LineShortNames.displayName` before writing the
    /// directory, so there is no line vocabulary in this target at all. The
    /// first version prettified canonical ids here ("hammersmith-city" →
    /// "Hammersmith & City") and that was a second naming map: it disagreed with
    /// the app's own station settings screen on the same station, and the
    /// project's own note on `LineShortNames` says that map is a stopgap the
    /// backend is expected to take over — at which point one copy would have
    /// been updated and one forgotten.
    ///
    /// Capped at [maxNamedLines] with a count, because the subtitle is one line
    /// in a system row and King's Cross tracks four.
    private var subtitle: String {
        guard !lines.isEmpty else { return mode.capitalized }
        return lines.count > Self.maxNamedLines
            ? lines.prefix(Self.maxNamedLines).joined(separator: ", ")
                + " +\(lines.count - Self.maxNamedLines)"
            : lines.joined(separator: ", ")
    }

    private static let maxNamedLines = 3

    // ⚠️ There was a `tombstone(id:name:)` factory here, for describing a
    // station the user had deleted. It is gone, and it must not come back: iOS
    // PERSISTS whatever `StationEntityQuery.entities(for:)` returns into widget
    // configurations and its own recently-used cache, so an entity built to
    // describe an absence became a live candidate that kept re-assigning deleted
    // stations to widgets. The full measurement is on `entities(for:)`.
    //
    // Nothing else needs it. The board's removed state is `WidgetData.removed`,
    // built from the widget's own configuration, and never travels through an
    // AppEntity.

    /// SF Symbol of last resort — see [RoundelImage] for when it is reached.
    static func symbol(for mode: String) -> String {
        switch mode.lowercased() {
        case "bus":                    return "bus.fill"
        case "tram":                   return "tram.fill"
        case "dlr", "overground",
             "elizabeth", "elizabeth-line",
             "national-rail":          return "train.side.front.car"
        case "river-bus":              return "ferry.fill"
        case "cable-car":              return "cablecar.fill"
        default:                       return "tram.tunnel.fill"   // tube
        }
    }
}

/// The roundel shown against a station in the configuration picker.
///
/// ## The same fallback ladder the board itself uses
/// `ModeIconProvider` already resolves a mode to the backend's roundel: cached
/// PNG → the backend's tint on a drawn roundel → a hardcoded mode colour. This
/// is that ladder again, ending in PNG DATA rather than a SwiftUI view, because
/// `DisplayRepresentation.Image` takes `Data` (verified in the SDK's
/// `AppIntents.swiftinterface`) and nothing else here can render a view.
///
/// The middle rung matters more than it looks. The cached PNGs arrive from the
/// backend `/modes` endpoint and are simply absent on a fresh install, or on a
/// device where that fetch has not run — so a picker that only knew how to show
/// cached PNGs would show nothing at all on exactly the first launch where the
/// user is setting their widgets up. Drawing the roundel ourselves means the
/// list always carries the right mark in the right colour.
///
/// `isTemplate: false` throughout: a template image is re-tinted by the system
/// to its own foreground colour, which would flatten every roundel to one
/// shade and throw away the only thing distinguishing a bus stop from a tube
/// station at a glance.
enum RoundelImage {
    /// Rendered once per mode per process. The picker asks for a
    /// representation per row and re-asks on every redraw of the sheet, and the
    /// cached-PNG rung of the ladder below is file I/O plus a re-render.
    ///
    /// Behind a lock because `EntityQuery` methods are `async` and the system
    /// is free to run them off any executor: an unsynchronised static
    /// dictionary mutated from two of them is a genuine data race, not a
    /// theoretical one, and Dictionary is not merely lossy under concurrent
    /// mutation — it can crash while resizing its storage.
    private static let lock = NSLock()
    private static var cache: [String: DisplayRepresentation.Image] = [:]

    static func forMode(_ mode: String) -> DisplayRepresentation.Image {
        let key = mode.lowercased()
        lock.lock()
        let hit = cache[key]
        lock.unlock()
        if let hit { return hit }

        // Built OUTSIDE the lock: it touches the filesystem and renders a
        // bitmap, and holding a lock across that would serialise every row of
        // the picker behind the slowest one. Two threads racing to build the
        // same mode simply both build it and the second write wins — the
        // result is deterministic, so that costs one wasted render at most.
        let image = build(mode)
        lock.lock()
        cache[key] = image
        lock.unlock()
        return image
    }

    private static func build(_ mode: String) -> DisplayRepresentation.Image {
        // 1. The backend's own roundel, already re-encoded to an archive-safe
        //    bitmap by ModeIconProvider (see its note on WidgetArchiver Code=2).
        if let cached = ModeIconProvider.icon(mode), let data = cached.pngData() {
            return .init(data: data, isTemplate: false)
        }
        // 2. Draw one, in the backend's tint for this mode where it has sent
        //    one and the hardcoded mode colour otherwise — the same two rungs,
        //    in the same order, that ModeIconView falls through on the board.
        let tint = ModeIconProvider.tint(mode) ?? UIColor(WidgetTheme.modeColor(mode))
        if let data = drawnRoundel(tint: tint) {
            return .init(data: data, isTemplate: false)
        }
        // 3. A system symbol, so a row can never be blank.
        return .init(systemName: StationEntity.symbol(for: mode))
    }

    /// A TfL roundel: a thick ring with a bar across it. Same proportions as
    /// `TflRoundelMark`, which is what the board draws when the PNG is missing,
    /// so the two surfaces cannot show different marks for the same mode.
    private static func drawnRoundel(tint: UIColor) -> Data? {
        let side: CGFloat = 96
        let format = UIGraphicsImageRendererFormat()
        format.opaque = false
        format.scale = 1
        let size = CGSize(width: side * 1.16, height: side)
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let c = ctx.cgContext
            let line = side * 0.19
            let ringSide = side * 0.92
            let ringRect = CGRect(
                x: (size.width - ringSide) / 2,
                y: (size.height - ringSide) / 2,
                width: ringSide, height: ringSide
            ).insetBy(dx: line / 2, dy: line / 2)
            c.setStrokeColor(tint.cgColor)
            c.setLineWidth(line)
            c.strokeEllipse(in: ringRect)
            c.setFillColor(tint.cgColor)
            c.fill(CGRect(x: 0, y: (size.height - line) / 2, width: size.width, height: line))
        }.pngData()
    }
}

/// Serves the picker from the App Group directory KMP writes on every board
/// change (`AppGroupKeys.WIDGET_STATIONS`).
///
/// Reading from the App Group rather than asking the app is not an
/// optimisation: the widget editor routinely runs while the app is not, and an
/// `EntityQuery` that needed the app awake would show an empty list to anyone
/// configuring a widget from a cold home screen.
/// An `EntityStringQuery`, not a plain `EntityQuery`, so the search field at
/// the bottom of the system picker actually filters. A user with two stations
/// will never use it; one who tracks eight will, and an inert search box is
/// worse than none.
struct StationEntityQuery: EntityStringQuery {
    /// Resolves the value the Edit Widget sheet shows as *currently selected*.
    ///
    /// ## Three things this has to get right, and it used to get one
    /// It was `all.first { $0.id == id }` — an exact lookup that returns nothing
    /// for an id the directory no longer lists. Nothing is not neutral here:
    /// AppIntents falls back to the display representation it cached when the
    /// widget was configured, so the sheet showed a station that was **not in
    /// the list below it and not on the board**, with no explanation. That is
    /// the reported defect.
    ///
    ///  1. **A station that still exists resolves to the DIRECTORY's copy**, not
    ///     the configuration's. So a renamed station, or one whose tracked lines
    ///     changed, shows what the app shows today rather than what it was
    ///     called when the widget was set up.
    ///  2. **A station whose grouping id changed still resolves**, through
    ///     `StationResolver.directoryEntry` — the same match the board uses, so
    ///     the sheet and the widget can never disagree about whether a station
    ///     is gone.
    ///  3. **A station that is gone resolves to NOTHING.** Not a substitute, and
    ///     not a description of the absence either. See below — this one was
    ///     learned the hard way.
    ///
    /// ## ⚠️ Whatever this returns, iOS may WRITE INTO A WIDGET
    /// This is not a display hook. The entity handed back here is persisted as a
    /// widget's configuration and cached as a recently-used value for the intent
    /// type, from where iOS will use it to fill in a parameter that has none.
    ///
    /// **Measured, on device.** An earlier version answered a deleted station's
    /// id with a "tombstone" — the same id, the last known name, subtitle
    /// "Removed from Stationly" — on the theory that naming the absence was
    /// friendlier than a blank. The App Group told the real story: entries in
    /// `widget_placed` that had read `"mode":"overground"` came back as
    /// `"mode":""`, which is the tombstone's own signature, because the tombstone
    /// deliberately carries no mode. iOS had taken it and stored it.
    ///
    /// From there it spread. A dead station, confirmed by us as a resolvable
    /// entity, is a candidate for every widget with an unresolved parameter — so
    /// deleted stations kept reappearing on widgets the user had never pointed at
    /// them, and one capture had FIVE of six widgets configured for a station
    /// that no longer existed. `defaultResult()` never got a chance, because a
    /// parameter iOS can fill from its cache is not unresolved.
    ///
    /// So the rule is the plain one every other entity query follows: **answer
    /// for things that exist.** An id with no station behind it resolves to
    /// nothing, iOS has nothing to propagate, and an unresolved parameter has
    /// nowhere to go but `defaultResult()` — which is the only thing here that
    /// knows how to pick a station the user actually tracks.
    ///
    /// What is lost: the Edit sheet, opened on a widget whose station was
    /// deleted, falls back to the name AppIntents cached. That is a stale label
    /// on one row of one sheet, and the row below it is the live station the user
    /// came to pick. The board still names the dead station honestly — that runs
    /// off the widget's own configuration in `StationResolver`, and nothing here
    /// touches it.
    func entities(for identifiers: [String]) async throws -> [StationEntity] {
        let all = AppGroupStorage.shared.readStations()
        // Preserve the caller's order — it is asking about specific ids, and
        // one already-configured widget asks about exactly one.
        return identifiers.compactMap { id in
            // An unconfigured widget's parameter. Never was a station.
            guard !id.isEmpty else { return nil }
            return all.first { $0.id == id }
        }
    }

    /// Matches the station name AND its lines: "victoria" should find both
    /// Victoria the station and the stations that line calls at, because at the
    /// moment of typing the user has one of the two in mind and no way to say
    /// which.
    func entities(matching string: String) async throws -> [StationEntity] {
        let needle = string.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return AppGroupStorage.shared.readStations() }
        return AppGroupStorage.shared.readStations().filter { station in
            station.name.lowercased().contains(needle)
                || station.lines.contains { $0.lowercased().contains(needle) }
        }
    }

    func suggestedEntities() async throws -> [StationEntity] {
        AppGroupStorage.shared.readStations()
    }

    /// The station a new widget starts on: **the first one on the home screen.**
    ///
    /// ## Why this is a one-liner
    /// It used to distribute — the first station no placed widget was already
    /// configured for, wrapping when they were all taken — which needed the exact
    /// home screen, which needed a cached snapshot with a TTL, a rule about empty
    /// answers, and a fallback to placement stamps. About 320 lines to decide
    /// which station a new widget *suggests*, on a screen where the user is
    /// already looking at one labelled preview per station and can swipe.
    ///
    /// It was also the source of a run of bugs, because everything it depended on
    /// moves: stations get added, widgets come and go, and the snapshot is only
    /// as fresh as the last time something asked for it.
    ///
    /// The user picks in the gallery. This just has to be a sane, stable, honest
    /// default, and "the station at the top of your home screen" is all three.
    ///
    /// Nil when the user tracks no stations. The widget then renders
    /// `WidgetData.needsStation` and asks to be given one, which is the only
    /// truthful thing available — see `StationResolver` rung 3.
    func defaultResult() async -> StationEntity? {
        AppGroupStorage.shared.readStations().first
    }
}

/// The widget's configuration: which station.
///
/// Deliberately ONE parameter. Everything else about the board — how deep each
/// platform goes, what it is ordered by — is set per station in the app, and a
/// second copy of those knobs in the widget editor would be a second answer to
/// the same question with no rule for which wins.
struct SelectStationIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Choose Station"
    /// Shown under the picker in Edit Widget, and it is the one place to answer
    /// the question the sheet raises by having exactly one row: *is this all I
    /// can set?* It is — everything else about the board is the station's own
    /// setting in the app, and saying so here stops someone hunting for depth
    /// and ordering controls that were deliberately not put in two places.
    ///
    /// One string literal on one line, and it has to be: `IntentDescription`
    /// takes a `LocalizedStringResource`, which is built from a literal at
    /// COMPILE time so the string can be extracted for localisation. A `+`
    /// concatenation is an ordinary `String` and does not match the initialiser.
    static var description = IntentDescription("Pick the station this widget shows. How many departures it lists, and which platform comes first, are set per station in Stationly.")

    /// "Station" and not "Board": the row's VALUE is a station name, and a
    /// label that disagrees with the thing next to it makes the user read both.
    @Parameter(title: "Station")
    var station: StationEntity?

    init() {}

    init(station: StationEntity?) {
        self.station = station
    }
}
