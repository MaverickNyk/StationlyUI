import Foundation
import WidgetKit

/// The widget's own refresh path — the iOS answer to Android's
/// `ACTION_MANUAL_REFRESH` in DepartureWidgetProvider.
///
/// Android re-runs the whole KMP pipeline (DepartureRepository →
/// SyncPredictionsUseCase → SQL → fan-out). This extension is a separate
/// process that can reach neither KMP nor the SQLite file, so it calls the
/// same REST endpoint the app does and maps the payload itself.
///
/// The duplication is deliberately kept to the MINIMUM that can't be avoided:
/// selecting the line/direction, ISO→epoch parsing, destination cleanup, and
/// the sort/cap. Every ETA *label* is still produced by `WidgetData.ticked`
/// at render time from `targetEpochMs`, so the rounding and per-platform bump
/// rules keep living in exactly one place on this side. Anything added here
/// must stay in lockstep with SyncPredictionsUseCase.
///
/// Known, accepted gap vs Android: this writes only the App Group (it can't
/// open the app's SQLite), so the refreshed board reaches the widget but not
/// the app's own store. The app re-syncs on next foreground, and every other
/// path (FCM, app refresh) still writes SQL normally.
enum WidgetRefreshService {

    private static let appGroupID = AppGroupID.value

    /// Matches Android's MANUAL_REFRESH_DEBOUNCE_MS. The button has no spam
    /// protection of its own and TfL rate-limits aggressive callers.
    private static let debounceSeconds: TimeInterval = 15
    /// Debounce applied instead of [debounceSeconds] when the previous attempt
    /// failed. iOS renders "Refresh failed, tap to retry" in that state —
    /// an affordance Android's widget doesn't have — and holding the full
    /// window behind it would make the button advertise a retry it silently
    /// refuses. Short enough to feel immediate, long enough that spam-tapping
    /// a broken backend still can't fan out.
    private static let failedRetrySeconds: TimeInterval = 3
    private static let lastRefreshKey = "widget_last_manual_refresh"
    /// Set when a refresh couldn't complete, cleared on success. This is the
    /// only refresh feedback a widget can actually show: it persists past
    /// `perform()`, so it survives into the render WidgetKit does afterwards.
    static let failedKey = "widget_refresh_failed"
    /// Epoch-seconds of the last SUCCESSFUL manual refresh. Kept for the
    /// on-device trace only — it deliberately drives no UI: a success glyph
    /// was tried and removed (see RefreshButton) because swapping the arrow
    /// on the happy path destroys the button's affordance.
    static let lastOkKey = "widget_last_refresh_ok"

    /// Mirrors SyncPredictionsUseCase's perPlatformCap = 8 — reserves for the
    /// tick layer to shift into the visible 3-row window, not rows to render.
    private static let perPlatformCap = 8

    private static var defaults: UserDefaults? { UserDefaults(suiteName: appGroupID) }

    /// Explicit ephemeral session rather than `URLSession.shared`: the shared
    /// session is documented as unavailable/limited inside app extensions,
    /// and an extension has no cache or cookie store worth keeping anyway.
    /// The timeout is short because the whole call has to finish inside the
    /// brief window the system gives an intent before reclaiming the process.
    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10
        // Left at the default (true) deliberately: with it off, a momentary
        // radio blip fails the tap instantly with "internet appears to be
        // offline" instead of riding it out. The 10s timeout is the real
        // bound, so waiting costs nothing worse than a slower failure.
        config.waitsForConnectivity = true
        return URLSession(configuration: config)
    }()

    /// Extension-local breadcrumb trail. A widget extension has no attachable
    /// console and no way to surface an error in its own UI, so failures are
    /// otherwise indistinguishable from "the button did nothing". Pull with
    /// `devicectl device copy from --domain-type appGroupDataContainer`.
    private static func trace(_ msg: String) {
        guard let d = defaults else { return }
        var entries = (d.array(forKey: "widget_refresh_trace") as? [String]) ?? []
        entries.append("\(Int(Date().timeIntervalSince1970)) \(msg)")
        if entries.count > 20 { entries = Array(entries.suffix(20)) }
        d.set(entries, forKey: "widget_refresh_trace")
    }

    enum RefreshOutcome { case refreshed, debounced, unavailable, failed }

    /// Record a failed attempt and re-render so the header can show the retry
    /// glyph.
    ///
    /// The debounce claim is deliberately left in place (a failed request
    /// still reached the backend), but [refresh] applies the much shorter
    /// [failedRetrySeconds] window while this flag is set — see the note
    /// there for why the full 15s would make the button lie.
    private static func markFailed() {
        guard let d = defaults else { return }
        d.set(true, forKey: failedKey)
        d.synchronize()
        WidgetCenter.shared.reloadTimelines(ofKind: StationlyDepartureBoardWidget.kind)
    }

    /// Returns without touching the network when tapped inside the debounce
    /// window, so a user drumming on the button can't fan out to TfL.
    static func refresh() async -> RefreshOutcome {
        guard let d = defaults else { return .unavailable }

        let now = Date().timeIntervalSince1970
        let last = d.double(forKey: lastRefreshKey)
        let window = d.bool(forKey: failedKey) ? failedRetrySeconds : debounceSeconds
        if last > 0, now - last < window { return .debounced }

        guard let stationId = d.string(forKey: "widget_station_id"), !stationId.isEmpty,
              let baseUrl = d.string(forKey: "widget_api_base_url"), !baseUrl.isEmpty,
              let apiKey = d.string(forKey: "widget_api_key"), !apiKey.isEmpty,
              let lineId = d.string(forKey: "widget_line_name"), !lineId.isEmpty
        else { return .unavailable }

        let direction = d.string(forKey: "widget_direction") ?? ""

        // Claim the window BEFORE awaiting: two taps landing together would
        // otherwise both pass the check above and both hit the network.
        d.set(now, forKey: lastRefreshKey)

        // NO in-flight/"loading" state is attempted here. Verified on device:
        // WidgetKit does not rasterise a reload requested from inside
        // `perform()` — the only render happens after perform() returns — so
        // a spinner or dimmed board is unreachable no matter how it's drawn.
        // What IS reachable is the OUTCOME, because it outlives perform();
        // see `markFailed`, the `failedKey` clear on the success path, and the
        // header's warning glyph.

        guard let url = URL(string: "\(baseUrl)/api/v1/stations/predictions/\(stationId)") else {
            return .unavailable
        }
        var request = URLRequest(url: url)
        request.setValue(apiKey, forHTTPHeaderField: "X-Stationly-Key")
        request.timeoutInterval = 10

        do {
            trace("fetch \(stationId)/\(lineId)/\(direction)")
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                trace("http \((response as? HTTPURLResponse)?.statusCode ?? -1)")
                markFailed()
                return .failed
            }
            guard let rows = mapPayload(data, lineId: lineId, direction: direction) else {
                trace("decode-failed bytes=\(data.count)")
                markFailed()
                return .failed
            }
            trace("rows=\(rows.count)")

            // Only the rows and the timestamp change — station/line/mode
            // identity is owned by KMP and must not be rewritten from here.
            let encoded = try JSONEncoder().encode(rows)
            d.set(String(data: encoded, encoding: .utf8) ?? "[]", forKey: "widget_predictions")
            d.set(now, forKey: "widget_last_updated")
            d.set(d.integer(forKey: "widget_reload_signal") &+ 1, forKey: "widget_reload_signal")

            // MUST flush before asking for a reload. cfprefsd can hold the
            // write in cache, and WidgetKit may regenerate the timeline in a
            // freshly-launched extension process that reads from disk — which
            // silently renders the PREVIOUS board despite the write having
            // "succeeded". KMP's IosWidgetManager.write ends the same way.
            d.set(false, forKey: failedKey)
            d.set(now, forKey: lastOkKey)
            d.synchronize()

            trace("wrote ok")
            WidgetCenter.shared.reloadTimelines(ofKind: StationlyDepartureBoardWidget.kind)
            return .refreshed
        } catch {
            trace("threw \(error.localizedDescription)")
            markFailed()
            return .failed
        }
    }

    // MARK: - Payload mapping (mirror of SyncPredictionsUseCase steps 1-5)

    /// Wire shape of GET /api/v1/stations/predictions/:naptanId — the same
    /// `FcmPayload` schema the FCM topic delivers, so one mapper serves both.
    private struct PredictionsResponse: Decodable {
        struct Line: Decodable { let dirs: [String: Direction]? }
        struct Direction: Decodable { let preds: [Pred]? }
        struct Pred: Decodable {
            let displayName: String?
            let platform: String?
            let eta: String?
            let stopLetter: String?
        }
        let lines: [String: Line]?
    }

    static func mapPayload(_ data: Data, lineId: String, direction: String) -> [DepartureRow]? {
        guard let payload = try? JSONDecoder().decode(PredictionsResponse.self, from: data),
              let lines = payload.lines else { return nil }

        // Case-insensitive line then direction lookup, matching the Kotlin
        // fallback chain (the API is not consistent about casing).
        let lineKey = lineId.lowercased()
        guard let line = lines[lineKey]
                ?? lines.first(where: { $0.key.lowercased() == lineKey })?.value
        else { return [] }

        let dirs = line.dirs ?? [:]
        let dirKey = direction.lowercased()
        let preds = (dirs[direction] ?? dirs[dirKey]
                     ?? dirs.first(where: { $0.key.lowercased() == dirKey })?.value)?.preds ?? []

        var seen = Set<String>()
        var mapped: [DepartureRow] = []
        for p in preds {
            let target = parseTargetEpochMs(p.eta)
            // "Unknown" is legacy for an unassigned stop; everything else is
            // backend-owned and passed through verbatim (see the platform
            // note in SyncPredictionsUseCase).
            let rawPlatform = p.platform ?? ""
            let platform = rawPlatform.caseInsensitiveCompare("Unknown") == .orderedSame ? "" : rawPlatform
            let destination = formatDestination(p.displayName ?? "")

            // Dedupe on absolute arrival time, not the label — two trains in
            // the same minute bucket are distinct rows.
            let key = "\(destination)_\(platform)_\(target.map(String.init) ?? (p.eta ?? ""))"
            if seen.contains(key) { continue }
            seen.insert(key)

            mapped.append(DepartureRow(
                destination: destination,
                platform: platform,
                // Placeholder only: `ticked(at:)` overwrites this from
                // targetEpochMs before anything renders.
                eta: "",
                isDue: false,
                stopLetter: p.stopLetter,
                targetEpochMs: target.map(Double.init)
            ))
        }

        return groupByPlatformSorted(mapped)
    }

    /// Mirror of GlobalBoardProcessor.processPredictions: rows sorted by
    /// arrival, platform groups ordered by their earliest arrival, each
    /// capped. Null targets sink last rather than posing as 0-min.
    private static func groupByPlatformSorted(_ rows: [DepartureRow]) -> [DepartureRow] {
        let sorted = rows.sorted { ($0.targetEpochMs ?? .greatestFiniteMagnitude)
                                 < ($1.targetEpochMs ?? .greatestFiniteMagnitude) }
        let groups = Dictionary(grouping: sorted, by: { $0.platform })
        let orderedKeys = groups.keys.sorted { a, b in
            let ea = groups[a]?.compactMap(\.targetEpochMs).min() ?? .greatestFiniteMagnitude
            let eb = groups[b]?.compactMap(\.targetEpochMs).min() ?? .greatestFiniteMagnitude
            if ea == eb { return a < b }   // stable for equal/absent targets
            return ea < eb
        }
        return orderedKeys.flatMap { (groups[$0] ?? []).prefix(perPlatformCap) }
    }

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        // TfL timestamps carry fractional seconds; without this option the
        // parse returns nil and every row silently loses its target.
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let isoFormatterNoFraction = ISO8601DateFormatter()

    static func parseTargetEpochMs(_ iso: String?) -> Int64? {
        guard let iso, !iso.isEmpty else { return nil }
        let date = isoFormatter.date(from: iso) ?? isoFormatterNoFraction.date(from: iso)
        guard let date else { return nil }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    /// Mirror of StationlyFormatters.formatDestination.
    static func formatDestination(_ name: String) -> String {
        let clean = name
            .replacingOccurrences(of: " Underground Station", with: "")
            .replacingOccurrences(of: " DLR Station", with: "")
            .replacingOccurrences(of: " Rail Station", with: "")
            .trimmingCharacters(in: .whitespaces)
        return clean.count > 25 ? String(clean.prefix(22)) + "..." : clean
    }
}
