# Release regression: Android (and what iOS shares)

Run this **every time a build goes to Play**, internal testing first. It is the
week of 23 to 26 November 2026 written down: the end-to-end run on build 8 that
certified Android 2.0, including every check that found a real bug. Each row
says what it caught last time, so nobody trims it as a formality.

Unit tests prove the logic. This proves the phone: real TfL data, a real
launcher, a real Play update, a real account.

| Tier | When | Time |
|---|---|---|
| **Smoke** (marked **S**) | Every build, before it leaves internal testing | about 45 min |
| **Full** (everything) | Every release to production, and after any change to sync, widgets, auth or the board pipeline | half a day, plus an overnight soak |
| **Upgrade** (§O) | Whenever an older version is still installed by users (today: v1, versionCode 2) | about 40 min |

Results go in a new row of the **run log** at the bottom, and failures go in
`docs/android-v2/BACKLOG.md` with the run's date.

---

## 0. Before touching a phone

### Safety rules (non-negotiable)
These phones are personal phones signed in to real accounts.

1. **Never drive a phone someone is using.** `idle` prints milliseconds since the
   last touch; wait until it is over 3 minutes. Never bypass a secure lock.
2. **Every tap goes through `need`** (the helpers do this): if Stationly is not in
   front, nothing is tapped. A blind tap after the app closes lands on the
   owner's home screen.
3. **Restore every phone setting you change** (screensaver, rotation, font scale,
   airplane mode). Write the old value down first.
4. **Delete screenshots and dumps** (`qa-out/`) at the end. They show a personal phone.
5. **On a production account: no purchases, no account deletion.** Payments and
   deletion are tested by the owner, or on the test account.
6. **Never paste secrets** (webhook secrets, `.env` values) into notes or chats.
   The deploy scripts echo `.env`; mask their output.

### Phones
| Phone | Build | Account | Use for |
|---|---|---|---|
| Pixel 11 Pro (`5A291FDDW0003D`) | **Production**, from Play | owner's real account | release candidate, Play upgrade, timings |
| Pixel 10 Pro Fold (`51131FDCG00030`) | **Staging**, `./gradlew :android:app:installStagingDebug` | staging account | features behind flags (closures), destructive tests |

Both builds use the package `com.stationly.mobile`, so a staging build cannot sit
next to a production one: installing one over the other means uninstalling first,
which wipes the phone's boards and widgets. Never do that on someone's phone
without asking.

### Tools
```sh
source scripts/qa/device.sh             # phone helpers (zsh or bash)
python3 -I scripts/qa/tfl_next.py ...   # what TfL says is next, to compare the board with
adb logcat -G 8M && adb logcat -c       # fresh log buffers for the run
```
Main helpers: `txt` (text on screen), `T "regex"` (guarded tap), `SW` (guarded
swipe), `shot NAME`, `cr` (crash count + app in front), `stations` (home order),
`widgets` (Stationly widget ids), `addm`, `save`, `del`, `idle`.

`txt` is empty on screens whose clocks never stop ticking (v1's home, a launcher
with a clock widget). Use `shot` and read the picture there; screenshot
coordinates × 1.43 = phone coordinates on the Pixel 11 Pro.

---

## A. Automated gate (must be green before any phone work)

| # | Command | Pass |
|---|---|---|
| A1 **S** | `./gradlew :core:testDebugUnitTest :composeApp:testDebugUnitTest :android:app:testStagingDebugUnitTest :core:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64 :android:app:assembleStagingDebug` | BUILD SUCCESSFUL, 0 failures (867 tests on 2026-11-26) |
| A2 **S** | `cd ../stationly-backend && npx ts-node src/tests/run.ts` | all pass (277) |
| A3 | Syncer: `mvn -o test -Dtest='!StationlyApplicationTests'` with `JAVA_HOME` on JDK 17 | all pass |
| A4 | iOS framework, when `commonMain` changed: `./gradlew :composeApp:assembleComposeAppDebugXCFramework` | builds (Xcode links a STALE framework happily otherwise) |
| A5 **S** | `scripts/android-release.sh production --version-code N` | refuses a dirty tree; its checks on the built files pass (see ANDROID_BUILD_AND_RELEASE.md) |

---

## B. Install, launch, stability

| # | Steps | Expected | Caught before |
|---|---|---|---|
| B1 **S** | Update from Play (internal testing) over the previous build | Installs; `adb shell dumpsys package com.stationly.mobile \| grep versionCode` shows the new code | |
| B2 **S** | Cold start: force-stop, `open`, time to first board | Under 1 s to a populated board (0.4 s on build 8); no blank screen | Blank NavHost on resume (singleTask fix) |
| B3 **S** | `cr` after every section | `crashes=0`; also check ANRs: `adb shell dumpsys activity processes \| grep -i anr` | |
| B4 | Background for 10 min, reopen | Same screen, fresh data, no "X ago" stuck | |
| B5 | Rotate: `adb shell wm user-rotation lock 1`, then `3`, then `0` (and `settings put system accelerometer_rotation` back) | Layout holds, no crash | `settings put user_rotation` does nothing on its own; use `wm` |
| B6 | Large text: `adb shell settings put system font_scale 1.5`, restore to the old value | Nothing clipped that matters; widgets stay their size | |
| B7 | Dark mode and Appearance: System/Light/Dark | Every screen readable in both | |
| B8 | Overnight soak: leave signed in, charging off, overnight | Same process in the morning (`adb shell pidof`), 0 crashes, battery reasonable (about 63 mAh/day on build 8: `adb shell dumpsys batterystats \| grep -A3 com.stationly`) | |

---

## C. Boards: adding, editing, deleting

Add one station in **every mode**. Use stations the account does not already
have; delete them afterwards (§C7) to leave the account as you found it.

| # | Steps | Expected | Caught before |
|---|---|---|---|
| C1 **S** | Tube: `addm "^Tube$" "Highbury" "^Highbury"`, pick a direction, `save` | Home within ~5 s, board populated | |
| C2 | Bus with both directions (e.g. Penton Street 73/205/214) | Headings read **"73 · Stop PN"** style, never **"Bus 498"** | AV2-11.46: unlettered stops said "Bus 498" until settings were opened |
| C3 | Bus route that runs one way only | The only direction is pre-selected; tapping it again deselects it (do not) | Tester deselected by mistake |
| C4 | DLR, Overground, Elizabeth line, one each | Setup titles: "Find **a** DLR station", "Find **an** Overground station" | AV2-11.38: "a Overground"; mode label "Dlr" (now "DLR", backend `formatModeLabel`) |
| C5 **S** | Station limit: with 4 stations, tap **+** | "Station Limit Reached… quota of 4 stations", Got it | |
| C6 | Station settings: filters (destinations, via), rows per platform, expanded, pin | Board changes at once; the same on the widget | |
| C7 **S** | Delete a station through its settings (`del "Highbury"`) | Home within ~6 s, the station gone, its widget says "Not set up yet" or "Choose a station" | One delete took ~6 s on build 8: note if slower |
| C8 | Reorder: Home settings, Your stations, drag | Home and carousel follow at once | |
| C9 | Admin portal (staging/prod admin), find the account | The count matches the phone. **Today the column counts BOARDS (routes × directions), not stations**: 4 stations showed as 10 | Owner asked why 10 vs 5; fix pending |

---

## D. Departure times against TfL (the product's one promise)

For **each mode**, open the board, `shot`, and immediately:

```sh
python3 -I scripts/qa/tfl_next.py <naptan>                 # Tube, DLR, bus
python3 -I scripts/qa/tfl_next.py <naptan> --line 73       # one bus route
python3 -I scripts/qa/tfl_next.py <naptan> --rail          # Overground, Elizabeth line
```

| # | Check | Pass |
|---|---|---|
| D1 **S** | Each row's minutes vs TfL's, same destination and platform | Within **±1 min** (the board floors; "Due" under 1 min). Done in all 5 modes on build 8, all matched |
| D2 | "X ago" on the board footer | Under 1:00 and counting up; amber, then red when stale |
| D3 | A **cancelled** train (`--rail` prints CANCELLED) | NOT on the board | A first comparison "found" one; it was the script matching wrong |
| D4 | Destination "Check Front of Train" | Shown as TfL sends it | |
| D5 | Night (after the last train) | "Service Closed / No departures expected here" (tube) or "Service ended for tonight / Back in the morning" (bus), not an empty board | |
| D6 | Busy multi-platform station (e.g. King's Cross) | No platform missing. The Syncer keeps 10 per direction; a busy station can lose rows | Open finding |

Known: Southfields eastbound sometimes has no TfL rows at all (a TfL feed gap,
investigated 2026-11-25, parked). Compare with TfL before calling it ours.

---

## E. Live updates and offline

| # | Steps | Expected |
|---|---|---|
| E1 **S** | Watch a board for 2 min with trains running | Rows move without touching the phone (live stream / FCM) |
| E2 | Airplane mode on for 1 min (note the old state), then off | Banner "Unable to reach Stationly servers"; clears itself and data catches up when back |
| E3 | Pull to refresh | Fresh "0:00 ago" |

---

## F. Widgets

| # | Steps | Expected | Caught before |
|---|---|---|---|
| F1 **S** | Add from the in-app card (Add to Home Screen) and from the launcher's widget list | **One** widget per add, bound to the right station | Duplicate widgets per add (2026-11-23) |
| F2 **S** | Refresh button | Spins briefly, then fresh time | Spin stuck on unlock |
| F3 | Arrows / scroll between platforms, tap to open the app | Correct station in the app | |
| F4 | Change a widget's station (widget settings) | Follows | |
| F5 | `widgets` vs the app's Home settings, Widgets | Same count; unbound ones say "Not set up yet / Choose a station for this one" | |
| F6 | Signed out (§G) | Every widget: "Signed out · Open Stationly to sign in" | |
| F7 | After an update, **before opening the app** | Widgets redraw in the new build's style, still bound | |

---

## G. Account: sign out, sign in, cross-device

| # | Steps | Expected | Caught before |
|---|---|---|---|
| G1 **S** | Profile, Sign out | Login screen at once, no crash, widgets "Signed out" | AV2-11.31: a session ending elsewhere could leave the app stuck |
| G2 **S** | Continue with Google, pick the account | Home with **every station, in the same order, with the same routes in the same order**; layout and appearance kept; widgets rebound (`widgets`, then look) | AV2-11.47: order came back reversed (fixed after build 8) |
| G3 | Sign in on a second device (iOS or Android) | Same stations, same order | |
| G4 | Add on one device | Appears on the other within a minute (user_sync push) | |
| G5 | Delete on one device | Gone on the other | iOS 1.1 lost boards to a background sync (2026-11-24) |
| G6 | Drag the stations into a new order, wait 5 s, sign out, sign in | The new order | AV2-11.47 |
| G7a | Update to the new build, open it once, then sign out and in **without changing anything** | The order from before the update | Fold 2026-11-26: Southfields first became last |
| G7 | Delete account (**test account only**) | Signed out with the "account removed" notice; other devices follow | |

---

## H. Home settings, screensaver, notifications

| # | Steps | Expected |
|---|---|---|
| H1 | Layout List / Carousel | Home follows; carousel pages = stations, in order |
| H2 | Screensaver: Home settings, Screensaver; choose Stationly; preview | Live board, logo right, no burn-in static parts. Note and restore `settings get secure screensaver_components` |
| H3 | Line alerts: Severe / All / Off | Text under it matches; "Quiet overnight" honoured |
| H4 | Notifications permission denied, then granted | Banner offers it while denied; gone once granted |
| H5 | Deep links (`adb shell am start -d "<link>"` for each link the site uses) | Opens the right screen, one task (`adb shell dumpsys activity activities \| grep -c com.stationly` stays 1) |

---

## J. Behind flags (staging phone only until switched on)

| # | Flag | Check |
|---|---|---|
| J1 | `flag.stationStatus.show` (station closures, AV2-8.4) | A closed station shows the closure card on home, widget and screensaver. **Off in production**; switching it on is AV2-8.4 |

---

## K. Support and payments

| # | Steps | Expected |
|---|---|---|
| K1 | Support screen | Tiers show; copy has no "coffee"/"donate", no dashes |
| K2 | Purchase (**owner, real card**; or a test card on staging) | Badge on the avatar; a slow card (pending) settles and then badges |
| K3 | Declined card (test account) | Clear failure, no badge |

---

## O. Upgrade from the version users have (Play to Play)

The real path: users have the old build from Play, and Play updates them.

### Getting the old build onto a phone
1. Play Console, App bundle explorer, the old version code (v1 = **2**),
   Downloads, **Signed, universal APK**. Check it:
   `apksigner verify --print-certs file.apk` (signed by Google's app-signing key)
   and `aapt2 dump badging file.apk | head -1` (versionCode).
2. Uninstall Stationly from the phone (with the owner's say-so: it wipes boards,
   widgets and settings on that phone; the account keeps its boards).
3. Install it **as if Play had**: `adb install -i com.android.vending file.apk`.
   Without `-i`, Play's integrity gate shows "Get this app from Play" and the app
   will not open. Do **not** tap "Get app" there: on an internal-testing account
   it installs the NEW build.

### Set the phone up like a real user of the old version
| # | Steps | Expected | Caught before |
|---|---|---|---|
| O1 | Sign in on v1 with Google | Home loads the account | |
| O2 | Note: v1 restores the account's legacy `stations` list with **no limit** (the limit is in v1's add flow only), one card per route and direction | An account used on v2/iOS shows many cards on v1. Expected, not a bug | Owner asked "v1 only allows one board" |
| O3 | Delete down to nothing (trash, Delete Board, ~5 s each), then add **one** station with v1's own flow (Set Up My Board) | v1 shape: one board | |
| O4 | Place a v1 widget (Add card, Add to home screen); set Stationly as the screensaver | Widget shows the board | |
| O5 | Record the "before": `shot`s of home and widget, `widgets`, screensaver setting | | |

### Update and check
| # | Steps | Expected | Caught before |
|---|---|---|---|
| O6 | `adb shell am start -a android.intent.action.VIEW -d "market://details?id=com.stationly.mobile"`, tap **Update** (capture `adb logcat -v time` meanwhile) | versionCode is the new one, installer `com.android.vending` | |
| O7 | Before opening: look at the widget | Redrawn in the v2 style, same station (log: `v1 upgrade: 1 widget(s) keep …`) | |
| O8 | Open | Still signed in; no login screen | |
| O9 | Wait ~30 s (setup is one station at a time), then `stations` | The v1 board **plus** the account's boards, **up to the limit of 4** (v1 board first, then the account's in order). Log: `v1 boards are in the account's list` | 2026-11-26: 5 stations (fixed: `BoardQuota.fit`) |
| O10 | Screensaver page | Still Stationly | |
| O11 | **+** at the limit | "Station Limit Reached" | |
| O12 | Restore the screensaver setting, delete the test station if wanted | | |

The previous v2 build to the new one (e.g. 8 to 9) is the same §O6 to §O9 with
no setup: the phone just updates. Check §G2's things survive: stations, order,
settings, widgets.

---

## Run log

| Date | Build | Phone | Tier | Result | Failures → backlog |
|---|---|---|---|---|---|
| 2026-11-23 | 2.0 staging | Fold | Full (QA sweep) | passed after fixes | see `QA_2026-11-23_ANDROID_SWEEP.md` |
| 2026-11-25/26 | 8 (2.0) prod | Pixel 11 Pro | Full + overnight + upgrade | **passed**; 0 crashes, timings matched TfL in 5 modes | AV2-11.31, 11.38, 11.46, 11.47, 11.49; admin count |
| 2026-11-26 | 9 (2.0) prod, internal testing | Pixel 11 Pro (test account Njk Hhj) | v1 (2.apk, Play installer) → Play update to 9, smoke + support | **passed**; 0 crashes. v1 widget redrawn before open; What's new once; v1 board migrated; drag order survived sign-out/in with filter; filter, carousel, dark, 2nd widget, screensaver, delete-with-widget; prod support sheet/profile £600 line, board-added ask | AV2-11.53 (migrated bus board showed stop "towards" text for ~2 min); Victoria 2nd/3rd train 1-2 min off TfL during cancellations (known feed divergence). Not run: purchase (owner's phone), on-open ask (needs 7 days; staging-verified). Store screenshots: `App Release/android_v2/screenshots/raw` |
