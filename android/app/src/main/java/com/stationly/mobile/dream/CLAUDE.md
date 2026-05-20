# Dream / Screensaver — Agent context

This package implements the Stationly **DreamService** (Android's official
"screensaver" / Daydream API). The system binds the service when the device
is docked or charging AND the user has chosen Stationly under
`Settings → Display → Screensaver`. We do not start it from app code — it is
a pure system feature.

## File layout (read this before editing)

```
dream/
├── StationlyDreamService.kt   System entry point. Hosts a ComposeView, wires
│                              lifecycle / SavedState / ViewModelStore owners,
│                              listens for ACTION_DREAM_REFRESH.
├── DreamHost.kt               Top-level @Composable. Computes responsive
│                              DreamDims, picks Landscape vs Portrait layout,
│                              owns the snapshot StateFlow.
├── DreamClock.kt              DigitalClock + AnalogClock + ClockPanel chooser
│                              + ClockAmbientGlow + rememberClockNow tick.
├── DreamWeatherStrip.kt       Date + temperature chip below the clock.
├── DreamSummary.kt            StationHeader, NextTrainHero, EmptyStatePanel,
│                              helpers (lineColorOf, prettyLineName, modeLabel,
│                              nextDeparture extension).
├── DreamBoard.kt              AndroidView wrapper around widget_departure_board
│                              XML. Diff-updates row TextViews on every refresh
│                              (don't tear down: it kills scroll position).
│                              Configurable via showHeader / showClock /
│                              fullscreen so both dream layouts can reuse it.
├── DreamFullscreenBoard.kt    Fullscreen-board dream — adaptive textScale from
│                              short edge (smaller in portrait), card centred
│                              on the canvas with width caps so it doesn't
│                              stretch on tablets. Delegates to DreamBoard with
│                              fullscreen=true.
├── DreamData.kt               DreamSnapshot data class + loadDreamSnapshot()
│                              (synchronous SQL read).
├── DreamSettings.kt           SharedPrefs read/write for ClockStyle + station id.
│                              Lives in its own prefs file (StationlyDreamPrefs)
│                              so app-prefs clearAll() on logout doesn't reset it.
├── DreamSettingsActivity.kt   Compose UI shown when the user taps the gear icon
│                              next to "Stationly" in system screensaver
│                              settings. Hero header + visual preview tiles for
│                              the layout picker (mini renders of each layout),
│                              clock-style tiles (digital/analog), station
│                              picker with line-colour dots.
├── WeatherStation.kt          Background poller (singleton-per-process) for
│                              met.no temperature using last-known location only.
└── CLAUDE.md                  This file.
```

External entry points:
- `AndroidManifest.xml` registers `StationlyDreamService` (with
  `BIND_DREAM_SERVICE`) and `DreamSettingsActivity` (in an isolated task).
- `res/xml/stationly_dream_info.xml` is the system-required metadata,
  pointing at `DreamSettingsActivity`.
- `service/FcmMessagingService` fires `ACTION_DREAM_REFRESH` after writing
  fresh predictions to SQL.

## Architectural invariants (do not break)

**1. Broadcast-only refresh, no polling.**
The FCM service writes new predictions to SQL then broadcasts
`ACTION_DREAM_REFRESH`. The dream's receiver bumps `refreshTick`, which
re-fires the host's `LaunchedEffect`, which re-reads SQL on `Dispatchers.IO`.
We deliberately do NOT poll on a timer — re-reading the same SQL every 30s
is just CPU drain (the data only changes when FCM lands).
The widget broadcast (`ACTION_UPDATE_WIDGET`) is `setComponent`-targeted and
won't reach dynamically registered receivers, which is why we have our own
action.

**2. Sizing follows the device's short edge, not orientation.**
`DreamHost` computes `shortEdgeDp = minOf(maxWidth, maxHeight).value`. A
given physical device produces the same scale value in portrait OR landscape.
`DreamDims` exposes MAX caps for clock sizes; `ClockPanel` then sizes the
clock to fill its actual slot up to that cap. Result:
- Phone landscape: small clock (30% of small screen IS small) — automatic.
- Tablet portrait: big clock (capped at the dim max so it isn't a billboard).
- Tablet landscape: chunky clock (30% of a tablet still has tons of room).

**3. Two dream layouts share one host, one board.**
`DreamLayout` enum picks between:
  - `CLOCK_AND_BOARD` — clock cluster + summary header + board, 30:70 in
    landscape and 35:65 in portrait (default).
  - `FULLSCREEN_BOARD` — just the widget XML scaled up to fill the screen,
    with widget's own ticking TextClock + chronometer along the bottom.
The user picks the layout in `DreamSettingsActivity`; the clock-style
section there is hidden when fullscreen is selected because that layout has
its own built-in TextClock.

Do not change the 30:70 / 35:65 split to fix sizing — fix sizing via
`DreamDims` and `ClockPanel`'s slot-fill math instead. The user has
explicitly validated those ratios.

**4. DreamBoard diff-updates row TextViews.**
The widget XML is inflated inside `AndroidView`. When fresh data arrives we
do NOT call `removeAllViews()` — that would reset `ScrollView.scrollY` mid-
gesture and was the root cause of the "have to pull pull pull to scroll"
bug. Instead, when row count and per-index types match the existing children,
mutate `.text` in place. Only fall back to a full rebuild when the structure
actually changes.

**5. `scaleAllText` is idempotent via TextView tags.**
The board's text-scale multiplier is applied on every refresh, so we cache
the XML-declared baseline on each TextView's `tag` and always scale from
that baseline. Without this the status row's font would grow on every FCM
tick. If you ever need to change a row's baseline manually, use
`setBaselineSp(sp)` — it clears the tag so the next `scaleAllText` re-caches.

**6. `ROW_BASE_SP` is the uniform baseline for board rows.**
The widget XML has a tiered 13sp/15sp hierarchy (designed for a tiny home-
screen tile). At dream scale that looks visually uneven, so every row is
forced to `ROW_BASE_SP` (14f) before `scaleAllText` multiplies. Don't
re-introduce per-row sp values.

**7. `WeatherStation` is singleton-per-process and uses LAST-KNOWN location
only.**
We never request a fresh GPS fix — burning GPS for a screensaver decoration
would be hostile. If location permission is denied or no provider has a
cached fix, the chip just doesn't render. The 30-minute poll interval is
under met.no's free-tier rate limit; don't shorten it.

**8. `DreamSettingsActivity` runs in its own task.**
`AndroidManifest.xml` declares it with `taskAffinity=""`,
`launchMode="singleTask"`, `excludeFromRecents="true"`. Without this, system
Settings stacks the activity onto Stationly's existing task and swipe-back
walks the user through MainActivity instead of returning to Settings.

**9. `ACTION_DREAM_REFRESH` registers with `RECEIVER_NOT_EXPORTED` on API 33+.**
Don't drop the SDK_INT branch — older devices need the un-flagged
registerReceiver.

## What is intentionally not here

- **No ViewModel.** The dream is short-lived (system unbinds when the user
  taps the device) and snapshot loading is cheap; a ViewModel adds ceremony
  without buying anything. The `LaunchedEffect(tick)` pattern is sufficient.
- **No network calls in DreamData.** All reads go through `Platform.sqlStorage`.
  Predictions land via FCM; the dream just reflects whatever is in SQL.
- **No widget broadcast reuse.** See invariant #1.
- **No clock style "None".** The original enum had it; the user removed it
  ("always have some clock visible"). If the legacy `"none"` string surfaces
  in SharedPrefs from older installs, `ClockStyle.fromStored` falls back to
  DIGITAL.

## Common gotchas

- **AndroidView + LinearLayout `0dp + weight=1`** only works when the parent
  has a fixed height. Compose `weight(1f, fill=false)` makes the parent
  wrap-content, breaking the ScrollView's viewport. We use
  `Modifier.heightIn(max = X)` to give the board a real cap when content
  overflows, while still wrapping content when it doesn't.
- **`@Suppress("MissingPermission")`** in `WeatherStation.readLastKnownLocation`
  is fine — we DO check `ACCESS_FINE_LOCATION` immediately before the call.
- **`isInteractive = true`** on `StationlyDreamService` makes touches go to
  views (so the ScrollView can be dragged) and NOT auto-dismiss the dream.
  Exit is via power button / system gesture. Don't flip this back to false.
- **Gesture exclusion** is applied to the inner ScrollView on every update
  (`applyGestureExclusion`) because a relayout (rotation / font change)
  invalidates the previous rect. API 29+ only; older devices silently no-op.

## When you change something here

After modifying any file in this folder, run `graphify update .` from the
repo root to keep the project's knowledge graph in sync (AST-only, no API
cost). The graph at `graphify-out/` is what future agents read for
architecture context.
