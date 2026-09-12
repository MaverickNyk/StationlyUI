# Widget — Agent context

The Stationly home-screen widget renders the same dot-matrix departure
board the in-app summary screen and the dream/screensaver show.
Everything in this package backs **a single AppWidgetProvider**:
`DepartureWidgetProvider`. The board itself is classic RemoteViews +
`R.layout.widget_departure_board` — no Compose. The configuration
screen is Compose, because it is an ordinary Activity.

**Since AV2-5.1 (2026-09-06) each placed widget shows its own station.**
Before that, `updateFromStorage` read `selections.first()` and pushed
that board to every `appWidgetId`, so two widgets meant two copies of
one station. Android needs no widget stack for this: the home screen
has always allowed many instances of one provider, each with its own
id. What was missing was a per-instance binding, which is
`WidgetBindingStore`.

## File layout

```
widget/
├── DepartureWidgetProvider.kt   The board. Lifecycle hooks, broadcast
│                                actions, RemoteViews builder,
│                                AlarmManager watchdog + colour-fade
│                                alarms.
│                                ⚠️ Editing this file breaks the
│                                incremental compile — see the banner
│                                at the top of it. Rebuild the module.
├── WidgetBindingStore.kt        appWidgetId → groupingId, in
│                                `widget_prefs`. The whole of "which
│                                station is this widget for".
├── WidgetConfigureActivity.kt   Compose. Three modes: MANAGER (no id —
│                                which widget?), CONFIGURE (an
│                                appWidgetId — the widget's settings
│                                PAGE: choose station, then the same
│                                BoardArrangementSection the station's
│                                own screen shows) and PIN (a station,
│                                then ask the launcher to place one).
│                                CONFIGURE stays open: the launcher acts
│                                on the result when this Activity
│                                FINISHES, not when it is set.
├── WidgetPinner.kt              `requestPinAppWidget`, so "Add to Home
│                                Screen" can live on the station itself.
├── WidgetRedrawTargets.kt       Which widgets a push is about. Pure,
│                                tested — the push names a POLE and a
│                                binding names a STOP.
├── WidgetPlacementProbe.kt      What is actually on the home screen,
│                                asked of AppWidgetManager. Fills
│                                `Board.widget`.
└── CLAUDE.md                    This file.
```

External entry points:
- `AndroidManifest.xml` registers `DepartureWidgetProvider` with the
  standard widget actions + our custom `ACTION_*` broadcasts, and
  `WidgetConfigureActivity` as the `android:configure` target named by
  `res/xml/departure_widget_info.xml`.
- `util/FreshDataNotifier` calls `updateFromStorage(...)` after every
  path that writes predictions to SQL — FCM, the widget's own refresh
  button, a cross-device reconcile.
- `core/.../platform/AndroidWidgetManager` sends `ACTION_UPDATE_WIDGET`
  broadcasts from the in-app code path so the widget refreshes when
  the user changes their selection or fetches via REST.
- `MainActivity` starts `WidgetConfigureActivity` with **no** id, from
  the shared Home settings → "Widget stations" row.

## How the data flows

1. **FCM lands** → `FcmMessagingService.handlePredictionUpdate`
   - Runs `SyncPredictionsUseCase.execute(payload, selection)` which
     persists predictions to SQL with **8 rows per platform** (see
     `GlobalBoardProcessor.processPredictions` and the
     `perPlatformCap` parameter)
   - Then calls `FreshDataNotifier.notifyPredictions(...)`, whose
     widget leg is `DepartureWidgetProvider.updateFromStorage(context)`
   - `updateForStation` redraws **only the widgets bound to the stop in
     the push** (AV2-5.3). The push names the naptan departures were
     FETCHED from and a binding names the HUB, so the id is resolved
     THROUGH the selections — matching it against bindings directly
     would silently never update a bus widget. `WidgetRedrawTargets`
     is that rule, extracted and tested.
   - `updateFromStorage` is the redraw-everything path (watchdog,
     manual refresh, clear) and still loops the placed ids, rendering
     **each one from its own binding**. (It used to call
     `updateWidgetContent`, which took one board and fanned it out to
     every id. That function is deleted — a helper that shows one
     station on every widget is a loaded gun in a file whose one rule
     is never to show the wrong stop.)
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
FCM, watchdog and manual refresh all go
`updateFromStorage` → `renderWidget` (per id) → `updateAppWidget`.
The watchdog scheduling sits at the END of `updateAppWidget` so every
path re-arms it. Don't bypass `updateAppWidget` or you'll leak alarms.

**1a. One widget is one STATION, and a station is all of its boards.**
`renderWidget` takes every selection whose `groupingId` matches the
binding and feeds them to `MultiLineBoardProcessor` — the same grouping
the home screen and the screensaver render from. It used to resolve the
binding to `selections.first { … }` and draw that one, so a user
tracking a station in both directions saw half their board with nothing
to say the other half existed. It looked correct in every screenshot,
because a widget showing one platform looks exactly like a widget that
only knows about one platform.

Verified on a Pixel 7 Pro: a station given a second line rendered
`6 departures across 3 platform(s)` where the old code drew one block.
The log says the platform count for exactly that reason — "with 6
departures" was true either way.

**1b. Never substitute another station's board.**
`renderWidget` resolves this id's binding against the live selections.
No binding, or a binding whose station is no longer one of the user's
boards, renders the honest empty state (`isBound = false`) and points
EVERY tap on it at the configuration screen — see `tapTargetFor`, which
is one answer shared by the board container's click and the rows
collection's pending-intent template, because those are two separate
wirings that have to agree. The empty state says "tap to choose a
station", so the tap has to land somewhere that can choose one; opening
the app would be an instruction the app cannot carry out.
It does **not** fall back to the first station.
Showing somebody a train that is not theirs, at a stop they are not
standing at, with nothing on screen to say so, is the worst thing a
departure board can do — and it is what this package used to do by
construction.

**1c. `updateAppWidget` is told what it is drawing; it does not look.**
`isBound`, `hasAnyBoard`, `mode` and `boundSelection` are parameters,
and `isBound`/`hasAnyBoard` default to FALSE. It used to run its own
`getAllSelections()` — one SQL read per widget per redraw — and derive
the mode roundel and the "has this ever loaded" check from the
account's FIRST selection, which is how a widget could wear another
station's icon.

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
- `core/.../util/MultiLineBoardProcessor` — the grouping. Feeds in
  (one per pole/line/direction), platform blocks out, ordered and
  labelled. Home, dream and widget all render from it, which is what
  stops them disagreeing about what a station looks like.
- `core/.../util/GlobalBoardProcessor.processPredictions` — the flat
  display cap, still used for "has anything loaded", the SDUI binding
  and the fallback state.
- `core/.../util/LineStatusRanker.rotation` — which line speaks for a
  board carrying several. Worst first, de-duplicated on (severity,
  reason) because sub-surface lines share track and share incidents.
  The widget has room for one line of status and takes the first.

- `SqlStorage.getPredictionsTimestamp` — the "X ago" source. All
  three call this same function.

**Depth is the STATION's, and its default is three.** `rowCapFor` reads
`BoardConfig.rowCap` — the "Show up to N per platform" setting (2-5,
default 3) that the home screen and the screensaver already obey.

It briefly followed the widget's own HEIGHT instead (`rowCapForHeight`,
2 on a strip and 4 when dragged tall). That was rejected by the owner on
2026-09-12 and **must not come back.** Two reasons, and the second is
the one that matters:

- The rule is a product rule. Three rows per platform is the structure,
  whatever the widget's size. Height decides how many BLOCKS you can see
  at once, which the scroll already handles; it does not get to decide
  how deep a platform is, because a widget showing fewer departures than
  the app for the same platform is not a smaller widget, it is a widget
  missing trains.
- The setting already existed and the widget was silently overriding it.
  Set a station to 5 and the app showed five while the home screen showed
  three, with nothing to explain the difference. Three is what that
  setting says when nobody has touched it, so reading the board keeps the
  product rule for everyone AND the promise the settings screen makes to
  whoever changed it.

Verified on hardware both ways: Bank DLR at 2 drew two rows per platform
while Hackney Wick, untouched, drew three.

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
- **Multiple widgets on one home screen, each with its own station.**
  `updateFromStorage` iterates all `appWidgetIds` and resolves each
  one's binding separately. Never assume there is only one, and never
  assume two of them show the same thing.
- **`RESULT_CANCELED` first in the config Activity.** Set before
  anything else, so backing out — or the process dying mid-screen —
  leaves the launcher believing the placement failed and removing the
  widget. `RESULT_OK` is the only thing that commits it.
- **`onDeleted` is not guaranteed.** It is the normal path for a
  removed widget and it can be missed (launcher replaced or its data
  cleared, restore from backup, broadcast dropped while force-stopped).
  `WidgetBindingStore.prune()` runs on every `updateFromStorage` and
  agrees the store with `getAppWidgetIds()`, which is the authority.
- **The `SDUI` payload path.** If the user's station has a stored
  SDUI template (`sdui_layout_<stationId>` SharedPref), we bind it
  with the ticked predictions and render through that path instead
  of `prepareLegacyRows`. Both paths produce identical ETAs.

## When you change something here

After modifying any file in this folder, run `graphify update .` from
the repo root to keep the project's knowledge graph in sync (AST-only,
no API cost). The graph at `graphify-out/` is what future agents read
for architecture context.
