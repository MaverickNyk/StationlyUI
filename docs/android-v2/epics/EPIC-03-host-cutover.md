# EPIC-03 — Host cutover · 21 pts

**Goal.** Make the shared Compose Multiplatform UI *be* the Android app (D2).

**The whole host contract is one signature.** iOS's host is 20 lines:

```kotlin
fun MainViewController(startLoggedIn: Boolean, deepLinkOobCode: String?) =
    ComposeUIViewController { App(IosPlatformAuthProvider(), startLoggedIn, deepLinkOobCode) }
```

Android's is an Activity calling `setContent { App(...) }`. Everything else in
this epic is replacing the nine placeholder `actual`s and untangling two
implementations of the same thing.

**Exit.** One UI, one theme, one font source, one navigation stack.

---

## AV2-3.1 — Host the shared UI · `L` · Review (S007)

**Depends on:** AV2-2.2 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.3
**Files:** `android/app/build.gradle.kts`, a new Activity, `AndroidManifest.xml`

### Tasks
- [x] **a.** `:android:app` depends on `:composeApp` — `"stagingImplementation"`, not
      `implementation`. See below for why the flavour scoping is load-bearing.
- [x] **b.** `V2MainActivity` in `src/staging`, calling
      `setContent { App(AndroidPlatformAuthProvider(this), …) }`.
- [x] **c.** Registered staging-only in a new `src/staging/AndroidManifest.xml`,
      as a second launcher with its own `taskAffinity`. `MainActivity` is
      untouched and still owns every deep link.
- [x] **d.** Confirmed. `StationlyApplication.onCreate` runs before any component
      of the process, and the staging manifest deliberately declares no
      `<application android:name>` — asserted by `V2HostManifestTest`, since
      `Platform` exposes no runtime "initialised" flag to check against.

### Watch for
- `launchMode="singleTask"` plus `onNewIntent` on the v1 Activity is
  **load-bearing**. The manifest comment explains it: without it, a relaunch
  spawned a new task, tasks piled up (three observed simultaneously), and a
  restored instance had an empty NavHost back stack — a blank screen. Carry the
  reasoning, not just the attribute.
- `rememberSaveable` versus `remember` for nav state, for the same reason.

### Acceptance criteria
- [ ] A staging build opens the shared UI and can navigate. **Needs hardware.**
      `assembleStagingDebug` links and packages, but no device was attached this
      session. This is the one thing S007 could not check, and it is why the
      story is in Review rather than Done.
- [x] A prod build is byte-for-byte unaffected in behaviour. Proven three ways,
      the last of which is the one that settles it — in the **packaged APK**:

      | | staging | prod |
      |---|---|---|
      | `compose.runtime` (resolved) | 1.8.0 | **1.7.0**, unchanged |
      | `compose.material3` (resolved) | 1.3.2 | **1.3.0**, unchanged |
      | launchable activities | `V2MainActivity` ("Stationly v2") + `MainActivity` | **`MainActivity` only** |
      | `com.stationly.app.ui.*` refs in dex | 326 | **0** |

      Read the last row as: the shared UI is not merely unreachable in a prod
      build, it is not in the file.

### Handoff notes

**S007 · 2026-09-05 · Review.** The shared UI has an Android host. Four files:
the dependency edge, `V2MainActivity`, a staging manifest, and a test that
guards the manifest decisions.

**The gate was RED on arrival** and the story was already claimed — the previous
session left `stagingImplementation(project(":composeApp"))` in the tree, which
does not compile. Kotlin DSL generates type-safe accessors only for
configurations that exist when the script is compiled, and flavour
configurations are created *by* this script. `"stagingImplementation"(...)` — the
string invoke — addresses the same configuration and works.

**The flavour scoping is not caution, it is measured.** Putting `:composeApp` on
the classpath moves the whole Compose runtime:

| | prod | staging |
|---|---|---|
| `compose.runtime` / `foundation` | 1.7.0 | **1.8.0** |
| `compose.material3` | 1.3.0 | **1.3.2** |

`compose-bom:2024.09.00` does not win that argument; Compose Multiplatform 1.8.0
does. So `implementation` would have moved the **live** app's Compose runtime out
from under `navigation-compose:2.8.0`, which is pinned to Compose 1.7 by a
comment recording a shipped blank-screen bug. AV2-3.5 promotes this to
`implementation` in the same change that deletes the v1 navigation stack, when
that pairing no longer exists.

**⚠️ The consequence, for whoever tests on staging: the v1 door on a staging
build now runs on Compose 1.8.0 with `navigation-compose:2.8.0`.** That is the
same *class* of mismatch that produced the shipped blank screen, in the other
direction. Prod is untouched, and AV2-3.5 deletes the v1 stack — but if v1's
screens misbehave on a staging build during the two-door period, suspect this
before suspecting your change.

**Two doors, two tasks.** `V2MainActivity` carries its own
`taskAffinity="com.stationly.mobile.v2"`. Sharing the default affinity would put
both launchers in one task, where `singleTask` on either clears the other off the
top — opening v1 from the drawer would silently destroy an open v2 screen, and
the tester would spend the afternoon debugging the wrong thing.

**`startLoggedIn` is saved, not recomputed.** `AppNavigation` turns it into the
NavHost `startDestination`, and a NavHost can only restore a saved back stack
onto the start destination it was built with. Recomputing `isLoggedIn()` after
process death asks Firebase something it may not have rehydrated: it says "no
user", the start destination flips to `auth/login`, and a stack rooted at
`summary` has nowhere to land. v1 shipped that bug and fixed it with
`rememberSaveable`; the shared `AppNavigation` takes the answer as a *parameter*,
so on Android the saving has to happen in the host. It is in
`onSaveInstanceState`.

**Finding — the same hole is still open one branch further in, in `commonMain`.**
`AppNavigation`'s `startDestination` is a plain `val`, and its second and third
branches call `authProvider.isEmailProvider()` / `isEmailVerified()` — recomputed
on every restore, exactly like the first. The host fix above covers the
logged-in/logged-out flip, which is the one that reaches most users, but a
user whose verification state resolves late can still land a `summary`-rooted
back stack on an `auth/verify-email` root. Fixing it means editing
`composeApp/src/commonMain/.../navigation/AppNavigation.kt`, which is outside
this story's file list and shared with a build going to TestFlight, so it is
logged rather than done. It belongs to **AV2-3.5**, wrapped in `rememberSaveable`
in one place for both platforms.

**Finding — component-name continuity at cutover.** Home-screen pins and
launcher shortcuts reference the *component name*, not the package. AV2-3.5 task
(a) must not simply delete `com.stationly.mobile.MainActivity` and promote
`.v2.V2MainActivity`: every v1 user with a pinned Stationly icon would find it
greyed out. Keep the old name alive as the exported launcher — either move the
v2 host into it, or add
`<activity-alias android:name=".MainActivity" android:targetActivity=".v2.V2MainActivity">`.

**What is deliberately NOT here.** No deep-link intent-filters on the v2 door
(two activities advertising `stationly://` would put a disambiguation dialog in
front of every link tap — AV2-3.4 adds them per-flavour). No staging banner and
no splash reconciliation (AV2-3.5 (d)). No `UserSyncCoordinator.reconcile` in
`onResume`, which v1's `MainActivity` does — that is the data plane, EPIC-04, and
the host stays at the twenty-line contract iOS already keeps. The `oobCode`
plumbing *is* wired, because it is part of that signature and it makes the door
drivable by hand:

```
adb shell am start -n com.stationly.mobile/.v2.V2MainActivity \
    -a android.intent.action.VIEW -d "stationly://reset?oobCode=…"
```

**For the reviewer:** install a staging debug build, open the **Stationly v2**
icon (there are now two), and check it reaches the login or summary screen and
navigates. Then open **Stationly Staging** and confirm the v1 app still works.

---

## AV2-3.2 — Real actuals, batch A · `L` · Review (S008)

**Depends on:** AV2-3.1 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.2
**Files:** `composeApp/src/androidMain/kotlin/com/stationly/app/platform/`

Four stubs, each currently a placeholder that compiles and does nothing.

| Actual | Today | Becomes |
|---|---|---|
| `AndroidConnectivityMonitor` | `flowOf(true)` | a real `ConnectivityManager` callback flow |
| `AndroidHapticFeedback` | no-op | `HapticFeedbackConstants` / `VibratorManager` |
| `ModeIconStore` | returns nothing | delegate to the existing `ModeIconCache` (`filesDir/mode_icons`) |
| `DeviceIdentity` | a fresh UUID **per process** | delegate to the existing persistent `DeviceIdProvider` |

### Tasks
- [x] **a.** `AndroidAppContext` in `composeApp/androidMain`. It does not hold a
      context — it *sources* one, from `Platform.appContext`, which the host
      already sets in `Application.onCreate`. It does hold the current Activity,
      weakly, because a `Context` alone is not enough for haptics (and AV2-3.3
      and AV2-3.4 both need one too).
- [x] **b.–e.** All four. Each writes to the shipped app's storage rather than
      beside it — see the handoff.

### Why `DeviceIdentity` is the one that matters
A per-process id means device sessions, the subscription registry and
"logging out releases my subscriptions" are all broken in ways that look like
backend bugs. iOS lost two days to exactly this class of problem: the keychain
session survived a reinstall while the device id did not, leaving ghost sessions
that logout could never release.

### Acceptance criteria
- [ ] The offline banner appears when the device goes offline. **Needs
      hardware**, same device check AV2-3.1 is waiting on. The flow is real:
      `registerDefaultNetworkCallback`, an emission before registration so a
      device that is simply online is not left silent, and `distinctUntilChanged`
      over a callback that fires on signal strength and metering too.
- [ ] `DeviceIdentity.deviceId()` is stable across process death. **Needs
      hardware** to observe, but the mechanism it depends on is checked here:
      `V1V2StorageContractTest` asserts it reads the same preferences file and
      key the shipped app already writes, and the write is `commit()` rather
      than `apply()`.
- [x] `GAP_ANALYSIS.md` §3.2 updated: four rows leave the stub table.

### Handoff notes

**S008 · 2026-09-05 · Review.** Four stubs became real. The thing worth carrying
forward is not any of the four implementations — it is what they all had to do.

**Every one of them writes into the shipped app's storage, not beside it.**
`:composeApp` cannot import `:android:app`; the dependency runs the other way.
So "delegate to the existing `DeviceIdProvider` / `ModeIconCache`" cannot mean
calling them. It means agreeing on a file name:

| | v1 writes | the shared UI now reads and writes |
|---|---|---|
| device id | `SharedPreferences("StationlyDevice")` → `device_id` | the same file, the same key |
| mode icons | `filesDir/mode_icons/<safeName>.png` | the same directory, the same sanitisation |
| tints | `mode_icons/tints.json` | still written, though nothing shared reads it |

Get any of those wrong and nothing errors. A changed prefs file issues every v1
user a **new device id**, and the backend releases a station's subscription only
when the last device signs out — so the old session becomes a ghost that logout
can never clear. iOS spent two days there. A changed icon directory is quieter
still: the shared UI re-downloads everything into a second set of files and the
**home-screen widget keeps rendering from the first**, untinted.

`V1V2StorageContractTest` (in `:android:app`, the only module that can see both,
and only on staging) reads those constants off both classes by reflection and
compares them, then calls `ModeIconCache.safeName` against
`modeIconFileName` over the real mode names. It is the only thing standing
between those two implementations and silent divergence. AV2-3.5 deletes the v1
halves and this test with them.

**`tints.json` is written by a store whose interface has no notion of tints.**
Deliberate. v1's widget reads it. Do not "tidy" it out before AV2-3.5.

**Haptics go through a View, not `Vibrator`.** Two consequences that are the
whole reason: `View.performHapticFeedback` respects the user's system
touch-feedback setting, and it needs no `VIBRATE` permission — so adopting the
shared UI does not add a permission to a live app. The cost is needing an
Activity, which is why `AndroidAppContext` tracks one. `CONFIRM`/`REJECT` are
API 30, so 26–29 falls back to `VIRTUAL_KEY`/`LONG_PRESS` — different from each
other, which is the point, and tested.

**Scope note — two files outside the story's list.**
1. `core/src/androidMain/.../Platform.kt`: `appContext` went from `private
   lateinit` to `lateinit … private set`. One line. The alternative was a second
   context holder in `composeApp/androidMain` with its own initialisation order
   to forget, in an app that already has exactly one place for this — every
   other Android platform service is built from that same field. Android-only
   file, so iOS is untouched.
2. `composeApp/build.gradle.kts` + a new `composeApp/src/androidMain/AndroidManifest.xml`:
   an `androidUnitTest` source set (for the pure decisions), and the two
   permissions the Android actuals need. `:android:app` already declares both,
   so the manifest merges to nothing today — which is the point, the library
   should not rely on that coincidence holding.

**Finding — two connectivity implementations now exist and they must be edited
together.** `android/`'s `NetworkState` is a process-wide `StateFlow` read by the
widget's RemoteViews builder; the new `getConnectivityFlow()` is a per-collector
`callbackFlow` because that is the shape the `expect` asks for. Both make the
same judgement call — `NET_CAPABILITY_INTERNET`, not `VALIDATED`, so a captive
portal counts as online — and the comment in each says so. AV2-3.5 deletes one.

**For the reviewer, on device:** toggle airplane mode with a board open and watch
for the offline banner. Then check the device id survived the upgrade — a v1
install that opens the shared UI must keep the same entry in the account's device
list, not gain a second one.

---

## AV2-3.3 — Real actuals, batch B · `M` · Backlog

**Depends on:** AV2-3.2 **Files:** as above, plus `ui/sdui/`

| Actual | Today | Becomes |
|---|---|---|
| `HomePromoPlatform` (3 members) | reports `AUTHORIZED` unconditionally | real POST_NOTIFICATIONS state, request, and a settings deep link |
| `PlatformLocationProvider` | `NoOpLocationProvider` | fused location; `play-services-location` is already a dependency |
| `SduiAssetCache` | returns null | a real on-disk cache |

### Tasks
- [ ] **a.–c.** The three actuals above.
- [ ] **d.** Confirm the notification-permission flow matches v1's
      `NotificationPermissionEffect` behaviour, which users already have.

### Acceptance criteria
- [ ] The POST_NOTIFICATIONS prompt actually fires on a fresh install. It cannot today.
- [ ] Nearby-station search returns stations. It cannot today.
- [ ] Only `SupportCheckout` remains stubbed, by D4.

### Handoff notes
_(none yet)_

---

## AV2-3.4 — Auth and deep links · `M` · Backlog

**Depends on:** AV2-3.2 **Files:** `AndroidPlatformAuthProvider.kt`, `AndroidManifest.xml`, flavour manifests

### Tasks
- [ ] **a.** Implement `signInWithGoogleInteractive()`. It currently returns a
      failure carrying the string *"Use the Google Sign-In button to continue."* —
      v1's flow talking about a button the shared `LoginScreen` does not have.
      `play-services-auth` is already a dependency of both modules.
- [ ] **b.** Leave `signInWithAppleInteractive()` unavailable. Correct on Android.
- [ ] **c.** Per-flavour deep-link scheme: `stationly` for prod,
      `stationly-staging` for staging. The manifest hardcodes `stationly` on all
      four hosts (`auth`, `reset`, `home`, `verified`).

### Why (c) is not cosmetic
iOS shipped this exact bug: a hardcoded `"stationly"` in `onOpenURL` silently
dropped **every** staging widget tap. Nothing errored; taps just did nothing.

### Acceptance criteria
- [ ] Sign-in completes from the shared `LoginScreen`.
- [ ] A `stationly-staging://` link opens the staging app, and a `stationly://`
      link does not.
- [ ] With both flavours installed, neither steals the other's links.

### Handoff notes
_(none yet)_

---

## AV2-3.5 — The cutover · `L` · Backlog

**Depends on:** AV2-3.3, AV2-3.4, AV2-1.3 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.3

The irreversible one. The v1 golden fixtures from AV2-1.3 must exist first.

### Tasks
- [ ] **a.** The v2 Activity becomes the launcher.
- [ ] **b.** Delete `com.stationly.mobile.ui.{summary,selection,profile,login}`,
      `SduiComponentRenderer`, `HomeConfigStore`, and the v1 `ui/theme` package.
- [ ] **c.** **One font pipeline.** v1 uses downloadable Google Fonts
      (`ui-text-google-fonts`, Inter Tight, `font_certs.xml`); `:composeApp` uses
      `compose.components.resources`. Two in one app is a bug waiting to happen —
      pick one and delete the other, do not leave both wired.
- [ ] **d.** Reconcile splash (`Theme.Stationly.Splash`), `enableEdgeToEdge()`
      and the staging banner against `:composeApp`'s `StationlyThemeHost`. One
      implementation each.
- [ ] **e.** Carry over the theme choice from v1's `ThemeRepository` to
      `UserSettings` as a one-shot translation on first v2 launch.

### Acceptance criteria
- [ ] The AV2-1.3 fixtures still assert what they should, or their change is
      justified in the commit message.
- [ ] No `com.stationly.mobile.ui` package remains except what the host needs.
- [ ] A user upgrading keeps their theme.

### Handoff notes
_(none yet)_
