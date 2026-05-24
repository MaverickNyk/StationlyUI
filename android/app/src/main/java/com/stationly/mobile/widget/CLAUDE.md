# Widget — Agent context

The Stationly home-screen widget renders the same dot-matrix departure
board the in-app summary screen and the dream/screensaver show.
Everything in this package backs **a single AppWidgetProvider**:
`DepartureWidgetProvider`. There's no Compose here — the widget is
classic RemoteViews + `R.layout.widget_departure_board`.

## File layout

```
widget/
├── DepartureWidgetProvider.kt   The whole widget. Lifecycle hooks,
│                                broadcast actions, RemoteViews builder,
│                                AlarmManager watchdog + colour-fade
│                                alarms.
└── CLAUDE.md                    This file.
```

External entry points:
- `AndroidManifest.xml` registers `DepartureWidgetProvider` with the
  standard widget actions + our custom `ACTION_*` broadcasts.
- `service/FcmMessagingService` calls `updateWidgetContent(...)` after
  every FCM payload is persisted to SQL.
- `core/.../platform/AndroidWidgetManager` sends `ACTION_UPDATE_WIDGET`
  broadcasts from the in-app code path so the widget refreshes when
  the user changes their selection or fetches via REST.

## How the data flows

1. **FCM lands** → `FcmMessagingService.handlePredictionUpdate`
   - Runs `SyncPredictionsUseCase.execute(payload, selection)` which
     persists predictions to SQL with **8 rows per platform** (see
     `GlobalBoardProcessor.processPredictions` and the
     `perPlatformCap` parameter)
   - Then calls `DepartureWidgetProvider.updateWidgetContent(...)` to
     immediately push the new rows to every widget instance
2. **Watchdog fires** (`ACTION_ETA_TICK`) → `updateFromStorage(context)`
   - Reads predictions back from SQL
   - Re-derives each row's `eta` from `targetEpochMs + now` via the
     shared `tickPredictions` helper in `ui/util/PredictionTicker.kt`
   - Drops rows whose train has departed (>60s past target)
   - Caps the survivors at 3 per platform for display
   - Renders RemoteViews and pushes via `appWidgetManager.updateAppWidget`
3. **User taps refresh** (`ACTION_MANUAL_REFRESH`) → same path as FCM
   but proactively hits the TfL repository (`fetchInitialData`) before
   re-reading SQL

The renderer never blits the SQL `eta` string directly — that string
was current at FCM time and is now stale by ~30s minimum, or many
minutes if FCM has gone silent. Every render re-derives.

## The watchdog (`ACTION_ETA_TICK`)

The widget needs to tick its ETAs every minute even when FCM is silent
(network drop, Syncer crash, Doze). Compose tickers can't help — the
widget is RemoteViews, no coroutines. Solution: AlarmManager.

**Behaviour:**
- Every render (FCM-driven, manual refresh, or watchdog fire) calls
  `scheduleEtaTickWatchdog(context)` at the very end, which schedules
  the next watchdog at the next wall-clock minute boundary that's at
  least 30s in the future
- Same `PendingIntent` + `FLAG_UPDATE_CURRENT` means scheduling a new
  alarm cancels and replaces any pending one — that's how FCM
  rendering "resets" the watchdog without explicit cancel calls
- Result in steady-state (FCM every ~30s): watchdog rarely fires
  because each FCM render replaces it
- Result when FCM stops: watchdog fires at the next minute boundary,
  re-derives ETAs, reschedules. Continues until FCM resumes.

**Why the next wall-clock minute (not `now + 60s`)?** Because the
home + dream surfaces tick aligned to wall-clock minutes (see
`ui/util/PredictionTicker.kt#rememberMinuteTick`). The widget must
flip at the same instant or the user sees inconsistent values
between surfaces.

**Why `set()` not `setExact*`?** Inexact alarms need no permission
(`SCHEDULE_EXACT_ALARM` is post-Android-12 gated). The OS batches
inexact alarms with other system alarms, lands within seconds of
target, and defers during Doze — exactly what Play Store wants.
Sub-minute precision is a polish, not a clinical-grade trigger.

**Lifecycle:**
- `onUpdate` → triggers `updateFromStorage`, which schedules the
  watchdog at the end of `updateAppWidget`
- `onDisabled` → cancels the watchdog (last widget removed; no need
  to keep waking the device)

## The chronometer ("X ago" timer)

`R.id.last_updated_timer` is a `Chronometer` view. Its base is set to
`SystemClock.elapsedRealtime() − (System.currentTimeMillis() − lastUpdatedMs)`
so the displayed "X ago" reflects **time since the FCM/REST payload was
persisted to SQL**, not time since the widget last redrew.

`lastUpdatedMs` is read via `SqlStorage.getPredictionsTimestamp(stationId, lineId)`
— the MAX of the row timestamps. All three surfaces (home, dream,
widget) read this same value, so the "X ago" is identical at any wall
moment.

The chronometer view is re-anchored on every full `updateAppWidget`
call because RemoteViews re-inflates the view hierarchy. The math is
idempotent (same base computed each time) so there's no visible
flicker — don't try to "optimise" by skipping the call; the
chronometer would otherwise show 0:00.

## Colour-fade alarms (`ACTION_TIMER_DIM` / `ACTION_TIMER_RED`)

Separate from the ETA watchdog. These shift the chronometer text from
amber → grey → red as the data ages, signalling staleness. Scheduled
at `now + 60s` (DIM) and `now + 180s` (RED) on every render. Pre-dates
the ETA watchdog work and is independent.

There's a latent bug here: the colour timers fire relative to render
time, not relative to `lastUpdatedMs`. So if data is already 5 min
old when the widget re-renders, the colour resets to amber and only
goes red 3 min later. Fix would be to anchor the colour alarms to
`lastUpdatedMs + 60s` / `lastUpdatedMs + 180s`. Not blocking — the
colour feedback is approximate anyway.

## Architectural invariants (do not break)

**1. Every render path lands in `updateAppWidget`.**
FCM → `updateWidgetContent` → `updateAppWidget`. Watchdog →
`updateFromStorage` → `updateAppWidget`. Manual refresh →
`updateFromStorage` → `updateAppWidget`. The watchdog scheduling
sits at the END of `updateAppWidget` so every path re-arms it.
Don't bypass `updateAppWidget` or you'll leak alarms.

**2. The renderer re-derives `eta` from `targetEpochMs`, never blits
the SQL string.**
SQL stores the eta that was current at FCM time. The tick layer
recomputes from `targetEpochMs + now` on every render. See
`tickPredictions` in `ui/util/PredictionTicker.kt`.

**3. Dropped rows shift the queue.**
After ticking, rows whose `targetEpochMs < now − 60s` are removed
from the list, and the visible 3-per-platform cap is re-applied.
Storage keeps 8 per platform (5 buffer rows beyond the visible 3)
so the queue can shift up without waiting for FCM.

**4. `lastUpdated` is the SQL row timestamp, not "now".**
`SqlStorage.getPredictionsTimestamp` is the only honest source. The
"X ago" chronometer base is computed from this value, not from
`System.currentTimeMillis()` at render time.

**5. No `SCHEDULE_EXACT_ALARM` permission.**
The watchdog uses inexact `AlarmManager.set()` deliberately. Don't
add the permission "for precision" — it's a Play Store reviewer
trigger and we don't need it.

**6. `onDisabled` cancels the watchdog.**
Don't add new self-rescheduling alarms without an `onDisabled`
cleanup, or the widget will keep waking the device after the user
has removed it.

## Consistency contract with home + dream

The widget shares its tick logic with the home Board and the dream
through these abstractions:

- `core/.../util/StationlyFormatters.formatMinutesRemaining` — the
  one formula that turns `(targetEpochMs, nowMs)` into a display
  string. Used by Compose `rememberTickedPredictions` AND the widget's
  inline tick step. Cannot diverge.
- `ui/util/PredictionTicker.tickPredictions` — the filter+reformat
  function. Used by Compose `rememberTickedPredictions` (which wraps
  it with a minute-tick state) AND directly by the widget.
- `core/.../util/GlobalBoardProcessor.processPredictions` with
  `perPlatformCap = 3` — the display cap. Both home and widget call
  this on their post-tick list.
- `SqlStorage.getPredictionsTimestamp` — the "X ago" source. All
  three call this same function.

If you find yourself writing tick-related logic ONLY in this file,
you've probably broken consistency. Either add it to the shared
helpers, or document why this surface needs to diverge.

## What is intentionally not here

- **No "Due" pulse animation** on rows. We tried it and it caused
  rendering artifacts on multi-platform stations where row diffs and
  animation timing overlapped. The colour signalling on home/dream
  carries the urgency cue; the widget uses static amber text.
- **No background service.** Everything is event-driven via
  broadcasts. The process is woken briefly, does its work, and is
  allowed to die.
- **No `WAKE_LOCK`, no `FOREGROUND_SERVICE`.** AlarmManager handles
  its own wake-up; the broadcast receiver `goAsync()` keeps the
  process alive long enough for the SQL read + render.

## Common gotchas

- **`updateAppWidget` rebuilds the view tree.** Every call applies a
  fresh RemoteViews actions list; the previous state is gone except
  for view IDs. Don't expect tags or animations to persist.
- **Multiple widgets on one home screen.** `updateWidgetContent`
  iterates all `appWidgetIds`. Don't assume there's only one.
- **The `SDUI` payload path.** If the user's station has a stored
  SDUI template (`sdui_layout_<stationId>` SharedPref), we bind it
  with the ticked predictions and render through that path instead
  of `prepareLegacyRows`. Both paths produce identical ETAs.

## When you change something here

After modifying any file in this folder, run `graphify update .` from
the repo root to keep the project's knowledge graph in sync (AST-only,
no API cost). The graph at `graphify-out/` is what future agents read
for architecture context.
