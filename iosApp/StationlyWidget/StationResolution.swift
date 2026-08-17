import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - What a PLACED widget shows
//
// The one rule:
//
//     A widget shows the station in its own configuration, or it shows why it
//     cannot. It never picks one, and it never substitutes another station's
//     board.
//
// One ordered ladder, run on every timeline build, deciding nothing that
// persists — so the configured id always survives and any state below is
// recoverable by the event that caused it going away.
//
// ## Why "never picks" is absolute
// A timeline provider is handed no widget identity. It cannot know which widget
// is asking or what that widget showed last, so any station it chooses is a
// guess about a widget it cannot see. Every substitution defect in this feature
// came from a guess here, and each attempt to make the guess smarter produced
// its own:
//
//   - "the first station no placed widget is showing" moved on its own, because
//     the set it was derived from moved.
//   - `stations[0]` moved every unconfigured widget onto each newly added
//     station, because a new station goes to the top of the home screen.
//   - a remembered "adopted" station held still, but caught widgets whose own
//     station had just been deleted — iOS nils an unresolvable parameter, so
//     those widgets arrive here looking unconfigured — and deleting station A
//     started showing station B on A's widget.
//
// The guessing was the bug, not the quality of the guess. Rung 3 asks the user.
//
// History, and the argument that has to be answered before reintroducing any of
// it: `docs/IOS_WIDGET_DESIGN.md` §9 and
// `docs/SESSION_2026-08-17_WIDGET_STATION.md`.
// ─────────────────────────────────────────────────────────────────────────────

enum StationResolver {

    /// The board a widget should render for its configured station.
    ///
    /// One ordered ladder, total and mutually exclusive — first match wins, and
    /// nothing falls through the end. Every branch returns a board that
    /// describes the widget's OWN station, or says why there isn't one.
    ///
    /// [configured] is the entity from the widget's `SelectStationIntent`, which
    /// is nil for a widget added before the configuration existed.
    static func board(for configured: StationEntity?) -> WidgetData {
        let storage = AppGroupStorage.shared

        // 1. SIGNED OUT — ahead of everything, because every branch below looks
        //    for a station to show and after a sign-out that is the wrong
        //    instinct: it would hand a board to a widget the previous account
        //    left behind. Also the only branch that must suppress the
        //    extension's own REST refresh, which authenticates with the API key
        //    and would otherwise refill what the sign-out just cleared.
        if storage.isSignedOut { return .signedOut }

        let stations = storage.readStations()

        // 2. NO STATIONS — a fresh install, or the last board deleted. The two
        //    are byte-identical in the App Group (`refreshAllBoards` runs the
        //    same `wipe()` for both) and the extension cannot break the tie: the
        //    uid lives in the app's standard defaults, which an extension cannot
        //    read. One state, true either way.
        //
        //    ⚠️ The legacy board is offered ONLY to a widget that has no
        //    configuration of its own. Those flat `widget_*` keys hold whichever
        //    station led the home screen when a pre-multi-station build wrote
        //    them, so handing them to a CONFIGURED widget would render one
        //    station's departures under another station's name — the exact
        //    substitution this file exists to forbid, arriving through the one
        //    rung that looked too early to matter. A configured widget gets the
        //    honest answer instead, and recovers by itself the moment the app
        //    writes the directory.
        //
        //    In practice the two branches agree, because `wipe()` clears the
        //    legacy keys alongside the directory and `legacyBoard()` then
        //    returns `.empty`. The split matters on the one device where they
        //    disagree: an upgrade whose legacy keys are still populated and
        //    whose directory has not been written yet.
        //
        //    ⚠️ This is also where a destructive cloud restore used to land for
        //    as long as its `clearAllData()` left the selection table empty. The
        //    app now suppresses that write for the duration (`WidgetRestore`),
        //    because only the app can tell a restore from a user with no boards.
        guard !stations.isEmpty else {
            return configured == nil ? storage.legacyBoard() : .noStationsTracked
        }

        // 3. UNCONFIGURED — no station of its own, so it ASKS FOR ONE. It does
        //    not pick.
        //
        //    ── This rung is where every substitution bug came from ──
        //
        //    It used to render a station: first `stations[0]`, then a remembered
        //    "adopted" one when that turned out to move. Both were guesses, and
        //    each guess produced its own defect. `stations[0]` moved every
        //    unconfigured widget onto each newly added station, because a new
        //    station goes to the TOP of the home screen. Adoption held still but
        //    put every unconfigured widget on ONE station, and — worse — quietly
        //    caught widgets whose own station had just been deleted, because iOS
        //    nils an unresolvable parameter (§9.5.1) and a deleted station's
        //    widget therefore ARRIVES HERE looking unconfigured. Delete station
        //    A and its widget started showing station B. Reported exactly that
        //    way, and it is the substitution this whole file exists to forbid,
        //    coming in through the one door left open.
        //
        //    There is no guess that survives contact. A provider is handed no
        //    widget identity, so nothing here can know which widget is asking or
        //    what it used to show. The only correct answer to "which station is
        //    this?" when nothing has said, is to say that nothing has said.
        //
        //    One tap fixes it permanently: picking a station writes a real
        //    configuration, and every rung below then applies for the life of
        //    the widget.
        guard let configured, !configured.id.isEmpty else { return .needsStation }

        // 4. REMOVED — the configured station is not in the directory. Named,
        //    not substituted, and not cleared: the configuration is untouched,
        //    so re-adding the station brings this widget straight back.
        //
        //    ⚠️ Reached only when iOS hands over the STORED entity for a station
        //    the directory no longer lists. It usually does not: once
        //    `entities(for:)` stops resolving that id, the parameter arrives nil
        //    and the widget lands on rung 3 instead. Both are safe and both ask
        //    for the same tap; this one can also say which station went, so it
        //    is kept for the builds where iOS takes that path.
        guard let entry = directoryEntry(for: configured, in: stations) else {
            return .removed(station: configured.name)
        }

        // 5. LIVE, or 6. WAITING — the station is tracked. Either its board has
        //    been written or it has not, and the honest answer to "not yet" is
        //    this station with nothing on it, never a different station with
        //    something on it.
        return storage.board(for: entry) ?? waiting(at: entry)
    }

    // MARK: - Resolving a configured id against the directory

    /// The directory entry a configured station corresponds to, allowing for the
    /// station's GROUPING ID having changed underneath a placed widget.
    ///
    /// ## Why an exact match is not enough
    /// A board's directory id is `parentStationId.ifBlank { station }`, so a
    /// selection that later acquires a hub id changes identity from a pole
    /// naptan (`490008805N`) to a StopArea (`490G00008805`). A cross-device sync
    /// can cause it: the legacy `stations` payload carries `parentStationId` as
    /// optional, so a row written by a client that did not send one comes back
    /// without one and acquires it locally afterwards.
    ///
    /// Without this the widget would announce "Smithwood Close was removed"
    /// while Smithwood Close is visibly sitting in the user's list — the most
    /// confusing failure the removed state can produce, and one the user did
    /// nothing to cause.
    ///
    /// ## The match has to be UNIQUE to be safe
    /// Name and mode together, and only when exactly one station matches. A
    /// name-only match would pair Paddington the bus stop with Paddington the
    /// tube station; requiring uniqueness means an ambiguous directory falls
    /// through to the removed state, which is merely unhelpful rather than
    /// wrong.
    static func directoryEntry(
        for configured: StationEntity,
        in stations: [StationEntity]
    ) -> StationEntity? {
        if let exact = stations.first(where: { $0.id == configured.id }) { return exact }

        // The configuration's OWN name and mode, and nothing else. There used to
        // be a lookup in a cached home-screen snapshot here, for the case where
        // iOS hands over an id with no entity around it — that snapshot is gone
        // with the assignment machinery, and it is not missed: the timeline
        // provider always receives the whole entity, so the one caller that has
        // to get this right always has a name to match on.
        guard !configured.name.isEmpty else { return nil }

        var matches: [StationEntity] = []
        for station in stations
        where sameStation(station, name: configured.name, mode: configured.mode) {
            matches.append(station)
            // Two is already ambiguous, and ambiguity falls through to the
            // removed state — so there is nothing to learn from a third.
            if matches.count > 1 { return nil }
        }
        return matches.first
    }

    /// Whether a directory entry describes the station a configuration names.
    ///
    /// Split out of the loop above because the compound condition inline was
    /// enough to make the Swift type-checker complain about the expression, and
    /// a build that times out at the wrong moment is a worse problem than a
    /// helper.
    private static func sameStation(_ station: StationEntity, name: String, mode: String) -> Bool {
        guard station.name.caseInsensitiveCompare(name) == .orderedSame else { return false }
        guard !mode.isEmpty else { return true }
        return station.mode.caseInsensitiveCompare(mode) == .orderedSame
    }

    /// A tracked station with no payload yet: the real name, no departures.
    ///
    /// Deliberately NOT the skeleton and NOT another station's board. The
    /// skeleton is `placeholder(in:)`'s treatment for "WidgetKit has no entry at
    /// all"; this is a real entry for a real station that has nothing to show
    /// this second, which is what the board's own empty row already says.
    private static func waiting(at station: StationEntity) -> WidgetData {
        WidgetData(
            stationName: station.name,
            lineName: "",
            direction: "",
            mode: station.mode,
            departures: [],
            status: "",
            lastUpdated: Date(timeIntervalSince1970: 0),
            isEmpty: false,
            stationId: station.id
        )
    }
}
