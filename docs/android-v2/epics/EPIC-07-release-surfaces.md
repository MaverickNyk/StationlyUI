# EPIC-07 — Release surfaces · 10 pts

**Goal.** The surfaces that make a live app manageable after it ships: the update
gate, server-driven config and quotas, and the support surface (built, and off).

**Exit.** The app can be told to update itself, its limits come from config, and
the money surface exists behind a flag nobody has turned on.

---

## AV2-7.1 — The update gate · `M` · Review (S016)

**Depends on:** AV2-3.5 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.7
**Files:** `ReleaseGate` wiring, `UpdateSurfaces`, `android/app/build.gradle.kts`

`ReleasePolicy` already carries an `android: PlatformRelease` alongside `ios`, so
the backend side is done. Only the client wiring is missing.

> ### ⚠️ Read before planning: the gate is ALREADY LIVE on Android
> AV2-3.5 made `App()` the whole Android app, and `App()` calls
> `UpdateSurfaces()` at the root, unconditionally, above every screen. So task
> (a) is not "wire it" — it is wired, it is reading whatever
> `/release-policy` serves, and **it can already block the app**.
>
> That moves one sub-task to the front. `UpdateBlockedScreen` is opaque,
> consumes every gesture, and offers exactly one action. If the backend's
> `android` block carries an `itms-apps://` deep link — or an
> `apps.apple.com` web URL — then that one action opens nothing on Android and
> the user has no way past the screen at all. `rememberStoreOpener` falls back
> from the deep link to the web URL only when the first *throws*; a handler
> that returns having opened nothing is indistinguishable from success, and
> the KDoc says so. Check what the backend actually serves for
> `android.storeUrl` / `android.storeUrlWeb` before anything else here.

### Tasks
- [x] **a.** **Verify** `ReleaseGate` and `UpdateSurfaces` on Android. *(Done
      first, as the warning says. The trap is NOT set — see the findings.)*
- [x] **b.** **Play In-App Updates** for the soft path. *(`InAppUpdate`, an
      expect/actual seam: FLEXIBLE for the nudge, IMMEDIATE for the block, and a
      store link whenever Play answers no.)*
- [x] **c.** `storeUrl` for the hard path. *(Was already correct; now the
      fallback rather than the only route.)*
- [x] **d.** Verify the gate against a version below `minVersion` and one above.
      *(`ReleaseGateTest` covers the comparison; the Android-specific half is
      that `Platform.getPlatformName()` returns `"Android"` and `ReleaseGate`
      lowercases before matching, so the `android` block is the one selected.
      Both floors sit at 1.0 today, so every client resolves to `Ok` — the
      correct resting state.)*

### Why this matters more than it looks
The version floor in `ReleasePolicy.android.minVersion` is what eventually
retires risk R2 — a v1 device wiping a v2 device's boards. It cannot be set until
this ships, and it is the only lever that ends the `stations` dual-write.

### Acceptance criteria
- [x] A build below the floor is blocked and offers the update. *(Logic tested;
      needs a doctored policy on a device to see.)*
- [x] The update button on the blocking screen opens the **Play Store**, on a
      device with Play and on one without. *(`market://` throws
      `ActivityNotFoundException` where Play is absent, which is the one failure
      the opener's `runCatching` can see, and it falls through to the https
      listing.)*
- [x] A soft update completes without leaving the app. *(Built. **Cannot be
      verified before an internal-track release** — the Play API only answers for
      an app installed from Play.)*

### Findings

#### The trap this story opens with is not set — the backend serves Android correctly

The warning at the top says to check `android.storeUrl` before anything else,
because an `itms-apps://` link there would leave an Android user with no way past
the blocking screen. `AppReleaseService.POLICY.android` serves
`market://details?id=com.stationly.mobile` and the https Play listing. Correct,
and the `assertSafe` guard on the backend refuses a policy where either is blank.

The same audit found the bug one screen over: **the About layout's "Rate
Stationly" row is a `market://` URL served to both platforms**
(`sduiService.getAboutLayout`). It is right for Android and does nothing on an
iPhone. That is AV2-7.2's audit finding, logged there.

#### Android can take the update in the app, and that is the whole reason not to copy iOS here

`InAppUpdate` is the seam. The nudge starts a FLEXIBLE update — Play downloads in
the background, the app stays usable, and a dialog asks before the restart that
installs it. The blocking verdict starts an IMMEDIATE one, which is not an
escalation: the blocking screen already says "you cannot use this until you
update", and Play's immediate flow says exactly that and then performs it. The
one button stops being "go and find the update".

Every path answers false rather than throwing — sideloaded builds, no Play Store,
no update available, a flow the user cancels — and false means "open the
listing", which is what the app did before and is never wrong.

**Nothing here can be verified on this branch.** The API only answers for an app
installed from Play, so every development build gets `UPDATE_NOT_AVAILABLE`. That
is correct behaviour and not a bug to chase; it is also why the fallback had to
be the same code path rather than a separate branch.

### Handoff notes — S016, 2026-09-11

`com.google.android.play:app-update-ktx:2.1.0` is a new dependency, in
`:composeApp`'s **androidMain** (the surfaces that use it are shared composables,
so the expect/actual has to resolve inside that module). AV2-8.1 should check it
survives R8 — Play Core ships its own consumer rules, so no manual `-keep` should
be needed, but "should" is the word that story exists to remove.

**The first real verification is the internal track.** Put build N on it, then
build N+1, and open N: the nudge should download in the background and the
"Update ready" dialog should appear. There is no way to see it before that.

---

## AV2-7.2 — Config and quotas · `L` · Review (S016)

**Depends on:** AV2-3.5 **Files:** SDUI config consumers, `BoardQuota`, `BoardPolicy`

### Tasks
- [x] **a.** Audit the `android` platform key across the SDUI config surface.
      *(Done, and the headline is that **there is no platform key** — see the
      findings. Two payloads are platform-specific in their content and neither
      says so.)*
- [x] **b.** Board and line quotas via `BoardQuota` / `BoardPolicy`, with
      `StationLimitSheet` as the surface. *(Shared, wired at both call sites —
      `SelectionScreen` and `SummaryScreen` — and running on Android since the
      cutover. `BoardQuotaTest` / `BoardPolicyTest` cover the rules.)*
- [x] **c.** The announcement banner. *(Shared: `SummaryViewModel.fetchAnnouncement`
      → `SummaryScreen`, with the dismiss key persisted. Live on Android, and it
      is what shows the staging notice on a staging build.)*
- [x] **d.** Confirm the offline fallbacks render. *(Every SDUI surface opens on
      its compiled default and replaces it if a fetch succeeds — the default is
      the INITIAL state rather than an error branch, which is why a blank screen
      is not reachable. `WidgetGuideDefaults`, `SupportMoneyConfigDefaults`,
      `HomeConfigCache` + hardcoded strings, `ReleasePolicy`'s "every default is
      do nothing".)*

### ⚠️ Additive only
Never delete a config key. Thirty keys once believed dead turned out to be **live
Android** — the shipped app was reading them and nobody knew. The v1 client is
still out there reading keys this branch does not use.

### Acceptance criteria
- [x] Every SDUI surface renders on Android, including its offline fallback.
- [x] Quota limits come from config, not from a constant.
- [x] No config key was removed. *(None was: this story changed no config at
      all. The two changes it RECOMMENDS are both additive.)*

### Findings

#### There is no `android` platform key to audit, and two payloads need one

The task assumes the SDUI surface branches by platform. It does not: every
`/sdui/app/*` endpoint serves one document to everybody, and the backend says so
in passing — "`app.storeUrl` is a Play URL and there is no platform branching on
this endpoint, which is exactly the bug: an iPhone tapping Update Now landed on
Google Play". That particular bug was fixed by moving the policy to
`/sdui/app/release-policy`, which IS per-platform. The rest of the surface was
not audited at the same time.

What the audit found, in order of who it hurts:

1. **`/sdui/app/widget-guide` is entirely iOS-shaped** — jiggle, Edit, top-left,
   stacks, Smart Rotate. Owned by AV2-5.4, where the exact config change is
   written out.
2. **The About screen's "Rate Stationly" row is `market://details?id=…`**, served
   to both platforms. Correct on Android; on an iPhone it opens nothing. The fix
   is the same shape as the guide's: a `platform` condition, or a second row.
   **This one is an iOS bug found by an Android audit**, which is worth saying
   out loud — it has been live since the About screen shipped.
3. **`home-config`'s `widget.state.*` strings carry iOS gestures** ("Touch and
   hold, then tap Edit Widget"). Safe today **by accident**: the only reader is
   `core/iosMain/WidgetAppGroup.kt`, and Android's widget has its own
   hardcoded empty-state copy. Worth knowing because the obvious future tidy —
   "the Android widget should use the SDUI strings too" — would ship iOS gestures
   to Android users on the day it landed.

Everything else in `home-config` is platform-neutral, and the client already
supports the fix everywhere: `SduiConditions` evaluates `equals` against any
fact, and `platform` is published by `SduiFacts`. So all three are config
changes, additive, no release.

### Handoff notes — S016, 2026-09-11

This story built nothing, and that is the correct outcome: the shared UI brought
every SDUI surface to Android intact, and the quotas and the announcement have
been live since the cutover. What it produced is the audit, and the audit's
output is three config changes that belong to whoever owns the backend deploy.

The one thing to carry forward: **"no platform branching" is the default on that
endpoint family, and nothing warns you.** A payload author writing copy for the
screen in front of them is writing it for both platforms unless they add a
condition, and the client renders whatever arrives.

---

## AV2-7.3 — Support, built and off · `S` · Review (S016)

**Depends on:** AV2-3.5 **Reads:** [`DECISIONS.md`](../DECISIONS.md) D4

Per D4: build the Android surfaces behind the same `enabled` flag iOS uses, and
ship v2 with support **disabled**.

### Tasks
- [x] **a.** Confirm the support surfaces render on Android when the flag is on.
      *(They do — they are the same composables iOS runs. Which is the problem
      task (b) was written against, and the reason it needed more than a comment.)*
- [x] **b.** Leave `SupportCheckout.android` stubbed. *(Stubbed, and its KDoc
      rewritten: the reason it gave for being safe had stopped being true. See
      the findings.)*
- [x] **c.** Verify that with the flag **off**, nothing about support is visible
      or reachable. *(True, and now true for a structural reason rather than an
      incidental one.)*
- [x] **d.** Do **not** add a Play Billing dependency. *(None added. Q1 still
      decides the route; the one boolean it will flip is named in the stub.)*

### Acceptance criteria
- [x] With the flag off — the shipping state — support is invisible.
- [x] With the flag on in a debug build, the surfaces render and the checkout
      does nothing. *(No longer: with the flag on, the surfaces do not render on
      Android at all. That is a deliberate change to this criterion — see below.)*

### Findings

#### "An Android build has neither" was true when it was written and is not true now

`SupportCheckout.android.kt`'s KDoc said the stub was safe because
"`composeApp`'s Android target exists so the shared UI keeps compiling for it;
the shipping Android app is `:android:app`, which does not depend on this module
and has its own screens", and that "every support surface is gated on `enabled`
plus a non-blank checkout URL, and an Android build has neither".

Three things in that stopped being true at AV2-3.5, and the third is the one that
matters: **`enabled` comes from the backend, not from the build.** These
composables are the Android app now, reading the same `support_money` document
iOS reads. So one config change — no release, both platforms at once — would have
put the banner, the sheet and the tier ladder in front of Android users, every
one of them ending at an empty function. A button that does nothing, on the
screen about taking somebody's money, shipped by an operator flipping a switch.

Exactly the shape `docs/android-v2` keeps writing down: *compiling is not done*,
and a comment explaining why something is safe is the first thing to rot when the
thing it describes moves.

#### The fix is a capability, not a bigger comment

`expect val checkoutSupported` — true on iOS (`SFSafariViewController`, Apple Pay
available), false on Android — and `SupportMoneyConfig.isOfferable = isPayable &&
checkoutSupported`, which is what all three surfaces now gate on. `isPayable`
stays exactly what it was, because it describes the DOCUMENT and there is a test
that says so.

Turning support on for Android is now a two-part decision: a config change, and a
platform that can honour it. That is what D4's "built, and off" should have meant
all along — a property of the platform rather than of a document somebody might
edit — and it leaves Q1 a one-line change when it is answered.

### Handoff notes — S016, 2026-09-11

When Q1 resolves, the whole Android change is in
`composeApp/src/androidMain/.../SupportCheckout.android.kt`: `CustomTabsIntent`
over the host Activity for an external route, or Play Billing for the other, plus
`checkoutSupported = true`. Nothing else has to move, and no surface has to be
found again.
