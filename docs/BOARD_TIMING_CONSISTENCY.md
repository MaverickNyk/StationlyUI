# Board timing consistency — home board, hero, widget

Session handover. Branch `ios-parity`.

**Goal:** one departure shows the same countdown on every surface at every
instant, counts down on its own without a refetch, and rolls the next departure
into view as trains leave.

---

## 1. The pipeline

One implementation in `core`, three consumers. The order of the three steps is
the design, and each was wrong before in a different way.

```
raw departures (SQL, ROW_RESERVE deep — reserves AND just-departed rows)
  │
  ├─ MultiLineBoardProcessor.buildGroups(rowCap = ROW_RESERVE, nowMs)
  │     grouping · block order · header text
  │
  ├─ BoardTicker.tick(nowMs, payloadAgeMs, displayRows)
  │     shed departed · retain "Gone" · monotonic bump — all PER BLOCK
  │
  └─ MultiLineBoardProcessor.rowsFrom(groups, rowCap = prefs.rowCap)
        display depth, applied last
```

`WidgetData.ticked(at:)` in `AppGroupStorage.swift` is the hand-mirrored twin of
step two. The extension is a separate process and cannot call Kotlin, so parity
is by hand: **change one, change both, in one commit.** Shared constants are
30 s departed grace, 60 s retention age, `"Gone"`, `ROW_RESERVE`.

### Two invariants that are easy to break

**Cap last.** Trimming to the visible depth before shedding departed rows leaves
a block with nothing to shift up. That was the headline bug (§2.1).

**The cap lives in `rowsFrom`, not in `tick`.** The hero picks the soonest train
*per line* out of the same ticked blocks, and `rowsPerPlatform` is explicitly not
allowed to re-point the hero. Capping inside `tick` makes a line whose next train
sits below another line's rows report "no departures" while its train is two
minutes away.

---

## 2. Bugs fixed

### 2.1 The home board was never given its reserves — `SummaryViewModel.kt:284`

`GlobalBoardProcessor.processPredictions(rawPreds)` used the **default cap of 3**
per platform. `SyncPredictionsUseCase` stores 10. The board therefore received
three rows, and as each departed it went 3 → 2 → 1 → empty and stayed empty until
the next push. The widget reads SQL directly and kept all of them, so the two
surfaces disagreed within a minute of every update.

Android's own ViewModel has always passed `Int.MAX_VALUE`; the Compose port
dropped it. Now passes `Int.MAX_VALUE` — the call is a sort, not a cap.

### 2.2 The monotonic bump grouped by the wrong key on bus

`tickPredictions` bumped per `prediction.platform`; the board and the widget
group by **pole** (`groupKeyFor`). TfL letters stops only at multi-stop
interchanges, so at an ordinary hub every pole reports `platform = ""` and two
poles were bumped as one queue: the home board read `Due, 1 min` where the widget
read `Due, Due` for the same two buses.

The bump now runs over the blocks themselves, so the queue it enforces is the
queue a passenger experiences.

### 2.3 No "Gone" retention on the home board

The widget held departed rows labelled `Gone` to fill a block's shortfall; the
home board padded with blank rows. Same train, two answers. `BoardTicker` now
implements the widget's rule for both, gated on payload age ≥ 60 s so a fresh
board is short rather than lying.

### 2.4 Two definitions of "due" in one function

`BoardTicker.bump` flagged a **lone** row `isDue = secs < 30` but a bumped row
`isDue = minutes == 0`. A single departure 45 s out was labelled `"Due"` and
flagged `isDue = false` — the label and the flag contradicting each other about
the same train. It reached the widget, which tints from the flag, so a lone
`Due` rendered in ordinary amber where the identical train on a busier platform
rendered red.

Both branches now route through `minutesAt` / `label`. `isDue` means exactly
"this row reads Due", which is what `SyncPredictionsUseCase` has always written
at ingest and what every consumer already assumed. Fixed in Kotlin **and** the
Swift twin.

### 2.5 Blocks whose trains had all left could lead the board

Departed rows now reach the grouping (they are what retention holds), and their
targets are in the past — so a platform with nothing catchable sorted *first*.
`buildGroups` takes an optional `nowMs`; `soonestArrival` keys on the soonest
**live** train and sinks a spent block to the bottom.

### 2.6 App crash on expanding a tall station — `Board.kt`, `BoardScrollbar`

```kotlin
(viewport * viewport / content).coerceIn(26.dp.toPx(), viewport)
```

`coerceIn(min, max)` throws when the range is empty, and here `max` is the
viewport. `card.AnimatedVisibility` expands the body from **height zero**, so
every open sweeps the rows area up through the small heights while `maxValue`
already reports overflow — `IllegalArgumentException`, `SIGABRT`, from inside
`MetalRedrawer.draw`.

Latent, not new: the bar only draws on a board that overflows, so a station had
to be tall enough to scroll before its expand animation could reach it. The
retained `Gone` blocks (§2.3) pushed King's Cross over that line. Fixed with a
guard — below one thumb height there is nothing meaningful to say about scroll
position.

Diagnosed from four device crash reports via `idevicecrashreport`; the
`lastExceptionBacktrace` named the frame directly.

### 2.7 Collapsed cards hid platforms — `MAX_COLLAPSED_LEGS`

Capped at two. For a user who keeps stations collapsed the card *is* the home
screen's answer, and a four-platform station showing two legs looks complete
while answering a narrower question than the one asked. Now one leg per block.

The cap was really paying a layout cost: `boardMaxHeight` charged the open board
`LEG_HEIGHT × MAX_COLLAPSED_LEGS` per collapsed station. That is now a
measurement (`collapsedLegCount`) rather than an assumption — see §4.

### 2.8 Usable predictions discarded at 8 minutes — `SqlStorage`

`getPredictions` dropped the whole payload at 8 minutes. Measured against the
live feed, TfL predicts **~25 minutes** ahead, so at minute 8 we were discarding
rows still describing trains 17 minutes in the future — handing the user "Signal
lost" underground while the device held a good answer. `PAYLOAD_TTL_MS` is now
30 minutes, sized to the feed's own horizon.

Not removed, deliberately: without it the app would go on counting down a train
TfL cancelled an hour ago.

---

## 3. Measurements

Taken from staging (`/api/v1/stations/predictions/:naptanId`) during the session.

| Naptan | Lines | `preds` sent | Deepest platform | Furthest arrival |
|---|---|---|---|---|
| King's Cross St. Pancras | 6 | 63 | 9 | 24.8 min |
| Edgware (Northern) | 1 | 20 | 10 | 24.5 min |
| Smithwood Close (bus pole) | 1 | 1 | 1 | 14.4 min |

**The backend applies no count cap** — `TubeDlrBusTramMixPredictionSource.ts`
pushes every TfL arrival, dropping only >2 min departed and far-future
unplatformed rows.

Two conclusions:

- **`ROW_RESERVE` 8 → 10.** At 8 the cap genuinely bit (Edgware sends 10 per
  platform). At 10 nothing is trimmed in practice.
- **A one-hour widget is not reachable from this source.** The 24.5–24.8 minute
  ceiling recurring across unrelated lines is TfL's feed, not our cap.
  `horizonMinutes` already sizes the timeline to the last departure, so
  `maxHorizon = 60` was never reachable. An hour needs scheduled-timetable data
  blended on the backend — a different source with different truth, and those
  rows would need to look different on the board. **Separate project.**

---

## 4. Performance and state integrity

| Change | Effect |
|---|---|
| `blockCount` counts into a `Set` | Was `flatMap` + `distinctBy`, allocating a `Pair` per departure to answer "how many distinct keys". Runs per collapsed station on every prediction update. |
| `List<Feed>.withFeeds()` extracted | The flatten idiom was written out at five call sites — five chances to lose the feed pairing and re-introduce the bus grouping bug. |
| `selectionsByStation` hoisted in `SummaryScreen` | Was grouped twice per recomposition. More importantly the two copies could disagree, so the height budget could reserve for a different set of stations than the one drawn. |
| Redundant `remember` key dropped | `lastUpdated` is derived from `sections`; it cannot change without `sections` changing. |
| One clock per widget write | `buildBoard` read `NSDate()` per station, so boards written in one pass judged "has this train left" against different clocks. |
| `rowCap` normalised at the Swift decoder | `max(1, …)` on the way in, so no renderer defends against a zero depth. |
| Hero/rows share one object | The hero reads its section's soonest live row *out of the ticked blocks*. Not a re-derivation — the same instance the row renders, so they cannot disagree. |

The per-minute tick is unchanged in cost: one `remember` keyed on a
wall-clock-aligned minute, recomputing grouping + tick for one card. No polling,
no network, no per-frame work.

---

## 5. Widget

- **`WidgetBoard.rowCap`** added to the wire format (Kotlin + Swift mirror). The
  extension cannot read `StationPrefsRepository` — it writes to the app's own
  NSUserDefaults suite, not the App Group — which is why the widget hardcoded
  three rows and contradicted the home board for anyone who moved the slider.
- **`BoardMetrics.rows(for:)`** — `min(canvas maximum, user's depth)`. The
  setting can make a widget shallower, never taller than its canvas.
- **Retention target follows the setting**: `slotsPerPlatform = min(rowCap, 3)`.
  It must never exceed the slots actually displayed — retained rows sort to the
  top, so holding more than `prefix` draws would hide the live trains beneath
  them. Under-shooting is harmless, which is why large stays at 3.

Rolling in the widget was already correct and is unchanged: one timeline entry
per minute for 15 minutes, then every 5 to the horizon, each entry re-derived by
`ticked(at:)`. No refresh budget, no network, app not running.

---

## 6. Verification

- **130 core tests pass** (7 classes, 0 failures, forced full run).
  `BoardTickerTest` walks the five-departure scenario
  minute by minute through `rowsFrom`, plus retention gating at the threshold,
  bus-pole bump separation, block ordering with spent platforms, `isDue`
  agreement across block sizes, and null-target passthrough.
- `:composeApp:compileKotlinIosArm64` and `:compileDebugKotlinAndroid` clean.
- `xcodebuild` clean; installed and launched on the iPhone 11.

**Not covered by tests:** the `BoardScrollbar` guard (§2.6) — a draw-phase
geometry bug, verified on device only. Worth remembering that the crash lived in
`drawBehind`, where nothing in the test suite reaches.

`:core:compileKotlinWasmJs` fails on a SQLDelight variant gap. **Pre-existing** —
verified identical on the stashed tree.

---

## 7. Known follow-ups

1. **Collapsed interchanges are now tall.** King's Cross with six lines tracked
   reserves ~8 legs ≈ 176 dp against the open board's budget. It cannot push the
   open board below `MIN_BOARD_HEIGHT` (three rows guaranteed), so it will not
   break — but with several big stations collapsed the open board sits at its
   floor. If it reads badly, a ceiling of 4–5 covers every ordinary station while
   stopping an interchange turning its collapsed card into a board.

2. **`SIGNAL_LOST_MIN = 6` rarely fires now.** With the board holding rows for
   30 minutes and retaining `Gone`, the fallback copy only appears when there is
   genuinely nothing to draw. Consistent with the chosen all-`Gone` behaviour —
   the footer's amber/red "X ago" and the dimmed rows carry the staleness — but
   it is a real change in how an offline board communicates.

3. **Shared-core changes reach Android.** `ROW_RESERVE` and `PAYLOAD_TTL_MS` live
   in `core`, so the native Android app gets deeper reserves and a longer TTL
   too. Both are strict improvements there (its ticker drops departed rows and
   `computeBoardFallbackState` handles empty), but it has not been run.

4. **Android keeps its own `PredictionTicker`.** `android/ui/util/PredictionTicker.kt`
   is a separate copy and still bumps by `platform`, so §2.2 is unfixed there.
   Out of scope for the iOS parity branch; worth folding into `BoardTicker` when
   that app is next touched.
