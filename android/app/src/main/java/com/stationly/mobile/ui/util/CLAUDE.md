# UI util — Agent context

Shared cross-surface utilities for the Compose UI layer. Today this
package holds the **prediction tick contract** — the single source of
truth for how the home Board, the dream NextTrainHero, the dream
dot-matrix, and the widget all compute their displayed ETAs.

## File layout

```
ui/util/
├── PredictionTicker.kt   The tick contract. `rememberMinuteTick`,
│                         `rememberTickedPredictions`, `tickPredictions`,
│                         `DEPARTED_GRACE_MS`.
└── CLAUDE.md             This file.
```

## The tick contract

Stationly displays "X min" departure times across five surfaces:

| Surface | Renderer | Tick driver |
|---|---|---|
| Home dot-matrix board | AndroidView + widget XML | `rememberTickedPredictions` |
| Home Next Departure hero | Compose Card | `rememberMinuteTick` + ticked list |
| Dream dot-matrix board | AndroidView + widget XML | `rememberTickedPredictions` |
| Dream Next Train hero | Compose Card | `rememberMinuteTick` + ticked list |
| Home-screen widget | RemoteViews | direct `tickPredictions` call |

All five must show the same value at any given wall-clock moment. The
only way to guarantee that is to share the math. That's what this
package provides.

## The three primitives

### `rememberMinuteTick(): MutableLongState`
Compose-only. Returns a `MutableLongState` holding the current wall-
clock millis, refreshed on the **next wall-clock minute boundary** and
every minute thereafter. Multiple call sites get independent state
objects but they all align to the same `xx:00:00` flip — so the hero
card and the dot-matrix board (each calling separately) recompose at
the same instant.

Implementation detail: the first delay is `60_000L − (now % 60_000L)`,
not `60_000L`. Without alignment, a composition starting at `12:25:40`
would tick at `12:26:40`, `12:27:40`, ... — off the user's phone
clock by 40s.

### `tickPredictions(predictions, nowMs): List<PredictionDisplay>`
Pure function. Takes a list of predictions and the current wall-clock
millis, returns a new list where:

1. Predictions with `targetEpochMs < (nowMs − DEPARTED_GRACE_MS)` are
   **filtered out** (the train has departed, no longer relevant)
2. Surviving rows have their `eta` string **re-derived** from
   `targetEpochMs` via `StationlyFormatters.formatMinutesRemaining`
3. Surviving rows have their `isDue` flag **re-evaluated** (within
   30s of target)

Rows with a `null` `targetEpochMs` (FCM ISO timestamp didn't parse —
defensive fallback) are passed through unchanged — neither dropped
nor re-formatted.

Called by:
- `rememberTickedPredictions` (Compose surfaces)
- `DepartureWidgetProvider.updateFromStorage` (widget — no Compose
  available, calls directly)

### `rememberTickedPredictions(predictions): List<PredictionDisplay>`
Compose convenience wrapper. Subscribes to `rememberMinuteTick` and
applies `tickPredictions` each tick. Returns a derived list that
re-emits on every wall-clock minute.

## Constants

### `DEPARTED_GRACE_MS = 60_000L`
How long after a train's `targetEpochMs` we keep showing it before
dropping. 60s is a Londoner-friendly grace window: "Due" stays
visible long enough for the user to act, then disappears so the
next upcoming train moves up.

This constant is the single threshold. Changing it from 60s to 90s
or 30s would shift the behaviour identically on home + dream +
widget — that's the point.

## How surfaces consume the contract

### Home Board (`ui/summary/components/Board.kt`)
```kotlin
val tickedPredictions = rememberTickedPredictions(predictions)
// dot-matrix uses tickedPredictions in prepareLegacyRows
// hero card uses tickedPredictions.sorted by ETA, firstOrNull()
```
Re-binds the SDUI template at tick time with the ticked list so
SDUI-driven boards also pick up the refreshed eta strings.

### Dream (`dream/DreamHost.kt` + `dream/DreamBoard.kt`)
- `DreamHost` resolves the hero via `rememberTickedPredictions` +
  `sortPredictions` so the hero shifts to the next upcoming train
  when the current one departs
- `DreamBoard` independently calls `rememberTickedPredictions` for
  its dot-matrix rows
- Slight redundancy (two minute-tickers) is intentional: each
  composable owns its own state; they align to the same boundary
  via the modulo math, no coordination needed

### Widget (`widget/DepartureWidgetProvider.kt`)
Cannot use Compose. Calls `tickPredictions(rawPredictions, nowMs)`
directly inside `updateFromStorage`. An `AlarmManager` watchdog
fires at every wall-clock minute boundary (≥ 30s away) so the widget
re-renders in sync with the Compose surfaces.

## Architectural invariants (do not break)

**1. The tick is a UI concern, not a storage concern.**
SQL stores the canonical state (predictions + `targetEpochMs`). The
tick layer is purely about rendering. Don't push `tickPredictions`
into `SyncPredictionsUseCase` or `SqlStorage` — it would mean
re-deriving on every read, even reads that don't need it (e.g.
background sync checks).

**2. `formatMinutesRemaining` is the only formatter.**
Lives in `core/.../util/StationlyFormatters.kt`. Every surface that
renders "X min" goes through this function — and through this one
only. No inline `(secondsRemaining + 30) / 60` math elsewhere.

**3. Minute-alignment is the consistency anchor.**
Surfaces tick at wall-clock minute boundaries, not arbitrary 60s
intervals from composition start. The home and the widget can both
crash + restart at different moments and still show the same value
at `12:25:00` because they re-align on the next boundary.

**4. SDUI predictions are NOT exempt from the tick.**
Earlier iterations gated `rememberTickedPredictions` behind
`sduiPayload == null` on the theory that SDUI-rendered ETAs are
server-formatted. Don't reintroduce that gate: the SDUI binding
runs at FCM time, so its eta strings go stale between FCM pushes
just like the legacy path. The Board re-binds the SDUI template
with the ticked predictions to keep them honest.

**5. `nextDeparture` derivation must sort by ETA, not list order.**
After ticking, the first row in the SQL-order list is NOT
necessarily the soonest upcoming train (Platform 1's earliest may
have departed while Platform 2's earliest survives). The hero
surfaces always call `StationlyFormatters.sortPredictions(...)`
before `.firstOrNull()`.

## When the tick layer changes shape

This contract is load-bearing across home + dream + widget. If you
add a new field to `PredictionDisplay` or change the formatter
semantics:

1. Update `formatMinutesRemaining` in `core/.../util/StationlyFormatters.kt`
2. Update `tickPredictions` in this file if the filter/reformat step
   needs to change
3. Verify the widget's `DepartureWidgetProvider.updateFromStorage`
   path still calls `tickPredictions` with the same shape
4. Run the offline test: device → airplane mode → wait 2 minutes →
   confirm all three surfaces show identical values
