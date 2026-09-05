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
