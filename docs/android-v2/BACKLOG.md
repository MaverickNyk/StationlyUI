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
| AV2-4.2 | Topic lifecycle | L | AV2-4.1 |
| AV2-4.3 | Cloud state dual-write | L | AV2-4.2 |
| AV2-4.4 | Sessions and activity | M | AV2-4.3 |

## EPIC-05 — Widget v2 · 16 pts · [detail](epics/EPIC-05-widget.md)

> One widget per station, and — unlike iOS — configurable from inside the app.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-5.1 | Per-instance widget binding | L | AV2-4.3 |
| AV2-5.2 | In-app widget manager | L | AV2-5.1 |
| AV2-5.3 | Widget updates and placement | M | AV2-5.1 |
| AV2-5.4 | Widget guide | M | AV2-5.1, AV2-3.3 |

## EPIC-06 — Daydream · 6 pts · [detail](epics/EPIC-06-dream.md)

> ⚠️ Gated on **Q3**. Confirm Daydream survives into v2 before spending the sessions.

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-6.1 | Real dream actuals | M | AV2-3.2, Q3 |
| AV2-6.2 | Host the shared dream | M | AV2-6.1 |

## EPIC-07 — Release surfaces · 10 pts · [detail](epics/EPIC-07-release-surfaces.md)

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-7.1 | The update gate | M | AV2-3.5 |
| AV2-7.2 | Config and quotas | L | AV2-3.5 |
| AV2-7.3 | Support, built and off | S | AV2-3.5 |

## EPIC-08 — Rollout · 11 pts · [detail](epics/EPIC-08-rollout.md)

| Story | Title | Size | Depends on |
|---|---|---|---|
| AV2-8.1 | Release build integrity | L | all |
| AV2-8.2 | Upgrade verification on hardware | M | AV2-2.2, AV2-8.1 |
| AV2-8.3 | Ship | M | AV2-8.2 |

---

## Not in scope for v2

Recorded so they are not rediscovered as gaps.

| Item | Why not |
|---|---|
| Glance widget rewrite | D3 — the RemoteViews provider is tuned and works. Post-launch. |
| WebSocket live stream on Android | Android has FCM. Porting an iOS workaround for an iOS-only constraint. |
| Widget refresh budget on Android | `RefreshBudgetStore.android.kt` returns null deliberately. WidgetKit rations timeline builds; AppWidget does not. |
| Sign in with Apple on Android | iOS-only surface. `signInWithAppleInteractive` correctly stays unavailable. |
| Syncing appearance/arrangement state | Decided and reviewed: it is the highest-frequency, lowest-value state in the app. Device-local, per uid. |
| Enabling the tip jar on Android | D4 — the surface ships built and off. Q1 resolves the policy route separately. |
