# Widget refresh: per-widget budget, pre-dawn tier, and what the quota actually delivers

**2026-08-17.** iOS + `core` + `stationly-backend`.

Backend deployed to staging and the schedule **verified in force on the device**
(`widgetpush recv kind=policy.update` → republished with the new bands). Client
built, signed and installed on the test iPhone.

---

## 1. The finding that reframes the feature

Two full days of `widget_scheduled_build_log` pulled from a real device:

```
Sat   6 charged builds all day     (schedule planned ~35)
Mon  11 charged builds all day     (schedule planned ~42)

observed gaps: +111m  +125m  +143m  +225m  +248m  +627m
               ...while the tiers were asking for 15m and 30m
```

**We receive roughly a quarter of what we plan for.** Every number in the budget
model was tuned against a 43-reload allowance that the device never approaches,
so:

- The budget governor is currently **inert**. It exists to stop overspending and
  we are at ~25% of the ceiling.
- **Tightening any tier's `intervalMinutes` is pushing on a door already shut.**
  iOS allocates against how often a widget is actually looked at, not against
  what we ask for.
- The levers that still have room are the ones with **separate budgets**:
  `BGAppRefreshTask` for data, WidgetKit push for pixels.

**Unresolved, and it now matters a great deal:** Apple documents the 40–70 as
per *widget*, and the model assumes it. If it is per *app*, three widgets share
one allowance and the plan is 3× over — which would explain the observed rate
better than anything else. Testable on a clean device by comparing per-widget
counts in the build log against the plan.

---

## 2. Bugs fixed

### 2.1 The budget ledger was wrong with more than one widget (correctness)

`budgetCount`, `budgetWindowStart` and `nextScheduledAt` were single shared keys,
while Apple's allowance is per widget. Worse than imprecise, the shared marker
made metering **depend on the order WidgetKit happened to invoke providers in**:

> A scheduled burst across three widgets charged **one**. The first build pushed
> `nextScheduledAt` a full interval into the future; the two that followed
> milliseconds later read that new marker, concluded they had arrived far too
> early to be the schedule firing, and recorded themselves free.

Once widgets drift apart it degrades further — whichever lands just after the
marker pays for all of them, and there are orderings where the widget that *was*
on schedule is the one excused.

**Fix:** every widget keeps its own count, window and marker, keyed by
`station#family` (`DepartureBoardProvider.ledgerId`). `publishMirror` summarises
them into the two keys KMP already reads, as the **maximum** — the widget closest
to being throttled is the one the governor must protect.

### 2.2 Deleted widgets throttled the ones you kept (correctness)

Nothing in WidgetKit reports a widget removal. A deleted widget's entry simply
stopped being touched, and because the mirror is a maximum, one that had spent
forty reloads went on claiming forty for the rest of its 24-hour window.

**Fix:** `reap()`, run on every build. Two independent signals:

| Signal | Threshold | Notes |
|---|---|---|
| Window lapsed | 24 h | Finished by definition |
| `.after` marker overdue | `ghostAfter` = 6 h | Rewritten on every build, so it is the liveness test |
| …plus absent from the app's probe | `observationGrace` = 2 h | Only **shortens** the verdict |

**The probe may only accelerate a verdict, never reach one.** An entry still
building on schedule is kept however the probe answers. A widget iOS is still
building is a widget Apple is still metering, so deleting its tally would
under-report the budget — and it would re-register at 1 on its next build,
resetting itself forever. Under-reporting is the direction that ends in a
silently throttled widget.

### 2.3 The roster was a shared blob with read-modify-write (race)

The first version of 2.1 stored the ledger ids in a `widget_budget_roster` array.
That is exactly the pattern the two counters deliberately avoid, for exactly the
same reason: `UserDefaults` has no atomic append, so two widgets registering in
the same burst could silently drop one — and with it that widget's whole spend.

**Fix:** the set is **derived** by scanning the suite for the two key prefixes.
Derivation cannot race, because every widget writes only keys bearing its own id.
Removed a key, a function (`remember`), a constant (`rosterCeiling`) and the
ceiling-trim path that could discard a live widget's tally.

### 2.4 A free-only widget leaked its marker forever (edge case)

The free path returned early without registering, so a widget that never had a
charged build was in no roster — while `recordScheduledNext` wrote its marker on
every build. Never enumerated, never reaped.

**Fix:** enumeration scans **both** prefixes. The two keys are written at
different moments (count during `recordReload`, marker only once the provider has
decided what to ask for next), so either alone misses a real widget.

### 2.5 A reap did not reach the governor until the next charged build (correctness)

The free path returned before `publishMirror`, so a reap that had just removed a
deleted widget sat unpublished.

**Fix:** the exemption is decided once (`charged`), the window arithmetic happens
once, and `publishMirror` runs on every path.

### 2.6 An emptied ledger left a stale high mirror (edge case)

`publishMirror` returned early when no live entry was found, leaving whatever the
mirror last held. Remove every widget and the governor would keep degrading the
next widget placed, for a full day, on the strength of spend by widgets that no
longer existed.

**Fix:** no live entries now clears the mirror to `count = 0, start = now`.

### 2.7 Nothing refreshed between 23:00 and 06:30 (freshness)

P3 asked for 180 minutes **and** set `backgroundTaskMinutes: 0`. Both cheap layers
off at once, so a commuter's first glance of the day got the stalest board of the
day.

**Fix:** new **P5 pre-dawn** tier, daily 05:00–06:30, with its two numbers
pointing deliberately in opposite directions:

| | value | why |
|---|---|---|
| `intervalMinutes` | 90 | ~1 metered reload across the band. Nobody watches a screen at 05:20. |
| `backgroundTaskMinutes` | 20 | Dense, but a separate budget and data only. Costs no reloads. |

The deep night (23:00–05:00) still costs zero battery.

### 2.8 P2's background wake lagged its own display cadence (freshness)

`backgroundTaskMinutes: 60` against `intervalMinutes: 45`, so an off-peak board
could be told to redraw with data already an hour old. Now 45. Costs nothing —
different budget.

### 2.9 Every app push to staging had been failing for two days (infrastructure)

An APNs **topic is the app's bundle id**, so it is per-environment like the base
URLs. Since the environment split (2026-08-15) staging inherited
`APNS_BUNDLE_ID=com.stationly.mobile` from `.env.defaults`, which neither
`.env.remote` nor `staging_deploy.sh` overrode — while the staging app is
`com.stationly.mobile.staging`. APNs rejected every push per device as
`DeviceTokenNotForTopic`.

Dead for two days: `policy.update`, `boost.start`/`stop`, `user.sync`, and the
WidgetKit push type (its topic is `<bundle>.push-type.widgets`, derived from the
same value). **It surfaced as nothing at all** — a push nobody receives is
indistinguishable from a push nobody sent, which is why it lasted. The
"verified on device 2026-08-09/10" entries in `IOS_WIDGET_REFRESH.md` §6 all
predate the split.

Found only because `policy.update` is the sole way to push a schedule change past
the 12-hour TTL, so it was the first thing tonight's work actually needed.

**Fix — derived, not configured.** `ApnsService.bundleId()` now composes the topic
from `APP_ENV`, mirroring how iOS composes the same id from
`STATIONLY_BUNDLE_BASE` plus a per-config suffix:

```ts
const explicit = process.env.APNS_BUNDLE_ID?.trim();
if (explicit) return explicit;
return process.env.APP_ENV === 'staging' ? `${BUNDLE_BASE}.staging` : BUNDLE_BASE;
```

A new environment gets the right topic **by existing**, rather than by someone
remembering an override. Only the exact string `staging` diverts; anything else,
including an unset `APP_ENV`, resolves to production — the safe direction, since a
misconfigured staging box fails against its own devices while production on a
staging topic would take push down for real users. The three redundant config
paths added while diagnosing (`.env.defaults` hardcode, `.env.remote` entry,
`staging_deploy.sh` override) were all removed so one rule governs.

**Two operational notes this cost time to learn:**

- `GET /admin/device-push/status` returns the live `bundleId`. **Check it first**
  when pushes are not arriving.
- Permanent failures **prune the token**, so a topic bug empties the registry. The
  client's re-registration guard is **in-memory only**, so force-quitting and
  reopening the app re-registers. Merely foregrounding it will not, and neither
  will reinstalling — installing over an app keeps its container.

### 2.10 Gallery previews could mis-attribute a station (correctness, defensive)

`timeline(for:in:)` runs for the gallery and edit sheet too. Those builds wrote
placement stamps, and `HomeStateProbe` matches stamps to real widgets **by
family**, taking the most recent — so browsing the gallery could hand a genuinely
placed widget the station of one merely looked at. They also shared a `ledgerId`
with the real widget, so they could move its `.after` marker and invent spend.

**Fix:** `context.isPreview` skips both. Not the cause of anything observed —
`preview` has never appeared in the trace on this device.

---

## 3. Reverted

**The unchanged-board back-off**, built earlier in the same session and removed
before commit. It fingerprinted the rendered board and stretched the next
interval by up to 2× when two consecutive charged builds saw identical data.

Three reasons:

1. **It worked against the goal.** A quiet stop backs off to 30 minutes; the bus
   that finally appears is then up to 30 minutes late reaching the screen. The
   moment the information becomes valuable is the moment it stopped looking.
2. **The saved budget had no consumer.** We are at 25% of the ceiling (§1).
3. **Architecturally wrong for SDUI.** The schedule is served so iOS, Android and
   web can all be driven from one document. A client that quietly stretches its
   own cadence makes that document a suggestion.

Gone with it: `RefreshSegment.maxIntervalMinutes`, the `maxInterval` wire field,
and their two tests. `RefreshPolicyEvaluator.kt` and `RefreshScheduleAppGroup.kt`
are byte-identical to `HEAD`, which is the check that the revert was clean rather
than approximate.

---

## 4. Performance

- **One suite scan per build, not three.** `syncGeneration`, `reap` and
  `publishMirror` each fetched the ledger set for itself. The set is now
  enumerated once in `recordReload` and threaded through.
- **Mirror writes are compare-before-write**, the same discipline as KMP's
  `putIfChanged`. `publishMirror` now runs on every build (§2.5) and the values
  move rarely, so an unconditional write would wake `cfprefsd` for nothing most
  of the time.
- **`reap` returns immediately on an empty set**, so a first-ever build does no
  work and allocates no `Set`.
- **The observed-widgets array is only read when the observation is fresh
  enough to be usable**, rather than read and then discarded.
- **`String(describing: context.family)` computed once per build**, not twice.
  The placement stamp and the ledger id now share the one value, which also makes
  it impossible for the two to disagree about what family a widget is.
- **`recordReload` returns `Int` again.** It briefly returned a `Reload` struct
  carrying a `charged` flag, which existed solely for the reverted back-off's
  streak. No caller read it.

---

## 5. Diagnostics added

| Key | What it answers |
|---|---|
| `widget_observed_raw` | **How many widgets does this person actually have.** `kind\|family` straight from `getCurrentConfigurations`, nothing inferred. |
| `widget_observed` / `_at` | The reconciled `family\|station` view, for the reaper. Stations are **inferred** by family-matching — do not read as an inventory. |
| `id=` on `widget_scheduled_build_log` | With two widgets the gaps are only meaningful per widget; interleaved lines otherwise read as one healthy widget. |
| `preview` on `widget_refresh_trace` | Separates a home-screen build from a gallery render. |

**A hard-won lesson is written up in `IOS_WIDGET_REFRESH.md` §6.1:** reinstalling
the extension leaves phantom widget registrations. A device with **three** widgets
reported **ten**, and `getCurrentConfigurations` (app side, clears on app
relaunch) disagreed with `chronod` (timeline requests, clears only on a **device
reboot**) for the better part of an hour. Read `widget_observed_raw`, and only
after a reboot.

---

## 6. Verification

```bash
./gradlew :core:testDebugUnitTest          # 30 tests, 0 failures
./gradlew :core:compileDebugKotlinAndroid  # Android unaffected
./gradlew :composeApp:assembleComposeAppDebugXCFramework   # MUST precede xcodebuild
cd iosApp && xcodebuild -scheme "iosApp Staging" -configuration "Debug Staging" ... build
cd stationly-backend && ./.scripts/staging_deploy.sh       # deployed, health check 403 = up
```

**End-to-end proof the schedule reached the device**, which is the step every
earlier attempt stopped short of:

```
21:27:35  widgetpush recv kind=policy.update reason=P5 pre-dawn
21:27:35  schedule republished

   23:00 -> 05:00   P3  (180m, background wake off)
   05:00 -> 06:30   P5  (90m timeline, 20m background wake)   <- new
   06:30 -> 09:30   P1  (15m)

   bgtask scheduled in 45m    <- P2's wake, was 60
```

Deployed artifact confirmed to carry P5, the 05:00 split and both
`backgroundTaskMinutes` changes (`dist/services/refreshPolicyService.js`).

**Verified on device:** per-widget ledger keyed correctly to the three real
widgets; 7 entries derived by prefix scan with no roster key; foreground builds
still marked `FREE` without incrementing; generation stamp matching the build.

**NOT verified:** the reaper actually firing (needs a widget deleted and ~2h),
and the mirror-clearing path of §2.6. The extension has no test target, so the
Swift halves are reasoned rather than measured.

---

## 7. What to do next

### 7.1 WidgetKit push on a cadence — the only lever with real headroom

Working again since §2.9, registered on the test device, drawing on a budget
separate from the 40–70, and unlike `BGAppRefreshTask` it both fetches **and**
repaints. Today it fires only on disruption transitions.

**Why this is the answer to "why isn't peak actually 15 minutes".** The tier
already asks for 15. `.after` is a floor, not a timer, and measured across both
peaks on a real device that 15-minute ask was delivered at:

```
07:25 → 07:55  +30      16:58 → 17:28  +30
07:55 → 08:59  +63      17:28 → 17:59  +30
08:59 → 09:24  +25      17:59 → 18:59  +60
```

Averaging ~43 minutes for a 15-minute request. Lowering `intervalMinutes` cannot
help; the system is already declining the current one. There is no API for a
clock-driven redraw, deliberately. Push is the only mechanism that bypasses it.

**Do NOT push every minute.** Four separate reasons, and the project has already
reached this conclusion once — `Station_*` / `LineStatus_*` FCM topics are
Android-only precisely because "per-minute pushes would exhaust the iOS quota":

1. **Widget pushes are metered too.** A separate allowance from the timeline
   quota is not an unlimited one, and its throttling behaviour is barely
   documented. Overspending it plausibly ends worse than the status quo.
2. **Silent pushes are best-effort by contract.** Apple rate-limits them and
   states they may be delayed or dropped. 1,440/device/day is the shape that
   triggers exactly that.
3. **Battery.** Every push wakes the extension, calls the API and re-renders the
   board. 1,440 wakes a day is visible in Settings, and iOS responds by
   throttling the app further.
4. **Fan-out cost.** Pushes are per device. A thousand users is 1.4M pushes/day,
   each triggering a backend fetch.

**And it buys almost nothing.** Rows already re-derive locally every minute at
zero cost (`ticked(at:)`), so a per-minute push would spend the whole budget
redrawing numbers the widget can compute itself. What ages between refreshes is
the prediction *set*, which does not move on a one-minute scale.

**Sizing:**

| Cadence | Pushes / device / day | |
|---|---|---|
| Every 1 min | 1,440 | not viable |
| Every 15 min, **peak only** | 26 | start here |
| Every 15 min, 06:30–23:00 | 66 | only if 26 lands cleanly |

Peak-only at 15 minutes delivers the originally intended rush-hour cadence for 26
pushes, none of which touch the timeline quota. That is a plausible ask; 1,440 is
not.

**Shape of the work:** a cadence field on the tier so the schedule stays the
single SDUI source and Android/web inherit it, plus scheduling in
`devicePushService`. Ship peak-only, measure how many of the 26 actually land,
then decide whether to extend. If they are throttled, fall back to 30 minutes at
peak and you are still ahead of today's ~43-minute average.
### 7.2 Everything else

1. **Settle per-widget vs per-app** (§1). Everything in the budget model depends
   on it.
2. **Start incrementing `RefreshPolicy.version` at launch.** Held at 1 while
   pre-launch because no client is in the field to be stale. Once real devices
   hold cached copies, that field is what lets a `policy.update` push say "you
   are stale" without carrying the document.
3. **`policy.update` belongs in `staging_deploy.sh`.** Editing the schedule does
   not reach devices by itself; the 12 h TTL is only checked on app foreground,
   and `forcePolicyRefresh()` is reachable ONLY from that push. Deferred on
   2026-08-10; it has now cost real time twice, including tonight, where the
   deploy was inert until the push was sent by hand.

---

## 8. What the widget actually does now, unaided

For anyone asking "how often does it refresh on its own", with no app launch and
no refresh tap:

| | Frequency | Notes |
|---|---|---|
| **Screen redraws** | **6–11/day** | Measured. Gaps of 25–250 min, once 627. |
| Background data fetches | tier cadence (15/45/30/20 min) | Separate budget. Refreshes DATA, **cannot repaint**. |
| Disruption pushes | unpredictable | Fetch *and* repaint. Dead 2026-08-15 → 08-17, see §2.9. |

**The display never looks frozen**, because rows re-derive every minute locally
for free. What ages between redraws is the prediction set, not the countdown.

Tonight's work did not raise the 6–11. Nothing on the device can; see §7.1. What
it changed is *when* freshness lands — the pre-dawn band means the ~06:30 redraw
shows minutes-old data instead of hours-old.
