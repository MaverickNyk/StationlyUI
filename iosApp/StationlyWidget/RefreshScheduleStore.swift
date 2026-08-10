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

    /// Record what we just asked WidgetKit for, so the NEXT build can tell
    /// whether it was the schedule firing or something else.
    static func recordScheduledNext(_ date: Date) {
        guard let d = defaults else { return }
        d.set(date.timeIntervalSince1970, forKey: AppGroupKeys.nextScheduledAt)
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
    @discardableResult
    static func recordReload(at date: Date = Date()) -> Int {
        guard let d = defaults else { return 0 }
        let now = date.timeIntervalSince1970
        let start = d.double(forKey: AppGroupKeys.budgetWindowStart)

        // A build arriving materially earlier than we asked for was not the
        // schedule firing. The grace absorbs ordinary system jitter around the
        // requested time; anything earlier than that had another cause.
        let scheduledFor = d.double(forKey: AppGroupKeys.nextScheduledAt)
        let isExternallyTriggered = scheduledFor > 0 && now < scheduledFor - scheduleGrace

        if isAppForeground(at: date) || isExternallyTriggered {
            guard start <= 0 || now - start >= 24 * 60 * 60 else {
                return d.integer(forKey: AppGroupKeys.budgetCount)
            }
            d.set(now, forKey: AppGroupKeys.budgetWindowStart)
            d.set(0, forKey: AppGroupKeys.budgetCount)
            return 0
        }

        // Roll the window as a block rather than sliding it. A true sliding
        // window needs every timestamp retained, and the extra precision buys
        // nothing when the ceiling it feeds is already set conservatively.
        if start <= 0 || now - start >= 24 * 60 * 60 {
            d.set(now, forKey: AppGroupKeys.budgetWindowStart)
            d.set(1, forKey: AppGroupKeys.budgetCount)
            return 1
        }
        let next = d.integer(forKey: AppGroupKeys.budgetCount) + 1
        d.set(next, forKey: AppGroupKeys.budgetCount)
        recordScheduledBuild(at: date, spent: next)
        return next
    }

    /// Append one line per METERED build — see `AppGroupKeys.scheduledBuildLog`.
    ///
    /// The chatty `refreshTrace` holds 20 entries and rolls in minutes during
    /// active use, which made "the widget did not refresh for two hours"
    /// impossible to investigate after the fact. This log records only builds
    /// that actually cost quota, so at ~42/day it spans more than a day and the
    /// GAPS BETWEEN LINES are the answer.
    ///
    /// Deliberately terse: a timestamp and the cadence in force, nothing that
    /// would make it roll faster than the question it exists to answer.
    private static func recordScheduledBuild(at date: Date, spent: Int) {
        guard let d = defaults else { return }
        let cadence = self.cadence(at: date)
        var log = d.stringArray(forKey: AppGroupKeys.scheduledBuildLog) ?? []
        log.append("\(Int(date.timeIntervalSince1970)) \(cadence.tierId) ask=\(cadence.intervalMinutes)m n=\(spent)")
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
