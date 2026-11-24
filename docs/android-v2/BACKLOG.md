# Android v2 — backlog

Index only. The board state is [`BOARD.md`](BOARD.md); the working detail is in
[`epics/`](epics/). Sizes: `S`=2, `M`=3, `L`=5, `XL`=8 points, where a point is
roughly one focused hour.

---

## EPIC-01 — Safety net · 9 pts · [detail](epics/EPIC-01-safety-net.md)

> Make it impossible to break the three contracts silently, and capture what v1
> does before anything changes it.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-1.1 | The gate, in CI | M | — |
| AV2-1.2 | Lock the v1 contract | M | — |
| AV2-1.3 | Capture v1's golden outputs | M | — |

## EPIC-02 — Database migration · 11 pts · [detail](epics/EPIC-02-database.md)

> The `.sq` file says Android owes one migration. It is right, and its own list
> of what that migration owes is wrong.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-2.1 | Reinstate migrations | L | AV2-1.1 |
| AV2-2.2 | Prove the migration | M | AV2-2.1 |
| AV2-2.3 | Close the drift permanently | M | AV2-2.2 |

## EPIC-03 — Host cutover · 21 pts · [detail](epics/EPIC-03-host-cutover.md)

> Make the shared Compose Multiplatform UI *be* the Android app.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-3.1 | Host the shared UI | L | AV2-2.2 |
| AV2-3.2 | Real actuals, batch A | L | AV2-3.1 |
| AV2-3.3 | Real actuals, batch B | M | AV2-3.2 |
| AV2-3.4 | Auth and deep links | M | AV2-3.2 |
| AV2-3.5 | The cutover | L | AV2-3.3, AV2-3.4, AV2-1.3 |

## EPIC-04 — Data plane · 18 pts · [detail](epics/EPIC-04-data-plane.md)

> Android's advantage. Feed the v2 board model from FCM, and stop a v1 device
> deleting a v2 device's work.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-4.1 | FCM at v2 | L | AV2-3.5 |
| AV2-4.2 | Topic lifecycle | L | ✅ S016 |
| AV2-4.3 | Cloud state dual-write | L | ✅ S016 |
| AV2-4.4 | Sessions and activity | M | ✅ S016 |

## EPIC-05 — Widget v2 · 16 pts · [detail](epics/EPIC-05-widget.md)

> One widget per station, and — unlike iOS — configurable from inside the app.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-5.1 | Per-instance widget binding | L | AV2-4.3 |
| AV2-5.2 | In-app widget manager | L | AV2-5.1 |
| AV2-5.3 | Widget updates and placement | M | ✅ S016 |
| AV2-5.4 | Widget guide | M | ✅ S016 |

## EPIC-06 — Daydream · 6 pts · [detail](epics/EPIC-06-dream.md)

> ✅ Done, S016. Q3 answered by taking the reversible option — the screensaver is
> `DreamService`, which is alive and well, not Daydream the VR platform.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-6.1 | Real dream actuals | M | ✅ S016 |
| AV2-6.2 | Host the shared dream | M | ✅ S016 |

## EPIC-07 — Release surfaces · 10 pts · [detail](epics/EPIC-07-release-surfaces.md)

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-7.1 | The update gate | M | ✅ S016 |
| AV2-7.2 | Config and quotas | L | ✅ S016 |
| AV2-7.3 | Support, built and off | S | ✅ S016 |

## EPIC-08 — Rollout · 11 pts · [detail](epics/EPIC-08-rollout.md)

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-8.1 | Release build integrity | L | 🔄 S016, needs a phone |
| AV2-8.2 | Upgrade verification on hardware | M | AV2-2.2, AV2-8.1 |
| AV2-8.3 | Ship | M | AV2-8.2 |

## EPIC-09 — The widget is the app's to run · [detail](epics/EPIC-09-widget-ownership.md)

> Raised by the owner after walking S017 on the phone.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-9.1 | Widget settings stop editing the user's board | M | ✅ S018 |
| AV2-9.2 | The manager earns its place | M | ✅ S018 |
| AV2-9.3 | Adding a widget ends on the home screen | S | ✅ S018 |
| AV2-9.4 | The pager bar belongs to the board | S | ✅ S018 |
| AV2-9.5 | Motion | M | ✅ S018 |
| AV2-9.6 | Copy | S | ✅ S018 |
| AV2-9.7 | Confirm the widget work fits the post-iOS model | S | ✅ S018 |
| AV2-9.8 | A widget belongs to the account that placed it | M | ✅ S018 |
| AV2-9.9 | One platform, drawn as two blocks | M | owner's product call |

## EPIC-10 — The widget behaves like the one on iOS · [detail](epics/EPIC-10-widget-parity.md)

> Raised by the owner 2026-09-13; extended 2026-11-21/22 by a customer report
> from Android 17 and the owner's review on a Pixel 10 Pro Fold.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-10.1 | The refresh button guards concurrency, not time | S | ✅ S019 |
| AV2-10.2 | Two animations, on manual action only, without flicker | M | ✅ S019 |
| AV2-10.3 | The touch targets are too small | S | ✅ S019 |
| AV2-10.4 | Adding a widget is one step, not two | S | ✅ S019 |
| AV2-10.5 | The manager says what a widget is | S | ✅ S019 |
| AV2-10.6 | The screensaver's platforms | M | ✅ S019 |
| AV2-10.7 | SDUI audit | L | ✅ S021 |
| AV2-10.8 | The Android 17 widget: rows, corners, updates | L | ✅ S021 |
| AV2-10.9 | Taps that do what they say | M | ✅ S021 |
| AV2-10.10 | Refresh that visibly happens | M | ✅ S021 |
| AV2-10.11 | The widget at every size, as iOS draws it | L | ✅ S021, S023 · LARGE split + status backfill DROPPED by owner 2026-11-23: Android widgets are placed as rectangles, not 4x4 squares |

## EPIC-11 — QA sweep of Android 2.0 · [detail](../QA_2026-11-23_ANDROID_SWEEP.md)

> A device pass over every screen and flow, 2026-11-23 (S023). Numbers are the
> QA doc's finding numbers. Every row says whether iOS gets the fix for free
> (shared code) or needs Swift.

| Story | Title | Size | State |
|---|---|---|---|
| AV2-11.1 | Nearby search: honest wording, 8 s cap, permission outside the cap (#1) | S | ✅ S023 · shared |
| AV2-11.2 | A typed search always beats the nearby lookup (#2) | S | ✅ S023 · shared |
| AV2-11.3 | System Back steps through add-station and closes the in-app browser (#3, #4) | S | ✅ S023 · shared; iOS edge-swipe only if enabled |
| AV2-11.4 | Direction summary names termini, not "Towards" (#6) | S | ✅ S023 · shared |
| AV2-11.5 | Bus routes in numeric order (#7) | S | ✅ S023 · shared |
| AV2-11.6 | Unlettered bus headers say where they go (#9) | M | ✅ S023 · shared incl. iOS widget feeds |
| AV2-11.7 | A stepping widget remembers its block, not its position (#5) | M | ✅ S023 Android · **iOS needs Swift** |
| AV2-11.8 | Widget row height at large text (#12) | S | ✅ S023 · Android only |
| AV2-11.9 | One logo, bottom-left, on every board surface | S | ✅ S023 · shared |
| AV2-11.19 | Widget and screensaver tick by the SERVED `board.tick.*` rules (grace, retention, "Gone") | M | ✅ S023 · `c1ffca4` · iOS already did |
| AV2-11.20 | Unlock refreshes widgets silently; only a press spins or rolls (a press during that refresh still does) | S | ✅ S023 · Android only |
| AV2-11.21 | Status marquee stutter: push redraws merged to one per widget per burst; app's 30 s poll stops redrawing the widget on Android | M | ✅ S023 · Android; shared VM change is a no-op on iOS |
| AV2-11.22 | Refresh roll goes row by row, not one curtain | S | ✅ S023 · Android only · owner checked on device |
| AV2-11.23 | "Add to Home Screen" placed two copies of one widget (Pixel launcher, gesture home); the app now goes home itself on accept | M | ✅ S023 · Android only · root-caused and verified on device |
| AV2-11.24 | `scripts/android-dev.sh` and `scripts/android-release.sh`: environment named, built artefacts verified (iOS-script parity) | M | ✅ S023 · see `docs/ANDROID_BUILD_AND_RELEASE.md` |
| AV2-11.25 | Line alerts: every rule served (`notify.*`), quiet by default, user level setting, config push; iOS plan in `docs/IOS_NOTIFICATIONS.md` | L | ✅ S023 · backend branch `dev_notification_policy` · device-verified overnight 2026-11-24 incl. a minified build, see `docs/LINE_ALERTS.md` |
| AV2-11.26 | v1 upgrade: widgets keep their station; v1-only boards survive on an account that also has an iPhone; CI gate uses the runner's JDK | M | ✅ device-verified 2026-11-24 with planted v1 data, see `analysis/MIGRATION.md` §6 · Android only; shared reconcile gains an opt-in flag iOS never sets |
| AV2-11.27 | v1 upgrade: preferences v1 saved as numbers/booleans broke "Set up a Board" (and could crash station settings); read as text now | H | fixed, unit-tested · device check needs build 6 on an upgraded phone · see `analysis/MIGRATION.md` §6 |
| AV2-11.28 | Play purchases were sent without the user token, so the server refused every one (401) and nothing was recorded | H | fixed, unit-tested · device check needs build 6 and a test purchase, mark checked after 15 min · see `SUPPORT_CONTRIBUTIONS.md` 8.7 |
| AV2-11.29 | A new widget starts at the full width and 2 rows (was 5x3; 4x2 in build 6 left a column empty on a 5-column Pixel) | S | done, AOSP's launcher rule run over 8 phone grids in a test · launcher offered 5x2 on a Pixel 11 Pro (5 columns) and 4x2 on an unfolded Pixel 10 Pro Fold (4 per half), checked with a probe app; the board itself not yet seen at it, Samsung/Xiaomi untested, a 6-column grid gets 5 |
| AV2-11.30 | Code review fixes: boards lost by a reconcile in a push-started process; offline sign-out hung on FCM; uncredited Play purchase retried only once per process; one station's lines or error landing on another; malformed line-status push could crash | H | fixed, tests for the first · not device-verified |
| AV2-11.31 | Forced sign-out (account deleted elsewhere) leaves the app on a dead session until restart: v1's auth observer was not carried over | M | open |
| AV2-11.32 | A deep link is handled again when the Activity is recreated (rotation, theme change) | S | open |
| AV2-11.10 | Landscape board and staging banner (#11) | M | open · device check first |
| AV2-11.11 | Store prices in the buyer's currency (#13) | M | open · **iOS needs Swift** |
| AV2-11.12 | Screensaver: both directions, board-location weather, "Route 39" (#14) | M | open |
| AV2-11.13 | Offline indicator in the app (#15) | S | open |
| AV2-11.14 | Widget manager: tell duplicates apart, back arrow (#16) | S | open |
| AV2-11.15 | List layout nested scroll; station-settings preview/header (#17, #18) | M | open |
| AV2-11.16 | Copy and polish batch (#19 to #28) | M | open |
| AV2-11.17 | "Select all" above the line limit (#8) | S | owner's product call |
| AV2-11.18 | Backend data: destination naming, Bank directions, a pole that stopped (#10, #32, #33) | M | backend |

---

## Not in scope for v2

Recorded so they are not rediscovered as gaps.

| Item | Why not |
|---|---|
| Glance widget rewrite | D3 — the RemoteViews provider is tuned and works. Post-launch. |
| WebSocket live stream on Android | Android has FCM. Porting an iOS workaround for an iOS-only constraint. |
| Widget refresh budget on Android | `RefreshBudgetStore.android.kt` returns null deliberately. WidgetKit rations timeline builds; AppWidget does not. |
| Play Billing | Q1 decides the route first. AV2-7.3 deliberately did not add the dependency — adding it pre-empts the decision. |
| Sign in with Apple on Android | iOS-only surface. `signInWithAppleInteractive` correctly stays unavailable. |
| Syncing appearance/arrangement state | Decided and reviewed: it is the highest-frequency, lowest-value state in the app. Device-local, per uid. |
| Enabling the tip jar on Android | D4 — the surface ships built and off, and since AV2-7.3 that is a platform capability (`checkoutSupported`) rather than a config flag somebody could flip by accident. Q1 resolves the policy route separately. |
