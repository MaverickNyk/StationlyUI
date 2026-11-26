# Stationly for Android 2.0: the release record

**Written:** 2026-11-26, the day build 9 passed its upgrade run and went to
internal testing. **Owner:** Nikhil. **Scope:** everything it took to replace the
first Android release (1.0, versionCode 2) with 2.0, across the app, the
backend and the Syncer, plus the support and contributions work that ships with
it.

This is the one document to read first. It points to the detailed ones rather
than repeating them; where it summarises, the linked document is the authority.

---

## Contents

1. [Where things stand today](#1-where-things-stand-today)
2. [What 2.0 gives a user](#2-what-20-gives-a-user)
3. [The journey, in order](#3-the-journey-in-order)
4. [What changed under the hood](#4-what-changed-under-the-hood)
5. [The v1 to 2.0 upgrade](#5-the-v1-to-20-upgrade)
6. [Support and contributions](#6-support-and-contributions)
7. [Backend and Syncer changes](#7-backend-and-syncer-changes)
8. [Release builds](#8-release-builds)
9. [How it was tested](#9-how-it-was-tested)
10. [Decisions that bind the code](#10-decisions-that-bind-the-code)
11. [Known issues and the 2.1 list](#11-known-issues-and-the-21-list)
12. [What iOS needs to know](#12-what-ios-needs-to-know)
13. [Runbooks](#13-runbooks)
14. [Owner actions](#14-owner-actions)
15. [Lessons](#15-lessons)
16. [Document index](#16-document-index)

---

## 1. Where things stand today

| Thing | State (2026-11-26) |
|---|---|
| Android app | **Build 9 (2.0)** on Play internal testing. Built from `dev_android_bring_to_v2 @ 14d9615`, clean tree. Upgrade run from v1 PASSED on the Pixel 11 Pro. Recommended next: staged production rollout. |
| Live Android app for most users | 1.0 (versionCode 2) until 2.0 is promoted. |
| Live iOS app | 1.1 (build 3). Unchanged by any of this; see §12. |
| Backend | PR #163 (DLR label + support reach) merged and **deployed to production** 2026-11-26 by the owner. Production serves the on-open ask (Android only), the running-costs line (£600 a month) and What's new copy. |
| Syncer | PR #85 (blank destination id) merged 2026-11-25. |
| App branch | `dev_android_bring_to_v2` merged into `master` 2026-11-26 (owner's call, no iOS release planned), with the Q5 guard in front of every scripted iOS build (§12.1). Owner: no iOS fix now; look later. 2.0.x Android hotfixes can come from `master` or the branch. |
| Play support products | Live in production (`SUPPORT_MONEY_ANDROID_ENABLED` on). Server crediting not yet proven with a real purchase (§14). |
| Test phones | Pixel 11 Pro (production, owner's phone; now signed in as the test account Njk Hhj with two Stationly widgets). Pixel 10 Pro Fold (staging debug). Redmi 13C 5G (staging, seen 2026-11-25). |

---

## 2. What 2.0 gives a user

Compared with 1.0, which showed one board for one line and direction:

- **Up to four stations**, each with every line and direction you want on one
  board. Swipe between them (carousel) or scroll them (list); drag to reorder.
- **Filters**: only trains finishing at chosen stations, or going through one.
- **A widget for each station**, placed from inside the app in one step, sized
  to the home screen grid, with a refresh you can see and a pager for
  platforms. A widget whose station is deleted says so.
- **The screensaver**: a big clock, date, weather and a live board while
  charging.
- **The same boards everywhere**: sign in on Android and iPhone, the boards and
  their order follow.
- **Line alerts** that are quiet by default (severe only, nothing overnight),
  with a setting for all or none.
- **Station closures** shown on the board, widget and screensaver (built,
  behind a served flag that is still off in production).
- **What's new**: one screen, once, for people updating from 1.0.
- **Support Stationly**: contributions through Google Play, a running-costs
  line, and a Supporter mark.
- Under it all: the shared board design with iOS, served copy and rules (SDUI),
  an update prompt when a build is too old, and dark mode.

---

## 3. The journey, in order

The working journal is [`android-v2/SESSIONS.md`](android-v2/SESSIONS.md) (one
entry per session) and the board is [`android-v2/BOARD.md`](android-v2/BOARD.md).
In short:

### September: building v2 (sessions S001 to S019)

| Dates | What happened |
|---|---|
| 2026-09-05 | Programme set up: gap analysis, kanban, decisions D1 to D4. Branch `dev_android_bring_to_v2` cut from `ios-parity @ b7b7a1c` (D1), because `ios-parity` was a strict superset of `master`. Safety net first: CI gate, v1 contract locked, v1 golden outputs captured. Database migrations reinstated and proven against a real v1 database (EPIC-01, EPIC-02). |
| 2026-09-06 | Host cutover: the shared Compose Multiplatform UI became the Android app (D2, D5). Stubs made real, sign-in, deep links, the board listening to pushes again, per-widget station binding (EPIC-03, start of EPIC-05). |
| 2026-09-08 to 09-11 | Data plane (EPIC-04): FCM at v2, topics that can be asked what they are subscribed to, `boards` as the one list the client believes with `stations` as a projection (D7, commit `666d32e`), activity trail driver. Widget visibility, the update gate, the shared screensaver, v1's last screens deleted. versionCode 3. |
| 2026-09-12 | Device day (S017, S018): the screensaver rendered for the first time and was fixed; widget settings became a station page; EPIC-09 (the widget is the app's to run), including AV2-9.8: widgets namespaced by account, after a second account could inherit the first person's live widget. |
| 2026-09-13 | EPIC-10 (the widget behaves like iOS's): a flicker measured at 132 redraws per refresh, brought to four; three animations; touch targets; one-step widget placement; platform blocks sorted by name. |
| 2026-09-17 to 09-23 | iOS work on the same branch family: StoreKit 2 support, iOS 1.1 released to the App Store. |

### November: finishing and shipping (S020 to today)

| Dates | What happened |
|---|---|
| 2026-11-21/22 | S020 rebase onto `master @ 53667ca` (iOS StoreKit + 1.1, website Support page). S021: the Android 17 widget (rows, corners, updates, taps, refresh, size families; D10 no gear on 12+). S022: support through Google Play Billing (D11). Backend PR #148, #151. |
| 2026-11-23 | Widget at full bleed with proportional rows; versionCode 4 with Play Billing 9.1.0 and target API 36. **QA sweep of the whole app on the Fold** (S023, [`QA_2026-11-23_ANDROID_SWEEP.md`](QA_2026-11-23_ANDROID_SWEEP.md)): 10 findings fixed, the rest filed as EPIC-11. Build scripts with verified artefacts (`android-dev.sh`, `android-release.sh`). |
| 2026-11-24 | Line alerts decided by the backend, quiet by default (backend PR #157). v1 upgrade keeps widgets and v1-only boards. Play purchases sent with the user's token (the 401 bug). Builds 5, 6 and 7. |
| 2026-11-25 | Sign-in and screensaver crashes, boards read half written, mode step and Profile on narrow phones (Redmi), widget type chosen by fit, station closures (S024, backend PR #160, Syncer PR #82), a train with no destination id no longer losing its board's update (S025, Syncer PR #85). **Build 8**. Full regression including an overnight run and a v1 upgrade on the Pixel. |
| 2026-11-26 morning | Board order fixed (sign-in brought boards back oldest first), the station limit applied to the v1 merge (a v1 user could reach five stations), forced sign-out, wording. Two code reviews. Release regression manual written ([`RELEASE_REGRESSION.md`](RELEASE_REGRESSION.md)). Commit `acc7cd8`. |
| 2026-11-26 midday | **Support reach** (§6.3): iOS had taken nothing since launch, and nothing measured why. Funnel logging, ask on reaching home, running-costs line, What's new. Tested on the Fold on staging, two xhigh code reviews, fixes, commits `ae9d4ea` and `7298be0`; settings moved to repo defaults (`c7cda58`). |
| 2026-11-26 afternoon | **Build 9** built and uploaded to internal testing. Backend PR #163 merged and deployed to production. **v1 to 9 upgrade run on the Pixel through Play: passed**, store screenshots taken. |

---

## 4. What changed under the hood

| Area | Before (1.0) | 2.0 | Where to read more |
|---|---|---|---|
| UI | Android-only screens | The shared Compose Multiplatform UI (`:composeApp`), hosted by `:android:app` | DECISIONS D2, D5 |
| Database | v1 schema, migrations off | Migrations reinstated, v1 → 3 tested on a real v1 database, drift checked in CI (`verifyCommonMainStationlyDatabaseMigration`) | [`android-v2/analysis/MIGRATION.md`](android-v2/analysis/MIGRATION.md) §1 |
| Cloud boards | `users/{uid}.stations`, one row per line and direction | `users/{uid}.boards`, per station with selections; `stations` still written as a projection after every accepted save so v1 phones and the backend's polling keep working | D7; [`USER_STATE_AND_ACTIVITY.md`](USER_STATE_AND_ACTIVITY.md); AV2-11.48 to retire it |
| Sync | v1 merge-by-replace | Revision gate (`stateRev`), debounced full-replace pushes, reconcile on push and on foreground, order kept (`Board.inOrder`) | [`CROSS_DEVICE_SYNC.md`](CROSS_DEVICE_SYNC.md) |
| Live data | FCM to v1's single board | FCM feeds the v2 board model, topics per station with a ledger; one delivery mechanism per signal | D9; EPIC-04 |
| Widget | One widget, the user's first station | RemoteViews (D3), one widget per station, bound per instance and per account, sized by fit, iOS-like pager and refresh | EPIC-05, 09, 10; AV2-11.29, 11.36 |
| Screensaver | v1's own | The shared one (`StationlyDreamService`) | EPIC-06 |
| Config | Mostly compiled in | SDUI home-config (copy, limits, timing rules, notification policy, support), stale-while-revalidate cached | [`SDUI.md`](SDUI.md), [`CONFIG_KEYS.md`](CONFIG_KEYS.md) |
| Station limit | Only in v1's add flow | Served `limits.boards.max` (4), checked at "+" and on the v1 merge (`BoardQuota.fitMerge`) | AV2-11.49 |
| Update gate | None | Served release policy; 426 from the backend blocks a too-old build | [`APP_UPDATE_POLICY.md`](APP_UPDATE_POLICY.md) |
| Notifications | v1 alerts | Backend-decided line alerts, quiet by default, user level | [`LINE_ALERTS.md`](LINE_ALERTS.md) |
| Activity trail | None on Android | Events queued in SQLite, uploaded in batches to `users/{uid}/activity` | [`USER_STATE_AND_ACTIVITY.md`](USER_STATE_AND_ACTIVITY.md) |
| Support | None | Google Play Billing, verified and consumed by the server | §6 |

---

## 5. The v1 to 2.0 upgrade

What happens once, on the first launch after Play updates 1.0 to 2.0
(full detail: [`MIGRATION.md`](android-v2/analysis/MIGRATION.md) §6):

1. **Database** migrates 1 → 3. The migration leaves one-shot notes in durable
   prefs (`V1Upgrade`): widgets pending, boards pending, What's new pending.
2. **Widgets** are re-drawn on `PACKAGE_REPLACED`, before the app is opened, and
   adopt the station v1 was showing (`V1WidgetAdoption`).
3. **Boards**: v1 saved only to the legacy `stations` list. The first reconcile
   MERGES (never deletes) the phone's v1 boards with the account's `boards`
   (an iPhone may have written some), within the station limit, then publishes
   the result.
4. **Sign-in survives** the update; v1's preferences stored as numbers or
   booleans are read as text (AV2-11.27).
5. **What's new** shows once, after the home screen has boards.

Verified on the Pixel through Play: build 8 (2026-11-25/26, owner's account)
and build 9 (2026-11-26, test account). See §9.

---

## 6. Support and contributions

The authority is [`SUPPORT_CONTRIBUTIONS.md`](SUPPORT_CONTRIBUTIONS.md)
(§8 Android, §9 reach). The short version:

### 6.1 Routes

| Surface | Route | Store cut | State |
|---|---|---|---|
| iOS 1.1 | StoreKit 2 consumables | 15% | Live since 1.1; **no contribution yet** |
| Android 2.0 | Google Play Billing one-time products £4 / £8 / £12 / £25 | 15% | Live in production config; server crediting to be proven with one real purchase |
| Website `/support/` | Stripe Payment Links | 0% | Live |

Every route writes the same `users/{uid}.supportMoney` ledger; a contribution
gives a 30-day Supporter mark. Nothing is ever locked behind it. Copy rules:
no "donate", no coffee framing, no dashes, personal tone.

### 6.2 Android purchase path

Pay → Play's sheet (one launch at a time) → thank-you at once → app posts the
token to `POST /support-money/verify-play` with the user's Firebase token →
server checks with Google, checks the account binding (`sha256(uid)`), records
at OUR tier price, then consumes. A purchase that cannot be credited stays
unconsumed and Google refunds it (3 days; minutes for a license tester).

### 6.3 Reach (2026-11-26)

**Why:** iOS had taken nothing. The only unprompted ask needed a NEW board, and
commuters add one or two on day one and never again; and nothing was logged,
so nobody could tell whether people were never asked, said no, or hit a
broken payment.

**What shipped** (all in build 9 and backend PR #163):

| Piece | What it does | Settings (backend `.env.defaults`, not secrets) |
|---|---|---|
| Funnel logging | `support.asked / ask_dismissed / sheet_opened / sheet_closed / tier_tapped / purchase / verified / thanks_shown`, `whats_new.*`, and Android `app.opened` (it never had one) | n/a |
| Funnel report | `stationly-backend/src/scripts/support_funnel.cjs --key=<service account> --days=N`, read-only | n/a |
| Ask on reaching home | Same banner, after 7 separate days of use, at most every 14 days whatever the answer; Android only | `SUPPORT_MONEY_ASK_ON_OPEN_PLATFORMS=android`, `ASK_MIN_DAYS=7`, `ASK_EVERY_DAYS=14` |
| Running costs line | "Stationly costs about £600 a month to run…" in the amounts sheet and on Profile. Below £100 real this month: the cost sentence only, no figure, no bar. Never a made-up figure (the owner asked for "£280"; declined: a fake figure at the point of payment). | `SUPPORT_MONEY_MONTHLY_COST_MINOR=60000`, `SUPPORT_MONEY_SHOW_RAISED_FROM_MINOR=10000` |
| What's new | One screen for v1 upgraders; Support Stationly on top, Got it below | `WHATS_NEW_V2_ENABLED=true` |
| Monthly total | `support_money_txns/{txnId}`, one row per REAL payment (test purchases, sandbox and unverified Apple receipts excluded), summed per London month, cached | n/a |

**Found on the way:** Apple's `price` is in milliunits, so every iOS
contribution would have been recorded 1000x too high (fixed: tier price); 17
taps on Pay in five seconds launched 17 Play purchases, all refused (fixed: one
launch at a time); a forged Apple date could crash a server worker (fixed);
"costs are covered" claimed with money before store fees (reworded).

**Production also already served** the board-added ask at 1 board / 1 day, so
iOS users were asked once and still nobody paid. The App Store Connect
agreement and product status are on the owner list (§14).

---

## 7. Backend and Syncer changes

### stationly-backend (merged PRs during the release)

| Date | PR | What |
|---|---|---|
| 2026-11-21 | #145, #146 | Website Support page to staging and production |
| 2026-11-22 | #148 | Android v2 contract (dev_android_bring_to_v2) |
| 2026-11-22 | #151 | Verify Google Play purchases for Android support tiers |
| 2026-11-23 | #154 | Pass `SUPPORT_MONEY_ANDROID_ENABLED` to production |
| 2026-11-24 | #157 | Notifications: live policy and backend-decided line alerts |
| 2026-11-25 | #160 | Feature flags, and station closures served |
| 2026-11-26 | #163 | "DLR" label; support reach (funnel script, on-open ask keys, running costs, What's new copy, Apple tier price, test money out of the total, Play errors without tokens, tests that never touch real Firestore) |

Release PRs (`main` → `release_staging` → `release_prod`) carried each one
out: #149/#150, #152/#153, #155/#156, #158/#159, #161/#162, #164/#165.

### StationlySyncer

| Date | PR | What |
|---|---|---|
| 2026-11-22 | #80, #81 | Release |
| 2026-11-25 | #82 | Tell phones which stations are closed |
| 2026-11-25 | #85 | A train with no destination has a blank id, not null |
| 2026-11-25 | #83/#84, #86/#87 | Release to staging and production |

---

## 8. Release builds

All built with `scripts/android-release.sh production --version-code N`;
artefacts and `BUILD_INFO.txt` in `android/build/releases/production-N/`.

| Build | Source | Built | Notes |
|---|---|---|---|
| 4 | `d4289c3` (dirty tree) | 2026-11-23 | Play Billing 9.1.0, target API 36; first internal build |
| 5 | `a51ff4e` | 2026-11-24 | CI gate on the runner's JDK |
| 6 | `629d79e` (dirty tree) | 2026-11-24 | Play token fix tested; screensaver crash seen on it (fixed later) |
| 7 | `4094e20` | 2026-11-24 | New widget at full width and 2 rows |
| 8 | `41fb4d7` | 2026-11-25 | Closures, null destination id fix, all of 25 Nov; full regression + overnight + upgrade passed |
| **9** | **`14d9615`** | **2026-11-26** | Board order, limit on upgrade, forced sign-out, support reach, What's new, Play one-launch fix, Android `app.opened`. Internal testing; upgrade run passed |

The script refuses a dirty tree now; 4 and 6 predate that.

---

## 9. How it was tested

- **The gate** (every change): `./gradlew :core:testDebugUnitTest
  :composeApp:testDebugUnitTest :android:app:testStagingDebugUnitTest
  :core:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64
  :android:app:assembleStagingDebug`; backend `npx ts-node src/tests/run.ts`
  (288 tests at build 9).
- **The manual**: [`RELEASE_REGRESSION.md`](RELEASE_REGRESSION.md), sections A
  to O, smoke and full tiers, with safety rules for personal phones.
- **Tools**: `scripts/qa/device.sh` (guarded phone helpers), `scripts/qa/tfl_next.py`
  (what TfL says, to compare row by row), `scripts/qa/support_flows.sh` (puts a
  staging debug phone into each support flow), `support_funnel.cjs` (the funnel
  from real events).
- **Code reviews**: several per release step; the 2026-11-26 ones are recorded
  in `SUPPORT_CONTRIBUTIONS.md` §9.7 and BACKLOG AV2-11.51/11.52.

### Run log

| Date | Build | Phone | Result |
|---|---|---|---|
| 2026-11-23 | 2.0 staging | Fold | QA sweep; passed after fixes |
| 2026-11-25/26 | 8 | Pixel 11 Pro | Full + overnight + v1 upgrade; passed |
| 2026-11-26 | staging | Fold | Support reach: What's new (both buttons, once), on-open ask, Maybe later, costs line, funnel events end to end to the staging server, a real staging purchase (Play took it, our server refused with Google `permissionDenied`: staging's service account not invited) |
| 2026-11-26 | **9** | Pixel 11 Pro | **v1 (2.apk) → Play update → 9: passed.** v1 widget redrawn before open; What's new once; v1 board migrated; three new stations; drag order kept through sign-out and sign-in with the filter; filter, carousel, dark mode, second widget, screensaver, delete with a widget; production support sheet and Profile with the £600 line; live times within about a minute of TfL |

**Store screenshots** from that run:
`App Release/android_v2/screenshots/raw/` (01 What's new, 02 lines and
directions, 03 King's Cross board, 04 filter, 05 home settings, 06 support
sheet, 07 two widgets, 08 screensaver, 09 Profile, 10 dark board). The status
bar shows notification icons (this Android ignores demo mode for them), and 07
shows the owner's wallpaper: crop or replace both when making preview images.

---

## 10. Decisions that bind the code

From [`android-v2/DECISIONS.md`](android-v2/DECISIONS.md):

| ID | Decision |
|---|---|
| D1 | Branch from `ios-parity`, not `master` |
| D2 | `:android:app` adopts the `:composeApp` shared UI |
| D3 | The widget stays RemoteViews; per-instance config is added |
| D4 | Support surfaces built and gated off for launch (superseded for Android by D11 and the owner switching it on) |
| D5 | The host keeps the v1 class name; the shared UI moves into it |
| D6 | Apple sign-in is a platform capability, asked of the provider |
| D7 | `boards` is the one list the client believes; `stations` is a projection |
| D8 | A platform capability, not a config flag, decides what a platform offers |
| D9 | One delivery mechanism per signal |
| D10 | The widget draws a settings gear only below Android 12 |
| D11 | Android takes support through Google Play Billing, never Stripe |

Owner decisions on 2026-11-26: the station limit applies to the v1 merge
(option B, accepting that a left-out board leaves the account); What's new
buttons are Support on top, Got it below; the running cost is £600 a month;
no invented "raised" figure, below £100 show the cost only; reach settings live
in the repo's `.env.defaults`, not GitHub secrets; backend changes ride the
branch already in review.

---

## 11. Known issues and the 2.1 list

None of these blocks 2.0. Full rows in [`android-v2/BACKLOG.md`](android-v2/BACKLOG.md).

| ID | Issue |
|---|---|
| AV2-11.53 | After the v1 update, a migrated bus board showed the stop's "towards" text as its destination for about two minutes, then corrected itself |
| (no id) | During Victoria line cancellations, the 2nd and 3rd trains read 1 to 2 minutes off TfL's own feed (known divergence between our source and TfL Arrivals) |
| AV2-11.52 | Unverified Apple receipts still credited to the account (kept out of the public total); refunds do not leave the monthly total; no backfill; the total is gross |
| AV2-11.51 | iOS never publishes a pending order; boards a reconcile brings in land on top; a served-limit race; the v1 merge cut removes a board from the account (accepted); a session ended elsewhere gives no notice |
| AV2-11.50 | Admin portal counts the legacy `stations` array (4 stations show as 10) |
| AV2-11.48 | Retire the legacy `stations` list once nobody runs v1 (order of steps in the row) |
| AV2-11.45 | Southfields / Wimbledon Park eastbound gap in TfL's feed (parked, backend) |
| AV2-11.44 / 8.4 | Turn station closures on in production after 2.0 is out (owner) |
| AV2-11.40 | The filter save can undo a concurrent board edit |
| AV2-11.31, 11.32 | Forced sign-out observer; deep link handled again on recreate |
| AV2-11.10 to 11.18 | QA sweep remainders: landscape, store prices in the buyer's currency, screensaver extras, offline indicator, widget manager, list scroll, copy batch, select all, backend naming |
| Staging only | Staging's Google service account is not invited in Play Console, so staging purchases are refused (`permissionDenied`) |

---

## 12. What iOS needs to know

The live iOS 1.1 app is unaffected: nothing here changed Swift, and the
backend's home-config for iOS only GAINED keys 1.1 has no code to read (checked
by rendering it before and after). The on-open ask is served `false` to iOS.

Before the next iOS release from this branch:

1. **Q5, the database (must fix before any iOS build from this code):** see
   §12.1.
2. AV2-11.33: a background sync that adds a board drops the iPhone's other
   boards, live in 1.1, fixed in shared code; ships with the next iOS build.
3. AV2-11.51(a): iOS should call `publishOrderIfPending` from `UserSyncBridge`.
4. To give iOS the on-open ask: add `ios` to
   `SUPPORT_MONEY_ASK_ON_OPEN_PLATFORMS` once a build with the shared code is out.
5. App Store Connect: check the Paid Apps agreement and the support products.

### 12.1 Q5: the iOS database, deferred by the owner

**Status:** open, deliberately not fixed. Owner, 2026-11-26: "avoid any iOS fix
now but document it and we'll take a look at it later."

**The problem.** The app's SQLDelight schema is versioned. Android 1.0 shipped
schema version 1 (the old layout), and this branch adds migrations 1 → 2 → 3.
iOS 1.0 and 1.1 shipped from `ios-parity`, whose database ALSO says version 1
but already has the newer tables (iOS never had the old layout). So when a
future iOS build from this branch opens an existing iPhone database, SQLDelight
sees version 1 and runs `migrations/1.sqm`, whose first statements create
tables that already exist (`CREATE TABLE ActivityEventEntity` and others). The
migration throws ("table ActivityEventEntity already exists"), the transaction
rolls back, the database stays at version 1, and the same failure happens on
every launch. The only way out for the user is to delete and reinstall.

**Who it hits.** Every App Store iPhone user who updates to the first iOS build
made from `dev_android_bring_to_v2` or from `master` after it is merged. Nobody
today: no iOS build has been made from this code, and Android is unaffected
(Android's version-1 databases really are the old layout).

**Where.** `core/src/commonMain/sqldelight/com/stationly/db/migrations/1.sqm`
(its header comment says iOS "has never shipped publicly", no longer true) and
the iOS driver, `core/src/iosMain/.../Database.ios.kt` (`NativeSqliteDriver`).
Background: [`android-v2/analysis/MIGRATION.md`](android-v2/analysis/MIGRATION.md)
§1.4b; the 2026-11-26 app code review flagged it as the one important finding.

**The fix when it is picked up** (not done): before the driver migrates, on
iOS only, detect a version-1 database that already has the v2 tables (for
example, `ActivityEventEntity` exists) and stamp it as version 2
(`PRAGMA user_version = 2`) so only `2.sqm` runs. Correct the comment in
`1.sqm`. Test on a simulator against a real 1.1 database (copy one off a
device or build 1.1 and seed it), then upgrade to the new build: boards,
settings and the activity queue must survive.

**The guard (2026-11-26).** The owner chose to merge the branch into `master`
now (no iOS release is planned) with a tripwire in front of every scripted iOS
build: `scripts/ios-q5-guard.sh`, sourced by `scripts/ios-testflight.sh` and
`scripts/ios-dev.sh`. It exits 3 with a message pointing here unless
`core/src/iosMain/kotlin/platform/Database.ios.kt` contains the marker
`Q5_FIXED`. `STATIONLY_IGNORE_Q5=1` lets a build through with a warning, for
building and testing the fix itself, never for a release.

**Until the fix lands, the rules:**
- No iOS release, and no iOS archive from Xcode directly (Xcode does not run
  the guard).
- The fix must add the `Q5_FIXED` marker beside it; the guard then lifts by
  itself. Delete the guard and its two `source` lines afterwards.
- Android releases are unaffected.

---

## 13. Runbooks

**Build for Play:** `scripts/android-release.sh production --version-code N`
(clean tree; verifies the built artefacts; never uploads). Upload the `.aab` in
Play Console → Testing → Internal testing. Details:
[`ANDROID_BUILD_AND_RELEASE.md`](ANDROID_BUILD_AND_RELEASE.md).

**Promote to production (staged):** Play Console → Production → create release
from the internal build → staged rollout (for example 20%) → watch Android
vitals (crashes, ANRs) for a day → widen. There is no rollback for the database
(MIGRATION §1.7): Play cannot downgrade, so a bad build is halted, then fixed
forward with a higher version code.

**Test an upgrade like a real user:** uninstall, `adb install -i
com.android.vending ~/Downloads/2.apk` (the installer flag lets Play update it
later), sign in, set up v1, then update from the Play listing. Uninstalling
removes that phone's Stationly widgets.

**Backend staging deploy:** `bash .scripts/staging_deploy.sh` from inside
`stationly-backend`, with `STAGING_SUPPORT_MONEY_ANDROID_ENABLED=true` or it
turns Android support off on staging. It echoes env values: never paste its
output. Production deploys go through PRs to `release_prod` (owner).

**Support flows on a staging phone:** `scripts/qa/support_flows.sh whatsnew |
ask | restore | events`.

**Funnel:** `node src/scripts/support_funnel.cjs --key=<service account>
--days=7`. Activity uploads overnight; leave two days before trusting a week.

---

## 14. Owner actions

| # | Action | Why |
|---|---|---|
| 1 | Promote build 9 to production as a staged rollout | The release |
| 2 | One license-tester purchase on build 9, then check `support.verified {"outcome":"credited"}` (or the ledger row after 15 min) | The only unproven link in Android money |
| 3 | Play listing and data safety form; use the new screenshots | Before 100% |
| 4 | App Store Connect: Paid Apps agreement active, support products approved | iOS has taken nothing |
| 5 | Invite staging's `firebase-adminsdk` account in Play Console (View financial data, Manage orders) | Staging purchases |
| 6 | Turn station closures on after 2.0 is out (AV2-8.4) | Built, flag off |
| 7 | Sign back in on the Pixel with your own account and re-add your widgets | The upgrade test removed them |
| 8 | Revoke the GitHub token in a comment in `StationlySyncer/local_scripts/staging_deploy.sh` (gitignored) | Security |

---

## 15. Lessons

- **Measure before concluding.** The widget flicker was 132 redraws, not "one
  animation is the limit"; the "nobody pays" question had no data at all until
  the funnel existed, and the funnel found a real bug (17 launches) within an
  hour.
- **Check the built thing, not the settings.** The release script verifies the
  artefact; the iOS script taught that every environment mix-up builds green.
- **Upgrades are a feature.** The v1 path broke in ways no fresh install shows:
  preferences of the wrong type, widgets with no station, boards only in the
  legacy list, a fifth station. Test it through Play, every release.
- **Shared code reaches iOS later, not never.** Every shared change was checked
  against "what does the live iOS 1.1 do with this" (served keys) and "what will
  the next iOS build do" (Q5).
- **Honesty is a product rule.** No invented totals, no "covered" with gross
  money, no dark patterns in the ask.
- **Tests must not touch production-like data.** The backend tests ran with real
  staging credentials and wrote rows; they are stubbed now.

---

## 16. Document index

| Document | For |
|---|---|
| [`android-v2/README.md`](android-v2/README.md), [`BOARD.md`](android-v2/BOARD.md), [`BACKLOG.md`](android-v2/BACKLOG.md), [`SESSIONS.md`](android-v2/SESSIONS.md), [`DECISIONS.md`](android-v2/DECISIONS.md), [`epics/`](android-v2/epics/) | The programme |
| [`android-v2/analysis/MIGRATION.md`](android-v2/analysis/MIGRATION.md), [`GAP_ANALYSIS.md`](android-v2/analysis/GAP_ANALYSIS.md), [`TEST_STRATEGY.md`](android-v2/analysis/TEST_STRATEGY.md) | Upgrade, gaps, testing |
| [`ANDROID_BUILD_AND_RELEASE.md`](ANDROID_BUILD_AND_RELEASE.md) | Building and shipping |
| [`RELEASE_REGRESSION.md`](RELEASE_REGRESSION.md) | The regression manual and run log |
| [`QA_2026-11-23_ANDROID_SWEEP.md`](QA_2026-11-23_ANDROID_SWEEP.md) | The QA sweep |
| [`SUPPORT_CONTRIBUTIONS.md`](SUPPORT_CONTRIBUTIONS.md) | Support, Play Billing, reach |
| [`USER_STATE_AND_ACTIVITY.md`](USER_STATE_AND_ACTIVITY.md), [`CROSS_DEVICE_SYNC.md`](CROSS_DEVICE_SYNC.md) | Boards, sync, activity |
| [`LINE_ALERTS.md`](LINE_ALERTS.md), [`APP_UPDATE_POLICY.md`](APP_UPDATE_POLICY.md), [`SDUI.md`](SDUI.md), [`CONFIG_KEYS.md`](CONFIG_KEYS.md) | Served behaviour |
| `stationly-backend/docs/SUPPORT_CONTRIBUTIONS.md`, `docs/STATION_STATUS.md` | Backend side |
