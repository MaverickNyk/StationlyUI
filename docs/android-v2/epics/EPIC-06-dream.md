# EPIC-06 — Daydream · 6 pts

> ## ⚠️ Gated on Q3
> **Confirm with the owner that Daydream survives into v2 before starting.**
> Daydream is deprecated on newer Android. v1 ships it, some users may rely on
> it, and it has no iOS equivalent at all — so it is pure Android surface area
> with no parity argument either way. Two sessions ride on the answer. Do not
> spend them on a guess.

**Goal.** The screensaver runs the shared UI instead of its own copy.

**Exit.** `StationlyDreamService` hosts `DreamHost`; the v1 `dream` package is
deleted.

**The Daydream is now the reason v1 code is still in the app.** AV2-3.5 deleted
every v1 screen, and stopped at what `dream/` imports:

| Still there | What the dream uses it for |
|---|---|
| `ui/theme/` (Theme, AppTheme, ThemeTokens, ThemeRepository, Color) | `DreamSettingsActivity` wraps itself in `StationlyThemeHost`; `DreamHost` resolves light/dark |
| `ui/theme/LineColors.kt` | line dots and pills — **moved here by AV2-3.5** from the top of v1's deleted `Board.kt`, and duplicated in `:composeApp` on purpose |
| `ui/util/` (NetworkState, PredictionTicker, StationStripFitter, BoardFallbackState, ScrollWrap) | `DreamBoard` |
| `util/HomeConfigStore` | shared with the widget (EPIC-05) |

So this epic's real exit is wider than it reads: deleting `dream/` is what
finally empties `com.stationly.mobile.ui`. If **Q3** comes back "no", deleting
the Daydream does the same job and is the cheaper answer.

One thing to preserve either way: v1's `AppSettings` reads the theme
**durable-first** so the screensaver shows the same theme as the app, which the
shared UI writes to `stationly_durable_prefs`. It has no setter, deliberately.
`V1ThemeCarryOverTest` fails if that precedence is dropped, and nothing else
would — a screensaver rendering last month's theme is not a thing users report.

---

## AV2-6.1 — Real dream actuals · `M` · Backlog (blocked on Q3)

**Depends on:** AV2-3.2, Q3 **Files:** `composeApp/src/androidMain/.../platform/DreamPlatform.android.kt`

All four members are placeholders, and all four have working implementations
**one package away** in `com.stationly.mobile.dream`.

**They are now placeholders inside the shipped app**, not inside a
build-verification target — AV2-3.5 changed what that module is. Nothing reaches
them today only because `openSystemScreensaverSettings()` returns `true` on
Android and sends the user to Settings → Display → Screen saver instead, which
is what v1's home-screen promo did. Making these four real is what lets that
`actual` return `false` and hands the feature back to the app; until then, do
not route anything else at `dream/settings` on Android.

| Member | Today | Source of the real one |
|---|---|---|
| `DreamPrefsBackend` | an in-memory map | `DreamSettings.kt` |
| `KeepScreenAwake` | no-op | the `DreamService` itself |
| `fetchMetNoForecast` | returns null | `WeatherStation.kt` |
| `lastKnownLatLon` | returns null | `WeatherStation.kt` / location stack |

### Tasks
- [ ] **a.–d.** Port the four, preferring delegation to re-implementation.
- [ ] **e.** `DreamPrefsBackend` must persist. An in-memory map means every
      screensaver start forgets the user's settings.

### Acceptance criteria
- [ ] Dream settings survive a restart.
- [ ] The weather strip shows real weather.
- [ ] The screen does not sleep during a dream.

### Handoff notes
_(none yet)_

---

## AV2-6.2 — Host the shared dream · `M` · Backlog

**Depends on:** AV2-6.1 **Files:** `StationlyDreamService`, `DreamSettingsActivity`, `AndroidManifest.xml`

### Tasks
- [ ] **a.** `StationlyDreamService` hosts `DreamHost` from `:composeApp`.
- [ ] **b.** `DreamSettingsActivity` hosts `DreamSettingsScreen`.
- [ ] **c.** Delete `com.stationly.mobile.dream`.
- [ ] **d.** The dream's snapshot loader must read the v2 board model —
      `getPredictions(station, line, direction)`, filters applied.

### Watch for
Keep `DreamSettingsActivity`'s `taskAffinity=""` and `launchMode="singleTask"`.
The manifest comment explains exactly what breaks without them: system Settings
stacks the activity onto Stationly's own task, and back-navigation then walks
through `MainActivity` and the whole app history instead of returning to
Settings. Also keep `excludeFromRecents="true"`.

### Acceptance criteria
- [ ] The screensaver renders the shared board.
- [ ] Settings → Display → Screensaver → gear opens the settings screen, and back
      returns to Settings rather than into the app.
- [ ] The dream shows a multi-line, filtered board correctly, not just the first
      selection.

### Handoff notes
_(none yet)_
