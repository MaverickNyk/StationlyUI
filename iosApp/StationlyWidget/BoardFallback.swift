import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Why a board has nothing on it
//
// The widget's half of `core/util/BoardFallback.kt`, and the split is the same
// one `headerVariants` uses:
//
//     KMP decides the WORDS. This decides WHICH ONE.
//
// ## Why the choice has to happen here
// WidgetKit builds an hour of entries in one pass, and the correct message
// MOVES inside that hour. A board becomes "Live updates paused" six minutes
// after its payload lands, and "Service ended for tonight" at midnight — both
// on entries archived long before either moment. A finished string resolved by
// the app would be frozen onto every one of them.
//
// So the app publishes the table (`AppGroupKeys.boardFallback`) and this reads
// the render clock. Nothing here invents a sentence; the strings below are the
// last-resort copy for a device whose app has not written the table yet, and
// they are transcribed from `BoardFallbackDefaults` rather than written fresh.
//
// ## What this replaced
// One hardcoded line — "No departures right now" — for every situation the app
// distinguishes. So at 02:00 the home board said "Service ended for tonight ·
// Back in the morning" and the widget for the same station, in the same second,
// said the trains merely happened not to be running.
// ─────────────────────────────────────────────────────────────────────────────

/// The situations an empty board can be in. Mirrors `BoardFallbackKind`.
///
/// Raw values are the Kotlin enum's `name`, because that is what crosses the App
/// Group. Deliberately not ordinals: a kind inserted in the middle would
/// silently re-map every message on a device whose widget had not been rebuilt.
enum BoardFallbackKind: String {
    case offline      = "OFFLINE"
    case signalLost   = "SIGNAL_LOST"
    case disrupted    = "DISRUPTED"
    case lateNight    = "LATE_NIGHT"
    case earlyMorning = "EARLY_MORNING"
    case noUpcoming   = "NO_UPCOMING"
    case connecting   = "CONNECTING"
}

/// One resolved message: a title, and the detail under it.
struct BoardFallbackCopy: Decodable, Equatable {
    let title: String
    var detail: [String] = []

    /// The detail as one line, which is what a departure cell can hold.
    ///
    /// The FIRST line, not all of them joined. Only DISRUPTED has more than one
    /// ("No departures expected here" / "We'll update as things change"), where
    /// the home board draws two rows and a widget has one cell. Joining them
    /// produced a 58-character string in a cell that holds about 55 at full
    /// size, so it shrank and then truncated — losing the tail of the second
    /// half while making the first half harder to read, which is the worst of
    /// both. The first line is the substantive one in the only case where there
    /// is a choice.
    var detailLine: String { detail.first ?? "" }
}

/// The published table: every message, and the thresholds that select between
/// them.
struct BoardFallbackTable: Decodable {
    var copy: [String: BoardFallbackCopy] = [:]
    var signalLostMin: Int = 6
    /// Minutes past LOCAL midnight. See the note on the Kotlin type for why
    /// these cross as integers rather than as "HH:mm".
    var lateNightStartMin: Int = 0
    var lateNightEndMin: Int = 270
    var earlyMorningEndMin: Int = 360

    // ── Last resort ──
    //
    // Reached on a device whose app has not written the table yet: a fresh
    // install whose first board write has not run, or a widget added before the
    // app was ever opened. Transcribed from `BoardFallbackDefaults`, and the
    // ONLY copy in this target — everything else renders what it is handed.
    //
    // They are the strings the app itself would show in the same state, so the
    // worst case is that the two surfaces agree on slightly older wording, not
    // that they disagree.
    static let fallbackDefaults: [BoardFallbackKind: BoardFallbackCopy] = [
        .offline:      BoardFallbackCopy(title: "Offline", detail: ["Catching up when you're back"]),
        .signalLost:   BoardFallbackCopy(title: "Live updates paused", detail: ["Last refresh {age} ago"]),
        .disrupted:    BoardFallbackCopy(title: "Service disrupted", detail: ["No departures expected here"]),
        .lateNight:    BoardFallbackCopy(title: "Service ended for tonight", detail: ["Back in the morning"]),
        .earlyMorning: BoardFallbackCopy(title: "Service starting soon", detail: ["First departures incoming"]),
        .noUpcoming:   BoardFallbackCopy(title: "Nothing departing right now", detail: ["Watching for the next one"]),
        .connecting:   BoardFallbackCopy(title: "Connecting", detail: ["Live data starting up"]),
    ]

    func copy(for kind: BoardFallbackKind) -> BoardFallbackCopy {
        copy[kind.rawValue] ?? Self.fallbackDefaults[kind]
            ?? BoardFallbackCopy(title: "", detail: [])
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Choosing the row
// ─────────────────────────────────────────────────────────────────────────────

/// What an empty board should say, and how stale its "ago" timer should look.
struct BoardFallbackResult: Equatable {
    let kind: BoardFallbackKind
    let title: String
    let detail: String

    /// Whether the freshness colour ladder applies to the "ago" timer.
    ///
    /// False while the network is CLOSED, and that is the whole point of
    /// carrying it: after the last train nothing fetches, by design, so the
    /// timer legitimately climbs into hours and its red says "something is
    /// wrong" about a board that is behaving exactly as intended. The reading
    /// stays — it is true and it costs nothing — but it stops raising an alarm
    /// nobody can act on. See `LiveAgo.staleColor`.
    var freshnessMatters: Bool { kind != .lateNight && kind != .earlyMorning }
}

/// Resolves the message for one board, across a whole timeline.
///
/// ## A value, built once per timeline, asked once per entry
/// A batch is up to ~20 entries built from ONE payload, and almost everything
/// this needs is a property of that payload rather than of the entry: the
/// status string, whether a sync has ever landed, and when. Only the clock and
/// the row count move. Built as a free function it re-split the status string
/// and re-read the table for every entry, inside the archiving pass that stands
/// between a tap and the pixels.
///
/// So the invariants are taken apart once here, and [result(hasDepartures:at:)]
/// is the only thing that runs per entry.
struct BoardFallbackResolver {
    private let table: BoardFallbackTable
    /// The payload's line status, split ONCE. `ticked(at:)` re-derives rows and
    /// leaves this untouched, so it is the same string for every entry.
    private let status: StatusParts
    private let lastUpdated: Date
    private let hasTimestamp: Bool

    init(_ data: WidgetData, table: BoardFallbackTable) {
        self.table = table
        self.status = StatusParts(data.status)
        self.lastUpdated = data.lastUpdated
        self.hasTimestamp = data.hasTimestamp
    }

    /// The message for a board with no departures, or nil if it has some.
    ///
    /// Order mirrors `computeBoardFallbackState`, including the 2026-08-17 fix
    /// that moved SIGNAL_LOST BELOW the closed-network windows — see that
    /// function for the reasoning, which came off this widget.
    ///
    /// - Parameters:
    ///   - hasDepartures: of the TICKED board for this entry, not the payload.
    ///     A board with trains at 08:00 has none by 08:40, and both are entries
    ///     in the same batch.
    ///   - date: THIS entry's date, never `Date()`. A widget body is evaluated
    ///     while WidgetKit archives the whole timeline, so `Date()` returns the
    ///     build time for every entry at once and each one would be told the
    ///     payload is seconds old — including the entry that appears forty
    ///     minutes later. This function's entire job is to answer questions
    ///     about time, so being handed the wrong one is not survivable.
    func result(hasDepartures: Bool, at date: Date) -> BoardFallbackResult? {
        guard !hasDepartures else { return nil }

        // CONNECTING — the widget's own rung, and it has no equivalent in the
        // Compose path.
        //
        // `StationResolver.waiting` dates a board to the epoch when the app has
        // never written one for that station. Kotlin treats `lastUpdatedMs == 0`
        // as "cannot say how old" and falls through; here it is a state in its
        // own right, because a widget can be placed on a station the app has
        // not got to yet and "Connecting" is exactly what is happening. Ahead of
        // everything else: nothing below can be true of a board that has never
        // been fetched, and each would be a claim about a station nobody has
        // asked about yet.
        guard hasTimestamp else { return copy(.connecting) }

        // OFFLINE is deliberately NOT detected.
        //
        // It needs reachability, which an extension cannot get cheaply, and the
        // one signal available (`lastRefreshFailed`) only moves when the user
        // taps refresh — so a board could sit offline all morning and never
        // reach it. SIGNAL_LOST says the same thing the user can act on
        // ("this is old") without claiming a cause we cannot verify. The copy
        // is still published so the day reachability exists, nothing else has
        // to change.

        // DISRUPTED — a non-good-service status is almost certainly WHY there
        // is nothing here, and it outranks the time windows because an all-day
        // closure is more specific than "ended for tonight".
        //
        // ## ⚠️ The title is the TABLE's, not the live severity
        // `resolveBoardFallbackCopy` titles this with the severity, and that is
        // right for the home board. Here it printed the severity TWICE: the
        // status strip sits directly beneath this cell and always renders on an
        // empty board (`cells < maxRows` is what puts it there), so a delayed
        // line read
        //
        //     Severe Delays
        //     No departures expected here
        //     Severe Delays : signal failure at Euston
        //
        // Two adjacent cells saying the same two words, on a panel with three
        // lines to spend. The generic title plus the strip's specific one is
        // strictly more information in the same space, and it is what
        // `BoardMessageCell` already documents: *"the reason, when there is one,
        // is in the status strip directly beneath."*
        if status.isDisrupted { return copy(.disrupted) }

        // The closed-network windows, ahead of staleness. This is the ordering
        // fix: after the last train nothing fetches because there is nothing to
        // fetch, so a five-hour-old sync is correct behaviour and must not be
        // reported as "Live updates paused".
        let minute = Self.londonMinutes(at: date)
        if Self.inWindow(minute, table.lateNightStartMin, table.lateNightEndMin) {
            return copy(.lateNight)
        }
        if Self.inWindow(minute, table.lateNightEndMin, table.earlyMorningEndMin) {
            return copy(.earlyMorning)
        }

        // SIGNAL_LOST — we can say how old this is, and it is old.
        let ageMin = Int(max(0, date.timeIntervalSince(lastUpdated)) / 60)
        if ageMin >= table.signalLostMin {
            // `{age}` is substituted, never formatted from scratch: the sentence
            // is the app's and only the number is ours. It moves per entry,
            // which is exactly why the publisher leaves the placeholder in
            // (`substituteAge = false`) rather than resolving it app-side.
            return copy(.signalLost) {
                $0.replacingOccurrences(of: "{age}", with: Self.formatAge(ageMin))
            }
        }

        return copy(.noUpcoming)
    }

    /// The published message for a kind, with an optional transform on the
    /// detail — the one hook any rung needs, and only SIGNAL_LOST uses it.
    private func copy(
        _ kind: BoardFallbackKind,
        detail transform: (String) -> String = { $0 }
    ) -> BoardFallbackResult {
        let stored = table.copy(for: kind)
        return BoardFallbackResult(kind: kind,
                                   title: stored.title,
                                   detail: transform(stored.detailLine))
    }

    // MARK: - Time

    /// Minutes past midnight in Europe/London, which is the only zone TfL runs
    /// on. Not the device's zone: a user reading this board in New York is
    /// still asking whether the Northern line is running, and it is 05:00 in
    /// London or it is not.
    ///
    /// The calendar is built once — `Calendar.current` and a `TimeZone` lookup
    /// are not free, and this runs per entry, per widget, inside WidgetKit's
    /// archiving pass.
    private static let london: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/London") ?? .gmt
        return cal
    }()

    private static func londonMinutes(at date: Date) -> Int {
        let parts = london.dateComponents([.hour, .minute], from: date)
        return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }

    /// Half-open [start, end), wrapping over midnight.
    ///
    /// Written to mirror `inTimeWindow` in core statement for statement, so the
    /// two can be checked against each other by eye:
    ///
    /// ```kotlin
    /// if (start <= endExclusive) now >= start && now < endExclusive
    /// else now >= start || now < endExclusive
    /// ```
    ///
    /// It was three branches with the degenerate `start == end` split out
    /// first — same answer (a zero-width window matches nothing either way),
    /// but a different shape, and a reader comparing the two files had to prove
    /// that rather than see it.
    ///
    /// The wrap is not theoretical: the late-night window is 00:00 to 04:30
    /// today, but an SDUI config that moves its start to 23:30 has to keep
    /// working.
    private static func inWindow(_ minute: Int, _ start: Int, _ endExclusive: Int) -> Bool {
        start <= endExclusive
            ? minute >= start && minute < endExclusive
            : minute >= start || minute < endExclusive
    }

    /// Mirrors `formatAge` in core: "just now", "45 min", "2h", "2h 15m".
    private static func formatAge(_ minutes: Int) -> String {
        if minutes < 1 { return "just now" }
        if minutes < 60 { return "\(minutes) min" }
        let h = minutes / 60, m = minutes % 60
        return m == 0 ? "\(h)h" : "\(h)h \(m)m"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - The status string, split once
// ─────────────────────────────────────────────────────────────────────────────

/// "Severity : reason", taken apart.
///
/// Extracted from `DotMatrixStatusStrip`, which was the only reader until the
/// fallback resolver needed the same split to tell a disrupted line from a
/// healthy one. Two implementations of "where does the colon go" is one more
/// than a board should have.
struct StatusParts {
    let severity: String
    let reason: String

    /// Whether the board has a status at all.
    ///
    /// ⚠️ Load-bearing, and it is the reason the strip stopped printing "Good
    /// Service" over a blank status. That default was a claim about the line
    /// nobody had checked — the same defect as the old "No departures right
    /// now" one cell above it, arriving through the one place that looked like
    /// a formatting nicety.
    let isKnown: Bool

    var isDisrupted: Bool {
        guard isKnown, !severity.isEmpty else { return false }
        return !severity.lowercased().hasPrefix("good service")
    }

    init(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        isKnown = !trimmed.isEmpty
        guard let split = trimmed.range(of: ":") else {
            severity = trimmed
            reason = ""
            return
        }
        severity = String(trimmed[..<split.lowerBound]).trimmingCharacters(in: .whitespaces)
        reason = String(trimmed[split.upperBound...]).trimmingCharacters(in: .whitespaces)
    }
}
