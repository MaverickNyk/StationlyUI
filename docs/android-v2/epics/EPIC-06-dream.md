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

---

## AV2-6.1 — Real dream actuals · `M` · Backlog (blocked on Q3)

**Depends on:** AV2-3.2, Q3 **Files:** `composeApp/src/androidMain/.../platform/DreamPlatform.android.kt`

All four members are placeholders, and all four have working implementations
**one package away** in `com.stationly.mobile.dream`.

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
