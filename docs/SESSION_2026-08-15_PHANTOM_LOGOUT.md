# Handover — the app that logged itself out, and the widget that did not

_Session 2026-08-15. Branch `ios-parity` (StationlyUI). Two reported symptoms,
one shared root cause and one independent regression. Diagnosed off the
connected iPhone 11 (`AB7B04C8-…`) before a line was changed._

---

## 0. Read this first

Reported:

1. "The app automatically gets logged out after an hour or so — when I click on
   it again it appears to be logged out."
2. "Even after logging out the widget is not getting cleared; it keeps
   functioning as if nothing happened."

Neither was a backend problem, and neither was the deliberate force-logout
feature (§4 rules that out with evidence). Symptom 1 is a **race inside
FirebaseAuth that this app trusted**; symptom 2 is a **guarantee in
`USER_STATE_AND_ACTIVITY.md` that quietly regressed** when the widget extension
learned to fetch for itself.

| # | Where | Class | Severity |
|---|---|---|---|
| 1 | `AuthBridge.wireToKMP` auth-state listener | unsynchronised read treated as a sign-out | **high** |
| 2 | `ContentView` / `ComposeHostView` | a transient false sign-out becomes permanent | **high** |
| 3 | `DepartureBoardProvider` / `WidgetRefreshService` | signed-out account keeps a live board | high |
| 4 | `DevicePushCoordinator.handle` | deleted-account guard disarmed by #1 | medium |

---

## 1. The evidence, before the diagnosis

Pulled from the device with no instrumentation and no rebuild:

```
xcrun devicectl device copy from --device AB7B04C8-… \
  --domain-type appDataContainer --domain-identifier com.stationly.mobile \
  --source Library/Preferences --destination ./appdata
```

`com.stationly.mobile.plist` held **16 keys**. Of the nine `AuthBridge` writes:

| Key | Present? |
|---|---|
| `firebase_auth_token` | **yes** |
| `firebase_user_uid` | no |
| `firebase_user_email` | no |
| `firebase_user_display_name` | no |
| `firebase_user_photo_url` | no |
| `signin_provider` | no |
| `member_since` | no |
| `firebase_user_email_verified` | no |
| `firebase_user_is_email_provider` | no |

Decoding the token that *was* there:

```
user_id   HdNrDVNLO1fD7hkolI5xFYOWz2H3
email     testnyk67@gmail.com
iat       2026-08-15 15:24:54     ← issued minutes before the pull
exp       2026-08-15 16:24:54
auth_time 2026-08-14 22:43:10     ← the refresh token is alive and healthy
```

**That combination cannot be produced by any single code path.**
`persistUserIdentity` writes all nine; `clearUserInfo` removes all nine. The only
way to reach "token present, identity absent" is a `clearUserInfo()` landing
*inside* an in-flight `refreshTokenIfNeeded()`:

```
persistUserIdentity(user)                      ← 9 keys written
await user.getIDToken(forcingRefresh: true)    ← suspends
        …clearUserInfo() runs here…            ← 9 keys removed
set(token, forKey: "firebase_auth_token")      ← 1 key written back
```

So: a wipe happened while the session was demonstrably fine. Corroborated in the
App Group, which held a `widget_boards_v1::anon` bucket beside the real
`widget_boards_v1::21b0faf9…` — KMP writing state while `firebase_user_uid` read
back null, falling through to `UserSettings.NO_USER`.

---

## 2. Finding 1 — the listener cannot be trusted at launch

FirebaseAuth 11.15.0, `FirebaseAuth/Sources/Swift/Auth/Auth.swift`:

```swift
// line 174 — the public accessor
@objc public var currentUser: User? {
    kAuthGlobalWorkQueue.sync { _currentUser }        // ← barrier
}

// line 1422 — the listener's FIRST invocation
DispatchQueue.main.async {
    listener(self, self._currentUser)                 // ← raw ivar, no barrier
}

// line 1689 — where the user actually comes from
private func protectedDataInitialization() {
    kAuthGlobalWorkQueue.async { … try self.getUser() … }   // ← asynchronous
}
```

The Keychain user is loaded **asynchronously**. `currentUser` synchronises on the
same serial queue, so it blocks until that load lands and can never report a live
session as absent. The listener's initial callback reads the raw ivar from the
main queue instead, with nothing ordering the two — so at a cold launch it can
hand you `nil` for an account that is perfectly signed in.

`AuthBridge` believed it:

```swift
if let user { … } else { self.clearUserInfo() }        // ← the bug
```

**The cost.** With `firebase_user_uid` gone, every uid-namespaced store resolves
to `anon`: the user's boards, home layout and per-account settings all belong to
somebody who does not exist. `ProfileViewModel` has *two* separate workarounds
for downstream symptoms of this (`isIdentityLoading`, and a polling loop in
`loadProfile` waiting for keys to appear) — both written against the effect, with
the cause still live.

**Why "about an hour".** This is cold-launch-only. iOS keeps a suspended app
resident for roughly that long, so returning sooner is a warm resume and never
re-runs `wireToKMP()`. The 30-minute `BGTaskScheduler` wake (`bgtask scheduled in
30m` in the push trace) runs `didFinishLaunching` too, so the state can be
poisoned without the user opening anything.

### The fix

`settleAuthState()` discards the listener's argument and consults
`Auth.auth().currentUser` — the same value, through the barrier. Safe from there:
every invocation path is the main queue (the initial `main.async`, and a
notification observer registered `queue: .main`), never Firebase's work queue.

And the rule is now asymmetric on purpose: **only a sign-out this app asked for
may clear anything.** `expectingSignOut` is set by `logout()`, which every
deliberate teardown routes through — the KMP `signOut` command,
`signOutForAccountDeletion`, and the confirmed-gone branch of
`refreshTokenIfNeeded`. A `nil` nobody asked for changes nothing and is traced:

```
auth nil we didn't ask for — session kept
```

Holding stale identity for one more launch is invisible. Discarding a live one
takes the user's account away in front of them.

---

## 3. Finding 2 — why a millisecond of wrongness lasted the whole session

Even once Firebase resolves, nothing told the UI.

- `ComposeHostView.makeUIViewController` reads `startLoggedIn` **once**;
  `updateUIViewController` is a no-op.
- `AppNavigation` turns it into `startDestination`, which never changes for the
  life of the host.
- `.id(signedOutGeneration)` only moved on logged-in → logged-**out**.

So a host that booted signed-out stayed signed-out however the session resolved
afterwards: a sign-in form with the user's own valid session behind it, and no
way out but to sign in again. Two changes:

1. `isLoggedIn` now starts from `AuthBridge.hasSession` — `currentUser`, falling
   back to the stored token. That token is removed only on a real sign-out, so it
   cannot claim a session that has ended, and it covers the one case `currentUser`
   is wrong about: a Keychain read that failed because the device was locked or
   prewarmed, which Firebase retries on `protectedDataDidBecomeAvailable`.
2. The generation now also bumps on signed-out → signed-**in**, but only when
   `AuthBridge.isHandlingAuthCommand` is false.

That guard is the whole reason this is safe to do at all. The commit history is
explicit that rebuilding on sign-in "threw away the login flow's own navigation …
and crashed outright once there was more than one board to rebuild" — but that is
a sign-in **the Compose login flow performed**, which arrives as a KMP command. A
restore arrives on its own. `isHandlingAuthCommand` separates them, and carries a
10-second tail because `markDone()` runs on an earlier main-queue turn than the
notification it races.

---

## 4. Ruling out the deliberate force-logout

Worth stating, because it is the obvious suspect and it is not guilty. Both paths
that can end a session from outside — `refreshTokenIfNeeded`'s `isSessionGone`
branch, and a `user.sync` push with `reason = "deleted"` — write to
`PushTraceSwift` before acting:

```
token refresh → account gone, signing out
user.sync deleted → forcing sign-out
widgetpush recv kind=… reason=…
```

The 40-entry ring pulled from the device contained none of them. Conclusively:
both end in `logout()` → `clearUserInfo()`, which deletes the token — and the
token on the device was **issued after** any such event could have run.

### Finding 4 — but the guard was disarmed

```swift
if let pushUid, let currentUid, pushUid != currentUid { return false }   // was
```

`currentUid` is `firebase_user_uid` — the key finding 1 wipes. A missing local
uid did not fail the check, it **skipped** it, leaving a `deleted` push minted for
another account free to sign this one out. The two bugs compounded: the identity
race disarmed the safety check on the one push that can end a session.

Now a push that names a uid must match:

```swift
if let pushUid, pushUid != currentUid { return false }
```

Refusing costs a real deletion nothing — `refreshTokenIfNeeded` asks Google
directly on every foreground and ends the session on `.userNotFound`, no more
than one foreground later.

---

## 5. Finding 3 — the widget outlived the logout

`USER_STATE_AND_ACTIVITY.md` is unambiguous:

> A signed-out account must not leave a live departure board on someone's home
> screen.

That held while the widget could only be *given* data. It stopped holding when
the extension learned to go and get its own:

- `DepartureBoardProvider.timeline` fetches whenever what it holds is older than
  `staleAfterSeconds` (120 s);
- through `WidgetRefreshService`, authenticated by `widget_api_key` — **not** the
  user's token;
- for a station named in an `AppIntent` configuration that nothing on the app
  side can read or erase.

So `cleanupAll()` → `clearWidgetData()` → `wipe()` held for about two minutes,
and then the board came back, fully live, for an account that had signed out.

Deleting data cannot fix this, because the widget can always re-derive it. The
sign-out has to be **stated**, in the one place both processes can see:
`widget_signed_out`, declared in all the `AppGroupKeys` copies that need it.

| Side | Behaviour |
|---|---|
| `AuthBridge.logout()` | **raises** it, plus an immediate `reloadAllTimelines()` |
| `IosWidgetManager.clearWidgetData` | **raises** it — reached only from `cleanupAll()` |
| `AuthBridge.persistUserIdentity` | **lowers** it — Firebase produced an actual user |
| `AppGroupStorage.readWidgetData` | returns `WidgetData.signedOut` before any repoint |
| `DepartureBoardProvider.timeline` | skips the staleness fetch |
| `WidgetRefreshService.refresh` | returns `.unavailable` — covers the refresh button and the push handler |
| `EmptyWidgetView` | "Sign in to see your board", not "Open the app to add a station" |

### Why both edges ended up in Swift

The first version raised the flag in `clearWidgetData` and lowered it in
`refreshAllBoards` — the documented relight path (`restoreBoards` →
`setupStation` → `completeSetupAsync` → `updateWidget`). It is wrong in **both**
directions, and the second one is the worse bug:

- A `reconcileBoards` or live-stream frame still in flight at sign-out can write
  selections back into SQL and reach that line with nobody signed in — relighting
  a widget for an account that has gone.
- A user who signs back in **with no boards saved** takes `refreshAllBoards`'s
  `all.isEmpty()` branch and never reaches a board write at all. Their widget
  would read "Sign in to see your board" while they are signed in: the widget
  lying about them, with an instruction that does not work.

So both edges belong to the one side that knows whether there is a session.
Raised in `logout()` **and** `clearWidgetData` because the two halves of a
sign-out run in opposite orders — `ProfileViewModel.signOut` signs out of
Firebase *before* `cleanupAll()`, `signOutForAccountDeletion` *after* — so
either alone can be undone by whatever ran last. Lowered in
`persistUserIdentity`, which runs on sign-in, on every keychain restore and on
every token refresh, and only ever with a real user in hand.

It is also **not** the same as "no stations": a signed-in user who deletes their
last board wipes to the same empty App Group and must keep the refresh path they
have. `refreshAllBoards`'s own `wipe()` therefore never raises the flag.

`readWidgetData` checks it **first**, ahead of the repoint chain — that chain
exists to find an orphaned widget something to show, which after a sign-out would
hand it the board of whoever signs in next.

---

## 6. What this does NOT change

- Multi-session behaviour is untouched. Boards and filters still sync; settings
  stay device-local and per-uid via `saveDurable`; signing out and back in as the
  same person on the same device still returns their arrangement.
- Account deletion still reaches `forgetAccount(uid)`, and still runs **before**
  `logout()` — the ordering `SESSION_2026-08-13_STATE_REVIEW.md` §5 calls
  load-bearing.
- No backend change, no schema change, no migration.

---

## 7. Finding 5 — the write that resurrected the token

Found while verifying on device, and it is the other half of finding 1: the
mechanism that turns a wipe into a *torn* state rather than a clean one.

```swift
let token = try await user.getIDToken(forcingRefresh: true)   // network round trip
UserDefaults.standard.set(token, forKey: "firebase_auth_token")
```

A `clearUserInfo()` landing inside that await finds the token already fetched and
about to be written — so the write puts back the one key that had just been
removed. That is how "valid token, no identity" is produced, and it is what makes
the damage **permanent**: every reader of the identity sees keys that are gone,
while every check for "is there a session" sees a token that is there, and
`refreshTokenIfNeeded` returns early (`guard let user`) so nothing repairs it.

The write is now guarded on the state **after** the await:

```swift
guard !expectingSignOut, Auth.auth().currentUser != nil else { return }
```

### The activity log dated it to the second

`ActivityEventEntity` stamps every event with `firebase_user_uid`, and survives
`clearAllData` by design — so it is a record of when the app could still see who
it was:

```
2026-08-14 22:43:12  auth.logged_in  HdNrDVNLO1fD7hkolI5xFYOWz2H3   ← last auth event of any kind
2026-08-15 15:24:53  widget.count    HdNrDVNLO1fD7hkolI5xFYOWz2H3   ← last event with a uid
2026-08-15 15:24:54                  (token iat — uid absent from every event after)
```

**No `auth.logged_out` and no `auth.account_deleted` after the sign-in.** The user
never signed out, and nothing forced them out through a path this app owns.

### What the traces could NOT settle

Separately, and later, Firebase's own Keychain session on that device ended:
`Auth.currentUser` returned nil through the barrier, and the stored token had not
been refreshed since 15:24:54 despite foregrounds at 16:03 and 16:23. So it was
already gone before this session's re-signing, which rules out an entitlement
change from the rebuild.

What ended it is not recoverable from what is on the device: it is either
`refreshTokenIfNeeded`'s `isSessionGone` branch or FirebaseAuth's own
`signOutByForce` (`User.signOutIfTokenIsInvalid`, which this app does not trace),
and the 40-entry `push_trace` ring had rolled over the window. Both mean a token
refresh returned one of `userNotFound / userDisabled / userTokenExpired /
invalidUserToken` — routine on this staging project, whose activity log records
six `auth.account_deleted` events in one afternoon.

**It is independent of finding 1 and both are handled.** The identity wipe was
the listener race; a genuinely dead session now produces a clean signed-out app
instead of a zombie one (§3).

---

## 8. Verified on device (iPhone 11, iOS 26, staging)

**The fix firing, first build:**

```
16:23:22  auth nil we didn't ask for — session kept
```

The listener delivered nil, and the token and identity keys were preserved. The
previous build deletes all nine here.

**The recovery, after finding 5's fixes:** cold launch on a device holding the
torn state (orphaned token, no uid, no Firebase user):

| | before | after |
|---|---|---|
| `firebase_auth_token` | orphaned, unexpired | cleared |
| `firebase_user_uid`, email, provider | missing | cleared (consistent) |
| `widget_signed_out` | absent | `True` |

So the app presents the login screen and the widget reads "Sign in to see your
board", instead of a home screen with a "?" avatar and a "User" name that no
amount of waiting resolves.

**Sign in:** all nine identity keys written (`uid`, `testnyk67@gmail.com`,
"Njk Hhj", provider `Google`), and `widget_signed_out` **removed by
`persistUserIdentity`** — the relight needs no separate mechanism.

**Deliberate sign-out**, confirmed by the user on the home screen ("the widget
shows *Sign in to see your board*"), and in the App Group:

| | |
|---|---|
| `firebase_auth_token`, `firebase_user_uid` | gone |
| `widget_signed_out` | `True` |
| `widget_stations` | wiped |
| `widget_board_*` | wiped |

**Cold launch:** force-quit and reopened from the home screen — still signed in,
boards and name intact. This is the original symptom, and it no longer occurs.

### ⚠️ Do not verify auth state with `devicectl device process launch`

Three CLI cold launches "failed" this test before the method was questioned:
each logged `auth nil` and cleared the identity, seconds after a successful
sign-in. The cause is the harness, not the app — **a devicectl-launched process
cannot read the Keychain**, so `Auth.protectedDataInitialization` fails and
`currentUser` is nil on a device that is genuinely signed in.
`isProtectedDataAvailable` is `true` throughout, so it does not catch this: the
failure is entitlement-shaped, not lock-shaped.

Install with `devicectl device install app`, then have the user open it from the
home screen. That run passed immediately.

**It also changed the fix.** The recovery branch originally deleted the token and
identity whenever Firebase reported no user with the Keychain readable — which,
on this evidence, can be wrong about a live session. The two consequences are now
split by what it costs to be wrong:

- **Blanking the widget** is cheap and self-reversing (the next
  `persistUserIdentity` lowers the flag), and it is the only way a sign-out
  Firebase performs *itself* — `User.signOutIfTokenIsInvalid`, which never
  reaches our `logout()` — can reach the home screen. Acted on the weak signal.
- **Deleting credentials** is not reversible without the user signing in again,
  so it is now `discardTornIdentity()` — and only in **one direction**.

#### The asymmetry, which a first version got wrong

A token with no uid cannot be produced by any correct ordering: both writers put
the identity down first or alongside (`storeUserInfo` sets the token then calls
`persistUserIdentity`; `refreshTokenIfNeeded` calls `persistUserIdentity` before
it even asks for a token), and `clearUserInfo` removes all nine together. That
shape is debris — exactly what finding 5 leaves behind — and nothing else.

A uid with no token is **ordinary**, and the first version of this check deleted
it. `persistUserIdentity` writes the uid and does *not* write a token;
`settleAuthState` calls it synchronously and only then hops to
`refreshTokenIfNeeded` for the token. Every moment between those two — and every
refresh that fails offline — leaves a healthy session in precisely that shape.
Treating the mismatch symmetrically would delete a signed-in user's credentials
for being briefly half-written, which is the failure the method exists to avoid.

**The widget stays blank.** This is the test the old build fails, so it is the
one that matters. Sign-out at ~16:31; the extension rebuilt its timeline at
16:32:54, 16:34:24 and 16:34:35 — every one of them past `staleAfterSeconds`:

```
16:32:54 timeline quiet read=0ms tick=0ms entries=19 tier=P4 …
16:34:24 timeline quiet read=0ms tick=0ms entries=19 tier=P4 …
16:34:35 timeline quiet read=0ms tick=0ms entries=19 tier=P4 …
```

`widget_stations` and every `widget_board_*` still wiped, and **not one
`refresh targets=…` line** — the fetch never happened. Note there is no
`refresh skipped — signed out` line either: `readWidgetData` returns
`WidgetData.signedOut` and the timeline short-circuits before
`WidgetRefreshService` is reached at all, which is the cheapest correct path.
That guard stays in the service regardless, for the refresh button and the push
handler, which do not come through the provider.

### Still to confirm

- [ ] The original one-hour symptom, after a real eviction rather than a forced
      relaunch. Left with the user to observe.
