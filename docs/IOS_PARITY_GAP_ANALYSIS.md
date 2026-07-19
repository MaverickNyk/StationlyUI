# iOS ↔ Android Parity — Deep Gap Analysis (2026-06-15, updated 2026-07-20)

> **Read this first if you are continuing the iOS-parity effort.** It is the
> single, current map of *what still differs* between the live Android app
> (`android/`, the source of truth) and the iOS Compose-Multiplatform port
> (`composeApp/` + `iosApp/`). It supersedes the scattered "REMAINING" notes in
> `IOS_BUILD_AND_HANDOFF.md` §8 (those are still accurate but less complete).

---

## 🆕 2026-07-20 SESSION — full-component sweep + DREAM PORT (read this block first)

Branch `ios-parity` was **rebased onto latest `origin/master`** (0 behind) and
force-pushed. Then a fresh file-by-file sweep of EVERY Android component
(owner ask: *"anything in Android should look alike and match nicely on iOS —
dreams, widget, fonts, navigation"*). Zero-regression gate: `android/` and
`core/commonMain` untouched; `:android:app:compileProdReleaseKotlin` compiles
green.

**Landed this session (all `composeApp`/`iosApp` only):**

| Item | Status |
|---|---|
| P1 Email verification · P2 ConnectivityMonitor · P4 Haptics · BUG-1 status bar | ✅ were closed by the Jul-7 commit (`1d22fd3`, now `f25f1fd` post-rebase) — §3/§4 below are STALE for these |
| **P1b Sign in with Apple — full integration** | ✅ CODE COMPLETE + dormant: `AuthBridge.signInWithApple` (ASAuthorizationController + nonce + Firebase `appleCredential`) behind the `appleSignInInteractive` command; `signInWithAppleInteractive()` through `PlatformAuthProvider`/`LoginViewModel` (provider "apple", same post-login sync as Google); LoginScreen default-wires the button. **Entitlement NOT added** — personal team cannot provision it (same wall as push; see project.yml UNBLOCK). Until then the button shows a friendly "isn't available" banner. Firebase console Apple provider must be enabled when unblocking. |
| **P5 board-delete LoadingOverlay** | ✅ ported (`ui/common/LoadingOverlay.kt`) + wired in SummaryScreen (zIndex 10, "Deleting board…") |
| **Login-path ServiceUnavailableScreen** | ✅ real gap — Android showed the branded full-screen state on login backend-offline, iOS only suppressed the error. Ported Android's component to `ui/common/ServiceUnavailableScreen.kt` (brand row via StationlyLogo, CloudOff, context copy) and wired it in LoginScreen (`login_sync`) AND SelectionScreen (replacing the old divergent private version). |
| **🌙 DREAM / SCREENSAVER — full port** (was "skipped for v1"; owner reversed) | ✅ `ui/dream/` (9 files): DreamTheme, DreamSettings (+`DreamPrefsBackend` app-group prefs — survives logout clearAll like Android's separate prefs file), DreamData, DreamClock (digital + analog + glow), DreamWeatherStrip + WeatherStation (met.no via NSURLSession expect/actual, last-known `CLLocationManager().location` only, London fallback), DreamSummary (StationHeader/NextTrainHero/EmptyStatePanel), **DreamBoard as pure Compose** (same `prepareLegacyRows` + fallback + StaleColor ago + ROW_BASE 14sp × textScale as Android's XML board), DreamHost (30:70 / 35:65 layouts, DreamDims, FreshDataNotifier-driven refresh — no polling), DreamFullscreenBoard (symmetric cutout mirroring, 2.8× cap, portrait penalty), DreamSettingsScreen (full picker port: big preview, layout chips, theme tiles, clock tiles, station picker). **iOS infra differences (no system screensaver API):** hosted as routes `dream/settings` + `dream`; entry = Profile → "Screensaver" row; `KeepScreenAwake` (`idleTimerDisabled`) while composed; exit = tap canvas → "Done" pill (+ back-swipe). Android's FCM broadcast refresh ≙ `FreshDataNotifier.events`. |
| **Prediction tick contract** | ✅ ported Android `ui/util/PredictionTicker.kt` (30s grace + per-platform monotonic bump) and switched the home Board's inline tick to it — the bump was MISSING on iOS (two same-platform trains could share a label). Swift widget already mirrors it. |
| **composeApp android target un-broken** | ✅ was failing to compile (missing Jul-7 interface methods in `AndroidPlatformAuthProvider`; orphan `AndroidLocationProvider` + wrong-package actual). Fixed/removed — target compiles again (it ships nothing; it's a verification surface). |

**Sweep verdicts (no action needed):**
- **Fonts** — parity ✅ (DisplayFamily Inter Tight: GMS-downloadable on Android, bundled TTFs on iOS; BodyFamily system on both).
- **Navigation routes** — parity ✅ (`webview/` route intentionally replaced by SFSafari). **Transitions:** Android NavHost uses defaults; iOS adds a UIKit-style push/pop slide — kept as an iOS-native divergence (same class as the owner-approved Cupertino pull-refresh). ⚠️ Owner may veto: delete the enter/exit/pop transition args in `AppNavigation.kt` to match Android's default fade.
- **ScrollWrap / StationStripFitter** — Android View-system workarounds for the widget XML; iOS's pure-Compose board scrolls/ellipsizes natively. Not gaps.
- **StagingBanner / UrlOpener / WebViewScreen / NotificationPermissionEffect / AuthState** — equivalents exist or intentionally omitted (see §5).
- **SduiCache / HomeConfigStore** — Android caches SDUI strings on disk for instant cold-start + cold surfaces; iOS fetches per-screen (dream + dream-settings fetch best-effort with hardcoded fallbacks). A disk cache port is a nice-to-have: **P6 (open)**.
- **ModeColors / BackendErrorUtil / AppConstants** — tiny Android-only helpers; iOS covers via ModeIconStore tint fallback + `friendlyAuthError`. Not user-visible gaps.

**Late-session additions (same day):**
- **P6 CLOSED** — `ui/util/HomeConfigCache.kt` (Android `HomeConfigStore`
  analogue): SDUI home-config strings persist via `Platform.storageManager`;
  SummaryViewModel + DreamHost + DreamSettingsScreen read cache-first then
  refresh. Cold launches render last-synced SDUI copy instantly.
- **Widget ago staleness colour** — `LiveAgo` now walks amber→grey→red
  (`StaleColor` thresholds 60s/180s) per timeline entry, matching the Android
  widget's AlarmManager colour fades. Was static amber.

**SDUI contract (owner principle: the apps are SDUI-driven everywhere;
stationly-backend is the SDUI backend):** every label in the dream port reads
the SAME home-config keys Android's dream reads (`dream.settings.title`,
`dream.settings.section.theme/clock/station`,
`dream.settings.layout.<storedAs>.name/.desc`,
`dream.settings.theme.<storedAs>`, `dream.settings.clock.<storedAs>`,
`dream.settings.station.auto.title/subtitle`, `board.fallback.*`) — backend
copy changes flow to BOTH platforms; hardcoded strings are offline fallbacks
only. NEW iOS-only keys the backend may want to add:
`profile.screensaver.title` / `profile.screensaver.subtitle` (Profile entry
row) and `dream.settings.start` (Start button) — safe fallbacks until then.

**Still open after this session:** Apple/push entitlements (paid team) ·
on-device QA of everything above (device was unreachable this session — see
§8b checklist).

**North star (owner, unchanged):** iOS must be an *identical* port of Android
(look + behaviour) on iOS infrastructure, **all SDUI-driven**. Match `android/`,
do **not** invent.

**Hard constraints (do not break):**
- `android/` is **OFF-LIMITS** (it's live in production).
- `core/commonMain` is **OFF-LIMITS** (shared with the live Android app). iOS work
  goes in **`composeApp/`** (any source set), **`core/iosMain`**, or **`iosApp/`**.
- The shipped Android app (`android/app`) depends on `:core` only — it does **not**
  depend on `:composeApp`. So **every `composeApp` edit is iOS-only** and cannot
  affect the Android app. (composeApp has an `androidTarget`, but nothing ships it.)

---

## ⏯️ START HERE — current state (handoff, 2026-06-15)

**A fresh agent should do, in order:** (1) read this whole doc; (2) read
`IOS_BUILD_AND_HANDOFF.md` §2 (build runbook) + §7h; (3) `git log` (the 2026-06-15
work is committed + pushed, see below); (4) if iterating on UI, build + deploy per
§7 and QA against the checklist in §8.

**Branch:** `ios-parity` (tracks `origin/ios-parity`). The 2026-06-15 work is
**committed + pushed** in three commits on top of `9f2add7` (S9 parity pass):

| Commit | Scope | Sections |
|--------|-------|----------|
| `b9be379` | iOS home/board: row fonts, empty-state fallback, edge-to-edge dark bg, toggle pin, `splitLineStatus`/`isDarkTheme` dedup | §2.1–2.4, 2.7 |
| `a58cad4` | iOS login: Sign in with Apple (primary) + real Google "G" + `BrandGlyphs.kt` + `SocialSignInButton` | §2.6, 2.7 |
| _this docs commit_ | gap analysis (new) + handoff pointer | — |

New files: `ui/util/BoardFallback.kt`, `ui/common/BrandGlyphs.kt`,
`docs/IOS_PARITY_GAP_ANALYSIS.md`.

**Build/deploy status (2026-06-15):**
- **Device reflects HEAD** — rebuilt (XCFramework + Staging) and installed +
  launched on Nick's iPhone (UDID `00008030-001E0D9C3EFB802E`) with ALL of §2.
- ⚠️ **To SEE the new login landing you must be signed OUT** — if Nick is signed in,
  the app opens straight to Summary (Profile → log out to reach the landing).
- Everything **compiles green** (`:composeApp:compileKotlinIosSimulatorArm64`); each
  commit is its own compiling snapshot.

**Top of the backlog — SUPERSEDED, see the 2026-07-20 block above.** (P1, P1b,
P2, P4 and BUG-1 are all closed; remaining: P6 SDUI disk cache, paid-team
entitlements, on-device QA §8b.)

---

## 0. Methodology

Compared, file-by-file: the two UI trees
(`android/.../ui/` vs `composeApp/.../ui/`), the four screens + their ViewModels,
the `Board` + widget XML layouts, the shared `core` utilities each side consumes,
and the iOS Swift host (`iosApp/`). Line-count deltas were used only as a
*pointer* to where to look — most of the delta is **intentional omission**
(promos, dream/screensaver, Android-only permission prompts), not missing parity.

---

## 1. Status board — every gap, at a glance

| # | Area | Android | iOS before | Status |
|---|------|---------|-----------|--------|
| 1 | **Board empty-state messages** | `computeBoardFallbackState` → Offline / Live updates paused / Service ended / Disrupted / … | old `buildPlaceholderRows` "funny" copy | ✅ **FIXED 2026-06-15** |
| 2 | Board row fonts/padding | widget XML (15sp header, no letter-spacing, system-font clock) | 13sp header, +letter-spacing, mono clock | ✅ **FIXED 2026-06-15** |
| 3 | Dark bg to screen bottom | edge-to-edge | safe-area strip showed through | ✅ **FIXED 2026-06-15** |
| 4 | Theme toggle position | pinned bottom-right (`navigationBars` inset) | floated above home indicator | ✅ **FIXED 2026-06-15** |
| 5 | Hero countdown weight | Black/28sp/Monospace | identical code | ✅ **VERIFIED identical** (SF-Mono renders heavier; see §4) |
| 6 | Email verification flow | `VerifyEmailScreen` + `onNeedsEmailVerification` | none | ⚠️ **DEFERRED** (Swift/KMP boundary) |
| 7 | Google "G" glyph | real `ic_google_standard` vector | flat blue "G" | ✅ **FIXED 2026-06-15** (rebuilt as Compose `ImageVector`) |
| 7b | **Sign in with Apple button** | n/a (Android) | none | ✅ **UI DONE 2026-06-15** · integration deferred (§3 P1b) |
| 8 | Haptics | `performHapticFeedback` on key actions | none | ⚠️ **DEFERRED** (iOS-native polish) |
| 9 | Forced dark appearance | follows in-app theme | `.preferredColorScheme(.dark)` forces dark | 🐞 **BUG, documented** (§4) — breaks light theme |
| 10 | Board-delete loader | full-screen `LoadingOverlay` | card fades, no modal spinner | ⚠️ **DEFERRED** (minor; re-tap already guarded) |
| 11 | Pull-to-refresh style | Material spinner-in-pill | Cupertino amber ring | ⚠️ **Intentional divergence** (owner-approved; flag if strict) |
| 12 | Widget promo / dream promo / notif-denied banner | present | omitted | ✅ **Intentional omission** (iOS v1 decision) |
| 13 | Daydream screensaver | present | omitted | ✅ **Intentional omission** (iOS v1 decision) |
| — | Edit display name | dialog + `updateDisplayName` | **present** (`IosPlatformAuthProvider` + `AuthBridge`) | ✅ already closed |
| — | In-app browser | `WebViewScreen` | **present** (`InAppBrowser` → SFSafari) | ✅ already closed |
| — | `ServiceUnavailableScreen` | Selection + Login | **present** in Selection | ✅ mostly closed (verify Login path) |
| — | SDUI renderer light/dark | theme-aware | theme-aware (S9) | ✅ already closed |

---

## 2. What was FIXED on 2026-06-15 (this session)

All in `composeApp` only. Compiles green
(`./gradlew :composeApp:compileKotlinIosSimulatorArm64` → BUILD SUCCESSFUL).
Earlier-in-day build (#1–4) was installed + launched on Nick's iPhone; the
**board-fallback port (#1) has NOT been on-device QA'd yet**.

### 2.1 Board empty-state fallback messages (the big one) — NEW
**Gap:** Android's `Board` computes `computeBoardFallbackState(...)` and, whenever
there are **no predictions**, overrides the row area with a 4-row message block:
`Offline` · `Live updates paused · Last refresh N ago` · `Service ended for
tonight` · `Service starting soon` · `Nothing departing right now` ·
`Service disrupted`. This logic lives in **`android/.../ui/util/BoardFallbackState.kt`
— Android-only, NOT in shared `core`** — so iOS never had it. The iOS board fell
through to `core` `GlobalBoardProcessor.buildPlaceholderRows` (the *older* "funny
message" placeholder copy → "👋 Hey, welcome!", "🌙 Night owls only", …). Result:
**the two platforms showed different empty-board copy.**

**Fix:** faithful port —
- **`composeApp/.../ui/util/BoardFallback.kt`** (NEW) — kotlinx.datetime port of
  the Android state machine + copy table. Same `BoardFallbackKind`, same defaults,
  same SDUI keys (`board.fallback.*`), same thresholds.
- **`Board.kt`** — computes `londonTime` (`Europe/London`) + `fallbackState` from
  `ticked.isNotEmpty()`, `isOnline`, `lastUpdated`, `nowMin`, `lineStatus`,
  `homeConfig`. `DotMatrixPanel` renders the fallback rows (bold title + normal
  details, padded to `BOARD_FALLBACK_ROW_COUNT=4`, departure-row style) **instead
  of** the normal rows when `fallbackState != null`. Station strip + status strip +
  footer clock still render (parity).
- **`SummaryScreen.kt`** — passes `isOnline = !uiState.isBackendOffline` to `Board`.

**iOS connectivity note:** Android reads true device connectivity via
`NetworkState` (Android-only, uses `ConnectivityManager`). iOS has no equivalent
yet, so OFFLINE uses `!isBackendOffline` (set when a refresh throws). This is a
*weaker* signal, but the **age-based `SIGNAL_LOST`** ("Live updates paused · last
refresh N ago", fires at data age ≥ 6 min) covers a silent offline drop regardless
of why. A real `NWPathMonitor` expect/actual in `core/iosMain`/`composeApp` would
tighten OFFLINE — a good follow-up, not a blocker.

### 2.2 Board row fonts/padding → exact Android XML parity
`Board.kt` `DotMatrixPanel`, aligned to `widget_departure_row.xml` /
`widget_platform_header.xml` / `widget_departure_board.xml`:
- platform header **13sp → 15sp**, vertical pad → **horizontal 2dp**;
- dropped iOS-only letter-spacing on header (0.3) / destination (0.2) / station (0.2);
- footer clock dropped `FontFamily.Monospace` (Android `TextClock` = system font).

### 2.3 Dark-mode background reaches the bottom
`Theme.kt` `StationlyTheme` now wraps `content()` in
`Box(fillMaxSize).background(resolvedTokens.canvas)` inside `MaterialTheme` — a
theme-aware **edge-to-edge backdrop**. The host draws under the safe areas
(`ContentView` `.ignoresSafeArea()`), but a per-screen `Scaffold` only paints its
own bounds, so the home-indicator strip showed the window through. Now filled on
every screen, follows the light/dark toggle.

### 2.4 Theme toggle pinned to the true screen bottom-right
`SummaryScreen.kt` — toggle moved **outside** the `.padding(padding)` content box
and uses `windowInsetsPadding(WindowInsets.navigationBars)` + `padding(end=6,
bottom=2)` (Android's exact placement). Previously inside the padded box → the
scaffold's bottom inset floated it high; the prior code had *removed* the
`navigationBars` inset to avoid a double-application, which is what dropped it out
of the corner.

### 2.6 Login landing — Sign in with Apple + real Google "G" — NEW
**`LoginScreen.kt`** (landing). Owner ask: add the famous **Sign in with Apple**
as the primary button and reshape the Google button with Android's real "G".
- **`AppleButton`** (NEW, PRIMARY) — **adaptive max-contrast** per Apple HIG:
  **white** button (black logo/text) on the dark theme, **black** on light. (The
  first cut used a black button in both themes; on the near-black canvas it blended
  in and read as *secondary* — owner feedback. White-on-dark fixes the hierarchy.)
  Slightly taller (56dp), bold 16sp, with a 3dp lift shadow so it clearly reads as
  primary. Logo is a Compose `ImageVector` (bitten-apple path).
- **`GoogleButton`** (SECONDARY) — recedes below Apple: on the dark theme it's
  Google's **dark-brand button** (dark `#1C1C1E` surface + white text + colour
  "G"); on light it's the standard white button. Either way the glyph is the
  **official 4-colour Google "G"**, rebuilt as a Compose `ImageVector` from the
  exact path data in Android `res/drawable/ic_google_standard.xml` (drawn with
  `Image`, never tinted).
- Both glyphs are vectors → **crash-proof regardless of composeResources bundling**
  (the logo/font bundling has bitten us before — see handoff "⚠️ READ FIRST").
- **UI ONLY.** `launchApple()` calls an optional `onAppleSignIn` (null/no-op for
  now); a `TODO` marks where the `ASAuthorization` → Firebase OAuth credential
  flow goes. See §3 P1b.

### 2.7 Code-quality / modularization pass — NEW
A cleanup over everything above (no behaviour change; compiles green; `android/`
untouched):
- **`ui/common/BrandGlyphs.kt`** (NEW) — the Google "G" + Apple logo `ImageVector`s
  moved out of `LoginScreen` into one reusable home (`internal`/top-level), so the
  path data lives once and other surfaces (provider icons, etc.) can reuse it.
- **`SocialSignInButton`** (in `LoginScreen`) — a shared full-width button scaffold
  with a `leading(content)` glyph slot; `AppleButton`/`GoogleButton` are now thin
  wrappers (no duplicated `Button`+`Row` geometry).
- **`isDarkTheme()`** (in `Theme.kt`) — one helper for the `background.luminance()
  < 0.5f` branch; replaces 3 inline copies (`LoginScreen` ×2, `Board`).
- **`splitLineStatus()`** (in `Board.kt`) — one parser for the TfL
  `"Severity: Reason"` split; replaced **5** copy-pasted inline parses (disruption
  banner, status strip, fallback detection, legacy rows).

### 2.5 Hero countdown — VERIFIED, not changed
`NextDepartureRow` is byte-identical to Android (Black/28sp/Monospace number,
Medium/11sp/.55α "min", primary only when due/≤1 min). Left unchanged — changing
it would *invent* a divergence. See §4 for the SF-Mono caveat.

---

## 3. What's DEFERRED (real gaps, not yet done) — priority order

### P1 — Email-verification flow ⚠️ (crosses Swift/KMP boundary)
Android: `login/VerifyEmailScreen.kt` + `LoginViewModel.onNeedsEmailVerification`
+ `sendEmailVerification` / `reload`. iOS has **none** — a registered-but-unverified
user is not gated. To port: add `sendEmailVerification()` + `reloadUser()` commands
to `iosApp/AuthBridge.swift` (Firebase `currentUser.sendEmailVerification` /
`reload`), expose via `IosPlatformAuthProvider`, add the `auth/verify-email` route +
screen in `composeApp`. **Needs device QA** (real Firebase email round-trip). Risk:
medium (auth flow). Reference: `android/.../login/VerifyEmailScreen.kt`,
`LoginViewModel.kt` (the `needsEmailVerification` branch).

### P2 — `NWPathMonitor` connectivity for a true OFFLINE signal
Tightens §2.1's OFFLINE. `expect fun isDeviceOnline(): Flow<Boolean>` in
`composeApp/commonMain`, actual in `iosMain` using `NWPathMonitor`. Then feed it to
`SummaryViewModel` and pass through to `Board(isOnline=…)` instead of
`!isBackendOffline`. Risk: low–medium (platform code). Reference:
`android/.../ui/util/NetworkState.kt`.

### P1b — Sign in with Apple **integration** (UI already shipped, see §2.6)
The button exists and calls `onAppleSignIn` (currently null). To wire it:
`iosApp/AuthBridge.swift` performs `ASAuthorizationController` with an
`ASAuthorizationAppleIDProvider` request (scopes: fullName, email), takes the
returned identity token + nonce, builds a Firebase `OAuthProvider.credential(
withProviderID: "apple.com", idToken:, rawNonce:)`, signs in, then mirrors the
existing Google post-login sync. Expose a command on `IosPlatformAuthProvider`,
pass `onAppleSignIn` into `LoginScreen` from the host. **Add the `Sign in with
Apple` capability** in `iosApp/project.yml` (entitlement). Needs device QA (real
Apple ID round-trip). Reference: the Google path in `LoginViewModel` +
`AuthBridge.swift`. Risk: medium (auth + entitlement).

### ~~P3 — Google "G" glyph~~ ✅ DONE 2026-06-15 (see §2.6) — now a vector.

### P4 — Haptics on key interactions
Selection commit, board delete, pull-refresh trigger. `expect fun
hapticTap()`/`hapticSuccess()` in `composeApp`, actual in `iosMain`
(`UIImpactFeedbackGenerator` / `UINotificationFeedbackGenerator`). Android uses
`view.performHapticFeedback`. Risk: low.

### P5 — Board-delete `LoadingOverlay`
Android shows a full-screen "Deleting board…" modal during the backend unsubscribe;
iOS just fades the card out (re-tap is already guarded by `isDeletingBoard`).
Low value — iOS behaviour is acceptable. Port `android/.../common/LoadingOverlay.kt`
into `composeApp` + gate on `deletingBoardId != null` if desired.

---

## 4. BUGS found

### 🐞 BUG-1 — `.preferredColorScheme(.dark)` forces dark, breaks LIGHT theme
`iosApp/iosApp/iOSApp.swift:14` pins the whole SwiftUI/UIKit appearance to dark:
```swift
ContentView().preferredColorScheme(.dark)
```
Consequences when the user picks **Light** in the in-app toggle:
- the **status-bar text stays light** (white-on-cream → near-invisible);
- the window/safe-area chrome behind Compose stays black.

The in-app Compose theme *does* switch to light (canvas now fills to the edges
after §2.3), so content is correct — but the system chrome is wrong. **Why it's
still here:** it conveniently keeps the status bar readable on the dark default and
nobody had wired a dynamic status-bar style. **Fix (needs device QA):** drive the
status-bar/appearance from the active `AppTheme` — either remove
`.preferredColorScheme` and let Compose own it, or push the resolved light/dark
down to Swift so `preferredColorScheme` tracks the toggle. **Do not change blind** —
status-bar styling on CMP-iOS is finicky; verify on device. Until then, the app is
effectively dark-first, which matches the brand, so this is low-urgency.

### (watch) Disruption double-surface — *intended*, not a bug
When a line is disrupted with no predictions, iOS now shows BOTH the disruption
banner (above the board) AND the `DISRUPTED` fallback rows inside it. This matches
Android exactly — leave it.

---

## 5. Confirmed parity (don't "fix" these — they already match or are intentional)

- **Edit display name** — present on iOS (`IosPlatformAuthProvider.updateDisplayName`
  + `AuthBridge.swift` Firebase `createProfileChangeRequest`). Handoff §8.5 is stale.
- **In-app browser** — present (`composeApp/.../platform/InAppBrowser.kt` →
  `SFSafariViewController`). Handoff §8.7 is stale.
- **`ServiceUnavailableScreen`** — present, used in iOS `SelectionScreen` (backend-down
  full-screen state). *To verify:* the Login screen's offline path matches Android.
- **Theme toggle behaviour** — cycles Light→Dark→System, persists via NSUserDefaults.
- **SDUI renderer light/dark** — `ui/sdui/SduiRenderer.kt` is theme-aware (done S9).
- **Pull-to-refresh** — Cupertino indicator is an *owner-approved* iOS-native
  divergence. Only revert to Material if strict pixel-parity is demanded.
- **Promos / dream / Android notification-permission prompt** — intentionally
  omitted for iOS v1 (product-owner decision). Not gaps.

---

## 6. Files touched on 2026-06-15

```
composeApp/src/commonMain/kotlin/com/stationly/app/ui/util/BoardFallback.kt   (NEW)
composeApp/src/commonMain/kotlin/com/stationly/app/ui/summary/components/Board.kt
composeApp/src/commonMain/kotlin/com/stationly/app/ui/summary/SummaryScreen.kt
composeApp/src/commonMain/kotlin/com/stationly/app/ui/theme/Theme.kt
composeApp/src/commonMain/kotlin/com/stationly/app/ui/login/LoginScreen.kt   (Apple + Google "G")
composeApp/src/commonMain/kotlin/com/stationly/app/ui/common/BrandGlyphs.kt   (NEW — reusable glyphs)
```
**Shared helpers added (reuse these — don't re-inline):** `isDarkTheme()` in
`ui/theme/Theme.kt`; `SocialSignInButton` in `LoginScreen.kt`; `splitLineStatus()`
in `Board.kt`; `GoogleGLogo`/`AppleLogo` in `ui/common/BrandGlyphs.kt`.
Working tree on branch `ios-parity`, **uncommitted** pending owner on-device QA.

## 7. Build & deploy (recap — full detail in IOS_BUILD_AND_HANDOFF.md §2)
```bash
./gradlew :composeApp:assembleComposeAppDebugXCFramework        # ~7 min when composeApp changed
cd iosApp && ./xcodegen.sh
DD=build/DD
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" -derivedDataPath "$DD" -resolvePackageDependencies
chmod -R u+w "$DD/SourcePackages/checkouts"; find "$DD/SourcePackages/checkouts" -maxdepth 2 -iname BUILD -type f -delete
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" \
  -destination 'id=00008030-001E0D9C3EFB802E' -derivedDataPath "$DD" -allowProvisioningUpdates build
xcrun devicectl device install app --device 00008030-001E0D9C3EFB802E \
  "$DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device process launch --device 00008030-001E0D9C3EFB802E com.stationly.mobile
```
Device: Nick's iPhone (iPhone 11, iOS 26.3), UDID `00008030-001E0D9C3EFB802E`,
team `6D3CXG8U25`, Apple ID `nikhilkumar11896@gmail.com`. Unlock the phone before
`launch` (else `BSErrorCodeDescription=Locked`).

## 8. On-device QA checklist for the 2026-06-15 changes
1. **Board fallback (NEW, unverified):** delete all predictions / go offline / open
   at 02:00 London — confirm the board shows `Offline` / `Live updates paused` /
   `Service ended for tonight` rows (NOT the old "👋 Hey, welcome!" copy), with the
   station strip + status + clock still pinned.
2. **Dark bg:** black reaches the very bottom edge (no lighter strip at the home bar).
3. **Toggle:** sits low in the bottom-right corner, just above the home indicator.
4. **Board rows:** header/destination/ETA sizes + weights vs an Android device.
5. **Light theme (BUG-1):** toggle to Light — note the status-bar text legibility
   (expected to be poor until BUG-1 is fixed).
6. **Login landing (NEW):** the **Apple** button (black) sits above **Google**
   (white, real 4-colour "G"); both render their logos crisply, no flat "G".
   Tapping Apple is a no-op for now (integration deferred). Check both themes.

## 8b. On-device QA checklist for the 2026-07-20 session (device was offline)
1. **Screensaver entry:** Profile shows a "Screensaver" row (amber moon icon)
   above About → opens the settings screen (big 16:9 preview, layout chips,
   theme tiles, clock tiles, station picker when >1 board, amber **Start
   screensaver** button).
2. **Dream — Clock + Board:** Start → clock cluster (digital by default) +
   date/weather strip + station header + NEXT DEPARTURE hero + dot-matrix
   board. Portrait stacks 35:65; rotate for 30:70. Rows tick per minute, board
   matches the home board's fonts dot-to-dot at larger scale, ago-colour
   amber→grey→red with age. Screen must NOT auto-lock while the dream is up
   (and must auto-lock again after leaving).
3. **Dream — Fullscreen Board:** switch layout → amber-bordered signage card
   centred on black, built-in ticking clock in the footer, symmetric notch
   margins in landscape.
4. **Dream themes:** System/Light/Dark tiles change the canvas (board card
   stays dark). Fullscreen pins dark and hides the theme section.
5. **Dream exit:** tap canvas → "Done" pill appears top-right (auto-hides
   ~4s); tapping it (or back-swipe) exits.
6. **Dream refresh:** while the dream is up, a pull-refresh-driven or FCM
   data change on another surface should update the rows (FreshDataNotifier).
7. **Weather chip:** temperature + emoji under the clock (London fallback when
   no location permission). May take a few seconds on first entry.
8. **Apple Sign-In (dormant):** sign out → tap "Continue with Apple" → expect
   the friendly "Sign in with Apple isn't available right now…" banner (no
   crash, no hang) — full flow unblocks with the paid-team entitlement.
9. **Login offline state:** airplane-mode a signed-out app → login shows the
   branded full-screen "Can't reach servers" (STATIONLY brand row, CloudOff,
   Try again) instead of a bare form; Retry works after reconnecting.
10. **Board delete:** deleting a board shows the modal "Deleting board…"
    overlay that blocks taps until the backend unsubscribe completes.
11. **Same-platform label bump:** a station with two imminent trains on ONE
    platform must never show duplicate ETAs ("Due, Due" → "Due, 1 min").
