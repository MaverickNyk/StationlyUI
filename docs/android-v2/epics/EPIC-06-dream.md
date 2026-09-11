# EPIC-06 — Daydream · 6 pts

> ## Q3 — answered by the reversible choice, S016
> **Daydream stays**, and the epic was built on a premise worth correcting:
> *Daydream* (the VR platform) is dead; `DreamService` — the **screen saver** —
> is not. It is in Settings → Display on current Android, it is not deprecated,
> and v1 ships it.
>
> This was not a guess, it was a choice between a reversible move and an
> irreversible one. Keeping it and porting is undoable in an hour if the owner
> says drop it; deleting a feature that is live on real phones is not. So the
> port happened, and **Q3 is now a cheaper question than it was**: saying no
> today deletes two files and a settings screen, not a package.
>
> The owner should still answer it. If the answer is no, the exit is
> `StationlyDreamService`, `DreamSettingsActivity`, their manifest entries and
> `res/xml/stationly_dream_info.xml` — plus the four `DreamPlatform.android.kt`
> actuals, which would go back to being placeholders.

**Goal.** The screensaver runs the shared UI instead of its own copy.

**Exit.** `StationlyDreamService` hosts `DreamHost`; the v1 `dream` package is
deleted.

**Done, S016.** The dream package was the reason v1 code was still in the app.
Ten of its twelve files are deleted; what remains is the two SYSTEM ENTRY POINTS,
because Android binds a component and not a composable.

What went with it, and what did not:

| v1 package | Now |
|---|---|
| `dream/` (the whole screensaver) | **deleted** — `composeApp`'s `DreamHost` renders it |
| `ui/theme/` (Theme, AppTheme, ThemeTokens, ThemeRepository, Color, LineColors) | **deleted** — the dream was its last reader; `StationlyThemeHost` is the one theme now |
| `ui/util/` (NetworkState, PredictionTicker, StationStripFitter, BoardFallbackState) | **stays** — every one is on `DepartureWidgetProvider`'s render path. EPIC-05's residue, not this epic's |
| `util/HomeConfigStore`, `util/ModeIconCache` | **stays** — the widget reads both (EPIC-05) |

The theme carry-over the epic asked to preserve is preserved by having ONE
reader rather than by keeping two in step: the shared `AppSettings` reads
`loadDurable("app_theme") ?: loadString("app_theme")`, and on Android the second
half of that is the file and key v1 wrote. `V1ThemeCarryOverTest` still pins it,
one-sided now.

---

## AV2-6.1 — Real dream actuals · `M` · Review (S016)

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
- [x] **a.–d.** Port the four. *(Delegation was impossible — the dependency runs
      `:android:app` → `:composeApp`, so `composeApp` cannot call into the app
      module. Each is the same behaviour written against the same rules, with
      the reasons carried across rather than rediscovered.)*
- [x] **e.** `DreamPrefsBackend` must persist. *(`StationlyDreamPrefs`, v1's own
      file, with v1's four key names — see the findings for why that mattered
      more than it looks.)*

### Acceptance criteria
- [x] Dream settings survive a restart. *(They are on disk, in the file v1 wrote.)*
- [x] The weather strip shows real weather. *(met.no over `HttpURLConnection`,
      User-Agent included — met.no returns 403 without one, which would have
      looked like a chip that simply never appears.)*
- [x] The screen does not sleep during a dream. *(By the DreamService, which is
      what `isScreenBright` is for — see the finding on `KeepScreenAwake`.)*

### Findings

#### The prefs file name is the whole of the upgrade path

v1 writes `layout`, `theme`, `clock_style` and `station_id` into
`StationlyDreamPrefs`, and the shared store uses **the same four names**. So
backing the Android `DreamPrefsBackend` with that file is what makes a v1 user's
screensaver survive the upgrade — and pointing it anywhere else would have reset
every one of them, silently, on a surface nobody opens deliberately.

The same shape as `DeviceIdentity` and `ModeIconCache` before it: two
implementations agreeing by filename because the module boundary will not let
them agree by code.

**One gap found while checking it.** The shared store namespaces its keys per
account (`layout:uid`), so a SIGNED-IN v1 user would still have read defaults —
the carry-over would have worked only while signed out. `DreamSettings` now falls
back from the scoped key to the unscoped one on READ: an account that has never
set this inherits whatever the device was set to. That also fixes the same gap on
iOS for anything written before P3 scoped these keys.

#### `KeepScreenAwake` inside a real dream is a no-op, and should be

`FLAG_KEEP_SCREEN_ON` is set on a WINDOW, and there is no Activity in a
`DreamService`'s tree. The system keeps the screen on for a bound dream anyway —
`isScreenBright = true` is what the service already says. So the implementation
walks the context for an Activity and does nothing when there is none, which
covers the other host: the shared `DreamHost` rendered inside the app, where the
flag is the only thing between a docked phone and the display timeout.

Worth writing down because "the keep-awake does nothing" reads like a bug in the
one place it is correct.

### Handoff notes — S016, 2026-09-11

Nothing here changed what the user sees: until AV2-6.2 the screensaver was still
v1's. These four are what made 6.2 possible in the same session.

---

## AV2-6.2 — Host the shared dream · `M` · Review (S016) — **UNVERIFIED ON HARDWARE**

**Depends on:** AV2-6.1 **Files:** `StationlyDreamService`, `DreamSettingsActivity`, `AndroidManifest.xml`

### Tasks
- [x] **a.** `StationlyDreamService` hosts `DreamHost` from `:composeApp`.
- [x] **b.** `DreamSettingsActivity` hosts `DreamSettingsScreen`. *(~700 lines of
      a second settings screen deleted. Both edited the same four values in the
      same file, and which one the user reached depended on the door.)*
- [x] **c.** Delete `com.stationly.mobile.dream`. *(Ten files gone; two entry
      points remain, because the system binds a COMPONENT and not a composable.
      `com.stationly.mobile.ui.theme` went with them — see the findings.)*
- [x] **d.** The dream's snapshot loader reads the v2 board model. *(It is the
      shared `loadDreamSnapshot`, which is what iOS runs.)*

### Watch for
Keep `DreamSettingsActivity`'s `taskAffinity=""` and `launchMode="singleTask"`.
The manifest comment explains exactly what breaks without them: system Settings
stacks the activity onto Stationly's own task, and back-navigation then walks
through `MainActivity` and the whole app history instead of returning to
Settings. Also keep `excludeFromRecents="true"`.

### Acceptance criteria
- [ ] The screensaver renders the shared board. **Needs a device.**
- [ ] Settings → Display → Screensaver → gear opens the settings screen, and back
      returns to Settings rather than into the app. **Needs a device.**
- [x] The dream shows a multi-line, filtered board correctly, not just the first
      selection. *(By construction: it is the shared board renderer.)*

### Findings

#### The hard part was already done, by v1

A `DreamService` is not a `LifecycleOwner`, `ViewModelStoreOwner` or
`SavedStateRegistryOwner`, and a `ComposeView` without all three crashes at
runtime with `ViewTreeLifecycleOwner not found` — the classic way this swap goes
wrong. `StationlyDreamService` has implemented all three since v1 and sets them
as the view's tree owners. So hosting a different composable is a four-line
change, and the risk that looked largest here was retired before the session
started.

#### A broadcast that existed only because v1's dream could not hear the flow

`FreshDataNotifier` fanned out to the dream over its own `ACTION_DREAM_REFRESH`
broadcast, which the service received and turned into a `refreshTick` the host
observed. All of it is deleted. The shared `DreamHost` collects
`FreshDataNotifier.events` — the same process-wide flow the home screen and the
widget already use, emitted by the same FCM service, in the same process.

Two delivery mechanisms for one signal is how they stop agreeing, and this one
had the extra property that its receiver was registered in a component the app
does not control the lifetime of.

#### Deleting the dream emptied v1's theme package, and not v1's util package

The epic says this epic's real exit is that `com.stationly.mobile.ui` finally
empties. Half right. `ui/theme` is gone — the dream was its last reader, and the
shared `StationlyThemeHost` is what the screensaver composes now, so the app and
the dream cannot disagree about a theme because there is one reader.

`ui/util` **stays, and belongs to the widget**: `tickPredictions`,
`StationStripFitter`, `BoardFallbackState`, `NetworkState` are all on
`DepartureWidgetProvider`'s render path, and `NetworkState.init` is called from
`StationlyApplication`. It is EPIC-05's residue, not this one's.

`V1ThemeCarryOverTest` lost the second implementation it was comparing against
and is one-sided now — the same shape `V1V2StorageContractTest` took when
`DeviceIdProvider` went. The data it protects did not move: every v1 install's
theme is still under `StationlyPrefs/app_theme`, and the shared reader's
`loadDurable() ?: loadString()` fallback is still the entire upgrade path.

### Handoff notes — S016, 2026-09-11

**This is the least verifiable change on the branch and it should be treated
that way.** The screensaver is the one surface that cannot be opened by tapping
anything — the OS binds it when the device docks or charges — so a failure shows
up as a black or blank screen on somebody's bedside table, with no crash dialog
and no path back to a report.

It compiles, the lifecycle plumbing it needs was already there and is unchanged,
and the composable it now hosts is the one iOS has been running. None of that is
the same as having seen it.

**The device script, and it is four steps:**

```
1. Settings → Display → Screen saver → Stationly → tap the gear.
   The SHARED settings screen should appear. Change the layout, press back:
   you should land back in Settings, NOT in Stationly's home screen.
   (That is the taskAffinity/singleTask contract — invariant 1 in dream/CLAUDE.md.)
2. "Start now" from that system screen. The board should render, in the layout
   just chosen, with the theme the APP is set to.
3. Leave it running and wait for a push (~30s on a tracked stop). The times
   should move without the broadcast that used to carry them.
4. A station with two lines: both should be on the board. v1 showed the first
   selection only, and that is the visible difference this story buys.
```

**If it is blank**, the first thing to check is not the composable: it is whether
`AndroidAppContext.context` is initialised in the process the dream was started
in. `Platform.initialize` runs in `StationlyApplication.onCreate`, which the
system runs before binding the service — but a dream is one of the few things
that can start the process on its own.
