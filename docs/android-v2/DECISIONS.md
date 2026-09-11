# Android v2 — decision log

Decisions that bind future sessions. A session may **not** reverse one of these
on its own: raise it as an open question on the board and let the owner decide.

Format: what was decided, what it rules out, and what would have to be true to
revisit it.

---

## D1 — Branch from `ios-parity`, not `master`
**2026-09-05 · owner**

`dev_android_bring_to_v2` is cut from `ios-parity` @ `b7b7a1c`.

**Why.** `ios-parity` is a strict superset of `master`: 117 commits ahead, 0
behind, and `main` is an ancestor of `master`. The shipped Android app still
compiles there. Branching from `master` would have meant cherry-picking every one
of those 117 commits back, for no gain — prod safety comes from the
`release_prod` branch and `versionCode`, not from where a dev branch starts.

**Rules out.** Any framing of this work as "merge the iOS branch back". There was
never an Android branch to merge.

**Consequence, and it is the important one.** `StationlyDatabase.sq` removed its
migrations on the stated premise that *"no new Android release is planned"*. D1
falsifies that premise. Migrations come back — see EPIC-02.

**Revisit if.** Never, realistically. The alternative got strictly worse the
moment M2 (the Android app compiles on `ios-parity`) was measured.

---

## D2 — `:android:app` adopts the `:composeApp` shared UI
**2026-09-05 · owner**

The Android app depends on `:composeApp` and hosts `App()` in an Activity. The
~20 native screens under `com.stationly.mobile.ui` are deleted.

**Why.** `:composeApp` — the whole iOS v2 UI, 101 files — already compiles for
the Android target today. Re-implementing multi-line selection, branch filters,
station settings, home settings, the carousel, SDUI and support against the
native UI would roughly triple the work and would permanently fork the two
platforms, so every future feature ships twice. That is the opposite of why this
is a KMP project.

**Accepts.** JetBrains `navigation-compose` and `lifecycle-viewmodel-compose` at
`2.9.0-beta01` in a production Android app. iOS already ships on them. Mitigated
by pinning exact versions and by a nav back-stack regression test — v1 already
shipped a blank-screen bug caused by a navigation/Compose version mismatch.

**Rules out.** The hybrid option (shared UI for new screens, native for the rest).
Two theme systems and two navigation stacks in one app is a worse place to live
than either endpoint.

**Revisit if.** The beta artifacts prove unstable in a release build under R8,
which AV2-8.1 is the gate for.

---

## D3 — The widget stays RemoteViews; per-instance config is added
**2026-09-05 · owner**

`DepartureWidgetProvider` keeps its rendering. What gets built is an
`appWidgetId`-keyed station binding, a configuration Activity, and an in-app
widget manager.

**Why.** The existing 758-line provider is tuned: FCM-driven redraw, an ETA
watchdog for when FCM goes quiet, and exact control over the dot-matrix layout.
Glance would be a full rewrite of a working surface, a new dependency, and its
own layout constraints on a board whose typography is already pinned down.

**Rules out.** Glance for v2. It is post-launch work, tracked in the backlog's
out-of-scope list.

**Revisit if.** Per-instance configuration turns out to fight RemoteViews harder
than expected — but note that `AppWidgetManager` gives Android something iOS
cannot have at all (the app can read and write its own widget bindings), and that
capability is independent of the rendering technology.

---

## D4 — Support surfaces are built, and gated OFF for the v2 launch
**2026-09-05 · owner**

The Android support/tip surfaces are built behind the same `enabled` flag iOS
uses, and v2 ships with them disabled. `SupportCheckout.android` stays stubbed.

**Why.** Google Play bills digital goods through Play Billing. A general "tip the
developer" flow through an external Stripe checkout on a live app is the option
most likely to draw a policy rejection, and a policy review is not something to
put on a launch's critical path.

**Rules out.** Shipping the external checkout on Android in v2.

**Open.** Q1 — Play Billing, or a charity/non-profit exemption for external
checkout. Resolved separately, after launch. The surface ships off either way, so
the answer does not gate v2.


---

## D5 — The host keeps the v1 class name; the shared UI moves into it
**2026-09-06 · S011 (AV2-3.5)**

At the cutover, `com.stationly.mobile.MainActivity` was rewritten to host
`App()` rather than being deleted in favour of promoting `.v2.V2MainActivity`.

**Why.** Home-screen pins and launcher shortcuts reference the *component name*,
not the package. Promoting the v2 name and deleting the v1 one greys out the
icon of every existing user who pinned Stationly to their home screen — with no
error, no log line, and no recourse but re-adding it. The alternative was an
`<activity-alias>`, which works and is one more indirection to explain forever.

**Rules out.** Renaming or repackaging the launcher Activity, ever, while
`versionCode 2` installs exist in the wild. `HostManifestTest` asserts the name.

---

## D6 — Apple sign-in is a platform capability, asked of the provider
**2026-09-06 · S011 (AV2-3.5)**

`PlatformAuthProvider.supportsAppleSignIn` is abstract, with no default, and the
landing screen asks it before rendering the button.

**Why.** The shared landing screen offered "Continue with Apple" on Android for
as long as Android has run the shared UI, and the only outcome was an apology.
It is a property of the platform's auth stack, not of the build, not of a
compile-time `expect`/`actual`, and not of a config key — the provider is
already the seam where "what can this platform's auth actually do" is answered.

**No default, deliberately.** `= false` would let a third platform inherit "no
Apple" silently, which is the same class of mistake in the other direction. A
new platform has to answer.

**Rules out.** Gating this on `BuildConfig`, on SDUI config, or on an
`expect val`. Also rules out deleting `signInWithAppleInteractive` from the
Android provider: a future screen that forgets to ask should find a failure
carrying a sentence a user can read, not a `TODO()`.

---

## D7 — `boards` is the one list the client believes; `stations` is a projection
**2026-09-11 · S016, on the owner's 2026-09-06 direction**

Both platforms WRITE `boards` and READ `boards`. `stations` is written beside it,
after an accepted boards write and never on its own, as a lossy flat projection
for a v1 Android device on a shared account.

**Why.** The alternative was already running and it deleted people's boards.
Android wrote one array and reconciled against the other, and the backend derives
neither from the other on a write — so a board saved on Android left no trace in
the list its own next foreground diffed against, and that diff removes anything
the cloud does not have.

**Rules out.** Reading `stations` as an authority anywhere in the client. The
legacy `UserSyncRepository.reconcile` is deleted rather than deprecated,
because a correctly-named function that quietly destroys the user's boards is
worse than no function.

**The ordering is part of the decision.** The projection goes SECOND.
`/user/sync/stations` has no staleness check and no empty guard;
`/user/sync/boards` has both. Writing the projection first would mean a client
with a momentarily empty database wiping the legacy array while the guarded
endpoint protected the real one.

**Revisit if.** Q2 answers. Retiring the dual-write is a deletion at one call
site (`UserStateRepository.pushBoards`) and needs a measured v1 population, not a
date.

---

## D8 — A platform capability, not a config flag, decides what a platform offers
**2026-09-11 · S016**

Where a surface needs the platform to be able to DO something, the gate is an
`expect val` and the config is ANDed with it. `SupportCheckout.checkoutSupported`
is the first; `InAppUpdate.supported` is the second.

**Why.** `SupportMoneyConfig.enabled` comes from the backend, so it can be turned
on for every client at once with no release. Since the cutover the shared
composables ARE the Android app, so one config change would have put the whole
money surface in front of Android users, attached to a checkout that is a
deliberate no-op. The old protection was a comment explaining why that could not
happen, and the comment had been false since AV2-3.5.

**Rules out.** "Ships built and off" meaning a document somebody might edit. Off
is a property of the platform until the platform can honour it.

**Revisit if.** Q1 answers the Play policy route. The change is then one boolean
and one function in `SupportCheckout.android.kt`, and no surface has to be found
again.

---

## D9 — One delivery mechanism per signal
**2026-09-11 · S016**

A signal has exactly one path to each consumer. Three were removed this session
for having two:

- the dream's `ACTION_DREAM_REFRESH` broadcast, beside `FreshDataNotifier.events`
- `DeviceIdProvider`, beside `DeviceIdentity` — both able to MINT the id
- v1's `DreamSettingsActivity`, beside the shared `DreamSettingsScreen` — both
  editing the same four values in the same file

**Why.** Two mechanisms for one thing do not fail together. They drift, and the
one the user reaches depends on which door they came through, which makes the
symptom unreproducible.

**Rules out.** "Keep the old path as a fallback." A fallback for a path that
works is a second implementation with no tests and no readers.

**Revisit if.** A platform genuinely cannot reach the shared mechanism. That was
true of v1's dream, which is why the broadcast existed, and it stopped being true
the moment the dream ran shared code.

