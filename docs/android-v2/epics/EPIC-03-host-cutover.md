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

## AV2-3.1 — Host the shared UI · `L` · Backlog

**Depends on:** AV2-2.2 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.3
**Files:** `android/app/build.gradle.kts`, a new Activity, `AndroidManifest.xml`

### Tasks
- [ ] **a.** `:android:app` depends on `:composeApp`.
- [ ] **b.** A new Activity calling `setContent { App(AndroidPlatformAuthProvider(this), …) }`.
- [ ] **c.** Register it **staging-only**, behind its own launcher alias or a
      debug entry point. v1's `MainActivity` stays the launcher until AV2-3.5.
      Two doors into the app, briefly, so the new one can be exercised without
      betting the app on it.
- [ ] **d.** Confirm `Platform.initialize` still runs before first composition.
      `StationlyApplication.onCreate` already does it — keep it there.

### Watch for
- `launchMode="singleTask"` plus `onNewIntent` on the v1 Activity is
  **load-bearing**. The manifest comment explains it: without it, a relaunch
  spawned a new task, tasks piled up (three observed simultaneously), and a
  restored instance had an empty NavHost back stack — a blank screen. Carry the
  reasoning, not just the attribute.
- `rememberSaveable` versus `remember` for nav state, for the same reason.

### Acceptance criteria
- [ ] A staging build opens the shared UI and can navigate.
- [ ] A prod build is byte-for-byte unaffected in behaviour.

### Handoff notes
_(none yet)_

---

## AV2-3.2 — Real actuals, batch A · `L` · Backlog

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
- [ ] **a.** Add an application-context holder to `composeApp/androidMain`. All
      four need a `Context` and there is nowhere to get one today.
- [ ] **b.–e.** The four actuals above.

### Why `DeviceIdentity` is the one that matters
A per-process id means device sessions, the subscription registry and
"logging out releases my subscriptions" are all broken in ways that look like
backend bugs. iOS lost two days to exactly this class of problem: the keychain
session survived a reinstall while the device id did not, leaving ghost sessions
that logout could never release.

### Acceptance criteria
- [ ] The offline banner appears when the device goes offline. It cannot today.
- [ ] `DeviceIdentity.deviceId()` is stable across process death.
- [ ] `GAP_ANALYSIS.md` §3.2 updated: four rows leave the stub table.

### Handoff notes
_(none yet)_

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
