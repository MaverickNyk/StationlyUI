import Foundation

/// The cadence this extension should refresh at, and the tally of what it has
/// already spent.
///
/// ## Why the extension is handed a schedule rather than deciding for itself
/// Everything that decides WHEN to refresh — matching a time window, wrapping
/// midnight, expiring a boost, economising against the daily quota — lives in
/// `RefreshPolicyEvaluator` on the Kotlin side, where it is one implementation
/// with tests. This process cannot run Kotlin, so the alternative would be a
/// second copy of all of it in Swift, in a binary with no test target,
/// re-parsing a policy document it would have to fetch itself.
///
/// That is the same trap `WidgetRefreshService` documents for board identity,
/// and it has the same answer: **Kotlin decides, Swift reads.** KMP publishes a
/// list of segments; this file finds the one containing `now`.
///
/// ## Why a list and not a single current decision
/// The extension is the only thing running when the app is not. Handed one
/// decision, a phone untouched since yesterday evening would still be holding
/// the 18:00 answer and would apply rush-hour cadence at 03:00 — spending
/// battery and quota on a board nobody is looking at, with nothing on the
/// device to reveal it. A segmented schedule cannot go wrong that way: an old
/// schedule still knows what 03:00 is worth.
enum RefreshScheduleStore {

    /// One stretch of wall-clock with a constant cadence.
    ///
    /// Field names are the wire contract with `RefreshScheduleAppGroup.SegmentDto`
    /// in Kotlin — **change both, in one commit.** Times are epoch SECONDS, the
    /// unit everything else in the App Group uses; the Kotlin model is in millis
    /// and converts on the way out.
    private struct Segment: Decodable {
        let start: Double
        let end: Double
        let tier: String
        let interval: Int
        let dense: Int
        let sparseStep: Int
        let horizon: Int
        let boost: Bool
    }

    /// The resolved cadence for one moment.
    struct Cadence {
        let tierId: String
        let intervalMinutes: Int
        let denseMinutes: Int
        let sparseStepMinutes: Int
        let horizonMinutes: Int
        let boostActive: Bool
        /// True when no segment covered the moment asked about, so these are the
        /// compiled-in fallbacks. Surfaced in the trace only.
        let isFallback: Bool
    }

    /// What to do when the App Group has no usable schedule — a first launch
    /// before KMP has ever published, or a decode failure.
    ///
    /// Matches the off-peak tier rather than the rush one: the safe direction to
    /// be wrong is the cheap one. A fallback that guessed 15 minutes would burn
    /// the quota of exactly the devices whose app is not running to correct it.
    static let fallback = Cadence(
        tierId: "fallback",
        intervalMinutes: 45,
        denseMinutes: 15,
        sparseStepMinutes: 5,
        horizonMinutes: 60,
        boostActive: false,
        isFallback: true
    )

    private static var defaults: UserDefaults? { AppGroupDefaults.shared }

    /// The cadence in force at [date].
    ///
    /// Falls back rather than failing at every step: an extension that cannot
    /// answer this still has to return a timeline, and a board on a slightly
    /// wrong cadence beats no board at all.
    static func cadence(at date: Date = Date()) -> Cadence {
        guard let raw = defaults?.string(forKey: AppGroupKeys.refreshSchedule),
              let data = raw.data(using: .utf8),
              let segments = try? JSONDecoder().decode([Segment].self, from: data),
              !segments.isEmpty
        else { return fallback }

        let now = date.timeIntervalSince1970
        // Segments are contiguous and ordered, so a linear scan over a list
        // bounded at 48 is cheaper than the binary search it would take to beat
        // it — and this runs on the timeline path, where clarity is worth more
        // than the microseconds.
        guard let match = segments.first(where: { now >= $0.start && now < $0.end }) else {
            // Ran off the end: the app has not published in longer than the
            // schedule covers. The LAST segment is a better guess than the
            // first — it is the most recent thing KMP actually computed — but
            // it is still flagged as a fallback so the trace shows why.
            guard let last = segments.last, now >= last.end else { return fallback }
            return Cadence(
                tierId: last.tier,
                intervalMinutes: last.interval,
                denseMinutes: last.dense,
                sparseStepMinutes: last.sparseStep,
                horizonMinutes: last.horizon,
                boostActive: false,   // a boost cannot outlive the schedule that carried it
                isFallback: true
            )
        }

        return Cadence(
            tierId: match.tier,
            intervalMinutes: match.interval,
            denseMinutes: match.dense,
            sparseStepMinutes: match.sparseStep,
            horizonMinutes: match.horizon,
            boostActive: match.boost,
            isFallback: false
        )
    }

    // MARK: - Budget ledger

    /// How long a foreground heartbeat is trusted.
    ///
    /// Three ticks of the app's 5-second observer, so an ordinary missed beat
    /// (a busy main thread) does not flip the answer, while an app that has
    /// actually stopped is treated as gone within seconds.
    private static let foregroundGrace: TimeInterval = 15

    /// How far before the requested `.after` time a build still counts as the
    /// schedule firing. WidgetKit is documented to treat the date as "no
    /// earlier than", but it batches with system activity, so a small early
    /// arrival is ordinary. Anything earlier than this had another cause.
    private static let scheduleGrace: TimeInterval = 60

    /// The rolling window the ledger measures.
    private static let dayInterval: TimeInterval = 24 * 60 * 60

    /// How far past its own requested build a ledger entry may fall before it
    /// stops voting for the maximum.
    ///
    /// Six hours is well beyond anything the schedule can legitimately ask for
    /// — the policy ceiling is 120 minutes, and even a `.after` that WidgetKit
    /// declines for an hour is nowhere near this — so an entry past it has not
    /// merely been delayed. Generous on purpose: the cost of waiting too long
    /// is a stale vote, the cost of being too eager is discarding a live
    /// widget's tally, and only one of those two under-counts the budget.
    private static let ghostAfter: TimeInterval = 6 * 60 * 60

    /// The same test, shortened for an entry the app's probe also failed to
    /// find. Two independent signals agreeing is worth four hours of patience,
    /// but not the whole margin: `.after` has been measured arriving an hour
    /// late on a fifteen-minute ask, so a widget an hour overdue is ordinary
    /// and only one several hours overdue means anything.
    private static let observationGrace: TimeInterval = 2 * 60 * 60

    /// Record what we just asked WidgetKit for, so the NEXT build of THIS widget
    /// can tell whether it was the schedule firing or something else.
    static func recordScheduledNext(_ date: Date, ledgerId: String) {
        guard let d = defaults else { return }
        d.set(date.timeIntervalSince1970, forKey: AppGroupKeys.nextScheduledAt(ledgerId))
    }

    /// Whether the app is on screen right now — which decides whether this
    /// build costs anything. See `AppGroupKeys.appForegroundHeartbeat`.
    static func isAppForeground(at date: Date = Date()) -> Bool {
        guard let beat = defaults?.double(forKey: AppGroupKeys.appForegroundHeartbeat),
              beat > 0 else { return false }
        return date.timeIntervalSince1970 - beat < foregroundGrace
    }

    /// Record that WidgetKit built a timeline, and report the running total.
    ///
    /// ## Why the count is kept HERE
    /// A timeline build is the thing Apple meters, it happens in this process,
    /// and the app is not running for most of them. An app-side count would
    /// under-report so badly that the governor would never engage until the
    /// widget had already been throttled — which is the failure this whole
    /// mechanism exists to avoid, arriving silently.
    ///
    /// KMP reads these two values (`RefreshBudgetStore`) and never increments
    /// them. Two plain numbers rather than an encoded struct because both
    /// processes touch them and `UserDefaults` has no partial write: a
    /// read-modify-write of a shared blob would lose increments during exactly
    /// the bursts this is meant to measure.
    ///
    /// ## Only SCHEDULED rebuilds are charged
    /// Three kinds of build reach this function and only one of them spends the
    /// timeline quota:
    ///
    ///  - **Foreground.** WidgetKit exempts reloads requested while the app is
    ///    on screen, and the app requests one on every `scenePhase == .active`.
    ///    Counting those measured fourteen phantom "spends" in the five seconds
    ///    after an install.
    ///  - **Externally triggered** — a WidgetKit push, or an app-side
    ///    `reloadTimelines`. Apple meters both on their OWN budgets, separate
    ///    from the timeline quota. Detected as a build arriving well before the
    ///    `.after(next)` we last asked for.
    ///  - **Scheduled** — the timeline actually expiring. This is the one Apple
    ///    rations at ~40–70/day, and the only one worth counting.
    ///
    /// Charging all three is what drove a real device to `spent=70` against an
    /// allowance of 43, which stretched the interval to 627 minutes and left the
    /// board untouched through a Monday morning peak.
    ///
    /// The window still ROLLS on an uncharged build, so a widget first touched
    /// after a day away still starts a fresh 24 hours.
    ///
    /// ## Counted PER WIDGET
    /// Apple's ~40–70 builds a day is an allowance per widget, so a device-wide
    /// tally compares the wrong number against it — two widgets doing exactly
    /// what they should would read as one widget in trouble. Each widget keeps
    /// its own count and its own `.after` marker; [publishMirror] then hands KMP
    /// the worst of them, which is the one at risk of being throttled. See
    /// `AppGroupKeys.nextScheduledAt(_:)` for the ordering bug the shared marker
    /// caused before this.
    @discardableResult
    static func recordReload(ledgerId: String, at date: Date = Date()) -> Int {
        guard let d = defaults else { return 0 }
        let now = date.timeIntervalSince1970

        // Enumerated ONCE and threaded through. The three helpers below each
        // need the full set, and each used to fetch it for itself — three scans
        // of the suite on the timeline path, which is the one path in this
        // target where instrumentation must never cost more than what it
        // measures.
        var ids = ledgerIds(d)
        if syncGeneration(d, ids: ids) { ids = [] }
        reap(d, at: now, ids: &ids)
        ids.insert(ledgerId)

        let countKey = AppGroupKeys.budgetCount(ledgerId)
        let startKey = AppGroupKeys.budgetWindowStart(ledgerId)
        let start = d.double(forKey: startKey)

        // A build arriving materially earlier than THIS widget asked for was not
        // its schedule firing. The grace absorbs ordinary system jitter around
        // the requested time; anything earlier than that had another cause.
        //
        // Both exemptions decided in ONE place. They were two branches that each
        // re-derived the window arithmetic below, which is how the free path
        // came to skip [publishMirror] entirely — so a reap that had just
        // removed a deleted widget did not reach the governor until some later
        // build happened to be a charged one.
        let scheduledFor = d.double(forKey: AppGroupKeys.nextScheduledAt(ledgerId))
        let externallyTriggered = scheduledFor > 0 && now < scheduledFor - scheduleGrace
        let charged = !(isAppForeground(at: date) || externallyTriggered)

        // Roll the window as a block rather than sliding it. A true sliding
        // window needs every timestamp retained, and the extra precision buys
        // nothing when the ceiling it feeds is already set conservatively.
        //
        // The window still ROLLS on an uncharged build, so a widget first
        // touched after a day away still starts a fresh 24 hours.
        let spent: Int
        if start <= 0 || now - start >= dayInterval {
            d.set(now, forKey: startKey)
            spent = charged ? 1 : 0
            d.set(spent, forKey: countKey)
        } else if charged {
            spent = d.integer(forKey: countKey) + 1
            d.set(spent, forKey: countKey)
        } else {
            spent = d.integer(forKey: countKey)
        }

        publishMirror(d, at: now, ids: ids)
        // Logged in EVERY charged case, including the first build of a fresh
        // window. The log is read by the gaps between its lines, so a metered
        // build that wrote no line would manufacture exactly the silence
        // somebody is looking for an explanation of.
        if charged { recordScheduledBuild(at: date, spent: spent, ledgerId: ledgerId) }
        return spent
    }

    // MARK: - Per-widget ledger plumbing

    /// Every widget that currently holds a ledger entry, derived from the suite.
    ///
    /// Both prefixes are scanned because the two keys are written at different
    /// moments: the count during [recordReload], the marker only once the
    /// provider has decided what to ask for next. A widget that has so far had
    /// nothing but free builds has a marker and no count, and it must still be
    /// reapable or its marker outlives it forever.
    private static func ledgerIds(_ d: UserDefaults) -> Set<String> {
        var ids = Set<String>()
        for key in d.dictionaryRepresentation().keys {
            if key.hasPrefix(AppGroupKeys.budgetCountPrefix) {
                ids.insert(String(key.dropFirst(AppGroupKeys.budgetCountPrefix.count)))
            } else if key.hasPrefix(AppGroupKeys.nextScheduledAtPrefix) {
                ids.insert(String(key.dropFirst(AppGroupKeys.nextScheduledAtPrefix.count)))
            }
        }
        return ids
    }

    /// Delete every trace of the widgets that have stopped building.
    ///
    /// ## Why this is time-based, and cannot be an event
    /// **iOS never tells anyone a widget was removed.** There is no deletion
    /// callback in WidgetKit, the extension is not run to be informed, and
    /// `getCurrentConfigurations` returns an empty list when called from inside
    /// `timeline(for:in:)`. A removed widget simply stops asking for timelines.
    /// Its silence is the only notification there is, so this reads that
    /// silence.
    ///
    /// Two ways to be gone. A window older than the day the ledger measures is
    /// finished by definition. Otherwise the `.after` marker is the test,
    /// because it is rewritten on EVERY build: a widget that has missed the
    /// build it asked for by [ghostAfter] is not late, it is deleted, resized
    /// (a resize is a new family, so a new entry) or repointed at another
    /// station.
    ///
    /// Left alone, those entries do real harm rather than merely accumulating:
    /// [publishMirror] reports the maximum, so a deleted widget that had spent
    /// forty reloads goes on claiming forty, and the governor throttles the
    /// widgets the user kept to protect one that no longer exists. Measured on
    /// device before this: three placed widgets, seven ledger entries.
    private static func reap(_ d: UserDefaults, at now: TimeInterval, ids: inout Set<String>) {
        guard !ids.isEmpty else { return }
        // What the APP saw the last time it could ask properly, and when.
        // Absent on a device whose app has never run since this shipped, which
        // is why the silence rule is kept rather than replaced.
        let observedAt = d.double(forKey: AppGroupKeys.observedWidgetsAt)
        let observationUsable = observedAt > 0 && now - observedAt < dayInterval
        let observed: Set<String> = observationUsable
            ? Set(d.stringArray(forKey: AppGroupKeys.observedWidgets) ?? [])
            : []

        let dead = ids.filter { id in
            let start = d.double(forKey: AppGroupKeys.budgetWindowStart(id))
            if start > 0, now - start >= dayInterval { return true }

            let marker = d.double(forKey: AppGroupKeys.nextScheduledAt(id))
            let overdue = marker > 0 ? now - marker : 0

            // ── The probe may only ACCELERATE a verdict, never reach one ──
            //
            // An entry still building on schedule is kept however the probe
            // answers. That asymmetry is the safety property: a widget iOS is
            // still building is a widget Apple is still METERING, so deleting
            // its tally would under-report the budget — and the entry would
            // re-register at one on its next build, resetting itself again and
            // again. Under-reporting is the direction that ends in a silently
            // throttled widget, which is the failure this ledger exists to
            // prevent.
            //
            // It is not known whether `getCurrentConfigurations` enumerates
            // every record iOS builds for (Smart Stack members, configurations
            // resurrected from its AppIntents cache). Requiring the entry to
            // have gone quiet first means that question cannot cost anything.
            //
            // Skipped for `none#…`: an unconfigured build records itself under
            // that id, `notePlacement` refuses to stamp an empty station, and
            // the probe reports an unmatched widget as `family|` with no
            // station — so such an entry can never match a descriptor and would
            // always look dead.
            if observationUsable, overdue > observationGrace, !id.hasPrefix("none#"),
               let hash = id.firstIndex(of: "#") {
                let station = String(id[id.startIndex..<hash])
                let family = String(id[id.index(after: hash)...])
                if !observed.contains("\(family)|\(station)") { return true }
            }

            return overdue > ghostAfter
        }
        guard !dead.isEmpty else { return }
        dead.forEach { forget($0, in: d) }
        ids.subtract(dead)
    }

    /// Drop every trace of one ledger entry.
    private static func forget(_ ledgerId: String, in d: UserDefaults) {
        d.removeObject(forKey: AppGroupKeys.budgetCount(ledgerId))
        d.removeObject(forKey: AppGroupKeys.budgetWindowStart(ledgerId))
        d.removeObject(forKey: AppGroupKeys.nextScheduledAt(ledgerId))
    }

    /// Summarise the per-widget ledger into the two keys KMP reads.
    ///
    /// The MAXIMUM rather than the total or the mean, because the ceiling being
    /// modelled is per widget: what matters is how close the most-spent widget
    /// is to being throttled, not how much the device has spent in aggregate.
    ///
    /// Widgets the user removed are not filtered here — [reap] has already
    /// deleted them by the time this runs, on every build. That matters more
    /// than it sounds: one ghost left behind would throttle every widget still
    /// on the screen for the rest of its window.
    private static func publishMirror(_ d: UserDefaults, at now: TimeInterval, ids: Set<String>) {
        var worstCount = 0
        var worstStart = 0.0
        for id in ids {
            let start = d.double(forKey: AppGroupKeys.budgetWindowStart(id))
            guard start > 0, now - start < dayInterval else { continue }
            let count = d.integer(forKey: AppGroupKeys.budgetCount(id))
            // Ties break toward the LATER window, whose count KMP is less likely
            // to discard as already rolled. Erring toward believing the spend
            // protects the budget; the policy's own interval ceiling is what
            // stops that caution turning into the old 627-minute blackout.
            if count > worstCount || (count == worstCount && start > worstStart) {
                worstCount = count
                worstStart = start
            }
        }
        // No live entry at all — every widget removed, or every window lapsed.
        // The mirror must be CLEARED rather than left: a stale high count with
        // nothing left to justify it would go on degrading the next widget the
        // user places, for a full day, on the strength of spend by widgets that
        // no longer exist.
        let start = worstStart > 0 ? worstStart : now
        let count = worstStart > 0 ? worstCount : 0

        // Compared before writing, the same discipline as KMP's `putIfChanged`:
        // this runs on every build and the values move rarely, so an
        // unconditional write would wake `cfprefsd` for nothing most of the time.
        if d.integer(forKey: AppGroupKeys.budgetCount) != count {
            d.set(count, forKey: AppGroupKeys.budgetCount)
        }
        if d.double(forKey: AppGroupKeys.budgetWindowStart) != start {
            d.set(start, forKey: AppGroupKeys.budgetWindowStart)
        }
    }

    /// Drop the whole per-widget ledger when the installed build changes, and
    /// report whether it did.
    ///
    /// KMP zeroes the mirror for this reason already (`resetLedgerOnNewBuild`),
    /// but it cannot see the per-widget entries behind it — so without this the
    /// very next [publishMirror] would restore the count it had just cleared,
    /// carrying a tally from a binary that may have counted differently.
    private static func syncGeneration(_ d: UserDefaults, ids: Set<String>) -> Bool {
        // Unconditional, and deliberately not folded into the generation check
        // below: the roster was replaced by derivation WITHOUT a version bump,
        // so a device carrying the old key would otherwise keep it until the
        // next app update. One existence test per build to leave nothing behind.
        if d.object(forKey: AppGroupKeys.legacyBudgetRoster) != nil {
            d.removeObject(forKey: AppGroupKeys.legacyBudgetRoster)
        }

        let build = d.string(forKey: AppGroupKeys.budgetBuild) ?? ""
        guard !build.isEmpty,
              d.string(forKey: AppGroupKeys.budgetGeneration) != build else { return false }
        ids.forEach { forget($0, in: d) }
        d.set(build, forKey: AppGroupKeys.budgetGeneration)
        return true
    }

    // MARK: - Traces

    /// Append one line per METERED build — see `AppGroupKeys.scheduledBuildLog`.
    ///
    /// The chatty `refreshTrace` holds 20 entries and rolls in minutes during
    /// active use, which made "the widget did not refresh for two hours"
    /// impossible to investigate after the fact. This log records only builds
    /// that actually cost quota, so it spans more than a day and the GAPS
    /// BETWEEN LINES are the answer.
    ///
    /// Deliberately terse: a timestamp and the cadence in force, nothing that
    /// would make it roll faster than the question it exists to answer.
    private static func recordScheduledBuild(at date: Date, spent: Int, ledgerId: String) {
        guard let d = defaults else { return }
        let cadence = self.cadence(at: date)
        var log = d.stringArray(forKey: AppGroupKeys.scheduledBuildLog) ?? []
        // The ledger id is on every line because with two widgets the gaps in
        // this log are only meaningful per widget: interleaved lines from a
        // station that refreshes and one that does not would otherwise read as
        // one healthy widget.
        log.append("\(Int(date.timeIntervalSince1970)) \(cadence.tierId) ask=\(cadence.intervalMinutes)m n=\(spent) id=\(ledgerId)")
        if log.count > 60 { log = Array(log.suffix(60)) }
        d.set(log, forKey: AppGroupKeys.scheduledBuildLog)
    }

    /// Age of the published schedule in hours, for the trace. A number that
    /// keeps climbing is the signature of an app that is never being launched,
    /// which is the one condition this design cannot repair on its own.
    static func scheduleAgeHours(at date: Date = Date()) -> Int {
        guard let d = defaults else { return -1 }
        let at = d.double(forKey: AppGroupKeys.refreshScheduleAt)
        guard at > 0 else { return -1 }
        return Int((date.timeIntervalSince1970 - at) / 3600)
    }
}
