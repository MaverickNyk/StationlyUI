# Stationly iOS — Build, Architecture & Handoff

**Audience:** the next engineer/agent picking up the iOS app.
**Last updated:** 2026-06-14 (Session 8). **Branch:** `ios-parity` (off `dev_25Apr`, nothing merged).
**Companion doc:** `docs/IOS_PARITY_PLAN.md` (the phased plan + "⏯️ RESUME HERE").

This doc is the single source of truth for: how the iOS app is structured, how to
build/run it (including every Xcode-26 gotcha we hit), what's been ported, how FCM
and SDUI work on iOS, and what remains.

> **➡️ FRESH AGENT: start at §7g (Session 8, 2026-06-14)** — latest state (the
> dot-matrix board font + the in-progress home-screen polish); then §7f (Session
> 6) for the deploy runbook context, the owner's hard constraint (no `android/`,
> no shared commonMain edits — note: `composeApp` _is_ the iOS app, edit freely;
> only the `android/` module is off-limits), the
> exact working-tree status (UNCOMMITTED changes!), and the pending
> deploy-on-reconnect runbook. The "READ FIRST" block below is Session-3
> HISTORY: that crash was properly fixed in Session 5 (§7e.4 composeResources
> packaging) — kept because the failure mode explains the `composeResourcesBundled`
> gate you'll see in code.

---

## ⚠️ READ FIRST — Session 3 (2026-06-10): on-device crash fix + composeResources caveat
*(HISTORICAL — fixed in §7e.4. See banner above.)*

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
classes/plain funcs only. ~~Fix next session: add a thin composeApp-iosMain
wrapper object delegating to it, then call that from AppDelegate (currently
falls back to fire-and-forget `processPayload` + 2.5 s held completion).~~
**FIXED in Session 6 — see §7f.1.** The quirk itself is still real; any future
core suspend func that Swift must await needs the same composeApp-side wrapper.

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

## 7f. Landed in Session 6 (2026-06-12) — branch `ios-parity`

**Theme: finish the FCM→board/widget parity chain (the §7e leftovers), make the
widget track selection changes like Android, brand the app "Stationly".**
Built + installed + launched on Nick's iPhone (staging scheme) this session.

**⚠️ HARD CONSTRAINT from the product owner (2026-06-12, mid-session):
`android/` and shared `core/commonMain` code must NOT be touched — Android is
live and working.** Everything below lives in iOS-only compilation units:
`core/src/iosMain` (never compiled into any Android artifact),
`composeApp` (the CMP iOS app — the shipping Android app is the separate
`android/` tree with its own ViewModels), and `iosApp/` Swift. A planned
`sdui_payload` handler in commonMain `ProcessFcmPayloadUseCase` was dropped
for this reason (see "deliberately NOT done" below).

### 1. Suspend bridge: Swift can now truly await KMP FCM processing
The §7e discovery-2 fix, exactly as prescribed:
- `composeApp/src/iosMain/kotlin/com/stationly/app/platform/FcmPayloadBridge.kt`
  — added `suspend fun processPayloadAndWait(jsonString: String)` delegating to
  core's `FcmPayloadBridge.processPayloadAndWait`. Because composeApp is the
  ROOT framework module, K/N emits the ObjC bridge
  `processPayloadAndWait(jsonString:completionHandler:)` → Swift
  `async throws`. Verified present in the regenerated
  `composeApp.framework/Headers/composeApp.h`.
- The wrapper hops `withContext(Dispatchers.Default)` immediately because
  K/N exported suspend functions must be **CALLED from the main thread**
  (default `objcExportSuspendFunctionLaunchThreadRestriction=main`; this
  project does not override it in gradle.properties) — so Swift calls it from
  `Task { @MainActor in … }` and the hop keeps parsing/SQLite off main.
- `iosApp/iosApp/AppDelegate.swift` — BOTH receive paths now await:
  - `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`
    (background + foreground data-only pushes, i.e. every real Syncer topic
    push): `Task { @MainActor in try? await …processPayloadAndWait…;
    WidgetCenter reload; completionHandler(.newData) }`. The old
    fire-and-forget + fixed 2.5 s `asyncAfter` raced iOS suspension.
  - `processFcmPayload(_:)` (the `willPresent`/notification-tap helper): same
    await pattern, widget reload right after the write lands (was +2.0 s blind
    delay).
  - `FCMBridge.processPendingPayload` intentionally stays fire-and-forget
    (`processPayload`) — it runs in-foreground where WidgetReloadObserver
    covers the reload; no completion handler to hold open.

### 2. Home board now applies LineStatus_* pushes immediately
`composeApp/src/commonMain/kotlin/com/stationly/app/ui/summary/SummaryViewModel.kt`
(iOS app's home VM — NOT the Android one): the `FreshDataNotifier.events`
collector now calls `loadLineStatus(selection)` in addition to
`loadPredictions(selection)`. Android parity reference:
`android/.../ui/summary/SummaryViewModel.kt` prefsListener reloads BOTH on its
`line_status_data` ping (lines ~160-164). Before this, a line-status push
updated SQLite + widget strip but the in-app board's status line stayed stale
until the next selections re-collect.

### 3. iOS widget now tracks selection changes (pull model, Android parity)
**The architectural insight (read this before touching widget update code):**
Android's `AndroidWidgetManager` (core/androidMain) IGNORES the `WidgetState`
it is handed — `updateWidget` / `showWaitingState` / `clearWidgetData` all just
broadcast `ACTION_UPDATE_WIDGET`, and `DepartureWidgetProvider.updateFromStorage`
re-reads the PRIMARY (first) selection from SQL at render time. The widget is
pull-based; callers only say "something changed".

The old `IosWidgetManager` (core/src/iosMain/kotlin/platform/Platform.ios.kt)
was push-based — it wrote the caller's state verbatim into the App Group. Two
user-visible bugs followed:
- **Last-writer-wins:** `SummaryViewModel.loadPredictions` pushes the widget
  for EVERY board it reloads, and `StationLifecycleUseCase.completeSetupAsync`
  pushes the board being added — so with 2+ boards the widget showed whichever
  selection happened to be written last, not the primary.
- **Delete blanked the widget:** `discardStation` always calls
  `showWaitingState("No Station", …)` even when other boards remain.

Fix: `IosWidgetManager` is now pull-based like Android. `updateWidget(state)`
and `showWaitingState(…)` both run `refreshFromPrimary()`: re-read
`sqlStorage.getAllSelections().firstOrNull()`; if present, rebuild the
App-Group state from SQL (predictions via `GlobalBoardProcessor
.processPredictions`, status as `"Severity: reason"` via
`StationlyFormatters.formatStatusReason`, `lastUpdated` from
`getLastUpdatedTimestamp` in SECONDS, direction/mode from the selection — the
exact shape `ProcessFcmPayloadUseCase.refreshWidgetFromStorage` and the home
poll produce); if no selections remain, wipe the keys so the Swift widget
renders its designed empty state ("No station set" — the analog of Android's
"No boards yet" fallback). Every path still bumps `widget_reload_signal`.
The incoming `WidgetState`/strings are now just triggers, exactly like
Android's broadcast. Callers were NOT changed — `StationLifecycleUseCase` and
`ProcessFcmPayloadUseCase` (commonMain) are untouched.

Selection-change triggers that now all converge on the primary board:
station add (Selection flow + login cloud-restore → `setupStation`), board
delete (`discardStation`), logout (`cleanupAll` → `clearWidgetData` wipe),
FCM pushes, home VM reloads, pull-to-refresh.

### 4. App is named "Stationly" everywhere user-visible
`iosApp/project.yml`:
- app target `info.properties`: `CFBundleDisplayName: Stationly` +
  `CFBundleName: Stationly` → home screen, Settings, permission dialogs,
  app switcher.
- widget target `info.properties`: `CFBundleDisplayName: Stationly` → widget
  gallery header (per-widget title/description were already branded in
  `StationlyWidget.swift`: "Stationly Departures").
- **Deliberately NOT renamed:** xcodegen project/target/scheme names and
  PRODUCT_NAME stay `iosApp` — renaming them churns every documented build/
  deploy command (`iosApp.xcodeproj`, `"iosApp Staging"`, `iosApp.app` paths)
  and provisioning, for zero user-visible gain. Branding lives in the
  Info.plist properties. No "iosApp" string literals exist in UI code
  (grepped composeApp + Swift).

### 5. Widget tick layer — rows now count down between data writes
Root cause of "widget frozen at N min while 'ago' climbs": the widget rendered
the eta STRINGS written at App-Group-write time; only the `.timer` "ago" text
ticked. Android's widget never does this — its consistency contract
(android/.../widget/CLAUDE.md) says every render re-derives eta from
`targetEpochMs` (`tickPredictions`), and its ACTION_ETA_TICK watchdog re-renders
each minute. The KMP JSON already carried `targetEpochMs`
(PredictionDisplay, encodeDefaults=true) — Swift simply dropped it.

Fix (Swift-only, `iosApp/StationlyWidget/`):
- `AppGroupStorage.swift`: `DepartureRow` now decodes `targetEpochMs`;
  `WidgetData.ticked(at:)` is a line-for-line mirror of Android
  `tickPredictions` + `formatMinutesRemaining`: drop rows >30 s past target
  (DEPARTED_GRACE_MS), label "Due" < 60 s / floor minutes, isDue < 30 s,
  per-platform monotonic bump (labels strictly increase within a platform;
  null-target rows pass through unchanged at group end). **Keep these
  constants in lockstep with Android — they are duplicated by necessity
  (RemoteViews-Kotlin vs WidgetKit-Swift); change one, change both.**
- `StationlyWidget.swift` getTimeline: the 61 per-minute entries each get
  `data.ticked(at: entryDate)` — pre-rendered countdown for the next hour at
  zero refresh-budget cost; the iOS analog of Android's watchdog.

### Deliberately NOT done (and why) — next-agent candidates
- **`sdui_payload` FCM route on iOS** — Android's
  `FcmMessagingService.handleSduiUpdate` persists `sdui_layout_<id>` +
  extracts predictions into SQL. The natural home is commonMain
  (`ProcessFcmPayloadUseCase`) which is now off-limits per the owner
  constraint. If needed later: implement inside core/iosMain
  `FcmPayloadBridge.processPayloadAndWait` (route `root["sdui_payload"]`
  before topic routing; write via `Platform.storageManager.saveString` —
  composeApp SummaryViewModel already READS `sdui_layout_<station>`). Note the
  live Syncer only sends `payload` on `Station_*`/`LineStatus_*` topics, so
  this path is dormant capability, not a live gap.
- **`user_sync` route + `notification_payload` display + line-status
  transition local notifications** — Android-only for now; all are
  notification-UX parity (not board updates) and need UNUserNotificationCenter
  work. Pointless to build while push is signing-blocked (§7e discovery 1).
- **`stationly_all` topic subscribe** (Android does it in
  StationlyApplication.onCreate) — skipped ON PURPOSE: on iOS every data-only
  push burns the silent-push budget (~few/hour); broadcast announcements would
  starve the Station_* prediction pushes the widget depends on. Revisit only
  with alert-bearing pushes.

### Verification (all green this session)
1. `./gradlew :composeApp:compileKotlinIosSimulatorArm64` — green after each
   Kotlin change.
2. `processPayloadAndWait(jsonString:completionHandler:)` present in
   `composeApp/build/bin/iosArm64/debugFramework/…/composeApp.h`.
3. Full device pipeline (§7e sequence): XCFramework + resources → xcodegen →
   xcodebuild "iosApp Staging" → **BUILD SUCCEEDED** (validates the Swift
   changes) → devicectl install + launch on Nick's iPhone OK.

### On-device QA checklist (testable NOW, no push needed)
1. Home screen icon label reads **Stationly** (delete old install if cached).
2. Widget gallery header reads **Stationly**.
3. Two boards configured → widget shows the FIRST (primary) board, including
   after pull-to-refresh and app relaunch (previously could flip to board #2).
4. Delete the NON-primary board → widget unchanged (primary stays).
5. Delete the PRIMARY board → widget switches to the remaining board within
   ~5 s (WidgetReloadObserver) instead of blanking.
6. Delete ALL boards / log out → widget shows "No station set" empty state.
7. Widget ETAs COUNT DOWN each minute ("5 min" → "4 min"), Due trains drop off
   ~30 s after target, queue shifts up — without opening the app (§7f.5).
8. (Blocked on push signing) FCM push foreground → board rows AND status strip
   update instantly; background push → widget refreshes without reopening app.
   NOTE the in-app board can LOOK push-fed without push: it ticks per minute
   and REST-fetches on open/resume/pull — don't mistake that for FCM delivery.
   Proof of no delivery: `codesign -d --entitlements :- <built .app>` shows NO
   aps-environment → iOS never issues an APNs token → firebase-ios-sdk 11 has
   no channel at all (foreground included).

### SESSION-6 END STATE — working tree, staged build, deploy-on-reconnect

**UPDATE (end of session): all Session-6 changes are COMMITTED AND PUSHED** to
`origin/ios-parity` as six commits, `82ee0d6..53048f0` (FCM await bridge →
line-status reload → widget pull-model → widget tick layer → Stationly naming
→ docs), and the staged build was deployed + launched on Nick's iPhone. Tree
clean except this paragraph's follow-up commit. The per-file map below stands:

| File | Change |
|---|---|
| `composeApp/src/iosMain/.../platform/FcmPayloadBridge.kt` | added suspend `processPayloadAndWait` (root-module ObjC async bridge; §7f.1) |
| `iosApp/iosApp/AppDelegate.swift` | both FCM receive paths `Task { @MainActor in try? await … }` then widget reload (+ completionHandler in bg path); replaced 2.5 s/2.0 s blind timers (§7f.1) |
| `composeApp/src/commonMain/.../summary/SummaryViewModel.kt` | FreshDataNotifier collector also calls `loadLineStatus` (§7f.2) — composeApp = iOS app; NOT the shipping Android app |
| `core/src/iosMain/kotlin/platform/Platform.ios.kt` | `IosWidgetManager` rewritten pull-model: `refreshFromPrimary()` re-reads primary selection from SQL on every trigger; wipe-to-empty when none (§7f.3) |
| `iosApp/project.yml` | `CFBundleDisplayName`/`CFBundleName: Stationly` (app), `CFBundleDisplayName: Stationly` (widget) (§7f.4) |
| `iosApp/iosApp/Info.plist`, `iosApp/StationlyWidget/Info.plist` | xcodegen-GENERATED from project.yml — never hand-edit; re-run `iosApp/xcodegen.sh` instead |
| `iosApp/StationlyWidget/AppGroupStorage.swift` | `DepartureRow.targetEpochMs` decode + `WidgetData.ticked(at:)` tick layer (§7f.5) |
| `iosApp/StationlyWidget/StationlyWidget.swift` | per-minute timeline entries use `ticked(at:)`; doc comment updated (§7f.5) |
| `docs/IOS_BUILD_AND_HANDOFF.md`, `docs/IOS_PARITY_PLAN.md` | this documentation |

Suggested commit split if asked: (1) FCM await bridge [FcmPayloadBridge.kt +
AppDelegate.swift], (2) line-status reload [SummaryViewModel.kt], (3) widget
pull-model [Platform.ios.kt], (4) widget tick layer [StationlyWidget/*.swift],
(5) Stationly naming [project.yml + both Info.plist], (6) docs.

**Staged build, ready to deploy:** `iosApp/build/DD/Build/Products/Debug
Staging-iphoneos/iosApp.app` — built 2026-06-12 13:53 with ALL session changes
(verified: post-edit relink, `CFBundleDisplayName=Stationly` in both bundles).
It was already INSTALLED on Nick's iPhone at ~13:55; the launch attempt
returned `BSErrorCodeDescription = Locked` (screen locked — not an error in
the build). The owner then disconnected the device; deploy is PENDING
reconnection.

**Deploy-on-reconnect runbook (no rebuild needed unless code changed):**
```bash
# 0. device present? (UDID 00008030-001E0D9C3EFB802E is the one xcodebuild/
#    devicectl use; `xcrun devicectl list devices` shows a different CoreDevice
#    UUID — don't confuse them. Device must be UNLOCKED for launch.)
xcrun devicectl list devices
# 1. install the staged app
xcrun devicectl device install app --device 00008030-001E0D9C3EFB802E \
  "iosApp/build/DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
# 2. launch (—console to capture stdout if debugging)
xcrun devicectl device process launch --terminate-existing \
  --device 00008030-001E0D9C3EFB802E com.stationly.mobile
```
If Kotlin changed since: `./gradlew :composeApp:assembleComposeAppDebugXCFramework
:composeApp:assembleIosArm64MainResources` first; if project.yml changed:
`cd iosApp && ./xcodegen.sh`; then the §7e xcodebuild line. Then steps 1–2.

**Device-log how-to (what worked this session):**
- App stdout/os_log live: `xcrun devicectl device process launch --console …`
  (blocks; prints arrive on flush — Swift `print` is line-buffered, be patient).
- Whole-device unified log (apsd/dasd push activity): `sudo /usr/bin/log collect
  --device-udid 00008030-001E0D9C3EFB802E --last 30m --output x.logarchive`
  (NEEDS ROOT; type the full `/usr/bin/log` path — plain `log` is shadowed in
  this zsh). Parse with `log show --archive x.logarchive --predicate …`.
- `idevicesyslog`/`idevicescreenshot` DO NOT work on iOS 26 (§7c note).

## 7g. Landed in Session 8 (2026-06-14) — branch `ios-parity`

**Focus:** the home screen, owner-driven — _"make it iPhone-worthy, like Bevel."_
Four asks (board sharpness, profile "User" flash, pull-to-refresh, overall
polish). This entry covers what's **DONE** (the board) and what's **IN PROGRESS**.

> Session 7 (commits `3374887..99f5966`, already on `origin/ios-parity`) has no
> section of its own — it covered the widget budget/even share-out, auth
> identity-key heal, device-session re-upsert, and the synchronous profile-card
> seed. Noted here so the §7f → §7g numbering gap is explained.

### 1. Real dot-matrix board font + crisp LED rework ✅ (in-app board)
The owner sent two reference photos (a National Rail concourse board and a LU
platform sign "1 Hampstead … 4 mins / 23:29:54") — the board must read as round
lit amber dots on an unlit dot grid. Until now the board (in-app **and** widget)
only ever *approximated* that with system/monospace fonts.

**→ Full detail in the dedicated doc `docs/BOARD_DOTMATRIX_FONT.md`** (font
sourcing, OFL license, the subset command, the `BoardFont` switch-point, the
widget TODO). Summary of what changed in
`composeApp/.../ui/summary/components/Board.kt`:
- Bundled **DotGothic16** (Google Fonts, **OFL** — a true round-dot matrix face),
  subset to Latin (2.0 MB → **38 KB**) at
  `composeApp/src/commonMain/composeResources/font/dot_matrix.ttf`; license at
  `licenses/DotGothic16-OFL.txt`.
- `BoardFont` is a `@Composable get()` → `FontFamily(Font(Res.font.dot_matrix))`,
  falling back to `FontFamily.Monospace` behind the `composeResourcesBundled`
  gate. **Single switch-point** — applied to every board glyph (station name,
  platform headers, departures, ETAs, status strip, footer clock + "ago").
- Crisp rework: **removed the pulsing radial glow halo** (the main "not sharp"
  cause) → `Surface(shadowElevation = 14.dp)`; **per-row lit highlighters**
  (`ActiveStrip` = `#181818` lift + faint per-row dot texture) so each departure
  reads as its own LED cell; `PanelBg = #0A0B07` olive-black; removed section
  hairlines; footer clock is lit dot-matrix text.
- **v2 — owner feedback, verified on device:** (a) brought the **row
  highlighters back** — a first pass went continuous-grid/transparent-rows, which
  the owner disliked, so it was reverted; (b) fixed **"vertically stretched / too
  tall"** by pinning a tight `lineHeight ≈ fontSize` on every board line
  (DotGothic16 ships a large CJK line-gap) + slimmer sizes + 2 dp row padding;
  (c) **"Due" no longer turns red** — monochrome amber board now (`buildBoardLines`
  drops the `#FF5252` fallback). Detail in `docs/BOARD_DOTMATRIX_FONT.md` §4.
- **Deployed:** board v1 then board v2 both built (`assembleComposeAppDebugXCFramework`)
  + installed + launched on Nick's iPhone (Staging) and verified.
- **TODO — owner wants it "everywhere we show the board":** the iOS **widget**
  (`StationlyWidget/WidgetViews.swift` `DotMatrix*` views) still uses system
  fonts. Wire DotGothic16 into the widget bundle — steps in
  `docs/BOARD_DOTMATRIX_FONT.md` §5.

### 2. Profile "User" flash — fixed ✅
Root cause unchanged (auth identity-key race; `AuthBridge.swift:277`
`persistUserIdentity` writes the keys then posts `.authStateDidChange`). Fixed
entirely in shared Kotlin — **no Swift change needed**:
- `ProfileUiState` gains **`isIdentityLoading`**. `ProfileViewModel.initialState()`
  seeds it `true` when logged-in but the keys aren't readable yet;
  `loadProfile()` replaced the blunt `delay(1500)` with a **poll**
  (`IDENTITY_POLL_STEP_MS = 120`, `IDENTITY_POLL_TIMEOUT_MS = 3000`) that resolves
  the instant the keys land, then clears the flag.
- `ProfileScreen.ProfileHeaderCard(loading = …)` renders **`SkeletonBar`s** for
  the name + email (and a blank avatar monogram) while loading — never the literal
  "User"/"Recently". Falls back to "User" only if genuinely unresolved after 3 s.
- Same race on the home **top-bar avatar**: `SummaryViewModel.loadUserInitial()`
  now polls the same way (was a fixed `delay(1500)` → intermittent "?").

### 3. Pull-to-refresh — native Cupertino indicator ✅
`SummaryScreen`'s `PullToRefreshBox` now passes a custom
`indicator = { CupertinoRefreshIndicator(...) }`, replacing Material's Android
spinner-in-a-pill: a thin amber ring that **fills with `state.distanceFraction`**
as you pull, then becomes an indeterminate **spinner** while refreshing — riding
down with the existing rubber-band translation and fading/scaling in. Drawn with
`Canvas` + `drawArc`; the infinite-transition hook is called unconditionally and
visibility is gated after it (Compose rule).

> #2 + #3 compile (`compileCommonMainKotlinMetadata` BUILD SUCCESSFUL) and were
> built + deployed to device (Staging) in the same session.

### 4. Bevel home-screen pass ✅
`SummaryScreen` lifted toward a premium native feel (owner ref: the **Bevel**
app) — **boards-only, no greeting header**:
- **Soft gradient canvas** — the flat background is replaced by a faint amber wash
  up top fading into the base (`Brush.verticalGradient`, ~4.5–6% primary over
  `canvas`), drawn behind a **transparent Scaffold + transparent top bar** so it
  bleeds edge-to-edge (incl. behind the status bar).
- **Scroll-aware top bar** — `CenterAlignedTopAppBar` container is now
  `Color.Transparent`; a 1 px **hairline fades in only once content scrolls**
  (`rememberLazyListState` + `derivedStateOf` → `animateFloatAsState`), the iOS
  inline-nav cue. Top bar wrapped in a `Column` (bar + hairline).
- **Spacing rhythm** — list `contentPadding` top 8 / bottom 28, `spacedBy(22)`.
- Board cards already carry a crisp drop shadow (§1); the bottom amber glow stays
  for symmetric ambient depth.
- **NOT done (deliberately — low-risk to skip):** true content-scrolls-*under*-blur
  (hard in CMP on iOS — needs a backdrop-blur API), per-card shadow on the Explore
  cards, staggered entrance motion. Easy candidates if the owner wants more.

### 5. Widget dot-matrix font ✅
The iOS widget now uses the same DotGothic16 board face as the in-app board:
- `dot_matrix.ttf` **copied into `iosApp/StationlyWidget/`** (globbed in by the
  target's `sources`); registered via **`UIAppFonts`** in the widget Info.plist
  (added to `project.yml` → **`xcodegen` regenerate now REQUIRED**, done by the build).
- `WidgetViews.swift`: a `Font.dotMatrix(_:)` helper
  (`.custom("DotGothic16-Regular", fixedSize:)`, auto system-fallback) replaces
  `.font(.system(...))` on **every** board glyph (station, section headers,
  departures, ETAs, status, live clock, "ago") across small/medium/large.
- **"Due" is now amber** (the `DueRed` constant + its two uses were removed) — the
  widget matches the in-app monochrome-amber board. The no-station **branding**
  screen (`EmptyWidgetView`) intentionally stays system font.
- ⚠️ Widget rendering is the **only thing not yet eyeballed** — WidgetKit caches
  aggressively; remove + re-add the widget (or wait for a timeline reload) to see
  the new font. If `Font.custom` silently fell back to system, the font didn't
  register → check `UIAppFonts` + that `dot_matrix.ttf` is in the widget bundle.

### Build + deploy this session (STAGING, on-device)
Owner: _"build the iOS app in staging only, we're testing locally."_ Standard §2
pipeline with the `iosApp Staging` scheme. The only addition vs §2 is assembling
the iOS resources so the **new font** is packaged by the existing "Copy Compose
Resources" phase:
```bash
./gradlew :composeApp:assembleComposeAppDebugXCFramework :composeApp:assembleIosArm64MainResources
DD=iosApp/build/DD
xcodebuild -project iosApp/iosApp.xcodeproj -scheme "iosApp Staging" \
  -destination 'id=00008030-001E0D9C3EFB802E' -derivedDataPath "$DD" -allowProvisioningUpdates build
xcrun devicectl device install app --device 00008030-001E0D9C3EFB802E \
  "$DD/Build/Products/Debug Staging-iphoneos/iosApp.app"
xcrun devicectl device process launch --device 00008030-001E0D9C3EFB802E com.stationly.mobile
```
**NOTE:** later in the session `project.yml` gained the widget `UIAppFonts` (#5),
so from then on `cd iosApp && ./xcodegen.sh` IS required before `xcodebuild`
(regenerates the widget Info.plist with the font registration).

### Working tree (Session 8) — UNCOMMITTED
- **New:** `composeApp/src/commonMain/composeResources/font/dot_matrix.ttf`,
  `licenses/DotGothic16-OFL.txt`, `docs/BOARD_DOTMATRIX_FONT.md`.
- **Modified — board (#1):** `composeApp/.../ui/summary/components/Board.kt`.
- **Modified — profile "User" fix (#2):**
  `composeApp/.../ui/profile/ProfileUiState.kt` (`isIdentityLoading`),
  `ProfileViewModel.kt` (synchronous seed + poll), `ProfileScreen.kt`
  (`loading` param + `SkeletonBar`), `composeApp/.../ui/summary/SummaryViewModel.kt`
  (`loadUserInitial` poll).
- **Modified — pull-to-refresh (#3) + Bevel home pass (#4):**
  `composeApp/.../ui/summary/SummaryScreen.kt` (`CupertinoRefreshIndicator` +
  custom `indicator`; gradient canvas + transparent Scaffold/top-bar +
  scroll hairline + spacing).
- **New — widget font (#5):** `iosApp/StationlyWidget/dot_matrix.ttf`.
  **Modified (#5):** `iosApp/StationlyWidget/WidgetViews.swift` (`Font.dotMatrix`,
  Due→amber, `DueRed` removed), `iosApp/project.yml` (widget `UIAppFonts`).
- **Modified — docs:** `docs/IOS_BUILD_AND_HANDOFF.md`, `docs/BOARD_DOTMATRIX_FONT.md`.
- All five owner asks (#1 board, #2 profile, #3 pull-refresh, #4 Bevel home,
  #5 widget font) are **code-complete**. Only the **widget font render** is
  un-eyeballed (WidgetKit cache — see §5).
- Suggested commit split: (1) dot-matrix board font + crisp/v2 rework
  [Board.kt + font + license + BOARD_DOTMATRIX_FONT.md]; (2) profile "User"-flash
  fix [ProfileUiState/ViewModel/Screen + SummaryViewModel]; (3) native pull-refresh
  + Bevel home pass [SummaryScreen.kt]; (4) widget dot-matrix font
  [WidgetViews.swift + project.yml + StationlyWidget/dot_matrix.ttf]; (5) docs.

## 8. What's REMAINING (priority order)

1. **On-device QA of the rebuilt board + widget (TOP — needs the iPhone).** The
   board + widget were rebuilt this session (§7c "Departure board + widget") and
   compile + Swift-type-check, but have **not been rendered on a device**. Verify:
   in-app board scroll + colours vs Android; the widget at **systemSmall / medium
   / large** — row counts, truncation, the line-prefixed headers (`Piccadilly:
   Platform 1 (Eastbound)`), and the live "ago" ticking. If anything overflows,
   tune the per-family caps in `WidgetViews.swift` (small 3 / medium 4 / large 9).
   (Build the widget via Xcode — `swiftc -typecheck` passes but can't render.)
2. ~~**FCM in-app immediacy**~~ DONE — `FreshDataNotifier` collector (Session 3)
   now also reloads line status (Session 6 §7f.2); board + status strip update
   instantly on push. Untestable on device until push signing is unblocked.
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
