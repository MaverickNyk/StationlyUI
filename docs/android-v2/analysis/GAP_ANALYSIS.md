# Android v2 — the gap, and the frame for reasoning about it

**Branch:** `dev_android_bring_to_v2`, cut from `ios-parity` at `b7b7a1c`.
**Companion documents:** [`../BOARD.md`](../BOARD.md) is the
live ledger and the only file a session takes work from. This one is the map:
it changes when a *finding* changes, not when a task completes.

---

## 0. The frame

Every gap in this programme sits at exactly one intersection of two axes, and
naming both is what stops "port iOS to Android" from being a sentence nobody can
plan against.

**Axis 1 — the layer the gap lives in.**

| Layer | Module | Who shares it |
|---|---|---|
| `L0` Contract | backend REST + Firestore + FCM | iOS, Android, web |
| `L1` Core | `:core` `commonMain` | iOS, Android, web |
| `L2` Core-platform | `:core` `androidMain` / `iosMain` | one platform |
| `L3` Shared UI | `:composeApp` `commonMain` | iOS, Android |
| `L4` UI-platform | `:composeApp` `androidMain` / `iosMain` | one platform |
| `L5` Host | `:android:app` / `iosApp` | one platform |

**Axis 2 — the kind of gap.**

| Kind | Meaning | What the work is |
|---|---|---|
| `SHARED` | Already built at L0/L1/L3, works on Android the moment something calls it | wire it up |
| `STUB` | An `actual` exists so the target compiles, but it does nothing | replace with a real implementation |
| `ABSENT` | No Android implementation of any kind | build it |
| `DIVERGENT` | Android has its own older implementation that contradicts the v2 model | migrate or delete |
| `PLATFORM` | Android can do something iOS cannot, or must do it differently | design for Android, do not port |

The value of the frame is that the three columns have wildly different costs.
`SHARED` is hours. `STUB` is a day. `DIVERGENT` is where the regressions live,
because it is the only kind with a live user on the other side of it.

---

## 1. The three findings that reframed the project

These were established by measurement on 2026-09-05, not by reading. Re-verify
them before trusting this document after a long gap.

### 1.1 `ios-parity` is a strict superset of `master`

```
git rev-list --count master..ios-parity   → 117
git rev-list --count ios-parity..master   → 0
git merge-base master ios-parity          → e605177 (= master)
```

`main` is an ancestor of `master` (69 behind). So the order is
`main ⊂ master ⊂ ios-parity`, and there is no Android work anywhere that
`ios-parity` does not already contain. There was never an Android branch to
merge back.

### 1.2 The shipped Android app still compiles on `ios-parity`

```
./gradlew :android:app:compileStagingDebugKotlin  → BUILD SUCCESSFUL
```

The 117 commits changed `:core` by +20,765 lines and the Android app by
+38/−16 across 5 files. Those 38 lines are the whole of the Android app's
adaptation to the v2 core: `getPredictions`/`savePredictions`/
`getLastUpdatedTimestamp` gained a `direction` argument, `FcmPayload` was
renamed `PredictionsPayload`, `ProcessFcmPayloadUseCase` became
`ProcessPredictionsUseCase`, and `SelectionViewModel` learned to post the full
board list rather than one element.

That last one is not cosmetic. `syncStations` is a full REPLACE, so a
one-element post deleted every other board on the account — harmless while both
platforms were single-board, destructive the moment iOS started saving several.
**It is the first proof that the v1 Android client can corrupt v2 state**, and it
sets the tone for §5.

### 1.3 The entire iOS v2 UI already compiles for Android

```
./gradlew :composeApp:compileDebugKotlinAndroid  → BUILD SUCCESSFUL (warnings only)
```

`:composeApp` declares an `androidTarget` and carries a complete set of Android
`actual`s. Every one of them is a deliberate placeholder — the files say so:

> "The composeApp android target is a build-verification surface only — the
> shipped Android app (android/) has the real DreamService + WeatherStation.
> These actuals just have to compile."

So 101 files of v2 UI — multi-line selection, branch filters, station settings,
home settings, the station carousel, support, SDUI, the widget guide, update
surfaces — are one dependency edge and nine real `actual`s away from running on
Android. **Android v2 is an adoption project, not a port.**

---

## 2. Decisions taken (2026-09-05)

Recorded here because every session inherits them and none may relitigate them
without the owner.

| # | Decision | Consequence |
|---|---|---|
| D1 | Branch from `ios-parity` | No cherry-picking. Prod safety comes from `release_prod` + `versionCode`, not from the branch point. |
| D2 | `:android:app` adopts `:composeApp` shared UI | ~20 native screens under `com.stationly.mobile.ui` are deleted. One UI for both platforms. Accepts JetBrains navigation/lifecycle `2.9.0-beta01` in a production Android app — iOS already ships on them. |
| D3 | Widget stays RemoteViews; per-instance config added | The 758-line provider is tuned (FCM-driven, ETA watchdog, dot-matrix layout). Glance is post-launch work, not v2 work. |
| D4 | Support surfaces built, gated OFF for the v2 launch | Google Play bills digital goods through Play Billing; an external Stripe checkout on a live app is a policy risk. Resolve it as its own workstream, off the launch critical path. |

---

## 3. The gap inventory

Status legend: `SHARED` · `STUB` · `ABSENT` · `DIVERGENT` · `PLATFORM`.
Every row names the evidence so a later session can re-check it rather than
trust it.

### 3.1 Board model and multi-selection — the headline feature set

| Capability | Layer | Status | Note |
|---|---|---|---|
| Multiple lines per station | L1/L3 | `SHARED` | `Board` + `BoardSelection` (`core/.../model/user/Board.kt`, 527L). Android reaches it via D2. |
| Multiple directions per line | L1/L3 | `SHARED` | `direction` is in the `PredictionEntity` and `SyncStatusEntity` primary keys already. |
| Multiple stations (boards) | L1/L3 | `SHARED` | `users/{uid}.boards`; home renders one card per hub. |
| Bus hub grouping by `parentStationId` | L1 | `SHARED` | Board id is the hub; each `BoardSelection` carries its own pole naptan. See [`tfl-hub-id-is-stationnaptan`]. |
| Route/destination/via filters | L1/L3 | `SHARED` | `BoardFilterResolver`, `BoardFilter.kt`, `BoardFilterSheet.kt`; `matchesFilter` precomputed at ingest. |
| Branch (`viaKeys`) filtering on rejoining lines | L1/L3 | `SHARED` | `RouteGraph.kt` + `RouteGraphPicker.kt`. See [`route-graph-is-a-dag`]. |
| Station settings screen | L3 | `SHARED` | `StationSettingsScreen.kt` + `StationSettingsViewModel.kt`. |
| Home settings screen | L3 | `SHARED` | `HomeSettingsScreen.kt`, `HomeLayout.kt`, `BoardArrangement.kt`, `StationOrder.kt`. |
| **Android's v1 single-selection UI** | L5 | `DIVERGENT` | `ui/selection`, `ui/summary`, `ui/profile`, `ui/login` under `com.stationly.mobile` — deleted by D2. |

The single largest risk in the whole programme is that this column is *too*
green. Nothing here is Android work; all of it is Android *exposure*. The tests
that matter are in §5 and §6, not here.

### 3.2 The nine stubs — `:composeApp` `androidMain`

Measured by reading every file in `composeApp/src/androidMain`. Sizes are lines.

| File | Members | Status | What breaks until it is real |
|---|---|---|---|
| `PlatformLocationProvider.kt` (9L) | `platformLocationProvider()` | `STUB` | Nearby-station search returns nothing. |
| `platform/DeviceIdentity.kt` (22L) | `deviceId()`, `deviceInfo()` | `STUB` | A fresh UUID per process. Device sessions, the subscription registry and logout-releases-subscriptions all break. The shipped app already has a persistent `DeviceIdProvider` to delegate to. See [`ios-device-id-ghost-sessions`]. |
| `platform/DreamPlatform.android.kt` (22L) | `DreamPrefsBackend`, `KeepScreenAwake`, `fetchMetNoForecast`, `lastKnownLatLon` | `STUB` | In-memory prefs, no keep-awake, no weather. The shipped app has all four in `com.stationly.mobile.dream`. |
| `platform/HomePromoPlatform.android.kt` (13L) | `notificationAuthState`, `requestNotificationAuthorization`, `openAppNotificationSettings` | `STUB` | Reports `AUTHORIZED` unconditionally, so the notifications promo never shows and the POST_NOTIFICATIONS prompt never fires. |
| `platform/AndroidConnectivityMonitor.kt` (6L) | `getConnectivityFlow()` | `STUB` | `flowOf(true)`. The offline banner can never appear. |
| `platform/AndroidHapticFeedback.kt` (5L) | `performHaptic()` | `STUB` | No haptics anywhere. |
| `platform/ModeIconStore.kt` (14L) | `sync`, `hasIcon`, `cachedIconBitmap` | `STUB` | Mode roundels fall back to the drawn shape. `ModeIconCache` exists in the shipped app. |
| `ui/sdui/SduiAssetCache.android.kt` (20L) | `localPath`, `cachedPath` | `STUB` | Widget-guide media degrades to poster stills (a designed fallback, so this is the least urgent). |
| `ui/support/SupportCheckout.android.kt` (25L) | `openCheckout`, `dismissCheckout` | `STUB` | Intentional under D4. Leave stubbed for v2. |

Already real and ship-ready: `ComposeResourcesCheck.kt` (`true`, correct),
`ui/common/PlatformWebView.android.kt` (112L, a real WebView),
`ui/sdui/SduiSmartImage.android.kt` (Coil `AsyncImage`).

Real but **incomplete**: `AndroidPlatformAuthProvider.kt` (104L) —
`signInWithGoogleInteractive()` returns a failure carrying the string
*"Use the Google Sign-In button to continue."*, which is v1's flow talking. The
shared `LoginScreen` calls the interactive path. `signInWithAppleInteractive()`
correctly stays unavailable on Android.

### 3.3 The app host — `:android:app`

| Capability | Status | Note |
|---|---|---|
| Hosting `App(authProvider, startLoggedIn, deepLinkOobCode)` | `SHARED` on staging | AV2-3.1. `V2MainActivity` (`android/app/src/staging/`) is the Android half of that signature. Staging only, a second launcher; `MainActivity` is still the shipped app. |
| `:android:app` → `:composeApp` dependency | `SHARED` on staging | AV2-3.1, via `"stagingImplementation"`. **Not `implementation`:** it moves Compose 1.7.0 → 1.8.0 and material3 1.3.0 → 1.3.2 for whatever flavour it is on, and `navigation-compose:2.8.0` is pinned to Compose 1.7. AV2-3.5 promotes it alongside deleting the v1 nav stack. Side effect to know: **v1's screens on a staging build now run on Compose 1.8.0.** |
| Navigation | `DIVERGENT` | `MainActivity.kt` (443L) owns an AndroidX `NavHost` with 9 destinations. `AppNavigation.kt` in `:composeApp` replaces it. The `singleTask` + `onNewIntent` reasoning in the manifest comment is load-bearing — carried over to `V2MainActivity` in AV2-3.1, which additionally takes its own `taskAffinity` so the two doors cannot clear each other's back stacks. **Open:** shared `AppNavigation` derives `startDestination` from a plain `val`, so a restore after process death can root a saved back stack on the wrong destination — v1's shipped blank screen. AV2-3.1 saved `startLoggedIn` host-side; the `isEmailProvider()`/`isEmailVerified()` branch is still recomputed. Needs `rememberSaveable` in `commonMain` — AV2-3.5. |
| Deep links | `DIVERGENT` | Manifest hardcodes `scheme="stationly"` for `auth`/`reset`/`home`/`verified`. iOS learned the hard way that the scheme must be per-environment. See [`ios-deeplink-scheme-per-env`]. Staging needs `stationly-staging` via a flavor manifest. |
| Splash, edge-to-edge, staging banner, theme | `DIVERGENT` | v1 has `Theme.Stationly.Splash`, `enableEdgeToEdge()`, `StagingBanner.kt`. `:composeApp` has its own `StationlyThemeHost`. Reconcile, do not run both. |
| Typography | `DIVERGENT` | v1 uses downloadable Google Fonts (`ui-text-google-fonts`, Inter Tight, `font_certs.xml`); `:composeApp` uses `compose.components.resources`. Two font pipelines in one app is a bug waiting to happen. |
| Interactive Google Sign-In | `ABSENT` | See §3.2. `play-services-auth` is already a dependency of both modules. |
| `Platform.initialize` before first composition | `SHARED` | `StationlyApplication.onCreate` already does it. Keep it. Guarded by `V2HostManifestTest`: a staging `<application android:name>` would silently replace the class and is now a test failure. |

### 3.4 The data plane — where Android is *stronger* than iOS

This is the `PLATFORM` column, and it is the part of the programme that is
genuinely Android design work rather than adoption.

| Capability | Status | Note |
|---|---|---|
| FCM push delivery of departures | `PLATFORM` | Android's advantage. `LiveStream.android.kt` is 12 lines of deliberate no-ops: *"Android keeps FCM + REST for predictions/line status — the live stream is iOS-only."* Do **not** port the WebSocket stream. |
| `ProcessPredictionsUseCase` at v2 | `SHARED` | Already in `commonMain` and its KDoc names "Android FcmMessagingService routing" as a caller. |
| `FcmMessagingService` at v2 | `DIVERGENT` | 400+ lines written against single-selection assumptions. Needs direction scoping, per-board fan-out, and `matchesFilter` precompute at ingest. |
| Topic lifecycle | `SHARED` | `StationLifecycleUseCase` already emits `Station_{naptan}` and `LineStatus_{mode}_{line}` from the v2 model. v1's `FirebaseAuthManager` does its own subscribe/unsubscribe — delete that path. |
| `stationly_all` broadcast topic | `SHARED` | Keep. Powers `audience: {type:"all"}` pushes with zero Firestore reads. |
| Widget refresh budget | `PLATFORM` | `RefreshBudgetStore.android.kt` returns `null` on purpose: *"Android's widget is updated by FCM push and by its own provider, neither of which is rationed the way WidgetKit rations timeline builds."* Correct. Leave it. |
| Device registration / sessions / `stateRev` | `SHARED` | `/device/register`, `/user/state/rev` exist. Blocked on a real `DeviceIdentity` (§3.2). |
| Activity trail upload | `ABSENT` | `ActivityLog`/`ActivityUploader` are in `commonMain`; iOS drives them from `ActivityUploadScheduler.swift`. Android needs a WorkManager equivalent. `work-runtime-ktx` is already a dependency. |

### 3.5 The widget

D3 keeps RemoteViews. The gap is configuration, not rendering.

| Capability | Status | Note |
|---|---|---|
| Dot-matrix board rendering | `SHARED` | `widget_departure_board.xml` + provider. Font rules in [`dot-matrix-board-font`]. |
| FCM-driven redraw + ETA watchdog | `SHARED` | Keep. This is the thing iOS cannot do. |
| **One widget per station** | `ABSENT` | v1 has a single logical widget over "the" selection. v2 needs an `appWidgetId`-keyed station binding. |
| AppWidget configuration Activity | `ABSENT` | The standard Android placement-time flow. |
| **In-app widget manager** | `PLATFORM` | Explicitly requested: on Android the binding is editable *inside the app*, not only by long-pressing the widget. iOS cannot do this — `getCurrentConfigurations` returns `[]` inside a timeline and the app can never read the AppIntent config. See [`widgetkit-configurations-empty-in-timeline`] and [`ios-widget-placement-observation`]. Android has `AppWidgetManager.getAppWidgetIds()` and can both read and write. |
| `Board.widget` placement probe | `ABSENT` | `WidgetPlacement` is `@Transient`, device-local, re-derived on foreground. Android can derive it honestly, unlike iOS. |
| Widget guide screen | `SHARED` | `WidgetGuideScreen.kt` + SDUI `/sdui/app/widget-guide`. Needs `SduiAssetCache` (§3.2) for video; posters work without it. |

**The one rule that carries over unchanged:** never substitute another station's
board. See [`ios-widget-no-guessing-rule`]. Android's ability to configure
in-app makes this *easier* to honour, not optional.

### 3.6 Daydream

| Capability | Status | Note |
|---|---|---|
| Dream UI | `SHARED` | 11 files under `composeApp/.../ui/dream`, ported from Android's own. |
| `StationlyDreamService` | `DIVERGENT` | Exists and works; must host the shared `DreamHost` instead of `com.stationly.mobile.dream.*`. |
| `DreamSettingsActivity` | `DIVERGENT` | Keep the Activity and its `taskAffinity=""` isolation (the reasoning in the manifest is load-bearing); swap its content for `DreamSettingsScreen`. |
| Weather / keep-awake / location | `STUB` | §3.2 — the real implementations are sitting in `com.stationly.mobile.dream`, one package away. |

### 3.7 Release, config and money

| Capability | Status | Note |
|---|---|---|
| SDUI renderer, conditions, facts, icons | `SHARED` | v1 has a thinner `SduiComponentRenderer`; D2 deletes it. |
| SDUI config keys | `SHARED` | Additive only — 30 keys once thought dead were live Android. See [`sdui-config-strategy`]. |
| Board quotas / station + line limits | `SHARED` | `BoardQuota.kt`, `BoardPolicy.kt`, `StationLimitSheet.kt`. |
| Release gate / update policy | `SHARED` | `ReleasePolicy` already carries `android: PlatformRelease` alongside `ios`. |
| Update surface | `PLATFORM` | iOS deep-links to the App Store. Android should use Play In-App Updates for the soft path and `storeUrl` for the hard path. |
| Support / contributions | `ABSENT` by D4 | Build the surfaces, ship them off. Backend is on `feat/support-contributions`, dormant behind `SUPPORT_ENABLED=false`. |

---

## 4. What Android can do that iOS cannot

Worth stating positively, because a parity project drifts toward treating iOS as
the ceiling. It is not.

1. **Push-delivered departures.** FCM `Station_{naptan}` fan-out updates the app,
   the widget and the dream with no polling and no budget. iOS gave this up and
   built a three-engine refresh-budget system to approximate it
   ([`ios-widget-refresh-policy`]). Android should not inherit that machinery.
2. **A widget the app can see and configure.** §3.5.
3. **A screensaver.** Daydream has no iOS equivalent at all.
4. **A stable device identity across reinstall**, if `DeviceIdProvider` is
   wired to it — iOS loses the App Group container on delete
   ([`ios-device-id-ghost-sessions`]).
5. **In-app updates** rather than a link out to a store page.

Rule for the programme: **when iOS solved a problem that only existed because of
an iOS constraint, Android does not port the solution.** The live stream and the
widget refresh budget are both instances.

---

## 5. Compatibility contracts that must not break

Three contracts, and a session must know which one it is standing on.

### C1 — the v1 Android contract (live users, `versionCode 2`)
A phone running v1 must keep working while v2 rolls out, and must not be
corrupted by a v2 device on the same account.
- `/user/sync/stations` stays live and keeps its shape.
- The subscription registry reads the **union** of `boards` and `stations`;
  never merge them back into one list. See [`user-state-two-board-lists`].
- `FcmPayload`-shaped pushes must still parse on v1.
- **Known hazard:** v1's `syncStations` is a full REPLACE. §1.2 shows it already
  deleted v2 boards once. Any account with both a v1 and a v2 device is at risk
  until the v1 device upgrades. This is a rollout constraint, not just a test.

### C2 — the iOS contract (shipping to TestFlight now)
Android v2 shares `:core` and `:composeApp` with a build under active test.
- No breaking change to `commonMain` without checking the iOS call sites.
- `:composeApp` `commonMain` edits affect iOS immediately.
- The iOS framework must still assemble:
  `./gradlew :composeApp:assembleComposeAppDebugXCFramework`. See
  [`ios-stale-framework-pitfall`].

### C3 — the on-device contract (the schema)
`StationlyDatabase.sq` opens with a banner that says migrations were removed on
the premise *"no new Android release is planned"*. **D1 falsifies that premise.**
The banner even says so: *"If you find yourself writing one, check that premise
first — it is the only thing this policy rests on."* Detail in
[`MIGRATION.md`](MIGRATION.md).

---

## 6. Risk register

Ordered by expected damage, not by likelihood.

| # | Risk | Why it bites | Mitigation |
|---|---|---|---|
| R1 | Upgrade-in-place crashes on the old database | `Schema.create` runs only on empty databases. Every dev device is fresh, so this is invisible until it reaches a real user. Two primary keys changed — `ALTER TABLE` cannot do it. | Table-rebuild migration + a fixture test that opens a real v1 database. EPIC-02, before anything else. |
| R2 | A v1 device wipes a v2 device's boards | Proven, §1.2. | Backend union read; `allowEmpty` guard; force-update floor in `ReleasePolicy.android` before wide rollout. |
| R3 | The UI swap regresses something v1 users rely on | The entire UI layer changes at once for a live app. | Golden-output tests captured from v1 *before* the swap (EPIC-01). |
| R4 | A `commonMain` edit for Android breaks iOS mid-TestFlight | Shared source, two release trains. | C2 gate in CI on every session; `:core:testDebugUnitTest` + XCFramework assemble. |
| R5 | Beta navigation/lifecycle artifacts in a production Android app | `2.9.0-beta01`. | Accepted under D2. Pin exact versions; add a nav back-stack regression test — v1 already shipped a blank-screen bug from a nav/Compose mismatch (`NAVIGATION_BLANK_SCREEN_FIX.md`). |
| R6 | FCM topic churn during migration | Every device re-subscribes at once on upgrade. | Idempotent subscribe, diff against a persisted topic set, stagger. |
| R7 | Play policy rejection on the money surface | Live app, external checkout. | D4: gated off for v2. |
| R8 | R8 strips something the new dependency graph needs | `isMinifyEnabled = true` + reflection-heavy libs; the build file already warns to smoke-test release builds. | A release-build smoke test is an explicit gate in EPIC-08. |
| R9 | Two font pipelines | §3.3. | Pick one in EPIC-03 and delete the other. |

---

## 7. How to use this document

- A session **reads §3 for its own subsystem** and nothing else.
- A session **updates this file only when a finding changes** — a stub turned out
  to be real, a contract turned out to be looser, a risk turned out to be false.
  Task progress goes in `../BOARD.md`, never here.
- `gap-graph.json` is the machine-readable form of §3 and §6. Keep
  the two in step; the JSON is what an agent traverses to answer "what does this
  block?".

[`route-graph-is-a-dag`]: ROUTE_BRANCHES_AND_REJOINS.md
[`tfl-hub-id-is-stationnaptan`]: SESSION_2026-08-12_BOARD_MODEL.md
[`user-state-two-board-lists`]: SESSION_2026-08-11_USER_STATE.md
[`ios-device-id-ghost-sessions`]: SESSION_2026-08-12_SYNC_AND_IDENTITY.md
[`ios-deeplink-scheme-per-env`]: SESSION_2026-08-15_IOS_ENV_SPLIT.md
[`dot-matrix-board-font`]: BOARD_DOTMATRIX_FONT.md
[`ios-widget-no-guessing-rule`]: IOS_WIDGET_DESIGN.md
[`widgetkit-configurations-empty-in-timeline`]: SESSION_2026-08-17_WIDGET_STATION.md
[`ios-widget-placement-observation`]: SESSION_2026-08-14_WIDGET_CONFIG.md
[`ios-widget-refresh-policy`]: IOS_WIDGET_REFRESH.md
[`sdui-config-strategy`]: SDUI.md
[`ios-stale-framework-pitfall`]: IOS_BUILD_AND_HANDOFF.md
