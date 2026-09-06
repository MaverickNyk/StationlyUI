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
- [x] A staging build opens the shared UI and can navigate. **Verified on a
      Pixel 7 Pro, S009 (2026-09-06)** — the hardware S007 could not get. Two
      launcher icons; **Stationly v2** opens the shared landing, signs in, and
      navigates login → summary → home settings → profile → back. Screenshots in
      the S009 session note.
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

**S009 · 2026-09-06 · both halves run on hardware. The first passes. The second
does not, and it is not the host's fault** — see the finding.

### Finding — v2 writes user state that crashes v1 on launch

Once an account has been used in the v2 UI, opening the **Stationly Staging**
(v1) icon dies before it draws:

```
java.lang.IllegalArgumentException: Key "940GZZLUKSX_piccadilly" was already
used. If you are using LazyColumn/Row please make sure you provide a unique key
for each item.
    at SummaryScreen.kt:229
```

`SummaryScreen.kt:229` is `items(currentSelections, key = { "${it.station}_${it.line}" })`.
v1's model assumes one selection per (station, line). **The v2 board model does
not** — it keeps one selection per direction, so King's Cross St. Pancras with
Piccadilly westbound *and* eastbound is two rows with one key. The v2 summary
renders both happily, side by side (Platform 6 → Cockfosters, Platform 5 →
Heathrow); v1 throws on the duplicate.

Not caused by AV2-3.4: it reproduces on a plain `am start -n …/.MainActivity`
with no deep link, and nothing in this story touches selections.

**What it does and does not threaten.** It is not a prod risk today — prod ships
one UI, and AV2-3.5 deletes v1's summary in the same change that promotes the
shared one, so no shipped build ever has both. It *is*:
- a blocker for this story's second reviewer instruction, which cannot pass on
  any account that has opened v2;
- a live trap for AV2-8.2 (upgrade verification on hardware), which will hit it
  the moment it signs one account into both;
- a reason to be careful about ever shipping the two doors together.

**Not yet established:** whether a v1 install restoring this account *from the
cloud* crashes the same way, or only one whose local DB was written by v2. The
cloud path is the one that matters for AV2-8.2. Test it there.

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

      ⚠️ **AV2-3.3 corrected this.** The Activity tracking registered lazily, on
      first access, and that was too late to hear the first `onActivityResumed`
      — so `activity` was null for anything reading it before the user
      backgrounded the app once. It silently disarmed the POST_NOTIFICATIONS
      prompt and would have skipped haptics too. Now started eagerly by
      `StationlyActivityTracker`; see the AV2-3.3 finding.
- [x] **b.–e.** All four. Each writes to the shipped app's storage rather than
      beside it — see the handoff.

### Why `DeviceIdentity` is the one that matters
A per-process id means device sessions, the subscription registry and
"logging out releases my subscriptions" are all broken in ways that look like
backend bugs. iOS lost two days to exactly this class of problem: the keychain
session survived a reinstall while the device id did not, leaving ghost sessions
that logout could never release.

### Acceptance criteria
- [x] The offline banner appears when the device goes offline. **Verified on a
      Pixel 7 Pro, S009 (2026-09-06)**, with one correction to the wording — see
      below. The flow is real: `registerDefaultNetworkCallback`, an emission
      before registration so a device that is simply online is not left silent,
      and `distinctUntilChanged` over a callback that fires on signal strength
      and metering too.

      **There is no banner over a live board, by design.** Launching with no
      network gives the full-screen "Can't reach servers"; that is the offline
      surface and it appeared correctly, driven by real connectivity (the device
      arrived with WiFi enabled but joined to nothing). Toggling airplane mode
      with a board already open shows **nothing**, and that is right:
      `computeBoardFallbackState` returns `null` on `hasPredictions` *before* it
      tests `isOnline`, so a board with cached departures keeps rendering and
      ticking rather than being covered by a warning. `OFFLINE` is a board
      fallback, not a banner. Reword the criterion rather than "fixing" this.
- [x] `DeviceIdentity.deviceId()` is stable across process death. **Verified on
      a Pixel 7 Pro, S009.** `shared_prefs/StationlyDevice.xml` held
      `e46dce7d-e669-4864-8eef-34aebc58795d` unchanged across a crash, several
      `am force-stop`s, an update install, and a full sign-out — read back with
      `run-as`. The mechanism is also checked statically by
      `V1V2StorageContractTest`: same preferences file, same key the shipped app
      writes, and `commit()` rather than `apply()`.
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

## AV2-3.3 — Real actuals, batch B · `M` · Review (S010)

**Depends on:** AV2-3.2 **Files:** as above, plus `ui/sdui/`

| Actual | Today | Becomes |
|---|---|---|
| `HomePromoPlatform` (3 members) | reports `AUTHORIZED` unconditionally | real POST_NOTIFICATIONS state, request, and a settings deep link |
| `PlatformLocationProvider` | `NoOpLocationProvider` | fused location; `play-services-location` is already a dependency |
| `SduiAssetCache` | returns null | a real on-disk cache |

### Tasks
- [x] **a.–c.** The three actuals above.
- [x] **d.** Confirm the notification-permission flow matches v1's
      `NotificationPermissionEffect` behaviour, which users already have.

### Acceptance criteria
- [x] The POST_NOTIFICATIONS prompt actually fires on a fresh install.
      **Verified on a Pixel 7 Pro** after `pm clear`: sign in, and the dialog
      appears on the summary screen unaided. Granting writes
      `post_notifications_asked` / `post_notifications_granted` into
      `StationlyPrefs` — v1's file, v1's keys.
- [x] Nearby-station search returns stations. **Verified on the same device**,
      and deliberately on **Approximate** location, which v1 refuses (below).
      The distances read ~885 mi because the phone is not in London; the list is
      correctly ordered from where it actually is.
- [~] Only `SupportCheckout` remains stubbed, by D4. **Not literally true, and
      the criterion was incomplete.** `SupportCheckout` is stubbed by D4 — and
      so are the four `DreamPlatform.android` actuals (`DreamPrefsBackend`,
      `KeepScreenAwake`, `fetchMetNoForecast`, `lastKnownLatLon`), which are
      **AV2-6.1's** scope and blocked on **Q3**. Nothing else in
      `composeApp/androidMain` is a placeholder any more. Read the criterion as
      "only SupportCheckout (D4) and the dream actuals (Q3)".

### Handoff notes

**S010 · 2026-09-06 · Review.** The last three stubs are real, and the story
turned up a defect in AV2-3.2's plumbing that had been silently disarming the
one permission prompt Android only offers once.

**The notification flag is v1's flag.** `StationlyPrefs` /
`post_notifications_asked` / `post_notifications_granted` — the same file and
keys `NotificationPermissionEffect` already writes. This is the AV2-3.2 storage
contract again, and the failure mode is the quietest one yet: pick a different
file and every user who has already answered reads back as `NOT_DETERMINED`,
the shared effect calls `requestNotificationAuthorization()`, and Android —
which never re-shows a dialog it has already shown — returns the standing answer
with **no UI at all**. Nothing appears, nothing logs. A user who had *denied*
would also stop seeing the banner explaining why no alerts arrive, because their
state would read as undecided rather than denied. `V1V2StorageContractTest` now
compares all three names across the two implementations.

**Android needs that flag and iOS does not,** which is the whole reason
`NotificationAuthState` has three values: `checkSelfPermission` returns granted
or not-granted and cannot tell "denied" from "never asked", while iOS reports
`notDetermined` natively.

**Location asks for both permissions, and accepts either.** v1 requests
`ACCESS_FINE_LOCATION` alone and checks for it alone. Two consequences, both
fixed here: on API 31+ the single-permission request is the shape Android
documents *against*, and a user who grants **Approximate** has FINE denied and
COARSE granted — so v1 sees no permission and silently returns nothing. This
requests both together (which is what puts the Precise/Approximate choice in the
dialog) and treats either grant as usable, because a few hundred metres does not
change which stations are nearby. Verified by granting Approximate on purpose:
`ACCESS_FINE_LOCATION: granted=false`, `ACCESS_COARSE_LOCATION: granted=true`,
and the nearby list still populated.

**The permission request moved from the UI into the provider.** v1 launched it
from `SelectionScreen`; the shared `SelectionScreen` is `commonMain` and cannot
hold an Android launcher, and `LocationProvider` is a bare
`getCurrentLocation()` with nowhere to say "ask first". So the provider asks —
which is what `IosLocationProvider` already does with
`requestWhenInUseAuthorization`, so the shared contract assumed it all along.
The only visible change is *when*: `SelectionViewModel` pre-warms from `init`,
so the prompt lands as the selection flow opens rather than one step later at
the station picker. A `Mutex` serialises it, because that pre-warm and the
user's own "nearby stations" tap otherwise race into two system dialogs.

**`SduiAssetCache` is real but has no Android caller yet.** The widget guide is
iOS-first by `docs/SDUI.md` §1, so AV2-5.4 will be its first use. Downloads to
`cacheDir/sdui-assets` (re-downloadable content belongs in a directory the OS
may reclaim), keyed `<name>-<version>.<ext>` so a version check is one
`exists()`, reaping older versions *before* the new one lands, and writing to a
`.part` file it renames — a download killed halfway must not leave a truncated
file that `exists()` serves forever. The naming and versioning rules are pure
and unit-tested (`SduiAssetNamingTest`), including that a server-chosen URL
cannot produce a path that climbs out of the cache directory.

### The finding — a defect in AV2-3.2 that made this story fail silently

`AndroidAppContext.activity` registered its lifecycle callbacks **on first
access**, reasoning in its own KDoc that "everything reading this is a response
to a user touching the screen, which cannot happen before then".

That is false, and the shared UI already violated it. `NotificationPermissionEffect`
reads it from a `LaunchedEffect` during the summary screen's first composition —
no touch — and composition runs **after** `onActivityResumed`. So the tracker
registered too late to hear the only resume that had happened, reported no
Activity, and `requestNotificationAuthorization()` returned false without ever
launching. On a fresh install the prompt simply never appeared, and did not
appear on any later visit either: nothing calls `onActivityResumed` again until
the user happens to background the app and come back.

Nothing threw and nothing logged, because "no Activity" is a legitimate answer
meaning "cannot ask". It was only visible as *an absence*, on the one prompt
Android gives you a single chance at.

Fixed with `StationlyActivityTracker`, a content provider in `:composeApp`'s own
manifest. Android instantiates providers after `Application.onCreate` and before
the first Activity, which is the only hook a library has there without adding an
obligation to the twenty-line host contract — an obligation whose omission would
fail exactly this silently. Same mechanism `androidx.startup` and Firebase use,
without the dependency. The lazy path is kept as a fallback.

It merges into staging only, because `:composeApp` is a staging-only dependency:
confirmed in the merged manifests, one `activity-tracker` entry on staging and
zero on prod.

**This bug also affected haptics** (AV2-3.2) and would have affected any future
`actual` that needs an Activity. Interactive Google sign-in (AV2-3.4) escaped it
only because `V2MainActivity` passes itself to `AndroidPlatformAuthProvider`,
bypassing the tracker entirely.

### Scope note — four files outside the story's list

1. `composeApp/build.gradle.kts` + `composeApp/src/androidMain/AndroidManifest.xml`:
   `play-services-location` (the story said it was already a dependency — it is
   `:android:app`'s, and the dependency runs app → library, so `:composeApp`
   needs its own edge), the three new permissions, and the provider entry.
2. `platform/AndroidAppContext.kt` and the new `StationlyActivityTracker.kt`:
   the finding above.
3. `AndroidPlatformAuthProvider.kt`: its private `awaitActivityResult` became the
   shared `platform/ActivityResults.kt`, generic over the contract, because the
   notification and location prompts need the identical plumbing. Behaviour
   unchanged; AV2-3.4's device checks still pass.
4. `ui/station/HomeSettingsScreen.kt` (`commonMain`): the Notifications row read
   *"Open **iOS** notification settings"* — written when the shared UI only ran
   on iOS, and now on screen in the Android app. Now names neither platform.
   Other iOS-specific copy remains in `WidgetGuideDefaults.kt`; that is the
   widget guide, iOS-only by `docs/SDUI.md` §1, and belongs to **AV2-5.4**.

---

## AV2-3.4 — Auth and deep links · `M` · Review (S009)

**Depends on:** AV2-3.2 **Files:** `AndroidPlatformAuthProvider.kt`, `AndroidManifest.xml`, flavour manifests

### Tasks
- [x] **a.** Implement `signInWithGoogleInteractive()`. It currently returns a
      failure carrying the string *"Use the Google Sign-In button to continue."* —
      v1's flow talking about a button the shared `LoginScreen` does not have.
      `play-services-auth` is already a dependency of both modules.
- [x] **b.** Leave `signInWithAppleInteractive()` unavailable. Correct on Android.
- [x] **c.** Per-flavour deep-link scheme: `stationly` for prod,
      `stationly-staging` for staging. The manifest hardcodes `stationly` on all
      four hosts (`auth`, `reset`, `home`, `verified`).

### Why (c) is not cosmetic
iOS shipped this exact bug: a hardcoded `"stationly"` in `onOpenURL` silently
dropped **every** staging widget tap. Nothing errored; taps just did nothing.

### Acceptance criteria
- [x] Sign-in completes from the shared `LoginScreen`. **Verified on a Pixel 7
      Pro** — chooser ("to continue to Stationly Staging") → account → summary
      with live boards restored from the account.
- [x] A `stationly-staging://` link opens the staging app, and a `stationly://`
      link does not. **Verified on device**, both directions:
      `am start -d "stationly://home"` → *"unable to resolve Intent"*;
      `stationly-staging://home` → starts. `dumpsys package` lists
      `stationly-staging` against all four filters and no `stationly` at all.
- [ ] With both flavours installed, neither steals the other's links.
      **Cannot be tested and does not currently apply** — both flavours share
      `applicationId "com.stationly.mobile"`, so they cannot coexist on one
      device. See the finding below; this is now Q6 for the owner.

### Handoff notes

**S009 · 2026-09-06 · Review.** Sign-in from the shared UI works, and the two
environments can no longer answer for each other's links. Verified on hardware —
a Pixel 7 Pro was attached for this session, which also closed out the device
checks AV2-3.1 and AV2-3.2 had been waiting on.

**One scheme literal per flavour, and it feeds both halves.** The bug this story
exists to prevent needs *two* places to disagree: what the manifest registers,
and what the code accepts. So `deepLinkScheme("stationly-staging")` in
`build.gradle.kts` sets `manifestPlaceholders["deepLinkScheme"]` **and**
`BuildConfig.DEEP_LINK_SCHEME` from one argument. Writing them separately is now
the only way to get it wrong, and `DeepLinkSchemeTest` fails if you do.

**Changing the manifest alone would have SHIPPED the iOS bug, not fixed it.**
`MainActivity.handleDeepLink` compared `uri.scheme == "stationly"` at four call
sites. Flip only the manifest and a staging build starts *receiving*
`stationly-staging://reset?oobCode=…` and then silently drops every one of them
— arriving, matching no branch, doing nothing. So `handleDeepLink` was rewritten
onto `core`'s `parseDeepLink(url, BuildConfig.DEEP_LINK_SCHEME)`, which is what
that function was extracted for in AV2-1.3. Same for the one link the app
*builds*: `FcmMessagingService` hardcoded `"stationly://home?station=…"`.

**The v2 door still advertises no scheme.** AV2-3.1's staging manifest comment
said AV2-3.4 would move the filters onto `V2MainActivity`; it should not, and
does not. Two activities advertising one scheme puts a disambiguation dialog in
front of the user on every tap. `MainActivity` stays the deep-link owner until
AV2-3.5 deletes it, and the v2 host is driven with `am start -n` (component
named, so no filter has to match). Verified end to end anyway:

```
adb shell am start -n com.stationly.mobile/.v2.V2MainActivity \
    -a android.intent.action.VIEW -d "stationly-staging://reset?oobCode=AV2TESTCODE"
```
lands on the shared "Set new password" screen with the code carried through.

**Why the web client id is a constructor parameter.** `default_web_client_id` is
generated into `:android:app` by the google-services plugin, per flavour;
`:composeApp` cannot see that `R`. The obvious workaround —
`resources.getIdentifier("default_web_client_id", …)` — is a trap that only
springs later: the release build runs `shrinkResources`, and once AV2-3.5 deletes
`FirebaseAuthManager` (today the only `R.string.default_web_client_id` reference)
a string reached solely by name becomes an unused resource and is stripped.
Google sign-in would then work in every debug build and fail only in release.
Passing it in is a compile error instead.

**The legacy `GoogleSignIn` API, not Credential Manager.** v1 signs in with
exactly this client against exactly this web client id and the two must coexist
until AV2-3.5. Same library means a tester comparing the two doors is comparing
the doors. Migrating is worth its own story, once there is one login screen left.

**Sign-out now signs the Google client out too.** v1 did
(`FirebaseAuthManager.signOut`); the shared provider only called
`auth.signOut()`. Without it the client keeps the last account cached and the
next "Continue with Google" re-signs the *same* account with no chooser — "sign
out, sign in as someone else" fails on a shared phone in a way that looks like
the sign-out did not work. Confirmed on device:
`shared_prefs/com.google.android.gms.signin.xml` goes to `<map />` on sign-out,
and the chooser reappears on the next attempt.

**Cancellation surfaces a message, unlike v1.** v1 swallowed status 12501 and
left the screen silent. The shared `LoginViewModel` has no "failed, but say
nothing" channel — every failure becomes `uiState.error` — and adding one means
changing `commonMain`, which is iOS's shipped code. So Android now matches what
iOS already does with an abandoned Apple sheet: "Sign-in was cancelled."

### Scope note — three files outside the story's list, and why each had to be

1. `MainActivity.kt` and `FcmMessagingService.kt`: see above. Without them task
   (c) is not a fix, it is the iOS bug ported to Android.
2. `LoginViewModel.kt` (`commonMain`, shared with iOS) — a **crash** found on
   device. Detailed below, because it is the finding of this session.

### Finding — backing out of a slow sign-in crashed the app

Reproduced on a Pixel 7 Pro. Tap "Continue with Google", pick the account, then
press back while "Signing you in…" is up (the backend restore takes several
seconds):

```
java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to
'DESTROYED' … destination=Destination route=summary
    at androidx.navigation.NavController.navigate(NavController.android.kt:1003)
    at AppNavigationKt.AppNavigation$…$lambda$26(AppNavigation.kt:151)
    at LoginViewModel$onGoogleSignInInteractive$1.invokeSuspend(LoginViewModel.kt:318)
```

The mechanism, because it is not obvious and it will recur:

- Back destroys the host Activity, which clears the NavController's
  `ViewModelStore`, which cancels `viewModelScope`.
- The sign-in had **already succeeded**; its final resumption was sitting on the
  dispatcher queue. Cancelling *drains* that queue, so the rest of the coroutine
  ran synchronously **inside Activity destruction**.
- Kotlin only observes cancellation at suspension points, and there are none
  left after the last `await` — so the navigation callback fired into a dead
  NavHost.

The disguise is what makes it dangerous: the app died and relaunched **already
signed in**, because Firebase had persisted the credential. It reads as a blink.

Fixed with `navigateIfLive` — one private helper in `LoginViewModel`, applied to
all nine navigation callbacks in that file, that skips the callback when the
coroutine has been cancelled. iOS is unaffected in either direction: nothing
there destroys the view model mid-flow, so `isActive` is true and it is the call
it always was. Verified by re-running the exact sequence on device: no
`FATAL EXCEPTION`, process alive, sign-in still completed.

### Finding — the shared landing screen shows "Continue with Apple" on Android

`LandingContent` renders `AppleButton` unconditionally, above the Google button.
On Android its only outcome is "Sign in with Apple is not available on Android."
Hiding it is a `commonMain` edit to a screen iOS ships, so it is **not** done
here. AV2-3.5 owns the shared login surface; this should go with it. The failure
message is at least written for a user rather than a developer.

### Finding — the two flavours cannot be installed side by side

Acceptance criterion 3 assumed they could. They share
`applicationId "com.stationly.mobile"`; only `versionNameSuffix` differs.
Giving staging an `applicationIdSuffix` is not a build-file change — the
google-services plugin fails unless that exact package is registered as an
Android app in the staging Firebase project, and Google sign-in additionally
needs the (package, SHA-1) pair registered. That is owner-side console work.
Raised as **Q6**. Until then the criterion is not merely unverified, it is not
reachable — and the per-flavour scheme is still the right change, because the
schemes must not collide the day the ids diverge.

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
