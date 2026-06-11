# Stationly iOS — Build, Architecture & Handoff

**Audience:** the next engineer/agent picking up the iOS app.
**Last updated:** 2026-06-10 (Session 3). **Branch:** `ios-parity` (off `dev_25Apr`, nothing merged).
**Companion doc:** `docs/IOS_PARITY_PLAN.md` (the phased plan + "⏯️ RESUME HERE").

This doc is the single source of truth for: how the iOS app is structured, how to
build/run it (including every Xcode-26 gotcha we hit), what's been ported, how FCM
and SDUI work on iOS, and what remains.

---

## ⚠️ READ FIRST — Session 3 (2026-06-10): on-device crash fix + composeResources caveat

**The app builds, installs, runs, and was tested on the physical iPhone this
session. It crashed on first frame; the cause is found + fixed (committed). The
shared framework was rebuilding when this was written — rebuild + redeploy (steps
below) to confirm the fix on device.**

### The crash (composeResources are NOT bundled on iOS) — fixed
- Symptom: `SIGABRT` on the first frame, repeatedly. Crash `.ips` faulting thread =
  `org.jetbrains.compose.resources.MissingResourceException` →
  `painterResource(DrawableResource)`, thrown inside `MetalRedrawer.draw`.
- Root cause: **this project never wired Compose-Multiplatform `composeResources`
  packaging for the iOS target.** The generated resources (drawables, fonts) are NOT
  copied into `iosApp.app` (verified: no `*.ttf`, `stationly_logo`, or
  `compose-resources` dir in the built `.app` or the `.xcframework`). Any runtime
  `Res.drawable.*` / `Res.font.*` read throws → crash. (This is exactly why the
  original Login used a *drawn* "S" with a "swap for composeResources later" comment
  — composeResource reads were never validated on iOS.)
- Fix (committed): reverted **every** runtime composeResource read to drawn/system
  versions — logos are drawn "S" marks; `DisplayFamily` (`Type.kt`) is stubbed to
  `FontFamily.Default`. The Inter Tight TTFs (`composeResources/font/`), the logo PNG
  (`composeResources/drawable/`), and the real wiring (commented in `Type.kt`) are
  KEPT for when packaging is fixed. Everything else from Session 3 is intact.

### TODO — actually ship the logo + Inter Tight on iOS (the proper fix)
1. Wire the generated `compose-resources` dir into the **iosApp target's Copy Bundle
   Resources** build phase via `iosApp/project.yml` (xcodegen), so the drawables +
   fonts land in `iosApp.app`. (CMP static-framework resources aren't auto-embedded.)
2. Verify on device that `Res.drawable.stationly_logo` loads (it crashes instantly if
   missing), THEN restore the reads: uncomment `Type.kt` `DisplayFamily` body; swap
   the 3 drawn "S" marks back to `Image(painterResource(Res.drawable.stationly_logo))`
   (Summary top bar + update dialog, Profile top bar) and the wordmarks already pass
   `fontFamily = DisplayFamily`.

### Device build + run pipeline (VERIFIED working 2026-06-10)
Device: **Nick's iPhone, iOS 26.3, UDID `00008030-001E0D9C3EFB802E`**; Xcode 26.5;
signing `Apple Development: nikhilkumar11896@gmail.com`. **Disk is TIGHT (~3–4 GB
free) — do NOT build for the simulator (it ate >1 GB); test on the device.**
```bash
# 1. shared framework (~9 min; only when composeApp/core Kotlin changed)
./gradlew :composeApp:assembleComposeAppDebugXCFramework
# 2. project + SPM
cd iosApp && ./xcodegen.sh
DD=build/DD
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" -derivedDataPath "$DD" -resolvePackageDependencies
chmod -R u+w "$DD/SourcePackages/checkouts"; find "$DD/SourcePackages/checkouts" -maxdepth 2 -iname BUILD -type f -delete
# 3. build (signed) + install + launch
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" -destination 'id=00008030-001E0D9C3EFB802E' -derivedDataPath "$DD" -allowProvisioningUpdates build
xcrun devicectl device install app --device 00008030-001E0D9C3EFB802E "$DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device process launch --device 00008030-001E0D9C3EFB802E com.stationly.mobile
```
Crash logs: `idevicecrashreport -u 00008030-001E0D9C3EFB802E -k /tmp/crashes`, then
parse the newest `iosApp-*.ips` (faulting thread + `lastExceptionBacktrace` give the
Kotlin exception). NOTE: `idevicescreenshot` / `idevicesyslog` do NOT work on iOS 26
(CoreDevice flow); use the `.ips` crash reports for diagnosis.

---

## 1. What the iOS app actually is

It is **NOT** a native-Swift rewrite. It is **Kotlin/Compose Multiplatform**:

| Module | Language | Role | Targets |
|---|---|---|---|
| `core/` | Kotlin | Business logic, models, repositories, SQLDelight DB, Ktor, use cases, SDUI models | common, android, ios, wasmJs |
| `composeApp/` | Kotlin + Compose | **Shared UI** (login, selection, summary/board, profile, theme) | common, android, ios |
| `iosApp/` | Swift | Thin host: app shell + Firebase Auth, Google Sign-In, FCM/APNs, **SwiftUI widget** | iOS |
| `android/` | Kotlin | **Standalone native Android app** — the design source of truth | Android |
| `web/` | Kotlin/Wasm | Web app | wasmJs |

The iOS app = `core` + `composeApp` rendered through
`MainViewController` (`ComposeUIViewController`) → `ComposeHostView`
(`UIViewControllerRepresentable`) in `ContentView.swift`. Swift owns only what
Kotlin/Native can't: Firebase Auth (`AuthBridge.swift`), FCM
(`FCMBridge.swift`, `AppDelegate.swift`), and the home-screen widget
(`StationlyWidget/`).

**`android/` is the design reference. We do not modify it.** We port its screens
into `composeApp/` (the shared module that iOS renders). Android renders the same
shared screens too, but historically had its own native screens — the redesign we
chased lives in `android/.../ui/`.

### Why some things differ from Android by necessity
- Android's **dot-matrix board is rendered with `AndroidView` + XML** (`R.layout.widget_departure_board`) — impossible on iOS. The iOS board is a **pure-Compose reimplementation** of that XML (see §6).
- No `java.*` in shared Kotlin → `kotlinx.datetime`, `Clock.System` instead of `java.time` / `System.currentTimeMillis`.
- No Coil image loader in common → avatar/logo are drawn placeholders for now.
- No Android `Context`/`Intent` → composition locals (`LocalOpenUrl`, `LocalUriHandler`) or `expect/actual` providers.

---

## 2. How to build & run on a device (Xcode 26)

The project was authored for Xcode 15.4; the machine now runs **Xcode 26.5**.
Several things had to change — all committed. **Decision: test on a physical
iPhone with the STAGING backend** (`iosApp Staging` scheme).

### One-time machine setup
1. Accept license + first-launch: `sudo xcodebuild -license accept` then `sudo xcodebuild -runFirstLaunch`.
2. **Install the iOS platform component** (the SDK ships with Xcode, but the
   device-support/runtime is a separate ~8 GB download):
   Xcode → Settings → Components → iOS, or `xcodebuild -downloadPlatform iOS`.
   Without it, `xcodebuild` errors `iOS 26.x is not installed` for every destination.

### Build + install (staging, device)
```bash
# 1. shared framework (device + simulator). SLOW (~8 min) — only when composeApp/core changed.
./gradlew :composeApp:assembleComposeAppDebugXCFramework      # debug-only, faster than assembleXCFramework

# 2. regenerate the Xcode project (idempotent; needed when project.yml changes)
cd iosApp && ./xcodegen.sh

# 3. resolve + clean SPM checkouts (see "gotchas" below)
DD=build/DD
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" -derivedDataPath "$DD" -resolvePackageDependencies
chmod -R u+w "$DD/SourcePackages/checkouts"
find "$DD/SourcePackages/checkouts" -maxdepth 2 -iname BUILD -type f -delete

# 4. build for the connected iPhone (device id from `xcrun xctrace list devices`)
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" \
  -destination 'id=<DEVICE_ID>' -derivedDataPath "$DD" -allowProvisioningUpdates build

# 5. install + (optional) launch
APP="$DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device install app --device <DEVICE_ID> "$APP"
```
**First install only:** on the iPhone, Settings → General → VPN & Device
Management → trust the developer cert, then reopen the app. (Current device id:
`00008030-001E0D9C3EFB802E`; signing team `6D3CXG8U25`; Apple ID
`nikhilkumar11896@gmail.com`.)

### Xcode-26 fixes already applied (committed)
1. **`iosApp/xcodegen.sh`** — removed the `objectVersion 77→56` downgrade (an
   Xcode-15.4 workaround that left `SUPPORTED_PLATFORMS` empty on Xcode 26 →
   "can't find destination"). Native objectVersion 77 now.
2. **`iosApp/project.yml` → `schemes:`** — added explicit shared schemes
   (`iosApp Production`, `iosApp Staging`). The 4 custom config names
   (Debug/Release × Staging/Production) meant xcodegen generated **no** scheme,
   so Xcode's implicit scheme resolved to empty supported platforms.
3. **`iosApp/project.yml` → Firebase `from: 11.0.0`** (was pinned `10.28.1`).
   Firebase 10's build-time generated module maps fail under Xcode 26 explicit
   clang modules (`GoogleUtilities-NSData.modulemap not found`).
4. **`xcodeVersion: "26.0"`**.

### Per-build SPM gotchas (not committable; handle in the build script / CI)
- **nanopb** ships a Bazel `BUILD` file that collides (case-insensitive FS) with
  the `build/` dir Xcode wants → `File exists but is not a directory`. Delete it
  before building (step 3 above).
- **Firebase** writes generated module maps **into its own (read-only) SPM
  checkout** → `Permission denied`. `chmod -R u+w` the checkouts first (step 3).
- The Xcode GUI build generally handles both; the CLI path needs them explicit.

### Environments
`iosApp Staging` → `STATIONLY_ENVIRONMENT=staging` → `AppConfig` points at
`staging-api.stationly.co.uk` / `staging.stationly.co.uk`. `iosApp Production`
→ prod. One real Firebase project (`mindthetimefcm`) is shared across envs
(auth/Google/FCM work regardless — the env only switches the Stationly backend).

---

## 3. Architecture & data flow

```
Compose UI (composeApp)                 core (shared)                 platform
─────────────────────                   ─────────────                 ────────
SummaryViewModel ─reads─▶ Platform.sqlStorage (SQLDelight) ◀─writes─ SyncPredictionsUseCase
   │  (30s poll + on-resume)                 ▲                              ▲
   ├─ predictions ─▶ Board (dot-matrix)      │                       ProcessFcmPayloadUseCase
   ├─ lineStatuses ─▶ ExploreSection         │                              ▲
   └─ homeConfig (SDUI strings) ◀─ NetworkModule.sduiApi (Ktor)      FcmPayloadBridge (iosMain)
                                                                            ▲
ProfileViewModel / LoginViewModel ─▶ PlatformAuthProvider ─▶ AuthBridge.swift (Firebase)
                                                                            ▲
Widget (SwiftUI) ◀─ App Group NSUserDefaults ◀─ IosWidgetManager.updateWidget  AppDelegate (APNs/FCM)
```

**Local-first / minimal-Firestore:** the client never touches Firestore directly.
It reads/writes **local SQLite** (SQLDelight) + in-memory `StateFlow`s, and talks
to the Stationly backend over Ktor (SDUI + sync endpoints). FCM pushes deltas that
are written to SQLite once; the UI polls SQLite. **No change in this branch adds a
Firestore read/write.** Keep it that way — surface new data via the backend SDUI
endpoints + SQLite, not direct client→Firestore.

---

## 4. SDUI — the app is server-driven; keep it that way

Every screen we ported **reuses the same SDUI contract as Android** (same backend,
same keys):
- **Login** renders its form from `uiState.layout.components` (SDUI
  `Input`/`Button` with `validation`/`condition`); actions map to the VM.
- **Home strings** (`homeConfig` = `sduiApi.getHomeConfig().strings`) drive board
  labels, ExploreSection (`explore.*`), promos, dialog copy.
- **Profile About** renders `sduiApi.getAboutLayout()` (Card/Section/LinkRow),
  falling back to a hardcoded layout when offline.
- **Board** renders `sduiPayload` (SDUI `Header`/`Row`/`Message`) when present,
  else `GlobalBoardProcessor.prepareLegacyRows`.
- **Theme** is SDUI-synced: `ThemeRepository` fetches `getThemeTokens()` and
  caches it; applied next launch.

**Rule for new work:** if Android drives it via SDUI, the iOS port must read the
same SDUI keys/endpoints — do not hardcode strings/layout that the backend owns.

---

## 5. FCM → board/widget update flow (verified)

```
APNs / FCM push
 → AppDelegate (willPresent / didReceive / didReceiveRemoteNotification)
 → processFcmPayload(userInfo)  →  FcmPayloadBridge.shared.processPayload(json)   [Swift]
 → composeApp.FcmPayloadBridge  →  core FcmPayloadBridge (Platform.ios.kt)        [KMP]
 → ProcessFcmPayloadUseCase.invoke(payload):
       • departureRepository.processFcmPayload()  → writes predictions to SQLite
       • widgetManager.updateWidget(state)        → writes departures to App Group
                                                    + bumps widget_reload_signal
```
Then:
- **In-app board** — `SummaryViewModel` polls SQLite every **30 s** (+ refresh on
  app foreground); ETAs self-tick each minute via `rememberMinuteTick`.
- **Widget** — `WidgetReloadObserver` (AppDelegate) polls `widget_reload_signal`
  every **5 s** → `WidgetCenter.reloadAllTimelines()` → re-reads the App Group.

**Known latency:** silent FCM takes up to ~30 s to appear in-app (vs Android's
near-instant FreshDataNotifier push). Improvement options (not yet done): shorten
the poll, or have `ProcessFcmPayloadUseCase` emit an in-memory "fresh data" signal
the `SummaryViewModel` collects. Immediate on foreground already.

---

## 6. The dot-matrix departure board (the core visual)

Android renders it via `AndroidView` inflating `widget_departure_board.xml` (also
used by the Android widget). The iOS board (`composeApp/.../summary/components/Board.kt`)
is a **pure-Compose reproduction** of that XML:

- **Chrome on the themed canvas** (light/dark aware): line-colour pill + delete
  trash; disruption banner (themed danger, expandable); **Next-Departure hero**
  (themed surface, line-tint wash, big monospace ETA + depletion bar).
- **Dark signage panel** — locked TfL amber `#FFC819` regardless of app theme
  (signage never themes; matches Android's `@color/tfl_amber`):
  - Centered **TfL roundel** (`Canvas`-drawn ring + bar, tinted per mode) + station name.
  - Centered **platform-section headers** (`Northern: Platform 3 (Northbound)` via
    `StationlyFormatters.platformHeaderText` + `formatLinePrefix`).
  - Departure rows: destination (left) + ETA (right), all amber, Due in red.
  - Status strip: `severity : reason` (reason scrolls via `basicMarquee`).
  - Footer: roundel mark + **live ticking clock** (HH:mm:ss, per-second) + `M:SS ago`.
- **Self-ticking ETAs** via `rememberMinuteTick` + `StationlyFormatters.formatMinutesRemaining`.
- **SDUI-first**: renders `sduiPayload` components when present; else
  `GlobalBoardProcessor.prepareLegacyRows`.

The **SwiftUI widget** (`iosApp/StationlyWidget/WidgetViews.swift`) was restyled to
match: roundel + station header, platform-grouped sections, all-amber rows, status
+ "ago" footer. **Caveat:** the App-Group payload is a *flat* `[DepartureRow]`
(destination/platform/eta/isDue/stopLetter) — it has no direction or line prefix —
so the widget shows `Platform N`, not `Northern: Platform 3 (Northbound)`. Matching
Android fully needs `IosWidgetManager.updateWidget` (`core/.../Platform.ios.kt`) to
write the grouped SDUI structure. Follow-up.

---

## 7. What's been done (branch `ios-parity`)

| Commit | Area |
|---|---|
| `4f26fee` | Theme tokens + ExploreSection port + Xcode-26 build fixes |
| `9a6b2f1` | Full light/dark/system theme + SDUI token sync (ThemeRepository/Host/toColorScheme) |
| `f7081f5` | **Login** — redesigned landing + SDUI form + reset-confirm |
| `144972d` | **Profile** — header, My Stations, SDUI About, sign-out/delete dialogs |
| `8dd595b` | **Summary** — theme migration, dropped stale header |
| `5870019` | **Selection** — theme-aware palette |
| `5fe713c` | **EmptyStates** — theme-aware first-launch |
| `c70786f` | **Board** — pure-Compose dot-matrix departure board |
| `b29a5c9` | **Widget** — dot-matrix restyle to match Android |
| `7fbf91d` | cleanup (dead `SummaryHeader`, stray imports) + this doc |
| `04de5da` | **widget FCM fix** + **iOS push transitions** (see below) |

Each Kotlin commit was verified with `./gradlew :composeApp:compileKotlinIosSimulatorArm64`;
the device build + install succeeds (staging). The app + widget are running on the
physical iPhone.

---

## 7b. Landed in `04de5da` (session 2b)
- **Widget now updates on FCM** — root cause: `ProcessFcmPayloadUseCase` looked up
  the primary selection via `storageManager.loadSelections()`, which is **never
  written on iOS** (selections persist to SQLite only via `SelectionRepository →
  sqlStorage`). Added an optional `sqlStorage` fallback to the use case (default
  `null` so Android call sites are untouched) and pass `Platform.sqlStorage` from
  the iOS `FcmPayloadBridge`. Also: `AppDelegate.processFcmPayload` now calls
  `WidgetCenter.reloadAllTimelines()` ~2 s after a push (the foreground 5 s
  `WidgetReloadObserver` doesn't run in the background).
- **iOS push/pop transitions** — `AppNavigation` `NavHost` now slides screens in
  from the right / out to the left (reversed on back) for a native feel. Board
  content untouched.

## 7c. Landed in Session 3 (2026-06-10) — branch `ios-parity`

Per-screen commits, each `compileKotlinIosSimulatorArm64`-verified. **No
`android/` changes** (design source of truth untouched).

- **Selection screen RE-PORTED** (was the §8 top-priority gap). Replaced the
  stale pre-redesign Compose `SelectionScreen` with a faithful port of the
  redesigned `android/.../ui/selection/SelectionScreen.kt`:
  - SDUI `sdText()` with `{mode}`/`{station}`/`{line}` interpolation + the
    context-aware vocabulary helpers (bus→stops/routes/Buses, rail→stations/
    lines/Trains) + `summariseDestinations`.
  - `StepHeader` carries the picked mode's roundel forward (coil3 AsyncImage).
  - branch-aware `DirCard`: "towards {next station}" headline, tappable
    destination chips that swap the stops timeline, rail-only compass badge, the
    `e488e81` shape-jump fix (compass header gated on compass presence + pinned
    height, never on `sel`), routes-split hint. Dropped the removed `DirFunFact`.
  - theme-aware backgrounds (killed the hardcoded black band in light mode);
    solid-primary CTA; `onSurface`/`onSurfaceVariant`/`onPrimary` text colours.
  - kept the iOS VM wiring (`onDropdownSelected`, separate flows), `parseColorSafe`.

- **Summary screen parity**: real Material3 `PullToRefreshBox`; **real
  `stationly_logo`** via composeResources (`Res.drawable.stationly_logo`, PNG
  copied from `android/.../res/drawable/`) in top bar + update dialog; **profile
  photo avatar** in the top bar via coil3 (`firebase_user_photo_url`), monogram
  fallback (`SummaryUiState.photoUrl` added); **floating `ThemeToggleButton`**
  bottom-right — the light/dark/system selector — **newly ported**
  `ui/common/ThemeToggleButton.kt` into shared composeApp; lifecycle `ON_RESUME`
  → `reloadSelectionsFromDb()` (cross-screen consistency + pulls FCM-written rows
  from SQLite on foreground); themed `UpdateNudgeDialog`.

- **Profile screen parity**: **photo avatar** in `ProfileHeaderCard` via coil3
  (`uiState.photoUrl` was already loaded; was monogram-only); real
  `stationly_logo` in the top bar; Google provider badge uses `AlternateEmail`.

- **Departure board + widget — spot-clean parity** (the product-critical surface):
  - **In-app board** (`composeApp/.../summary/components/Board.kt`): the rows now
    `verticalScroll` inside the capped panel — a many-platform station (Bank,
    King's Cross) no longer clips departures; the station strip + status + footer
    stay pinned (matches Android's ScrollView). The dot-matrix structure
    (roundel + station header, line-prefixed platform headers, amber rows / red
    Due, marquee status, footer roundel + per-second clock + "M:SS ago") was
    already faithful from Session 2.
  - **SwiftUI widget** (`iosApp/StationlyWidget/`): rebuilt to read like the
    Android board. Platform headers now render `Piccadilly: Platform 1
    (Eastbound)` — added optional `WidgetState.direction` (default `""`, so every
    call site incl. `android/` is unchanged), populated by composeApp
    `SummaryViewModel` + `FormatDeparturesUseCase`, written as `widget_direction`
    by `IosWidgetManager`, and assembled by `WidgetData.platformHeader()`. Real
    line status (severity : reason) is now written from the poll (was always
    null). **Live ticking "M:SS ago"** via WidgetKit `Text(_:style:.timer)` — the
    iOS analog of Android's `Chronometer` (self-updates, no timeline reload).
    Roundel marks, lit-cell rows, per-family caps (small 3 / medium 4 / large 9).
  - **iOS platform constraints (documented, not bugs):** WidgetKit widgets are
    static snapshots — they **cannot scroll** and **cannot run animations**.
    The footer wall clock WAS worked around (Session 6, see
    `docs/IOS_WIDGET_DESIGN.md`): it ticks per second via a `.timer` Text
    anchored at midnight (elapsed-since-00:00 IS the time of day). The status
    reason does NOT marquee: a once-per-minute stepped scroll (the WidgetKit
    ceiling) was built, rejected as worse than static, and removed — the
    reason truncates with a tail. The widget shows a fixed row set per family.

- **Font — Inter Tight bundled** (`Type.kt` `DisplayFamily`): the brand wordmark
  on Summary + Profile now uses Inter Tight, matching Android. Android loads it
  via GMS Downloadable Fonts (no iOS provider), so the official OFL variable font
  was sliced into 6 static weights with `fonttools` and bundled in
  `composeResources/font/inter_tight_*.ttf` (license in
  `composeApp/THIRD_PARTY_LICENSES/`). Only affects shared `composeApp` (iOS); the
  shipping `android/` app keeps GMS fonts → no APK bloat. **Available for broader
  use** — apply `fontFamily = DisplayFamily` to more headlines/section headers to
  extend the match (Login brand mark is a good next target).

### Notable finding
- **Coil 3 IS already a composeApp dependency** (`coil3.compose.AsyncImage`); the
  earlier "no network images" caveat is obsolete. Avatars / mode-roundels / logos
  all load fine, and the brand logo is now a real bundled asset.

## 7d. Landed in Session 4 (2026-06-10, afternoon) — branch `ios-parity`

Four commits, each compile/typecheck-verified. **No `android/` changes.**

1. **`iOS auth` commit — the big functional batch:**
   - **Google login race FIXED**: Swift AuthBridge clears `auth_pending_command`
     immediately on receipt, but KMP treated key-vanishing as completion → the
     interactive flow "failed" while the user was in the Google sheet (second
     tap worked because the token had landed by then). New protocol key
     `auth_command_done` is Swift's LAST write for every command; KMP polls it
     (30 s default, 180 s for `googleSignInInteractive`).
   - **Backend session sync ported** (was completely missing on iOS): composeApp
     `LoginViewModel.syncUserAndSetupData` mirrors Android — `/user/sync/profile`
     with `deviceId`+`deviceInfo` (new `DeviceIdentity` expect/actual; the id
     lives in the APP-GROUP suite because logout's `storageManager.clearAll()`
     wipes the standard domain), station restore, primary-station setup (FCM
     topics + initial fetch + widget), `registerFcmToken(platform="ios")`,
     rollback-and-sign-out when the backend is unreachable. **Deliberately does
     NOT call `cleanupAll()`** post-login (on iOS it would wipe the
     `firebase_user_*` identity keys Swift just wrote — the "Stationly user"
     placeholder culprit).
   - **Identity reload**: SummaryViewModel re-reads name/photo on every
     ON_RESUME (fixes stale "?" avatar / "Stationly user").
   - **Profile edit-name**: `updateDisplayName` AuthBridge command +
     `PlatformAuthProvider.updateDisplayName` + dialog ported from Android +
     backend mirror via `syncProfile`.
2. **`iOS home` commit**: AnnouncementBanner + OfflineBanner theme-token tints
   (the "staging banner not theming" was the SDUI announcement); theme toggle
   truly bottom-right (Scaffold padding already includes the bottom safe area on
   iOS — the extra `navigationBars` inset double-applied); whole-screen
   rubber-band pull-to-refresh (`graphicsLayer` translation with friction);
   **in-app browser** — `openUrlInApp` expect/actual presents
   `SFSafariViewController` for http(s), external for mailto:/store links.
3. **`iOS board` commit**: square LED strips with faint dot lattice (Android's
   active row is a tiled pixel bitmap, not rounded chips), monospace tabular
   ETAs, 13 sp single-line platform headers, 22 dp mode roundel, Stationly red
   "S" maker mark footer-left + clock on a lit strip.
4. **`iOS widget` commit**: footer = S mark left / minute-accurate clock CENTER
   / "M:SS ago" together right (fixed the `.timer` greedy-expansion layout bug);
   per-minute timeline entries for the clock (~24 scheduled refreshes/day);
   platform-header dedup ("Platform Platform 2 (Westbound) (Inbound)");
   mode-tinted header roundel via new `WidgetState.mode` → `widget_mode`;
   square LED cells + dot lattice; **`UIBackgroundModes: remote-notification`
   added** (silent FCM was never delivered in background — the root cause of
   "widget doesn't update unless I open the app") + explicit `aps-environment`
   entitlement. True no-app-wake widget updates = iOS 26 WidgetKit push tokens;
   needs backend APNs work (follow-up; FCM topics can't target widget tokens).

**Syncer prerequisite for background widget refresh:** the Station_*/
LineStatus_* FCM messages must carry APNs `content-available: 1` (data-only FCM
to iOS is otherwise not delivered to backgrounded apps even with the background
mode). Verify in StationlySyncer's send path.

## 7e. Landed in Session 5 (2026-06-11) — branch `ios-parity`

**Theme: make FCM→widget actually work (the core product promise) + widget/app
visual parity.** All Kotlin compile-verified (`compileKotlinIosSimulatorArm64`),
widget Swift typecheck exit 0. **No `android/` changes; the Syncer repo was NOT
touched (product owner: StationlyUI only).**

### 1. FCM→widget chain — three root causes found + fixed

**(a) The client decoded the WRONG JSON shape (the big one).** The Syncer sends
data-only FCM: `putData("payload", json)` on topics `Station_<id>` /
`LineStatus_<mode>_<lineId>` (see StationlySyncer FcmService/LineService —
read-only reference). On iOS the whole APNs `userInfo` dict was serialised and
decoded directly as `FcmPayload` — but the real payload is a JSON *string* under
the `"payload"` key (exactly Android's `remoteMessage.data["payload"]`), so
EVERY real push threw MissingFieldException and was silently dropped.
`FcmPayloadBridge` (core `Platform.ios.kt`) now parses the envelope: `from` →
topic routing, `payload` → typed decode. LineStatus_* pushes (previously
ignored entirely on iOS) now route to `processLineStatusUpdate`.

**(b) `ProcessFcmPayloadUseCase` rewritten to mirror Android's
FcmMessagingService** (core, commonMain — Android only *constructs* this class,
never calls it, and the 4-arg constructor is unchanged, so `android/` is
source-compatible):
- `processStationUpdate(topicStationId, payload)` — selections matched by
  topic station id first (child-stop-id mismatch handling), then payload.id;
  predictions written to SQLite per matching selection via
  `SyncPredictionsUseCase` (the old path never wrote SQLite — only an
  in-memory flow!); widget rewritten ONLY when the primary selection was
  affected (old code blanked the widget on any push for a non-primary station).
- `processLineStatusUpdate(status)` — saves to SQLite, refreshes the widget
  status strip when the primary selection rides that line.
- `refreshWidgetFromStorage` rebuilds the exact WidgetState shape the
  SummaryViewModel poll writes (status "Severity: reason", lastUpdated
  SECONDS) so the widget never flips format per trigger.

**(c) Xcode's project-upgrade had REVERTED the push entitlement.** Opening the
project in Xcode 26.5 rewrote pbxproj/entitlements and dropped
`aps-environment` → no APNs token → no FCM at all. `./xcodegen.sh` regenerates
correctly from project.yml (which has the right values). **Rule: never commit
Xcode-initiated edits to generated files; always re-run xcodegen.**

**Swift side (AppDelegate/FCMBridge):**
- Background `didReceiveRemoteNotification` now AWAITS KMP
  (`FcmPayloadBridge.processPayloadAndWait(jsonString:completionHandler:)` — new
  suspend bridge) before `WidgetCenter.reload` + completionHandler — the old
  fire-and-forget +2 s reload raced background suspension.
- `Messaging.appDidReceiveMessage(userInfo)` called for FCM bookkeeping.
- Topic subscriptions queued by KMP now flush IMMEDIATELY via a debounced
  `UserDefaults.didChangeNotification` observer (was: only on token receipt /
  next foreground — adding a station mid-session didn't subscribe until app
  switch).
- Token rotation re-subscribes ALL topics from the `fcm_topics` ledger
  (`FCMBridge.resubscribeAllTopics`) — parity with Android `onNewToken`.

**Still required for background delivery (out of our repo):** FCM v1
data-only messages are delivered to iOS as background pushes (content-available)
automatically, BUT iOS throttles silent pushes (~a handful/hour budget) and
delivers none if the user force-quits the app. True no-app-wake widget refresh
= iOS 26 WidgetKit APNs push tokens (backend work, still a follow-up). Also
verify in Firebase Console that an **APNs auth key** is uploaded for
com.stationly.mobile (mindthetimefcm project) — without it APNs delivery fails
silently regardless of client correctness.

### 2. Widget redesign (WidgetViews.swift rewrite)
- **Type hierarchy (product owner spec):** station biggest > platform header >
  departure rows > status strip; footer clock ≈ station size; "ago" smallest.
  Implemented as per-family `BoardMetrics` (medium/large scales; small has its
  own compact set).
- **Fills the whole canvas:** every LitCell is height-flexible
  (`maxHeight: .infinity` + minHeight floors + layoutPriority) so the board
  stretches edge-to-edge with no dead band; cells have continuous-corner
  radius 5/6 ("well radiused" ask). The TfL dot-matrix identity (lit strips +
  dot lattice + amber-on-black) is INTENTIONAL — it's the real-station-board
  look; do not "modernise" it away.
- **Real Stationly logo** (`Assets.xcassets/StationlyLogo.imageset`, downscaled
  from android/res stationly_logo.png) replaces the drawn disc in footer +
  empty state.
- **Real mode roundels**: `ModeIconProvider` (AppGroupStorage.swift) reads
  `mode_icons/<mode>.png` + `tints.json` from the App Group — written by the
  new KMP ModeIconStore (below). Fallback chain identical to Android:
  cached PNG → backend tint → hardcoded mode colour.
- `containerBackgroundRemovable(false)` + `widgetAccentable()` on the station
  lockup for tinted/StandBy rendering modes.

### 3. ModeIconStore (composeApp expect/actual) — backend mode icons on iOS
- common: `ModeIconStore.sync(entries, iconVersion)` / `hasIcon` /
  `cachedIconBitmap`; iosMain actual writes the App-Group
  `mode_icons/` layout (same file names as Android's ModeIconCache;
  `safeName` must stay in lockstep with ModeIconProvider.swift); androidMain
  actual is a no-op (the shipping Android app has its own cache).
- Synced from composeApp SelectionViewModel.loadModes() (like Android) +
  SummaryViewModel `maybeWarmModeIcons` safety net (cloud-restore path), which
  re-pushes the widget after icons land.
- In-app Board station strip now renders the cached bitmap (drawn roundel
  fallback).

### 4. composeResources packaging FIXED (the Session-3 crash, properly)
- `iosApp/project.yml` → `postBuildScripts: Copy Compose Resources` copies
  `composeApp/build/generated/compose/resourceGenerator/assembledResources/
  ios{Arm64|SimulatorArm64}Main/` → `<app bundle>/compose-resources/` (the
  exact layout `Res` resolves). Run
  `./gradlew :composeApp:assembleIosArm64MainResources` before xcodebuild
  (chained into the deploy steps below).
- **Crash-proof gate:** new `composeResourcesBundled` expect/actual checks the
  bundle dir ONCE at runtime; every Res.* read is gated on it, so a stale
  build degrades to drawn logo/system font instead of SIGABRT.
- Restored: `Type.kt` DisplayFamily (real Inter Tight), real
  `stationly_logo` in Summary top bar + update dialog (via new shared
  `ui/common/StationlyLogo.kt`), Profile top bar, Login brand mark, Board
  footer maker mark.

### 5. App icon — the app had NONE
- `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset` (single 1024, alpha
  flattened for App Store). xcodegen picks xcassets up automatically.

### Deploy to device (Session-5 ready sequence)
```bash
./gradlew :composeApp:assembleComposeAppDebugXCFramework :composeApp:assembleIosArm64MainResources
cd iosApp && ./xcodegen.sh
DD=build/DD
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" -derivedDataPath "$DD" -resolvePackageDependencies
chmod -R u+w "$DD/SourcePackages/checkouts"; find "$DD/SourcePackages/checkouts" -maxdepth 2 -iname BUILD -type f -delete
xcodebuild -project iosApp.xcodeproj -scheme "iosApp Staging" -destination 'id=00008030-001E0D9C3EFB802E' -derivedDataPath "$DD" -allowProvisioningUpdates build
xcrun devicectl device install app --device 00008030-001E0D9C3EFB802E "$DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device process launch --device 00008030-001E0D9C3EFB802E com.stationly.mobile
```
**On-device QA checklist:** (1) logo renders (if drawn "S" appears, the copy
phase didn't run — check build log for "Copied compose-resources");
(2) Inter Tight wordmark; (3) widget shows real mode roundel after opening
Selection or ~1 min after Summary (warm-up); (4) trigger a Syncer push (or
wait for a real one) with app FOREGROUND → board updates instantly, widget
within ~5 s; (5) background the app → next push should refresh the widget
(subject to iOS silent-push budget — allow minutes, not seconds); (6) widget
fills its canvas at small/medium/large with the new type hierarchy.

### ⚠️ Session-5 deploy discoveries (READ before touching push or the bridge)

**1. PUSH IS BLOCKED ON SIGNING, NOT CODE.** Team `6D3CXG8U25` is a PERSONAL
(free) Apple development team — Apple forbids the Push Notifications
capability on personal teams, so provisioning fails whenever `aps-environment`
is in the entitlements ("Personal development teams … do not support the Push
Notifications capability"). This is also why Xcode kept auto-stripping the
entitlement (the mystery working-tree revert). Modern firebase-ios-sdk has no
non-APNs channel → with this team, FCM can NEVER deliver to the app,
foreground or background, regardless of the client fixes. Product owner
decision 2026-06-11: push DISABLED for now (`aps-environment` removed from
project.yml with an UNBLOCK comment). To enable push: paid Apple Developer
Program team → set DEVELOPMENT_TEAM → upload APNs auth key (.p8) to Firebase
console (mindthetimefcm → Cloud Messaging → Apple apps) → restore
`aps-environment: development` in project.yml. All client-side FCM fixes from
this session stay correct and dormant until then.

**2. K/N suspend-function export quirk.** `FcmPayloadBridge.processPayloadAndWait`
(core, suspend) compiles but is NOT in the framework's ObjC header — Kotlin/
Native only generates completionHandler bridging for suspend functions in the
ROOT framework module (composeApp); exported dependency modules (core) get
classes/plain funcs only. Fix next session: add a thin composeApp-iosMain
wrapper object delegating to it, then call that from AppDelegate (currently
falls back to fire-and-forget `processPayload` + 2.5 s held completion).

**3. Device deploy VERIFIED 2026-06-11:** build + install + launch succeeded
on Nick's iPhone (staging scheme, push-free provisioning); the
"Copy Compose Resources" phase ran (`Copied compose-resources (iosArm64Main)`
in the build log) → real logo + Inter Tight are in the bundle.

### Known-remaining (Session 5 could not finish)
- Profile sometimes shows "User"/no photo (identity key race) — NOT yet
  root-caused this session; reproduce with fresh sign-in then cold relaunch.
- In-app board "big and stretched" feedback only partially addressed (mode
  roundel + logo landed; spacing/scale pass on Board chrome + SummaryScreen
  still open).
- Google "G" glyph still drawn on Login.
- iOS 26 WidgetKit push tokens for true background widget refresh (backend).

## 8. What's REMAINING (priority order)

1. **On-device QA of the rebuilt board + widget (TOP — needs the iPhone).** The
   board + widget were rebuilt this session (§7c "Departure board + widget") and
   compile + Swift-type-check, but have **not been rendered on a device**. Verify:
   in-app board scroll + colours vs Android; the widget at **systemSmall / medium
   / large** — row counts, truncation, the line-prefixed headers (`Piccadilly:
   Platform 1 (Eastbound)`), and the live "ago" ticking. If anything overflows,
   tune the per-family caps in `WidgetViews.swift` (small 3 / medium 4 / large 9).
   (Build the widget via Xcode — `swiftc -typecheck` passes but can't render.)
2. **FCM in-app immediacy** — optional fresh-data signal so the in-app board
   updates instantly instead of on the 30 s poll / foreground reload.
3. **On-device visual QA pass** — colours/spacing/alignment vs Android, screen by
   screen (esp. the re-ported Selection + Summary). Needs the device build (§2).
4. **Login email-verification flow** — Android has `VerifyEmailScreen` +
   `onNeedsEmailVerification`; not ported. Add to nav + `LoginViewModel` if needed.
   Also re-verify Login flows/validation/copy vs the 1142-line Android screen.
5. **Profile edit-display-name** — Android has an edit-name dialog +
   `updateDisplayName`. NOT ported: needs `PlatformAuthProvider.updateDisplayName`
   + a Swift `AuthBridge` command (Firebase `createProfileChangeRequest`).
   Deferred — crosses the KMP/Swift boundary.
6. **Google "G" glyph** — still a drawn "G" on the Login Google button. Add
   `ic_google_standard` to composeResources and use `Res.drawable.*` (logo done).
7. **In-app WebView** — iOS hands links to Safari via `LocalOpenUrl`; port a
   `WKWebView` route if in-app browsing is wanted.
8. **Light-mode polish on shared toasts** — `OfflineBanner.kt`,
   `AnnouncementBanner.kt`, `SduiRenderer.kt` still hardcode dark. (Board's
   dot-matrix panel is intentionally always-dark signage — leave it.)
9. **iOS-native polish** — haptics on key interactions (selection commit, delete,
   pull-refresh), refine transitions, safe-area / Dynamic-Island correctness.
10. **App Store readiness** — icons, launch screen, prod Firebase plist decision,
    TestFlight, privacy manifest.

---

## 9. Conventions for the next agent
- Branch `ios-parity`, **per-screen commits**, messages explain the why.
- Verify every Kotlin change: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`.
- Theme-migration pattern: convert a screen's central palette to
  `@Composable get() = MaterialTheme.colorScheme.*` (names unchanged so call sites
  don't churn). Reads inside non-`@Composable` lambdas (`derivedStateOf`) must
  capture the colour in composable scope first.
- Keep it **SDUI-driven** and **local-first / zero direct Firestore**.
- Don't touch `android/` (design reference) or the backend (separate repo).
