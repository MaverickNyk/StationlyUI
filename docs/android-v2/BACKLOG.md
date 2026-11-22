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
| AV2-10.11 | The widget at every size, as iOS draws it | L | 🔄 S021 · LARGE split + status backfill left |

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
