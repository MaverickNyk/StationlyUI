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

    /// One station to rebuild: where to write it, and which feeds it is made of.
    ///
    /// `stationId` is empty for the legacy flat-key board, which is exactly the
    /// distinction `writeBack` needs and the same one `readWidgetData` already
    /// draws — so an unconfigured widget stays on the path it has always used.
    private struct RefreshTarget {
        let stationId: String
        let feeds: [BoardFeed]
        /// For the trace only — an empty id reads as nothing at all in a log line.
        var label: String { stationId.isEmpty ? "legacy" : stationId }
    }

    /// Record a failed attempt against ONE station and re-render so its header
    /// can show the retry glyph.
    ///
    /// Per station rather than global: with several widgets on the screen a
    /// single flag makes every board that refreshed FINE display a failure
    /// warning, which is the opposite of what the glyph is for. The one honest
    /// global reading would be "everything failed", and that is a state the
    /// caller can compose out of the per-station flags anyway.
    ///
    /// The debounce claim is deliberately left in place (a failed request still
    /// reached the backend), but [refresh] applies the much shorter
    /// [failedRetrySeconds] window while any station is flagged — see the note
    /// there for why the full 15s would make the button lie.
    private static func markFailed(_ stationId: String) {
        guard let d = defaults else { return }
        d.set(true, forKey: AppGroupKeys.refreshFailed(stationId))
        d.synchronize()
    }

    /// Rebuild EVERY board that has a widget on the home screen, in parallel.
    ///
    /// ## One tap refreshes every widget, not just the tapped one
    /// `reloadTimelines(ofKind:)` is kind-scoped, so a refresh has always
    /// re-rendered every Stationly widget — only one of them got new DATA. That
    /// left the others repainting stale rows, and the global debounce below then
    /// swallowed the taps that would have fixed them: with three widgets, two of
    /// the three buttons did nothing a user could see.
    ///
    /// So the unit of work is now the whole home screen. [targetStations] asks
    /// WidgetKit which stations are actually installed, which is what keeps this
    /// bounded — it is the number of WIDGETS the user has, not the number of
    /// boards in the app.
    ///
    /// ## Parallel, because the intent's window is the real constraint
    /// An AppIntent gets a short slice before the system reclaims the process,
    /// and serial fetches spent it linearly in the number of naptans. Requests
    /// to one host multiplex over a single connection, so N naptans now cost
    /// roughly ONE round trip regardless of N. That is what makes refreshing
    /// several stations affordable at all; [maxNaptansPerRefresh] still bounds
    /// the fan-out at TfL, which parallelism does nothing about.
    ///
    /// ## Failure is per station
    /// One station timing out must not discard another's good rows. Each target
    /// is written or skipped on its own, and only the ones that failed carry the
    /// retry glyph.
    ///
    /// The debounce stays GLOBAL. It exists to protect TfL from a user drumming
    /// on a button, and now that one tap does all the work there is no longer a
    /// reason to tap a second widget at all.
    ///
    /// [configuredStation] is the grouping id of the widget that was tapped. It
    /// is included even if WidgetKit does not list it, so the board under the
    /// user's finger can never be the one board left out.
    static func refresh(stationId configuredStation: String = "") async -> RefreshOutcome {
        guard let d = defaults else { return .unavailable }

        let now = Date().timeIntervalSince1970
        let last = d.double(forKey: AppGroupKeys.lastManualRefresh)
        let window = anyStationFailed(d) ? failedRetrySeconds : debounceSeconds
        if last > 0, now - last < window { return .debounced }

        let targets = await targetStations(d, tapped: configuredStation)

        // ONE call per distinct naptan ACROSS every target — one for a rail
        // station and one per POLE at a bus hub, deduplicated globally so two
        // stations sharing a pole cost one request between them.
        //
        // Derived BEFORE the guard so the guard tests what will actually be
        // fetched. Testing `feeds.first` instead would bail on a board whose
        // first feed happens to carry a blank naptan while the rest are fine.
        var seenNaptans = Set<String>()
        let naptans = targets
            .flatMap { $0.feeds.map(\.station) }
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
        // see `markFailed` and the header's warning glyph. The board's own
        // arrival animation is the confirmation the user actually reads — see
        // `BoardWidgetView`, which keys its rows on the data's timestamp.
        trace("refresh targets=\(targets.count) naptans=\(naptans.count)")

        // ── Every naptan at once ──
        //
        // A non-throwing group returning an optional per naptan, rather than a
        // throwing one: a throwing group cancels its siblings on the first
        // error, which would turn one station's timeout into a total failure
        // and undo the per-station isolation below.
        var payloads: [String: Data] = [:]
        await withTaskGroup(of: (String, Data?).self) { group in
            for naptan in naptans {
                group.addTask { (naptan, await fetch(naptan, baseUrl: baseUrl, apiKey: apiKey)) }
            }
            for await (naptan, data) in group where data != nil {
                payloads[naptan] = data
            }
        }

        // ── One board at a time, written independently ──
        var wroteAny = false
        var failedAny = false
        for target in targets {
            // A station's OWN naptans, in feed order. Any one of them missing
            // from the payloads means this board would be written short a
            // platform — a board that has silently lost a stop is worse than a
            // board that is visibly stale, so it is skipped whole and flagged.
            var seen = Set<String>()
            let mine = target.feeds.map(\.station)
                .filter { !$0.isEmpty && seen.insert($0).inserted }

            var rows: [DepartureRow] = []
            var ok = !mine.isEmpty
            for naptan in mine {
                guard let data = payloads[naptan],
                      let mapped = mapPayload(data, feeds: target.feeds.filter { $0.station == naptan })
                else { ok = false; break }
                rows.append(contentsOf: mapped)
            }
            guard ok else {
                trace("skip \(target.label) incomplete")
                markFailed(target.stationId)
                failedAny = true
                continue
            }

            // Re-group across naptans, so a bus hub's two poles interleave by
            // platform exactly as KMP's GlobalBoardProcessor left them. An
            // EMPTY result is a legitimate board (a station after the last
            // train), not a failure — only a missing payload is.
            let merged = groupByPlatformSorted(rows)
            guard let encoded = try? JSONEncoder().encode(merged),
                  let encodedString = String(data: encoded, encoding: .utf8)
            else {
                markFailed(target.stationId)
                failedAny = true
                continue
            }

            // Only the rows and the timestamp change — station/line/mode
            // identity is owned by KMP and must not be rewritten from here.
            if target.stationId.isEmpty {
                // Legacy path: no configured station, so the flat keys ARE the
                // board being shown.
                d.set(encodedString, forKey: AppGroupKeys.predictions)
                d.set(now, forKey: AppGroupKeys.lastUpdated)
            } else {
                writeBack(d, stationId: target.stationId, predictions: encodedString, at: now)
            }
            d.set(false, forKey: AppGroupKeys.refreshFailed(target.stationId))
            trace("wrote \(target.label) rows=\(merged.count)")
            wroteAny = true
        }

        guard wroteAny else {
            d.synchronize()
            WidgetCenter.shared.reloadTimelines(ofKind: StationlyDepartureBoardWidget.kind)
            return .failed
        }

        d.set(d.integer(forKey: AppGroupKeys.reloadSignal) &+ 1, forKey: AppGroupKeys.reloadSignal)
        d.set(now, forKey: AppGroupKeys.lastRefreshOk)

        // MUST flush before asking for a reload. cfprefsd can hold the write in
        // cache, and WidgetKit may regenerate the timeline in a freshly-launched
        // extension process that reads from disk — which silently renders the
        // PREVIOUS board despite the write having "succeeded". KMP's
        // IosWidgetManager.refreshAllBoards ends the same way.
        d.synchronize()

        WidgetCenter.shared.reloadTimelines(ofKind: StationlyDepartureBoardWidget.kind)
        return failedAny ? .failed : .refreshed
    }

    /// One naptan's payload, or `nil` if it could not be had.
    ///
    /// Every failure mode collapses to `nil` on purpose: the caller's job is to
    /// decide which BOARDS are now incomplete, and a 500, a timeout and a
    /// malformed URL are the same fact from that angle.
    private static func fetch(_ naptan: String, baseUrl: String, apiKey: String) async -> Data? {
        guard let url = URL(string: "\(baseUrl)/api/v1/stations/predictions/\(naptan)") else {
            return nil
        }
        var request = URLRequest(url: url)
        request.setValue(apiKey, forHTTPHeaderField: "X-Stationly-Key")
        request.timeoutInterval = 10
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                trace("http \((response as? HTTPURLResponse)?.statusCode ?? -1) \(naptan)")
                return nil
            }
            return data
        } catch {
            trace("threw \(naptan) \(error.localizedDescription)")
            return nil
        }
    }

    /// Every station with a widget on the home screen, plus the tapped one.
    ///
    /// `currentConfigurations()` is the only way to learn which stations are
    /// actually ON the home screen. Refreshing every station the APP tracks was
    /// the alternative and is strictly worse: someone who keeps eight boards and
    /// pins one widget would spend eight stations' worth of the intent's window,
    /// and seven of those fetches could not change a pixel.
    ///
    /// Falls back to the tapped station alone if WidgetKit declines to answer,
    /// so a refresh never becomes a no-op just because that call failed.
    private static func targetStations(_ d: UserDefaults, tapped: String) async -> [RefreshTarget] {
        var ids: [String] = []
        for info in await installedWidgets() {
            if let id = info.widgetConfigurationIntent(of: SelectStationIntent.self)?
                .station?.id, !id.isEmpty {
                ids.append(id)
            }
        }
        // The tapped widget last: it is almost certainly already in the list,
        // and appending rather than prepending keeps WidgetKit's own ordering.
        if !tapped.isEmpty { ids.append(tapped) }
        // An unconfigured widget contributes no id at all, so a home screen of
        // nothing but legacy widgets still has to reach the legacy board.
        if ids.isEmpty { ids.append("") }

        var seen = Set<String>()
        var targets: [RefreshTarget] = []
        for id in ids {
            // Resolved through the same reader the RENDERER uses, so a refresh
            // can never target a board other than the one on screen — including
            // its fallback to the legacy keys for a station since deleted.
            let board = AppGroupStorage.shared.readWidgetData(stationId: id)
            guard seen.insert(board.stationId).inserted else { continue }
            let feeds = board.feeds.isEmpty ? legacyFeed(d).map { [$0] } ?? [] : board.feeds
            if !feeds.isEmpty { targets.append(RefreshTarget(stationId: board.stationId, feeds: feeds)) }
        }
        return targets
    }

    /// The widgets currently on the home screen.
    ///
    /// The completion-handler form, bridged, rather than the `async`
    /// `currentConfigurations()` — that one is **iOS 18+** and this extension
    /// deploys to 17. An empty array on failure is the right answer: the caller
    /// falls back to refreshing the tapped station alone, so a refusal here
    /// costs the other widgets their update rather than breaking the tap.
    private static func installedWidgets() async -> [WidgetInfo] {
        await withCheckedContinuation { continuation in
            WidgetCenter.shared.getCurrentConfigurations { result in
                continuation.resume(returning: (try? result.get()) ?? [])
            }
        }
    }

    /// Whether ANY board is currently flagged as failed, which is what shortens
    /// the debounce window. One station still owed a retry is enough.
    private static func anyStationFailed(_ d: UserDefaults) -> Bool {
        AppGroupStorage.shared.readStations().contains {
            d.bool(forKey: AppGroupKeys.refreshFailed($0.id))
        } || d.bool(forKey: AppGroupKeys.refreshFailed(""))
    }

    /// Raised from 3 now that the fetches are concurrent: the old cap existed
    /// because serial requests spent the intent's window linearly, and that is
    /// no longer how this behaves. What it still bounds is fan-out at TfL and
    /// peak memory in an extension with a hard ceiling, so it stays — a dozen
    /// covers several stations including bus hubs with lettered poles.
    ///
    /// The cap TRUNCATES rather than failing, and a board whose naptans fall
    /// outside it is skipped whole by the loop above rather than written short.
    private static let maxNaptansPerRefresh = 12

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
    /// `PredictionsPayload` schema the FCM topic delivers, so one mapper serves both.
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
            // The feed is the ONLY thing that knows which line these belong to —
            // the payload is keyed by line but the rows come out of it flat, and
            // once merged there is nothing left to tell a Circle train from an
            // H&C one at the same platform. Stamping here is what lets a
            // refreshed board keep the prefixes a pushed one shows; without it
            // they would vanish on every refresh tap.
            mapRows(preds, into: &mapped, seen: &seen, lineShort: feed.lineShort)
        }
        return groupByPlatformSorted(mapped)
    }

    private static func mapRows(_ preds: [PredictionsResponse.Pred],
                                into mapped: inout [DepartureRow],
                                seen: inout Set<String>,
                                lineShort: String) {
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
                targetEpochMs: target.map(Double.init),
                lineShort: lineShort
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
