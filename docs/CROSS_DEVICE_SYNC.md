# Cross-Device Sync, Multi-Device Sessions & Board-Setup Flow (Android)

_Client design notes. Introduced in commit `e4d92c1` (branch `dev_25Apr`)._

Covers how the Android app keeps a user's state in lockstep across devices, how it
participates in the backend's per-device session model, and the fast board-setup
flow. Pairs with `stationly-backend/docs/SESSIONS_AND_SUBSCRIPTIONS.md`. Touch points:
`UserSyncCoordinator`, `DeviceIdProvider`, `FcmMessagingService`, `FcmTokenRegistrar`,
`FirebaseAuthManager`, `LoginViewModel`, `SelectionViewModel`, `UserSyncRepository`,
`StationLifecycleUseCase`.

---

## 1. Device identity (`DeviceIdProvider`)

A stable per-install `deviceId` (random UUID) is generated once and stored in a
**dedicated `StationlyDevice` SharedPreferences file** — NOT `StationlyPrefs`, which
`FirebaseAuthManager.logout()` wipes. It must survive logout so the same device
presents the same identity on re-login. `DeviceIdProvider.info()` also gathers
`DeviceInfo` (platform / OS version / model / app version).

`deviceId` + `deviceInfo` are sent in `SyncProfileRequest` on every login/profile sync
and in the `/user/logout` body, so the backend can track per-device sessions.

---

## 2. FCM token registration — register ON LOGIN

The backend can only push `user_sync` to **registered** FCM tokens. `FcmTokenRegistrar`
historically only ran on app launch (`StationlyApplication.onCreate`) and token
rotation (`onNewToken`) — so logging in within an already-running process left the
token unregistered for the new uid, and **every push reached 0 devices.**

**Fix:** `LoginViewModel.syncUserAndSyncData` (the common success path for
google/email/verify) calls `FcmTokenRegistrar.ensureRegistered()` right after sync.

> If cross-device push "doesn't work", check this first: the device's token must be in
> `users/{uid}/fcm_tokens`. The other historical cause was the shared-key rate limiter
> 429-ing `/user/fcm/register` (fixed backend-side by per-UID limiting).

On logout, `FirebaseAuthManager` **unregisters** the token (before sign-out, while the
token is valid) so pushes for the signed-out user can't reach a device that later signs
in as someone else.

---

## 3. Cross-device sync (`UserSyncCoordinator` + `UserSyncRepository.reconcile`)

One reconcile routine, two triggers:

1. **Push (real-time):** `FcmMessagingService` receives a silent `user_sync` data
   message → `UserSyncCoordinator.handleUserSync(ctx, reason, pushUid)`.
2. **Foreground fallback:** `MainActivity.onResume` → `reconcile(force=false)`
   (debounced **120 s**) — covers a missed push (offline / killed app).

`handleUserSync` **ignores the push if `pushUid != currentUser.uid`** (a token can
linger on a device that switched accounts), and only force-logs-out on `deleted` when
it matches.

`reconcile(uid)`:
1. Pre-warm a valid Firebase ID token (`getIdToken`) — at cold-start `onResume` the
   cached token may be missing; bail and retry rather than fire a tokenless 401.
2. `UserSyncRepository.reconcile` does a **non-destructive diff** of cloud `stations`
   vs local (keyed `id|line`): added → `setupStation`, removed → `discardStation`.
   Unchanged stations are untouched (no flicker, no dropped predictions).
3. Reload the Firebase user (surfaces a remote display-name change), ping
   `SummaryViewModel` (the `"selections"` SharedPrefs key) to re-read SQL, refresh
   widget + dream.
- Serialised by a `Mutex` so concurrent push + foreground can't race.
- **Safety:** `getUserProfile` throws a typed `UserNotFoundException` on **404**
  (account deleted) → reconcile force-logs-out. Transient (429/5xx) errors are caught
  and retried, leaving local state intact (never wipes a board on a failed fetch).

### Account-deletion propagation (defence in depth)

Deleting an account logs out every other device via three layers:
1. **`user_sync` `deleted` push** → `forceLogout` (instant, when the device is online &
   the app alive).
2. **Foreground 404** → reopening the app detects the deleted profile and logs out.
3. **Firebase token refresh** (~1 h) → SDK discovers the deleted user → `MainActivity`
   auth-state eviction.

`forceLogout` sets a one-shot flag in the `StationlySyncFlags` prefs file (survives the
logout wipe) so the login screen shows a brief "Your account was removed" notice.

> No system can instantly log out an **offline or force-killed** device — that's a
> physical limit. Such a device logs out on its next foreground / token refresh.

---

## 4. Board-setup flow (await data, async the rest)

`SelectionViewModel.onActionTriggered` ("Setup the board"):
1. `resolveStation` (await — need the exact stop) + `cleanupAll`.
2. **`StationLifecycleUseCase.persistAndFetch(selection)` — AWAITED.** Persists the
   selection and fetches its first predictions + line status into SQL, so the board
   renders **populated** instead of flashing a "no departures yet" empty state. The
   fetch is best-effort (a network hiccup still navigates; FCM/refresh fills in).
3. Navigate to the board.
4. **Detached `backgroundScope`:** `completeSetupAsync` (FCM topic subscribe + widget),
   `FreshDataNotifier`, and backend `syncStations` — none of these gate the board
   appearing.

`StationLifecycleUseCase` building blocks (single source of truth):

| Method | Awaited before nav? | Does |
|---|---|---|
| `persistAndFetch` | **yes** | save selection + clear stale + fetch predictions/line-status into SQL + freshness pings |
| `completeSetupAsync` | no (detached) | FCM topic subscribe + push fetched data to widget |
| `setupStation` | n/a | `persistAndFetch` + `completeSetupAsync` (used by login + reconcile, where blocking is fine) |

> Login and reconcile call `setupStation` (await both). Only the interactive setup
> flow splits them to keep navigation instant. `publishFreshData`/`fetchData` (earlier
> iterations) were removed during consolidation.

---

## 5. Logout (`FirebaseAuthManager.logout`)

Order matters — the auth-gated backend calls must run **before** `auth.signOut()`
(which nulls `currentUser`, the source of the bearer token):
1. Concurrently (in a `coroutineScope`, worst-case ~4 s instead of 7 s):
   - `POST /user/logout` with `deviceId` (ends this device's server session →
     last-device decrement),
   - unregister this device's FCM token.
2. Unsubscribe FCM topics for active selections (before wiping SQL).
3. `auth.signOut()` + Google sign-out → `MainActivity` auth-state observer evicts to
   login.
4. Wipe local SQL + SharedPrefs + widget.

Earlier this was fire-and-forget and raced `signOut()` → the request went out
tokenless (401) and neither the count decrement nor `loggedIn=false` ran. Awaiting
(capped) before sign-out fixed it.

---

## 6. SDUI note

The app stays SDUI-driven. The only hardcoded UI added is the **profile links fallback**
(`ProfileAboutFallback` in `ProfileScreen`), rendered through the same `SduiSection`
path **only while `getAboutLayout()` is loading/offline** so links appear instantly;
the server layout overrides it the moment it arrives. The board-delete loader is a
transient `LoadingOverlay` (modal), not content.

---

## 7. Known gaps / future work

- **Live display-name update:** reconcile reloads the Firebase user, but the UI may not
  recompose until next screen open (the auth-state listener doesn't always fire on
  `reload()`). Name changes reflect on next profile open.
- **Reconcile ignores `reason`:** it always does the full station diff + name reload.
  Correct and idempotent, just slightly more work than needed for a `profile`-only push.
- **`id|line` identity:** a direction/name change on the *same* station isn't detected
  by reconcile (one-board model makes this an edge case).
- **FCM rotation cleanup:** `onNewToken` doesn't unregister the previous token (the
  backend prunes dead tokens on push failure, so it self-heals).
- **Offline logout:** local sign-out succeeds offline, but the server session lingers
  until the TTL prune or next login elsewhere (a pending-logout retry would close this).
