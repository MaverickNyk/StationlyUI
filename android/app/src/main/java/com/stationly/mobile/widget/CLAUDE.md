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
├── V1WidgetAdoption.kt         One-shot: widgets placed by v1 keep the
│                                station they showed. See MIGRATION.md §6.
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
   - Groups the RAW rows into blocks (full `rowReserve`), then ticks the
     blocks with core's `BoardTicker.tick` under the SERVED `BoardPolicy`
     (`tickedGroupsFor`): `eta` re-derived from `targetEpochMs + now`,
     departed trains dropped after `board.tick.departedGraceMs`, the last
     ones retained as "Gone" (dim amber) when the payload is old enough
   - Caps each block at 3 per platform for display
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
`composeApp/.../ui/util/MinuteTick.kt#rememberMinuteTick`). The widget must
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
EVERY tap on it at the one destination that can fix it — see
`tapTargetFor`, which is one answer shared by the board container's
click and the rows collection's pending-intent template, because those
are two separate wirings that have to agree.

**Four empty states, each owning its words AND its tap** (AV2-10.7,
`WidgetEmptyState`): `NEEDS_STATION` and `REMOVED` say "Tap to choose…"
and open this widget's configuration; `SIGNED_OUT` and `NO_STATIONS` say
"Tap to sign in" / "Tap to add your first station" and open the APP,
because the configuration screen can do neither and only shows an "Open
Stationly" button. Copy comes from the served `widget.state.*` keys (the
backend sends Android wording to Android: short taps, not iOS's
"Touch and hold, then tap Edit Widget"), compiled defaults otherwise.
Board fallbacks (signal lost, late night…) use the SERVED
`board.fallback.*` strings, same as home and the screensaver.
It does **not** fall back to the first station (`V1WidgetAdoption`, once, for
widgets v1 placed, is the only exception: it is what they already showed).
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
`BoardTicker` in core and `tickedGroupsFor` in the provider.

**3. Dropped rows shift the queue.**
After ticking, rows past the SERVED grace (`board.tick.departedGraceMs`,
not a constant) are removed, and the visible 3-per-platform cap is
re-applied. Storage keeps `board.tick.rowReserve` per platform so the
queue can shift up without waiting for FCM. Group first, tick second,
cap last: capping first leaves a block nothing to shift up.

**3a. The widget ticks by the SERVED rules, like every other surface**
(2026-11-23). It used an Android-only `tickPredictions` with a
hard-coded 30 s grace and no retention, so it ignored the SDUI timing
rules iOS introduced and the app board and screensaver follow. That
helper is DELETED; never reintroduce a compiled-in grace or label.

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

- `core/.../util/BoardTicker` — the tick: grace, retention, departed
  label, bump, all from the served `BoardPolicy`. The home board, the
  screensaver and the widget (`tickedGroupsFor`) all call
  `BoardTicker.tick` on grouped blocks; the iOS widget's Swift copy
  points at it. Cannot diverge.
- `core/.../util/StationlyFormatters.formatMinutesRemaining` — the
  one formula that turns `(targetEpochMs, nowMs)` into a display
  string, used inside `BoardTicker`.
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

- **The root view is `@android:id/background`** (`android.R.id.background` in
  code), not an app id. That id is how an Android 12+ launcher finds the
  background to crop to the system corner shape. It is also `match_parent`:
  every rows container is 0dp + weight, so a `wrap_content` root on a launcher
  that measures "at most" gave the rows zero height (Android 17 customer
  report, 2026-11). Do not change either.
- **Rows that never scroll stretch; the list does not.** Stepping pages and
  fallback messages use `widget_departure_row_fill` (0dp + weight 1) so the
  board fills the widget like the iOS one. The scrolling `rows_list` keeps
  `widget_departure_row`: a ListView item cannot take weight.
- **Rows run edge to edge** (owner, 2026-11-22, as iOS): `board_content` has
  no side padding; the inset lives inside rows/headers (12dp), the pager (10dp),
  status (12dp) and footer (14dp). Do not put side padding back on the board.
- **Full bleed top to bottom, and rows keep a fixed proportion** (owner,
  2026-11-23). `board_content` has no padding at all: the station row touches
  the top edge and the lit clock row the bottom, with corner clearance as padding
  INSIDE them. On a stretching board (`BoardProportions`, Android 12+) every row
  outside the stretching container is sized as a multiple of a departure row:
  station 1.45, platform 1.2, footer 1.1, status 0.8, each floored at its own
  text. Platform headers inside a fill container hold 1.2 by weight
  (`widget_platform_header_fill`). The tap zones move with the header. A
  scrolling list and a crowded board keep natural heights.
- **The pager's "1/2" sits beside the title, as iOS's `PlatformPagerHeader`**:
  centred together, platform x 0.72, bold, `tfl_amber_dim`, shown at EVERY size
  and never squeezed (the title is wrap_content + weight, so it shrinks first,
  and the fitter subtracts the marker's measured width). On a 2x2 the arrow
  columns slim to 30dp (Android 12+). Headers that still do not fit at their
  shortest rung shrink to 70% before ellipsising (`WidgetText.fit`, iOS
  `minimumScaleFactor(0.7)`). RemoteViews will not inflate a plain `View`: a
  spacer must be a FrameLayout, or every widget shows "Can't load widget".
- **A new widget starts at the FULL WIDTH and 2 rows** (owner, 2026-11-24), by
  `departure_widget_info.xml` alone: the picker and `requestPinAppWidget` both read
  it. Three copies, `xml-v31` (Android 12+), `xml` (8 to 11) and `_6` (a 6 column
  home screen the app can recognise, `WidgetDefaultSize`), alike in everything but
  the default size. No attribute means "full width"; the XML comments say how
  the launcher arrives at it, and `WidgetDefaultSizeTest` runs its rule.
- **A new widget starts on SCROLL, every station** (owner, 2026-11-23). It used
  to start on STEP when a station had several platforms. Arrows ("Buttons") are
  a choice in the widget's settings; `applyDefaultsAndFinish` writes SCROLL on
  first placement only and never resets a widget already set up.
- **A stepping widget remembers its BLOCK, not its position** (QA 2026-11-23).
  `WidgetPageStore` keeps `pagekey_<id>` (the header's `key`: platform, or bus
  pole naptan) beside `page_<id>`; renders and steps resolve with
  `PlatformPages.resolve(rows, key, index)`. Blocks re-sort as departures move,
  so an index alone silently switched a widget to the other direction. The iOS
  widget still stores an index (`WidgetPageIntent.swift`): same fix needed there.
- **Unlettered bus headers name where that side goes**: "Bus 39 → Putney Bridge
  Station" from `Feed.terminus` (= `directionDestinations` when it holds one),
  else "Bus 39, 639 towards Church Road" from `Feed.nextStop` (=
  `directionTowards`, which is the NEXT stop despite its name). Every
  `Feed(...)` builder must pass both, or both sides of the road read "Bus 39".
- **Type follows the size class** (`WidgetSize`: COMPACT / SHORT / MEDIUM / LARGE,
  the iOS families). Chosen by FIT (`WidgetSize.of`): SHORT and COMPACT_SHORT (tight
  padding; iOS medium's type) only where the ordinary class cannot hold three
  departures under a platform, as two rows of a Xiaomi 6x7 (158dp) cannot. The
  user's text size shrinks only what cannot scroll: the arrows board and messages. Medium is the XML defaults. Headers pick a rung of
  `headerVariants` that fits (`WidgetText`); never let a header wrap.
- **Tapping a bound widget opens the app on ITS station** via
  `<scheme>://home?station=<groupingId>` (the iOS widget's link) and
  `BoardFocus.request` in MainActivity. Keep the PendingIntent per widget id.
- **No settings gear on Android 12+** (D10, `showsSettingsButton`). Settings is
  the launcher's long-press menu there; the gear and `zone_settings` are GONE.
  Android 8 to 11 keep the gear: it is their only door from the widget.
- **Bigger targets come from TAP ZONES, never bigger rows.** The owner wants
  the touch area larger and the rows their original size. The root is a
  FrameLayout: `board_content` underneath, transparent `zone_*` FrameLayouts on
  top with the same PendingIntents as the icons. Gear LEFT, refresh RIGHT, so
  their zones never touch. Chevron zones are GONE outside step mode.
- **Never `match_parent` a view in the header.** The refresh spinner did, and its
  intrinsic height grew the header on every refresh.
- **Do not rely on `partiallyUpdateAppWidget`.** The Pixel launcher on Android 17
  dropped it. The refresh spinner is a full render keyed on `refreshingIds`.
- **The refresh spinner is the icon turning** (`ic_refresh_spinning`), and a
  render while the widget is in `refreshingIds` must not consume the REFRESH
  motion: the roll belongs to the redraw with the new rows. Scroll mode rolls
  via `applyRefreshSnapshot`, then the list returns.
- **The refresh roll is row by row** (owner, 2026-11-23: "pull every row", not
  one curtain). The refresh flipper has no inAnimation; the old board fades
  (`widget_refresh_out`, 90 ms) and the arriving page is
  `widget_platform_page_refresh`, whose layout animation (`widget_rows_refresh`)
  raises each row a beat after the one above (36 ms apart, 240 ms each). A
  layout animation plays on a view's FIRST draw, so it lives only in that layout;
  never put it on `widget_platform_page`, or every push would cascade. After a
  roll, `settleAfterRoll` redraws at rest (scroll mode gets its list back; a
  launcher rebuilding the widget cannot replay a stored roll).
- **A press during a running fetch still spins and rolls.** The unlock refresh
  is the usual one; the press is armed and spun, and the running fetch's
  `stopSpinners` takes the spinner down and redraws it.
- **"Add to Home Screen" goes home itself, or the Pixel launcher adds it TWICE**
  (2026-11-23, root-caused on device). The launcher queues an accepted pin and
  places it when it next comes to the front; coming back with the swipe-up
  gesture made it process the queue twice: two copies of ONE widget id. Android
  updates one view per id, so the copy it skipped froze, and a tap on it spun
  the other. Same station, back to back: gesture home → 2 copies (31), Home key
  → 1 (32). Every duplicate in the launcher log was a pin from inside the app.
  Fix: `ACTION_PINNED` calls `WidgetPinner.showHomeScreen` first, a normal
  launcher resume (verified: 33, one copy, a later swipe-up added nothing),
  and only while the app is VISIBLE behind the dialog. The Widgets screen never
  hit it because `pinAndFinish` finishes as it asks, and there a second HOME
  would only scroll the launcher away from the new widget. To
  spot one: `dumpsys appwidget` lists the id once while two views show it, and
  the launcher's `dumpsys activity <launcher>` log shows two
  `AddWorkspaceItemsTask` lines with the same `appWidgetId`. Removing one copy
  of a duplicated id leaves the other working; remove both to clear it.
- **Only a press animates** (owner, 2026-11-23). Unlock and boot refresh via
  `triggerRefresh`, the same action with NO widget id; no id means no spinner,
  no `MIN_SPINNER_MS` hold and no roll. It used to spin every widget on each
  unlock. A press's PendingIntent must keep `EXTRA_APPWIDGET_ID`.
- **Every render restarts the status marquee**, because a TextView restarts it
  whenever its text is set. So redraws must be few (owner, 2026-11-23: it
  stuttered "da da da da" after a refresh). Push-driven redraws
  (`updateForStation`, `updateForLine`) go through `scheduleRedraw`: one render
  per widget once the burst is quiet for `REDRAW_QUIET_MS` (300 ms), capped at
  `REDRAW_MAX_WAIT_MS` (1.2 s). Measured before: 2 renders of each Highbury
  widget in 130 ms, 4 of Smithwood in 400 ms, and 64 every 30 s from the app's
  poll. The app's 30 s SQL poll (`SummaryViewModel`) no longer redraws the
  widget on Android (iOS still needs it), and its other widget updates now name
  `stationId`, because blank means "sweep every widget".
- **Scroll mode stretches when nothing would scroll** (`ScrollFit`, decided
  before the rows are built). Recalibrate its constants at font scale 1.0 if the
  row or header layouts change height.
- **A failed refresh is per station** (`RefreshFailures`): retry glyph on that
  station's widgets only, cleared by the next success. Do not make it global.
- **A widget broadcast must finish inside ~10s.** Taps are foreground
  broadcasts; the refresh is capped at `RECEIVER_BUDGET_MS` (8s) or it ANRs.
- **An app update must re-inflate placed widgets.** Same layout id means a
  launcher may reapply new actions onto the OLD view tree. `MY_PACKAGE_REPLACED`
  draws `widget_reinflate` for one frame first. Any layout change relies on it.
- **"Add to Home Screen" binds in the PROVIDER**, on `WidgetPinner.ACTION_PINNED`.
  The callback must stay FLAG_MUTABLE or the platform cannot add the widget id.

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

## Station closures

`noticesFor` is what TfL says about the boards' stations (`StationNoticeStore`, off
unless `flag.stationStatus.show`). A board a notice HIDES is left out by the render and
by `boardRowsFor` alike, or the chevrons would count pages that are not drawn. With none
left the message replaces the rows; the strip is `BoardNotice.stripWith`.
The title row is `widget_warning_row_fill` (an amber sign either side, one on a 2x2) and the
strip wears one. The rows are TfL's own words if they all fit, else the short details
(`rowsThatFit`; owner, 2026-11-25).
