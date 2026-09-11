# Dream / Screensaver — Agent context

Android's **DreamService** (the "screensaver"). The system binds it when the
device is docked or charging AND the user has chosen Stationly under
`Settings → Display → Screensaver`. We never start it from app code — it is a
pure system feature, which makes it the one surface in the app that cannot be
reached by tapping anything.

## What is left here, after AV2-6.2

Two files. Everything else moved.

```
dream/
├── StationlyDreamService.kt   System entry point. A DreamService is not a
│                              LifecycleOwner / ViewModelStoreOwner /
│                              SavedStateRegistryOwner, so it implements all
│                              three and sets them as the ComposeView's tree
│                              owners — that is what makes coroutines, Flow and
│                              animations work inside a dream. It then hosts
│                              `composeApp`'s `DreamHost`.
├── DreamSettingsActivity.kt   The gear next to "Stationly" in system
│                              screensaver settings. Hosts `composeApp`'s
│                              `DreamSettingsScreen`.
└── CLAUDE.md                  This file.
```

**The screensaver itself is `composeApp/src/commonMain/kotlin/com/stationly/app/ui/dream/`** —
`DreamHost`, `DreamBoard`, `DreamClock`, `DreamSettings`, `DreamTheme`,
`WeatherStation` and the rest. It is the same code iOS runs. Read its files for
the layout ratios, the sizing rules, the diff-update discipline on the board
rows, and the theme split between canvas and signage; every invariant that used
to be listed here belongs to that package now and is documented there.

The platform half of it is
`composeApp/src/androidMain/.../platform/DreamPlatform.android.kt`: the
preferences file, keep-awake, the met.no fetch and last-known location.

## Why this package still exists at all

Because the system binds a **component**, not a composable. `DreamService` and
the settings Activity are Android entry points and have to live in the app
module; what they draw does not.

## Architectural invariants (do not break)

**1. The three manifest attributes on `DreamSettingsActivity`.**
`taskAffinity=""`, `launchMode="singleTask"`, `excludeFromRecents="true"`.
System Settings starts this Activity, and without them Android stacks it onto
Stationly's own task: back-navigation walks the user through `MainActivity` and
the whole app history instead of returning to Settings, and the screensaver
picker shows up in Recents as if it were the app.

**2. `isInteractive = true` on the service.**
Touches reach the views — so the board's ScrollView can be dragged — and do not
auto-dismiss the dream. Exit is the power button or the system gesture. Do not
flip it back.

**3. Refresh comes from the shared flow, and there is no second mechanism.**
`FreshDataNotifier` (core) emits after the FCM service writes predictions to
SQL, in this same process, and the shared `DreamHost` collects it. The
`ACTION_DREAM_REFRESH` broadcast this package used to send is **gone**: it
existed because v1's dream had no way to hear that flow, and two delivery
mechanisms for one signal is how they stop agreeing. Do not reintroduce it — if
the dream stops refreshing, the bug is in the flow every other surface shares,
which is the right place for it to be.

**4. No polling.** The data changes when FCM lands. Re-reading the same SQL on a
timer is CPU drain on a docked phone.

**5. The dream renders the v2 board model.** Multi-line, filtered, grouped by
hub — the same board the home screen shows. v1's copy rendered the first
selection only, and that difference was invisible until a user tracked two lines
at one station.

## When you change something here

After modifying any file in this folder, run `graphify update .` from the repo
root to keep the project's knowledge graph in sync (AST-only, no API cost). The
graph at `graphify-out/` is what future agents read for architecture context.
