# iOS widget refresh — architecture and handover

**Status 2026-08-10.** Shipped and verified on device (iPhone 11, iOS 26.3, staging).

---

## 1. The problem this solves

The iOS widget's only background freshness used to come from FCM push. The live
stream closes on background (`iOSApp.swift`), the in-extension refresh only runs
on a tap, and the timeline was `.atEnd` with a 30–60 min horizon. So "stop
relying on FCM" and "refresh adaptively" were the same task — remove one and the
other must replace it, or the widget goes stale in a pocket.

The constraint that shapes everything: **WidgetKit meters timeline reloads at
~40–70/day** and silently *throttles* a widget that overspends, which on a home
screen is indistinguishable from a broken widget. A 15-minute peak cadence is
96/day and is not reachable from the timeline alone.

---

## 2. Architecture

### 2.1 The schedule is data, not code

`GET /api/v1/sdui/app/refresh-policy` serves a `RefreshPolicy`: an open-ended
list of **tiers** keyed by opaque `String`, plus **windows** mapping day+time to
a tier. Keying by string (never an enum) is what let tier `P4` ship
backend-first with no client release.

Evaluated by `RefreshPolicyEvaluator` (`core/commonMain`) — pure, total, no
clock, no I/O. Both `nowEpochMs` and persisted state are arguments, which is
what makes midnight-wrapping windows, boost expiry and budget degradation
testable without a device.

### 2.2 The extension gets a SCHEDULE, not a decision

The widget extension cannot run Kotlin. Handed a single "current decision" it
would apply rush-hour cadence at 03:00 on a phone whose app had not been opened
since evening — invisible, and paid for in battery.

So KMP publishes a **segmented schedule** (48 h, ≤48 segments) into the App
Group and the extension's entire share of the logic is "find the segment
containing now" (`RefreshScheduleStore`).

### 2.3 Four triggers, one rationed

| Trigger | Fetches first | Charged to the timeline quota |
|---|---|---|
| Scheduled `.after(interval)` | yes | **yes** — the ~40–70/day one |
| WidgetKit push (iOS 26+, direct to extension) | yes | no — separate Apple budget |
| App reload (foreground, live stream, BGAppRefreshTask) | yes if stale | no — foreground is exempt |
| Tap the refresh arrow | yes | no — separate 72/day limit |

**Every build that spends quota fetches before drawing.** A reload re-runs the
provider against whatever is in the App Group; it does not fetch. That is fine
when the app just wrote the board and wrong in the two cases that matter — a
WidgetKit push (which deliberately does not wake the app) and a scheduled reload
after a long quiet stretch. `DepartureBoardProvider` fetches when the stored
board is >120 s old.

That 120 s test is also what stops the two refreshing layers paying twice for
one fetch. `WidgetBoard.lastUpdated` is the *sync* time (`Platform.ios.kt`, from
`getLastUpdatedTimestamp`), not a render time, so a `BGAppRefreshTask` that
fetched a minute ago is visible to the extension as data a minute old and the
scheduled build that follows renders it without a round trip of its own. No
extra signalling between the two is needed, and none should be added.

### 2.3.1 The ledger is kept PER WIDGET

Apple's 40–70 builds a day is an allowance **per widget**, so a single device-wide
tally answers the wrong question the moment a second widget is placed: two
widgets behaving perfectly read as one widget in trouble.

Each widget keeps its own count, its own 24-hour window and its own `.after`
marker, keyed by station + family (`DepartureBoardProvider.ledgerId` — the same
pair `notePlacement` stamps, because WidgetKit hands a provider no instance
identity of its own). `RefreshScheduleStore.publishMirror` then summarises them
into the two keys KMP already read, as the **maximum** rather than the total:
what the governor has to protect is the widget closest to being throttled.

The shared marker was worse than imprecise, it was **order-dependent**. A
scheduled burst across three widgets charged one: the first build pushed
`nextScheduledAt` a full interval into the future, and the two that followed
milliseconds later read that new marker, concluded they had arrived far too
early to be the schedule firing, and recorded themselves free. Once widgets
drift apart it degrades further — whichever one lands just after the marker pays
for all of them, and there are orderings where the widget that *was* on schedule
is the one excused.

Because KMP cannot reach the per-widget entries, its build-change reset
(`resetLedgerOnNewBuild`) would be undone by the next mirror recompute. The
extension therefore stamps the build it counted under (`widget_budget_generation`)
and wipes the whole roster when it stops matching.

### 2.4 Cadence

| Tier | When (Europe/London) | Interval | BGTask |
|---|---|---|---|
| P1 rush | Mon–Fri 06:30–09:30, 16:00–19:30 | 15 min | 15 min |
| P2 off-peak | Mon–Fri 09:30–16:00, 19:30–23:00 | 45 min | 45 min |
| P4 weekend | Sat–Sun 06:30–23:00 | 30 min | 30 min |
| P5 pre-dawn | daily 05:00–06:30 | 90 min | 20 min |
| P3 night | daily 23:00–05:00 | 180 min | off |

Cost: **~42 scheduled reloads on a weekday, ~36 at the weekend**, against a
routine allowance of `55 − 12 = 43`. Both pinned by tests.

**P5 exists because the two cheapest layers were both switched off at once.**
P3 asks 180 minutes *and* disables the background wake, so between 23:00 and
06:30 nothing refreshed at all and a commuter's first glance of the day got the
stalest board of the day. P5 splits the last ninety minutes off and points its
two numbers in opposite directions: one timeline reload across the whole band
(nobody is watching a screen at 05:20) but a background wake every 20 minutes,
which costs no reloads because it draws on a separate budget and only fetches
data. The 06:30 board is then minutes old rather than hours.

**P2's background wake was 60 against a 45-minute display cadence**, so an
off-peak board could be told to redraw with data already an hour old. Matching
the two costs nothing for the same reason.

### 2.4.1 What the interval actually delivers — measured

**`.after(date)` is a floor, not a promise.** Measured on device during a P1
peak (16:00–19:30) asking for 15 minutes, WidgetKit delivered:

```
15:30:49  CHARGED
16:30:55  CHARGED   (+60m)
17:01:00  CHARGED   (+30m)
```

So the requested 15 became an actual **30–60 minutes**. WidgetKit batches
reloads with other system activity and rations them against a per-widget
allowance it tunes to how often the widget is *visible*. Asking for 14 reloads
inside a 3.5-hour peak is dense relative to what ~40–70/day averages to.

Later the same evening it was worse still — **18 minutes with no build at all**,
despite two independent requests landing in that window:

```
17:16:14  last render
17:31:13  BGTask refreshed the DATA and called reloadAllTimelines()  → no build
17:31:14  .after() scheduled reload due                              → no build
17:34:37  WidgetKit push                                             → build, ~15s
```

**This is the one claim in this document still resting on assumption.** The
device had absorbed ~15 reinstalls and dozens of pushes that day, so the most
likely explanation is an exhausted Apple-side allowance rather than a fault in
the timeline policy — but that has NOT been demonstrated. Settling it needs a
genuinely quiet observation: no pushes, launches or installs for ~40 minutes,
then read `widget_scheduled_build_log`. Do that before concluding anything about
steady-state behaviour, and be aware that any push sent during the window
invalidates the result — an earlier attempt was contaminated exactly that way
and produced a false "auto-refresh fired".

Three consequences worth holding in mind:

1. **The tier interval is a request describing priority**, not a guarantee. Its
   real job is to tell iOS this period matters more than 03:00 does.
2. **`BGAppRefreshTask` works, and it refreshes DATA but not the DISPLAY.**
   Verified 2026-08-10: scheduled at 17:15:54, fired at 17:31:13 —
   `refresh(bgtask) refreshed=true`, board rewritten. But the
   `reloadAllTimelines()` it then issues is **ignored, because the app is
   background-woken**, so the widget kept showing its 17:16 render:

   ```
   data  lastUpdated  17:31:13   ← BGTask refreshed it
   widget last render 17:16:14   ← 15 minutes stale on screen
   ```

   This is the single most important thing to understand about the feature:
   **freshness of the DATA and freshness of the PIXELS are separate problems on
   iOS, and only one of them can be solved from the device.** BGTask solves the
   data; only a timeline build — granted by WidgetKit, or forced by a WidgetKit
   push — repaints. A user watching an "ago" timer is watching the pixels, so
   they see the older number even when the data underneath is current.
3. **Push is the only path that reliably closes the gap on demand**, which is
   why the disruption trigger matters more than the tier density does.

If tighter peak cadence is ever genuinely needed, the lever is not a smaller
`intervalMinutes` — the system is already declining the current one. It is
`backgroundTaskMinutes` and getting BGAppRefreshTask actually granted.

### 2.5 Push signals

One vocabulary (`PushEnvelope`, `commonMain`) over per-platform transports.

| Signal | Reaches | Purpose |
|---|---|---|
| `widget.refresh` | widget directly | refetch now; scoped by `lines` for disruption |
| `policy.update` | app | refetch the schedule even when not running |
| `boost.start` / `boost.stop` | app | hot mode; self-expiring |
| `user.sync` | app (**and Android via FCM**) | cross-device account changes |

`Station_*` / `LineStatus_*` FCM topics remain **Android-only by design** —
per-minute pushes would exhaust the iOS quota before mid-morning.

---

## 3. Bugs fixed

Ordered by how badly each would have hurt in production. Every one was found on
a real device; none would have failed a test suite as written at the time.

### 3.1 FirebaseAuth swallowed the APNs token (blocker)
`FirebaseAppDelegateProxyEnabled` defaults to **on**, and FirebaseAuth swizzles
the APNs delegate methods for phone-number auth. Under SwiftUI's
`UIApplicationDelegateAdaptor` the token never reached our delegate.
`isRegisteredForRemoteNotifications` reported `true` — the token *was* being
issued — while the callback never fired, with no error anywhere. Survived a
device restart and a full reinstall, because it is in-process.
**Fix:** `FirebaseAppDelegateProxyEnabled: false`.
**Diagnostic to reuse:** log `isRegisteredForRemoteNotifications` at the call
site and ~8 s later. `true` + no callback ⇒ intercepted in-process; stop
suspecting network, key or entitlements.

### 3.2 Governor blacked the widget out for 10 hours
Back-off stretched to the whole remaining 24 h window. An over-counted ledger
produced `next=627m` and the widget sat untouched through a Monday morning peak.
**Fix:** `RefreshBudget.maxIntervalMinutes` (120). Overspending fails gracefully
— Apple stops honouring reloads while the tick layer keeps counting down —
whereas a frozen board fails abruptly with nothing on screen to explain it. When
the two conflict, err toward asking.

### 3.3 Ledger charged builds Apple does not meter
Pushes and taps have their own budgets; charging them against the timeline quota
drove the ledger to 84 against an allowance of 43. **Fix:** store the requested
`.after` date; a build arriving >60 s early was externally triggered, so free.

Known imprecision, accepted: a push landing *after* the scheduled time is
indistinguishable from the schedule firing, so it is charged. Observed once
(17:34:37 push recorded as `n=1`). It over-counts in the safe direction — the
model believes we have spent slightly more than we have — and the alternative
(a marker written by the push handler) would have to survive a process that may
never run, which is a worse trade.

### 3.4 Boost had zero visible effect
`boost.start` sent both pushes concurrently. The widget push repainted with the
**pre-boost** schedule; the app applied the boost a second later and its
`reloadTimelines()` was dropped, because iOS ignores that from a
background-woken app. The boost stored perfectly and did nothing for 90 minutes.
**Fix:** for state-changing signals, send the background push first, wait
`STATE_APPLY_DELAY_MS` (5 s), then repaint. `widget.refresh` stays concurrent —
it carries no state.

### 3.5 Disruption pushes would have reached nobody
The registry scopes by line id, but the client sent only stations — and
`widget_stations` carries line **display names**. `"Hammersmith & City"` would
never match a `hammersmith-city` incident. Tube lines whose display name is the
id capitalised hid it. **Fix:** `WidgetStationRef.lineIds` alongside `lines`;
client registers ids, lower-cased.

### 3.6 Foreground reloads counted against the budget
Measured 14 phantom spends in the 5 s after an install. **Fix:** a foreground
heartbeat in the App Group — a timestamp, not a flag, so a killed app cannot
strand it. Required an explicit `synchronize()`; without it the cross-process
write was invisible.

### 3.7 APNs registration gated on notification permission
Silent and WidgetKit pushes need no such permission. Any user declining
notifications got **zero** widget triggers, silently. Registration was also
inside `if firebaseReady` (dead FCM-era coupling) and only ran on cold launch,
so a resume never re-asked and a **rotated token was lost forever**.
**Fix:** unconditional, outside the Firebase block, and on every foreground.

### 3.8 Device registration 401'd
Mounted under `/user/*`, which is Firebase-auth gated, while the client sends
only the API key — breaking the signed-out-devices-still-register case its own
comment promised. **Fix:** moved to `/device/register`. Moving it then removed
what populated `req.user`, so **no uid was stored** and `user.sync` reached no
iOS device; fixed with optional bearer verification (`resolveUid`).

### 3.9 `CFBundleVersion` hardcoded to `"1"`
`CURRENT_PROJECT_VERSION` never reached the plist, so the build-change ledger
reset could never fire — and every App Store build would have shipped as
version 1.

---

## 4. Performance

**Policy compiled once (`Compiled`).** `schedule()` ran `decide()` per segment,
each running a 96-step forward simulation, each step re-splitting every window's
`"HH:mm"` strings and re-scanning the tier list: **~110,000 string parses per
publish**, scaling with window count — and the weekend work adds windows.
Parsing, tier resolution, day-set normalisation and boundary times now happen
once; the hot loop is integer comparisons. Every fallible step moved to compile
time, which is also what makes an unknown tier inert rather than fatal.

**Timeline fetch bounded (6 s).** One measured build spent **15.3 s** in fetch —
`DepartureRepository` allows 15 s/stop and `WidgetRefreshService` gives
URLSession 10 s with `waitsForConnectivity`. A widget extension gets a short
slice before the system reclaims it. Overrunning now renders held data and
retries next build, rather than risking termination.

**Redundant registration POSTs skipped.** `register()` runs on every foreground,
token callback and account reconcile, but the payload only changes when a token
rotates or boards are edited. Signature-compared; remembered only on HTTP 200 so
a failure retries.

**`apnsEnvironment()` cached.** Was re-reading and string-scanning a
multi-kilobyte provisioning profile on every registration.

---

## 5. Code quality

- **App-target `AppGroupKeys.swift`.** The device-push work spread raw App Group
  literals across the app target — the precise bug class the extension's
  `AppGroupKeys` exists to prevent, reintroduced in the one target lacking the
  guard. Now three files kept in lockstep by hand (app, extension, KMP), grouped
  by writer so a change tells you what else must move.
- **Weekend fallback test rebuilt.** `uncoveredTimeFallsBackToDefaultTier` relied
  on an incidental 06:30–07:00 hole in the shipped schedule. Closing that hole
  would have silently deleted the coverage; it now builds a policy with a
  deliberate gap and tests the mechanism.
- **Budget-fit tests added.** A weekday must fit the allowance; a weekend must
  use **at least 75%** of it. The floor is what would have caught the original
  under-use, and stops a future tweak quietly giving it back.
- **Dead code removed** (`UserSyncBridge.isUserSyncType`).

---

## 6. Verification

```bash
./gradlew :core:testDebugUnitTest                      # 29 refresh + 156 core, 0 failures
./gradlew :android:app:compileProdDebugKotlin          # Android unaffected
./gradlew :composeApp:assembleComposeAppDebugXCFramework   # MUST precede xcodebuild
cd iosApp && ./xcodegen.sh && xcodebuild -scheme "iosApp Staging" ... build
```

On-device state lives in the App Group; there is no console in a widget
extension, so the traces are the instrument:

```bash
xcrun devicectl device copy from --device <UDID> \
  --domain-type appGroupDataContainer --domain-identifier group.com.stationly.shared \
  --source Library/Preferences --destination /tmp/ag
```

- `widget_scheduled_build_log` — **start here for "is it refreshing by itself?"**
  One line per metered build, 60 entries (>1 day). The gaps between lines are
  the answer. Added because `widget_refresh_trace` holds 20 chatty entries and
  rolls in minutes, which made a report of "it did not update for two hours"
  impossible to investigate — the evidence was gone before anyone looked.
  Every line carries `id=<station>#<family>`: with two widgets the gaps are only
  meaningful **per widget**, and interleaved lines from a station that refreshes
  and one that does not otherwise read as one healthy widget.
- `widget_budget_roster` + `widget_budget_count_<id>` — the per-widget ledger the
  mirror summarises. Read these when the governor's degradation looks wrong for
  the number of widgets placed.
- `widget_observed_raw` — `kind|family` per widget straight from
  `getCurrentConfigurations`, **nothing inferred**. Start here for "how many
  widgets does this person have". `widget_observed` beside it attaches a station
  to each by matching placement stamps by family, which is fine for the activity
  trail and misleading for a count.

### 6.1 Reinstalling the extension leaves phantom widgets — cost an hour

**A development device can report far more widgets than exist, and it is the
installs that cause it.** Observed 2026-08-17: a phone with **three** widgets
placed reported **ten** from `getCurrentConfigurations`, and the extension was
asked to build **seven** distinct `(station, family)` timelines — including a
`systemLarge` for a station that had no large widget, and two with no
configuration at all (`cfg=nil`). Five `devicectl install` cycles inside ninety
minutes had each re-registered the extension, and the stale registrations stayed
live.

Two parts of iOS disagree while this is happening, which is what makes it so
confusing to diagnose:

| Source | Reported | Correct? |
|---|---|---|
| `getCurrentConfigurations` (app) | 3 after an app restart, 10 before | catches up on app relaunch |
| `chronod` timeline requests (extension) | 7 | only a **device reboot** clears it |

Restarting the app is not enough — it does not touch `chronod`. **Reboot the
phone**, then read `widget_observed_raw` and the trace. After the reboot the
same device built exactly three ids, matching the three placed widgets.

Consequences worth knowing before believing any budget measurement taken on a
dev device:

- The ledger is not wrong when this happens. Those builds are real, they are
  metered, and counting them is correct. What is wrong is the device.
- It plausibly explains §2.4.1's open question about `.after` under-delivery:
  a device servicing ten widget instances instead of three would see roughly a
  third of the per-widget grant rate. **Not demonstrated** — settling it needs a
  quiet observation on a freshly rebooted phone.
- Do not conclude anything about a user's widget count from
  `widget_placements` or from the ledger roster. Only `widget_observed_raw`
  answers that, and only after a reboot.
- `widget_refresh_trace` — timeline builds in detail: tier, interval, spend,
  FREE marker, fetch timings. Short-lived; use for the last few minutes.
- `push_trace` — app side: pushes received, registration, BGTask scheduling
- `widget_refresh_schedule` — the published segments

Manual triggers (`$ADMIN` = `STATIONLY_ADMIN_KEY`, `$API` =
`https://staging-api.stationly.co.uk/api/v1`):

```bash
curl -s -H "Authorization: Bearer $ADMIN" "$API/admin/device-push/status"

curl -s -X POST -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"kind":"widget.refresh","reason":"manual"}' "$API/admin/device-push/send"

curl -s -X POST -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"kind":"boost.start","minutes":90,"reason":"match:wembley"}' "$API/admin/device-push/send"
```

### What is verified, and what is not

**Verified on device (2026-08-09/10):**

| Behaviour | Evidence |
|---|---|
| `widget.refresh` push → fetch + repaint | foreground and backgrounded (`state=2`) |
| WidgetKit push repaint latency | build ~15 s after send |
| Line-scoped targeting | Piccadilly-scoped push → `devicesTargeted: 1` |
| `boost.start` / `stop` | widget moved to `tier=P1 boost next=15m`, 90-min window |
| `user.sync` by uid | `widgetpush recv kind=user.sync` |
| BGAppRefreshTask | `refresh(bgtask) refreshed=true`, 15 min after scheduling |
| Disruption trigger | live TfL severity transitions, correctly classified |
| Tier vs wall clock | Mon 17:20 → P1/15m; live P2→P3 transition at 23:00 |
| Ledger metering | charged builds only; foreground marked FREE |
| Back-off ceiling | `next=120m`, not 627m |
| Served vs compiled policy | tiers, windows, budget all agree |

**NOT verified — the one open question:** that scheduled `.after` timeline
reloads fire on a device that has not been hammered by a day of development.
See §2.4.1. Everything else above rests on measurement; this rests on a
plausible explanation.

**Measured 2026-08-17: we receive about a QUARTER of what we plan for.**
Two full days of `widget_scheduled_build_log` from a real device:

```
Sat  6 charged builds all day   (schedule planned ~35)
Mon 11 charged builds all day   (schedule planned ~42)

observed gaps: +111m  +125m  +143m  +225m  +248m  +627m
               ...while asking for 15m and 30m
```

This reframes the whole budget model. **The ceiling is not the binding
constraint — iOS's willingness is.** The governor guards against a 43-reload
allowance the device never approaches, so it is currently inert, and tightening
any tier's `intervalMinutes` is pushing on a door that is already shut. Apple
allocates against how often a widget is actually *looked at*, which a test
device is bad at earning.

Two consequences for anyone tuning this:

1. **Do not "fill the quota" by asking more often.** We already ask ~4× what we
   get. The lever that works is the layers with their own budgets: BGAppRefreshTask
   for data, WidgetKit push for pixels.
2. **The per-widget vs per-app question now matters a lot.** Apple documents the
   40–70 as per widget and the model assumes it. If it is per *app*, three
   widgets share one allowance and the plan is 3× over, which would explain the
   observed rate better than anything else. Unresolved. Testable on a clean
   device by comparing per-widget counts in the build log against the plan.

**NOT verified — the per-widget ledger, reaper and preview guard (2026-08-17).**
The Kotlin side is covered by 30 `commonMain` tests; the Swift halves are
reasoned, not measured. The extension has no test target. What to look for:

- Place two widgets on different stations. `widget_budget_roster` should list
  two ids and `widget_scheduled_build_log` should show both charging
  independently. Before this change a scheduled burst across three charged one.
- **Read `widget_observed_raw` first, and only after a device reboot.** See §6.1.
- The reaper should clear an entry within ~2h of the app next opening after a
  widget is deleted, and never touch one still building.

---

## 7. Known limitations and open items

1. **The widget requires iOS 26+** (the app stays 16+). `.pushHandler` cannot be
   version-gated: SwiftUI has no `AnyWidgetConfiguration` and
   `WidgetBundleBuilder` has no `buildEither` — its `buildOptional` is marked
   *"if statements in a WidgetBundleBuilder can only be used with #available
   clauses"*. Both verified against the 26.5 SDK. Without it a background push
   cannot repaint the widget at all. Alternative if the tail matters: a second
   extension target pinned to 26.0.

2. **Editing the schedule does not reach devices by itself.** The 12 h TTL is
   only checked on app foreground, so a device whose app is never opened keeps
   the old policy indefinitely. After changing `refreshPolicyService.ts` and
   deploying you **must** also send `{"kind":"policy.update"}`. This belongs in
   `.scripts/staging_deploy.sh` — considered and deliberately deferred.

3. ~~The automatic disruption trigger is unproven.~~ **Verified on live TfL data
   (2026-08-10).** Observed firing on real severity transitions, correctly
   classified:

   ```
   DISRUPTION: ⚡ bakerloo changed   — "Severe Delays" → "Minor Delays"
   DISRUPTION: ⚡ district recovered — "Minor Delays"  → "Good Service"
   DISRUPTION: ⚡ northern disrupted — "Good Service"  → "Minor Delays"
   ```

   The test device tracks Piccadilly and Victoria and was correctly NOT woken by
   any of the above — the line scoping works, and a device is only disturbed by
   lines it actually shows.

4. **A weekday has ~1 reload of headroom** (41.8 of 43). Apple's real ceiling is
   40–70 and varies per device, so a device at the low end could be throttled
   before our own governor engages. Options: raise `dailyReloadCeiling`, or
   soften the evening peak (3.5 h at 15 min = 14 reloads, the largest line item).

5. **No admin-console entry points.** Assessed and deferred: only `boost`
   genuinely wants a human, `policy.update` belongs in the deploy pipeline, and
   the rest are automatic. A UI would also need backend-side boost tracking
   first — the backend is currently stateless about active boosts.

6. **Android has not adopted the shared policy.** The model and evaluator sit in
   `commonMain` for it to use; its FCM path works today, so this is optional.

7. **Peak cadence is ~30 min in practice, not 15.** See §2.4.1 — measured, and
   inherent to `.after` being a floor. The board still counts down locally every
   minute between reloads, so it reads live; what is 30 minutes old is the
   underlying prediction set, not the displayed countdown.

8. **The visible "ago" timer can lag the data by a full interval.** BGTask
   refreshes the board on time (verified), but cannot repaint the widget — so
   the user sees the previous render's age climbing while current data sits
   underneath. See §2.4.1. The only reliable repaint is a WidgetKit push, which
   is why the disruption trigger matters more than tier density does.

   If this becomes the dominant complaint, the lever worth trying is **lowering
   total reload demand** so more of what we ask for is granted: we currently
   request ~42/day against an Apple allowance of 40–70 that is tuned per device
   and per visibility. Asking for less may get a higher proportion honoured.
   That is a hypothesis, not a measurement — it has not been tested.

9. **Not eyes-verified:** the widget visibly repainting on the home screen. The
   traces show reloads with fresh data; nobody has watched the pixels.
