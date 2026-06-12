# iOS Parity Plan — bring the iOS app to the redesigned Android feature set

**Status as of 2026-06-12 (Session 7).** Living handoff doc; update it as phases land.

## ⏯️ RESUME HERE (continuation for a fresh agent / new session)

**CURRENT (2026-06-12, end of Session 7).** Session 7 work (all built, deployed
to the device, and verified except where noted; UNCOMMITTED on `ios-parity`):

1. **Medium widget de-crumbled** — fixed 6-cell content budget (station + ONE
   platform group header + 3 rows + footer), status strip is backfill-only,
   footer shed under 150pt, surplus height now shared EVENLY across cells
   (the layoutPriority ladder was removed — it made only header/footer balloon
   on 1–2-row boards). Full rationale: `IOS_WIDGET_DESIGN.md` §3.6.
2. **Mode roundel un-squashed** — `ModeIconProvider.rerendered` no longer
   forces a 48×48 square (keeps source aspect); `ModeIconView` is
   height-anchored with natural width like Android's fitCenter
   (`IOS_WIDGET_DESIGN.md` §3.7).
3. **"?" avatar / "User" profile fixed (root cause)** — Firebase keeps its
   session in the KEYCHAIN (survives app delete/reinstall) but the identity
   keys (`firebase_user_email/_display_name/_photo_url/_uid`,
   `signin_provider`, `member_since`) lived in NSUserDefaults (wiped on
   delete) and were ONLY written during an explicit sign-in. Restored
   sessions therefore launched "logged in" with no identity. Fix:
   `AuthBridge.swift` now has `persistUserIdentity(_:)`, called from the
   auth-state listener (synchronously) AND `refreshTokenIfNeeded()` (every
   foreground) — self-healing. Verified healed on-device by pulling
   `Library/Preferences/com.stationly.mobile.plist` via
   `xcrun devicectl device copy from --domain-type appDataContainer …`.
4. **Cold-start race guards (KMP)** — `SummaryViewModel.loadUserInitial` and
   `ProfileViewModel.loadProfile` re-read once after 1.5s if identity keys
   are empty while logged in (the VM could read before the Swift listener's
   first write landed).
5. **Backend device-session re-registration (KMP)** — restored sessions never
   passed through LoginViewModel, so `/user/sync/profile` (which records
   deviceId + deviceInfo session data) was never re-sent after a reinstall.
   `SummaryViewModel.registerDeviceSession()` now re-upserts it best-effort
   at home-screen creation (after loadUserInitial settles). NOT yet verified
   against the staging backend's session store — next agent: confirm a
   session doc exists for this deviceId (App-Group key `stationly_device_id`).

6. **Profile "User" flash eliminated** — `ProfileScreen` renders
   `displayName.ifBlank { "User" }` and the VM state was seeded ASYNC (two
   IO-hopping reads before the first state write), so under main-thread
   contention the empty default state stayed visible (~1-in-10 visits:
   "User" + blank photo, photo slowest since AsyncImage starts only after
   state lands). `ProfileViewModel` now seeds the StateFlow synchronously
   from the non-suspend auth-provider getters. LATENT, not yet addressed:
   a stale-token 401 on any non-auth endpoint triggers
   `signOutFromAuthExpiry` → full identity wipe + Firebase sign-out
   (NetworkModule HttpResponseValidator); iOS refreshes the token only on
   foreground, so a >1h-old token used right at foreground could force a
   spurious logout. `/user/sync/*` and `/auth/*` are excluded.

**HISTORY — Session 6 (2026-06-12) — authoritative detail lives in
`docs/IOS_BUILD_AND_HANDOFF.md` §7f.** One-paragraph summary:
FCM→board/widget client chain is COMPLETE (Swift awaits KMP via the composeApp
suspend bridge; home board reloads predictions+status on push; widget rewrites
pull-model from the PRIMARY selection on every trigger incl. selection changes;
widget ETAs self-tick per minute like Android). App displays as **"Stationly"**.
Push DELIVERY is still hard-blocked on signing (personal team — §7e/§7f
evidence + unblock steps). **Working tree has UNCOMMITTED Session-6 changes**
(file list in §7f) and a built staging .app is staged for deploy — the owner
disconnected the device mid-session; redeploy runbook in §7f. Owner constraints:
never touch `android/` or shared `core/commonMain`; Syncer repo read-only;
dot-matrix board look is intentional brand.

---

**HISTORY — Where we were (2026-06-09, end of Session 2):**
- Working on git branch **`ios-parity`** (off `dev_25Apr`). 8 commits landed (list
  below). Nothing merged to `dev_25Apr`/`master` yet.
- **All four screens + the full theme system are ported and compile-verified for
  iOS**; the XCFramework links for device + simulator. **NOT yet visually QA'd on
  a device** — that's the immediate next milestone.
- **In flight when the session ended:** a long Apple download —
  `xcodebuild -downloadPlatform iOS` (the iOS *platform component*, needed to run
  on device/simulator on Xcode 26; the SDK alone isn't enough). Check if it
  finished: `xcrun simctl list runtimes | grep -i ios` should show iOS 26.x (not
  just 17.5), and `xcrun xctrace list devices` shows the iPhone.

**Decision: build + test with the STAGING config** (user's call). Use the
`iosApp Staging` scheme → `STATIONLY_ENVIRONMENT=staging` → AppConfig points at
`staging-api.stationly.co.uk` / `staging.stationly.co.uk`. The single real Firebase
plist (`mindthetimefcm`) is shared across envs (auth/Google/FCM work regardless).

**Exact next steps to get it on the iPhone (once the platform download is done):**
```bash
cd iosApp && ./xcodegen.sh                       # 1. regenerate project (idempotent)
# 2. pre-clean SPM checkouts (Xcode-26 + Firebase/nanopb quirk — see §2 "gotchas")
DD=build/DD
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" -derivedDataPath "$DD" -resolvePackageDependencies
chmod -R u+w "$DD/SourcePackages/checkouts"
find "$DD/SourcePackages/checkouts" -maxdepth 2 -iname BUILD -type f -delete
# 3. build for the connected device (id from `xcrun xctrace list devices`)
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" \
  -destination 'id=00008030-001E0D9C3EFB802E' -derivedDataPath "$DD" \
  -allowProvisioningUpdates build
```
If the CLI build fails on **code signing** (needs an interactive Apple ID), fall
back to the GUI: `open iosApp/iosApp.xcodeproj` → scheme `iosApp Staging` → select
the iPhone → Signing & Capabilities: Automatically manage signing, Team
`6D3CXG8U25` (both `iosApp` and `StationlyWidget` targets) → **⌘R**. First run:
trust the cert on the phone (Settings → General → VPN & Device Management).
Install a CLI-built `.app` to the device with
`xcrun devicectl device install app --device <id> <path-to.app>`.

**First-run smoke test:** login (email + Google) → board renders → the new
**Network / Fares cards (ExploreSection)** → open Profile. Capture defects in §0.

---

## Session 2 progress (2026-06-09) — branch `ios-parity`

Per-screen commits, each compile-verified (`compileKotlinIosSimulatorArm64`):

- `4f26fee` theme tokens + ExploreSection + Xcode-26 build fixes
- `9a6b2f1` **full light/dark/system theme + SDUI token sync** (ThemeRepository,
  StationlyThemeHost, toColorScheme; via Platform.storageManager)
- `f7081f5` **Login** — redesigned landing + SDUI form + reset-confirm
- `144972d` **Profile** — header, My Stations, SDUI About, sign-out/delete dialogs
- `8dd595b` **Summary** — theme migration, dropped stale SummaryHeader
- `5870019` **Selection** — theme-aware palette
- `5fe713c` **EmptyStates** — theme-aware first-launch
- `09f1aec` docs

Decisions applied: full light/dark/system theme; **promos omitted** on iOS v1;
new branch + per-screen commits; keep porting forward; test on **staging**.

### What's LEFT to do (priority order)
1. **Get it running on the iPhone** (blocked only on the platform download above)
   and do the first visual QA pass — expect layout/spacing/colour tweaks.
2. **Replace drawn placeholders with real assets**: brand logo + Google "G" glyph
   are currently drawn (an amber "S" / a "G" letter). Add `stationly_logo` +
   `ic_google_standard` to `composeApp/src/commonMain/composeResources/drawable/`
   and use generated `Res.drawable.*` in Login/Summary/Profile top bars.
3. **Network avatar on Profile** — currently monogram only (no Coil in CMP).
   Add Coil 3 KMP (`coil-compose` multiplatform) and render `uiState.photoUrl`.
4. **Email-verification flow** — Android has VerifyEmailScreen +
   `onNeedsEmailVerification`; not ported. Add to nav + LoginViewModel if needed.
5. **In-app WebView** — iOS currently hands links to Safari via `LocalOpenUrl`.
   Port a `WKWebView` screen if in-app browsing is wanted.
6. **Light-mode polish on the remaining shared components**: `OfflineBanner.kt`,
   `AnnouncementBanner.kt` still hardcode dark (acceptable as dark toasts, but
   migrate to tokens for full light parity). **`Board.kt` + `SduiRenderer.kt`
   stay hardcoded dark ON PURPOSE** — dot-matrix signage, never themed (matches
   Android). `SummaryHeader.kt` is now dead code (unused) — delete or leave.
7. **DirCard "shape jumps on select" fix** from Android `e488e81` not yet applied
   to the Compose SelectionScreen — apply if the visual QA shows the jump.
8. **Widget parity** — the SwiftUI widget exists; align it with the redesigned
   board after the app screens are confirmed.
9. Decide staging→prod Firebase plist story before a prod TestFlight build.

---

## 0. TL;DR for the next session

- The iOS app is **Compose Multiplatform**, not a native Swift rebuild. Shared
  Kotlin (`core/`) + shared Compose UI (`composeApp/`) render inside a thin
  Swift host (`iosApp/`). `android/` is the **mature, source-of-truth** native
  app, continuously redesigned.
- The Compose UI in `composeApp/` is a **stale port of an older Android** (last
  synced 2026-06-01; Android shipped "Prod ready" + "Prod UI updates" after).
  The drift is foundational, not cosmetic: Android moved to a **token-based,
  SDUI-driven, light/dark theme system**; the iOS Compose port is still on a
  hardcoded dark-only `colorScheme`.
- **Decisions locked** (by product owner, 2026-06-08):
  1. Android Daydream **screensaver → skipped for v1** on iOS (no OS analog).
  2. **Keep porting `android/` → `composeApp/` forward** (android stays the
     source of truth; not converging Android onto composeApp yet).
  3. Start with **Phase 0** (build green) before bulk porting.
- **Blocker found in Phase 0:** full **Xcode is not installed** on this machine
  (only Command Line Tools + XcodePilot). `xcodebuild` / simulator runs are
  impossible until Xcode is installed. **User is installing Xcode.**
- **What works without Xcode:** `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
  compiles all shared Kotlin/Compose for iOS. This is the verification loop used
  for all porting until Xcode lands. **It is green.**
- **Landed this session (compiles for iOS):** theme-token foundation +
  `ExploreSection` (the biggest functional gap). See §5.

---

## 1. Architecture

| Module | Role | Targets |
|---|---|---|
| `core/` | Business logic, models, repositories, SQLDelight DB, Ktor, use cases | common, android, ios, wasmJs |
| `composeApp/` | **Shared Compose UI** (login, selection, summary, profile, sdui) | common, android, ios |
| `iosApp/` | Swift host: app shell + Firebase Auth, Google Sign-In, FCM/APNs, SwiftUI widget | iOS |
| `android/` | **Source-of-truth** native Android app (own full Compose UI) | Android |
| `web/` | Kotlin/Wasm web app | wasmJs |

iOS app = `core` + `composeApp` rendered via
`MainViewController` (`ComposeUIViewController`) → `ComposeHostView`
(`UIViewControllerRepresentable`) in `ContentView.swift`. Swift handles what
Kotlin/Native can't: Firebase Auth (`AuthBridge.swift`), FCM (`FCMBridge.swift`,
`AppDelegate.swift`), and the home-screen widget (`StationlyWidget/`).

**KMP↔Swift auth protocol:** KMP writes `auth_pending_command` to
`NSUserDefaults`; `AuthBridge.swift` observes, dispatches Firebase, writes back
`firebase_auth_token` / `auth_pending_error`; KMP `IosPlatformAuthProvider`
polls. This is complete and robust — do not rewrite it.

---

## 2. iOS build setup (Phase 0 reference)

- `iosApp/project.yml` → XcodeGen. Regenerate with `iosApp/xcodegen.sh` (it
  patches `objectVersion` 77→56 and strips `preferredProjectObjectVersion` for
  Xcode 15.4).
- Deployment target iOS 16 (widget 17). Dev team `6D3CXG8U25`.
- SPM deps: Firebase iOS SDK 10.28.1 (Auth + Messaging), GoogleSignIn 7.x.
- Framework dep: `composeApp/build/XCFrameworks/debug/composeApp.xcframework`
  (build via `./gradlew :composeApp:assembleXCFramework` — **needs Xcode** for
  the Kotlin/Native linker).
- App Group `group.com.stationly.mobile` (shared with widget). URL scheme for
  Google + Firebase reset deep links already wired.
- Configs: Debug/Release × Staging/Production (`STATIONLY_ENVIRONMENT`).

**Phase 0 checklist once Xcode is installed:**
1. `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`
2. `cd iosApp && ./xcodegen.sh`
3. `./gradlew :composeApp:assembleXCFramework`
4. Open `iosApp/iosApp.xcodeproj`, resolve SPM packages, build to a simulator.
5. Confirm `GoogleService-Info.plist` is real (not placeholder) for the target
   environment; verify APNs entitlement + push works on a real device.
6. Smoke test: launch → login (email + Google) → board renders → ExploreSection
   shows → widget populates. Record real defects here.

---

## 3. Android feature inventory (source of truth)

- **Auth:** login / register / forgot-password / reset-confirm / **verify-email**;
  email+password + Google. (iOS Compose nav has all but **verify-email**.)
- **Selection:** mode → line → direction/station, SDUI-driven, location-aware.
- **Summary (home):** live board, honest "updated X ago" timer, offline /
  signal-lost fallbacks, **ExploreSection** (network status + fares),
  announcement / offline / notification-denied banners, widget & screensaver
  promo cards, pull-to-refresh, profile photo avatar, real logo.
- **Profile:** account info, saved stations, sign-out, theme toggle.
- **Theme:** token-based (`ThemeTokens`), light/dark/system, SDUI-synced via
  `ThemeRepository` (`/sdui/app/theme-tokens`), `LocalThemeTokens`.
- **Dream / screensaver:** Android Daydream — **out of scope for iOS v1.**
- **Widget:** Android `DepartureWidgetProvider` (iOS has its own SwiftUI widget).
- **WebView:** in-app `WebViewScreen` (not ported; iOS hands off to Safari).
- **FCM:** push → SQLite + widget refresh; notification channels/dispatch.

---

## 4. The gap (Android current vs iOS Compose port)

Line counts (Android : Compose) show the drift — Compose is a smaller, older cut:

| Screen | Android | Compose | Note |
|---|---:|---:|---|
| LoginScreen | 1142 | 470 | Largest drift; re-verify flows/validation/copy |
| SelectionScreen | 1158 | 936 | Closest (last aligned 06-01) |
| SummaryScreen | 662 | 391 | ExploreSection + promos + pull-refresh missing |
| ProfileScreen | 883 | 526 | Re-diff fields, saved-station mgmt, theme toggle |

### Root-cause gap: the theme-system migration
Android `ui/theme/` is now: `ThemeTokens` (21 tokens × light/dark) +
`ThemeRepository` (SharedPrefs cache ⊕ SDUI background fetch, applied next cold
launch) + `Theme.kt` `StationlyThemeHost` (provides `LocalThemeTokens`, projects
to `MaterialTheme.colorScheme`). The iOS Compose port had **none of this** — it
hardcoded `darkColorScheme` + fixed `Color()` constants. Every redesigned screen
reads `LocalThemeTokens.current.*` and themed `colorScheme`, so this foundation
must exist first. **Partially landed this session — see §5.**

### Summary delta (fully diffed)
Compose Summary is missing vs Android: ExploreSection ✅(now ported), promo cards
(widget/dream/notification-denied — Android-specific intents, need iOS variants
or omission), `PullToRefreshBox` (Compose uses a manual spinner), profile-photo
avatar (`AsyncImage`), real logo drawable (`R.drawable.stationly_logo` →
needs a CMP `composeResources` asset), `NotificationPermissionEffect`, lifecycle
`ON_RESUME` reload. Compose still renders the **old** `SummaryHeader`
("Live Network" pulse) which Android **removed**.

---

## 5. Landed this session — theme foundation + ExploreSection (compiles for iOS)

New/changed files in `composeApp/src/commonMain`:
- `ui/theme/ThemeTokens.kt` **(new)** — full `ThemeTokens` data class +
  `DefaultDarkTokens` / `DefaultLightTokens` + `LocalThemeTokens`. Ported 1:1
  from Android (kept field set in lockstep with backend `themeService.ts` and
  `core/.../SduiThemeTokens`).
- `ui/theme/AppTheme.kt` **(changed)** — `StationlyTheme` now provides
  `LocalThemeTokens` (dark default) alongside the existing dark `colorScheme`.
  **Additive, zero regression** — existing screens unaffected.
- `ui/common/LocalOpenUrl.kt` **(new)** — `OpenUrl` typealias + `LocalOpenUrl`
  (port of Android `UrlOpener.kt`).
- `App.kt` **(changed)** — provides a real `LocalOpenUrl` backed by
  `LocalUriHandler` (Safari hand-off until an in-app WebView exists).
- `ui/util/MinuteTick.kt` **(new)** — `rememberMinuteTick` (kotlinx.datetime).
- `ui/summary/components/ExploreSection.kt` **(new)** — full port: network-status
  card + fares card + both dialogs. `java.time` → `kotlinx.datetime` for the
  peak/off-peak window maths. **Reuses the same SDUI `strings` (homeConfig) map
  and identical `explore.*` keys as Android**, so one backend template set drives
  both platforms.
- `ui/summary/SummaryScreen.kt` **(changed)** — renders `StationExploreSection`
  as the last LazyColumn item (matches Android position).

Verify: `./gradlew :composeApp:compileKotlinIosSimulatorArm64` → BUILD SUCCESSFUL.
**Not yet visually QA'd** (needs Xcode/simulator) — do that in Phase 0.

---

## 6. Phased plan

### Phase 0 — Build green + defect list  *(in progress)*
Get the app building and running on a real device; produce a defect list.

**Xcode 26 build fixes applied this session** (the project was set up for Xcode
15.4; Xcode 26.5 is now the toolchain). All committed in `iosApp/`:
1. **`xcodegen.sh`** — removed the `objectVersion 77→56` downgrade (an
   Xcode-15.4 workaround). On Xcode 26 the downgrade left `SUPPORTED_PLATFORMS`
   unresolved → no destinations matched. Native `objectVersion = 77` now.
2. **`project.yml` schemes** — added explicit shared schemes
   (`iosApp Production`, `iosApp Staging`). The 4 custom config names left
   xcodegen unable to auto-pick a debug/release config, so it generated **no
   scheme** → Xcode's implicit scheme had empty supported platforms. Build/run/
   archive configs are now pinned per scheme.
3. **`project.yml` Firebase** — bumped `10.28.1` → `from: 11.0.0`. Firebase 10's
   build-time generated module maps fail under Xcode 26 explicit clang modules
   (`GoogleUtilities-NSData.modulemap not found`). 11.x fixes it. (Auth/Messaging
   APIs used by `AuthBridge.swift` / `FCMBridge.swift` are stable across 10→11 —
   verify on first Xcode build.)
4. **`project.yml` `xcodeVersion: "26.0"`**.

**Two transient SPM/Xcode-26 gotchas (handled at build time, not committable):**
- nanopb's checkout ships a Bazel `BUILD` file that collides with the `build/`
  dir Xcode wants there (case-insensitive FS) → "File exists but is not a
  directory." Remove it before building:
  `find <DerivedData>/SourcePackages/checkouts -maxdepth 2 -iname BUILD -type f -delete`
- Firebase writes generated module maps **into its own (read-only) checkout** →
  "Permission denied." Make checkouts writable before building:
  `chmod -R u+w <DerivedData>/SourcePackages/checkouts`
  (Xcode's GUI build generally handles this; the CLI path needed it explicitly.)

**Verified:** shared Kotlin compiles + links for iOS (`assembleXCFramework` ✅);
Swift + Firebase 11 compiles up to the point of needing a run destination.

**Remaining for a device run (needs the user / Xcode GUI):**
- The iOS **platform component isn't installed** in Xcode 26 (only an old iOS
  17.5 simulator runtime exists, too old for Xcode 26). Install via
  **Xcode → Settings → Components → iOS**, or `xcodebuild -downloadPlatform iOS`
  (large download). Required for both device deploy and any simulator.
- **Device deploy:** open `iosApp/iosApp.xcodeproj`, pick scheme
  **`iosApp Production`**, select the iPhone, ensure signing team `6D3CXG8U25`
  + a provisioning profile, Run. (Decision: testing on a physical iPhone, not
  the simulator.)
- Before opening: `./gradlew :composeApp:assembleXCFramework` then
  `cd iosApp && ./xcodegen.sh`.

Smoke test once running: login (email + Google) → board renders →
**ExploreSection** (new) shows network + fares cards → widget populates. Record
defects below.

### Phase 1 — Re-sync core screens to current Android *(the bulk)*
1. **Finish the theme foundation** (started §5): port `ThemeRepository` (SDUI
   theme-token fetch + cache via `core` storage) and a `StationlyThemeHost` that
   supports light/dark/system; migrate screens off hardcoded `Color()` to
   `colorScheme` / `LocalThemeTokens`. Add the Stationly logo to
   `composeApp/.../composeResources/drawable`.
2. **Summary** — remaining items in §4 (promos with iOS-safe actions, pull-to-
   refresh, profile photo, drop the old `SummaryHeader`).
3. **Login** (largest drift) — re-verify every flow, validation, and copy.
4. **Profile** — re-diff account fields, saved-station management, theme toggle.
5. **Selection** — smallest delta; reconcile.

### Phase 2 — Missing surfaces
Verify-email flow into nav; in-app WebView (`WKWebView` via interop) or keep
Safari hand-off; banner/empty/offline/fallback parity.

### Phase 3 — iOS-native polish
Widget parity with redesigned board; notification-tap deep-link to the right
board; dark-mode/safe-area/Dynamic Island; haptics. (Screensaver stays out.)

### Phase 4 — Release readiness
Icons, launch screen, App Store metadata/screenshots, TestFlight, crash/
analytics, prod push-cert validation, privacy manifest.

---

## 7. Porting gotchas (Kotlin/Native — learned this session)

- **No `java.*`** in shared code: `java.time` → `kotlinx.datetime`
  (`Instant.fromEpochMilliseconds`, `toLocalDateTime(TimeZone)`, `LocalTime`/
  `LocalDate` are `Comparable`, `date.plus(1, DateTimeUnit.DAY)`, `daysUntil`).
  No `TextStyle`/`Locale` — map day/month names manually.
- **No `System.currentTimeMillis()`** → `Clock.System.now().toEpochMilliseconds()`.
- **No Android `Context`/`Intent`** → use composition locals (`LocalOpenUrl`,
  `LocalUriHandler`) or `expect/actual` platform providers (pattern already in
  `PlatformAuthProvider`, `LocationProvider`).
- **No Coil `AsyncImage`** in CMP common → need a multiplatform image loader
  (e.g. Coil 3 KMP) or load the avatar in Swift.
- **No `R.drawable`** → put assets in `composeResources/` and use generated `Res`.
- Material icons: `compose.materialIconsExtended` IS available in `composeApp`.
- Verification loop without Xcode:
  `./gradlew :composeApp:compileKotlinIosSimulatorArm64`.

---

## 8. Open questions to confirm later
- Promo cards on iOS: widget-pin has no iOS analog; notification-denied →
  `UIApplication.openSettingsURLString`. Decide which promos survive on iOS.
- In-app WebView vs Safari hand-off (currently Safari).
- Theme: ship light/dark/system on iOS v1, or dark-only to match the current
  port and add light later?
