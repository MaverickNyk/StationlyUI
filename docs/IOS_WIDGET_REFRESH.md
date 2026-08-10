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

### 2.4 Cadence

| Tier | When (Europe/London) | Interval | BGTask |
|---|---|---|---|
| P1 rush | Mon–Fri 06:30–09:30, 16:00–19:30 | 15 min | 15 min |
| P2 off-peak | Mon–Fri 09:30–16:00, 19:30–23:00 | 45 min | 60 min |
| P4 weekend | Sat–Sun 06:30–23:00 | 30 min | 30 min |
| P3 night | daily 23:00–06:30 | 180 min | off |

Cost: **~42 scheduled reloads on a weekday, ~35 at the weekend**, against a
routine allowance of `55 − 12 = 43`. Both pinned by tests.

### 2.4.1 What the interval actually delivers — measured

**`.after(date)` is a floor, not a promise.** Measured on device during a P1
peak (16:00–19:30) asking for 15 minutes, WidgetKit delivered:

```
15:30:49  CHARGED
16:30:55  CHARGED   (+60m)
17:01:00  CHARGED   (+30m)
```

So the requested 15 became an actual **30–60 minutes**. This is not a bug and
not fixable from our side: WidgetKit batches reloads with other system activity
and rations them against a per-widget allowance it tunes to how often the widget
is *visible*. Asking for 14 reloads inside a 3.5-hour peak is dense relative to
what ~40–70/day averages to, so the system smooths it.

Three consequences worth holding in mind:

1. **The tier interval is a request describing priority**, not a guarantee. Its
   real job is to tell iOS this period matters more than 03:00 does.
2. **`BGAppRefreshTask` is the engine meant to close that gap** — a separate
   budget, so it can wake the app between throttled timeline reloads. On the
   development device it has **never been granted**: iOS learns from usage
   patterns and ~15 reinstalls in a day resets that learning. On a normally-used
   phone it should contribute; treat it as unproven.
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

Verified on device: `widget.refresh` (foreground and backgrounded), line-scoped
targeting, `boost.start`/`stop` with the widget acting on the new cadence,
`user.sync` targeted by uid, live P2→P3 tier transition at 23:00, ledger
metering, and the 120-minute back-off ceiling.

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

3. **The automatic disruption trigger is unproven.** Deployed and accumulating
   per-line severity baselines, but it cannot fire until a real TfL line changes
   state. Edge-triggered, 10-min per-line cooldown, `apns-collapse-id` per line.

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

8. **`BGAppRefreshTask` has never been observed running.** It is registered and
   scheduled correctly (`bgtask scheduled in 15m` appears in the trace), but iOS
   has never granted it on the development device. Expected given repeated
   reinstalls; unproven on a normally-used phone.

9. **Not eyes-verified:** the widget visibly repainting on the home screen. The
   traces show reloads with fresh data; nobody has watched the pixels.
