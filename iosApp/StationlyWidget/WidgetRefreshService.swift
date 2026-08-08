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
    // Key names live in AppGroupKeys, one place per target. Two of them are
    // worth knowing about here:
    //   - `refreshFailed` is set when a refresh could not complete and cleared
    //     on success. It is the ONLY refresh feedback a widget can show, because
    //     it outlives `perform()` and so survives into the render WidgetKit does
    //     afterwards; the header reads it directly.
    //   - `lastRefreshOk` exists for the on-device trace and deliberately drives
    //     no UI. A success glyph was tried and removed (see RefreshButton):
    //     swapping the arrow on the happy path destroys the button's affordance.

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
        var entries = (d.array(forKey: AppGroupKeys.refreshTrace) as? [String]) ?? []
        entries.append("\(Int(Date().timeIntervalSince1970)) \(msg)")
        if entries.count > 20 { entries = Array(entries.suffix(20)) }
        d.set(entries, forKey: AppGroupKeys.refreshTrace)
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
        d.set(true, forKey: AppGroupKeys.refreshFailed)
        d.synchronize()
        WidgetCenter.shared.reloadTimelines(ofKind: StationlyDepartureBoardWidget.kind)
    }

    /// Returns without touching the network when tapped inside the debounce
    /// window, so a user drumming on the button can't fan out to TfL.
    ///
    /// [configuredStation] is the grouping id the tapped widget is pinned to.
    /// It decides both what is fetched and where the result is written: with
    /// several widgets on screen, refreshing "the widget" would rewrite the
    /// legacy keys and leave the board under the user's finger untouched while
    /// silently changing a different one.
    ///
    /// The debounce is deliberately GLOBAL rather than per station. It exists
    /// to protect TfL from a user drumming on a button, and three widgets are
    /// three buttons — a per-station window would let the same finger fan out
    /// as wide as the home screen has widgets.
    static func refresh(stationId configuredStation: String = "") async -> RefreshOutcome {
        guard let d = defaults else { return .unavailable }

        let now = Date().timeIntervalSince1970
        let last = d.double(forKey: AppGroupKeys.lastManualRefresh)
        let window = d.bool(forKey: AppGroupKeys.refreshFailed) ? failedRetrySeconds : debounceSeconds
        if last > 0, now - last < window { return .debounced }

        // The configured board when there is one, the legacy keys otherwise —
        // exactly the fallback the renderer uses, so the refresh can never
        // target a board other than the one on screen.
        let board = AppGroupStorage.shared.readWidgetData(stationId: configuredStation)
        let feeds: [BoardFeed] = board.feeds.isEmpty
            ? legacyFeed(d).map { [$0] } ?? []
            : board.feeds

        // ONE call per distinct naptan, which is one call for every rail
        // station and one per POLE at a bus hub — the poles are separate stops
        // with separate ids and the endpoint is addressed by naptan. Capped:
        // a hub with a dozen poles would spend the intent's whole window on
        // network and be killed mid-flight.
        //
        // Derived BEFORE the guard so the guard tests what will actually be
        // fetched. Testing `feeds.first` instead would bail on a board whose
        // first feed happens to carry a blank naptan while the rest are fine.
        var seenNaptans = Set<String>()
        let naptans = feeds.map { $0.station }
            .filter { !$0.isEmpty && seenNaptans.insert($0).inserted }
            .prefix(maxNaptansPerRefresh)

        guard !naptans.isEmpty,
              let baseUrl = d.string(forKey: AppGroupKeys.apiBaseURL), !baseUrl.isEmpty,
              let apiKey = d.string(forKey: AppGroupKeys.apiKey), !apiKey.isEmpty
        else { return .unavailable }

        // Claim the window BEFORE awaiting: two taps landing together would
        // otherwise both pass the check above and both hit the network.
        d.set(now, forKey: AppGroupKeys.lastManualRefresh)

        // NO in-flight/"loading" state is attempted here. Verified on device:
        // WidgetKit does not rasterise a reload requested from inside
        // `perform()` — the only render happens after perform() returns — so
        // a spinner or dimmed board is unreachable no matter how it's drawn.
        // What IS reachable is the OUTCOME, because it outlives perform();
        // see `markFailed`, the refreshFailed clear on the success path, and the
        // header's warning glyph.

        // Any naptan failing abandons the whole refresh, discarding what the
        // earlier ones returned. Deliberate: half a board written over a whole
        // one is a board that has silently lost a platform, and the retry glyph
        // asks for a tap that will fix it.
        do {
            var rows: [DepartureRow] = []
            for naptan in naptans {
                guard let url = URL(string: "\(baseUrl)/api/v1/stations/predictions/\(naptan)") else {
                    continue
                }
                var request = URLRequest(url: url)
                request.setValue(apiKey, forHTTPHeaderField: "X-Stationly-Key")
                request.timeoutInterval = 10

                trace("fetch \(naptan) feeds=\(feeds.count)")
                let (data, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                    trace("http \((response as? HTTPURLResponse)?.statusCode ?? -1)")
                    markFailed()
                    return .failed
                }
                // Every (line, direction) this station tracks AT THIS NAPTAN,
                // merged. The endpoint answers with every line calling there,
                // so without the feed list a refresh would put lines on the
                // board that the user never asked to track.
                guard let mapped = mapPayload(
                    data,
                    feeds: feeds.filter { $0.station == naptan }
                ) else {
                    trace("decode-failed bytes=\(data.count)")
                    markFailed()
                    return .failed
                }
                rows.append(contentsOf: mapped)
            }
            // Re-group across naptans, so a bus hub's two poles interleave by
            // platform exactly as KMP's GlobalBoardProcessor left them.
            let merged = groupByPlatformSorted(rows)
            trace("rows=\(merged.count)")

            // Only the rows and the timestamp change — station/line/mode
            // identity is owned by KMP and must not be rewritten from here.
            let encoded = try JSONEncoder().encode(merged)
            let encodedString = String(data: encoded, encoding: .utf8) ?? "[]"
            if board.stationId.isEmpty {
                // Legacy path: no configured station, so the flat keys ARE the
                // board being shown.
                d.set(encodedString, forKey: AppGroupKeys.predictions)
                d.set(now, forKey: AppGroupKeys.lastUpdated)
            } else {
                writeBack(d, stationId: board.stationId, predictions: encodedString, at: now)
            }
            d.set(d.integer(forKey: AppGroupKeys.reloadSignal) &+ 1, forKey: AppGroupKeys.reloadSignal)

            // MUST flush before asking for a reload. cfprefsd can hold the
            // write in cache, and WidgetKit may regenerate the timeline in a
            // freshly-launched extension process that reads from disk — which
            // silently renders the PREVIOUS board despite the write having
            // "succeeded". KMP's IosWidgetManager.write ends the same way.
            d.set(false, forKey: AppGroupKeys.refreshFailed)
            d.set(now, forKey: AppGroupKeys.lastRefreshOk)
            d.synchronize()

            trace("wrote ok \(board.stationId.isEmpty ? "legacy" : board.stationId)")
            WidgetCenter.shared.reloadTimelines(ofKind: StationlyDepartureBoardWidget.kind)
            return .refreshed
        } catch {
            trace("threw \(error.localizedDescription)")
            markFailed()
            return .failed
        }
    }

    private static let maxNaptansPerRefresh = 3

    /// The single (line, direction) the pre-multi-station keys describe.
    ///
    /// Only reachable from a widget with no configured station — one added
    /// before this build, or one whose station has been deleted. Kept because
    /// dropping it would silently turn that widget's refresh button into a
    /// no-op with nothing on screen to explain why.
    private static func legacyFeed(_ d: UserDefaults) -> BoardFeed? {
        guard let station = d.string(forKey: AppGroupKeys.stationId), !station.isEmpty,
              let line = d.string(forKey: AppGroupKeys.lineName), !line.isEmpty
        else { return nil }
        return BoardFeed(station: station, line: line,
                         direction: d.string(forKey: AppGroupKeys.direction) ?? "")
    }

    /// Patch the refreshed rows and timestamp into one station's stored board,
    /// leaving every other field exactly as KMP wrote it.
    ///
    /// A read-modify-write of the JSON rather than a rewrite: identity (name,
    /// mode, feeds, line) is the app's to own, and this process cannot
    /// reconstruct it — it has no SQLite and no idea what the user tracks. Any
    /// field it failed to copy back would silently vanish from the board.
    private static func writeBack(_ d: UserDefaults, stationId: String,
                                  predictions: String, at now: TimeInterval) {
        let key = AppGroupKeys.board(stationId)
        guard let raw = d.string(forKey: key),
              let data = raw.data(using: .utf8),
              var object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let rows = (try? JSONSerialization.jsonObject(with: Data(predictions.utf8))) as? [Any]
        else { return }
        object["predictions"] = rows
        object["lastUpdated"] = Int(now)
        guard let out = try? JSONSerialization.data(withJSONObject: object),
              let string = String(data: out, encoding: .utf8) else { return }
        d.set(string, forKey: key)
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

    /// Every tracked (line, direction) at one naptan, merged.
    ///
    /// Takes a LIST because a widget is configured with a station and a station
    /// can track several lines both ways — the same merge KMP does in
    /// `IosWidgetManager.buildBoard`. Deduplication spans the whole list, so a
    /// train appearing under two of the user's feeds is one row.
    static func mapPayload(_ data: Data, feeds: [BoardFeed]) -> [DepartureRow]? {
        guard let payload = try? JSONDecoder().decode(PredictionsResponse.self, from: data),
              let lines = payload.lines else { return nil }

        var seen = Set<String>()
        var mapped: [DepartureRow] = []
        for feed in feeds {
            // Case-insensitive line then direction lookup, matching the Kotlin
            // fallback chain (the API is not consistent about casing).
            let lineKey = feed.line.lowercased()
            guard let line = lines[lineKey]
                    ?? lines.first(where: { $0.key.lowercased() == lineKey })?.value
            else { continue }

            let dirs = line.dirs ?? [:]
            let dirKey = feed.direction.lowercased()
            let preds = (dirs[feed.direction] ?? dirs[dirKey]
                         ?? dirs.first(where: { $0.key.lowercased() == dirKey })?.value)?.preds ?? []
            mapRows(preds, into: &mapped, seen: &seen)
        }
        return groupByPlatformSorted(mapped)
    }

    private static func mapRows(_ preds: [PredictionsResponse.Pred],
                                into mapped: inout [DepartureRow],
                                seen: inout Set<String>) {
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
