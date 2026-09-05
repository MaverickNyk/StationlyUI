# EPIC-07 — Release surfaces · 10 pts

**Goal.** The surfaces that make a live app manageable after it ships: the update
gate, server-driven config and quotas, and the support surface (built, and off).

**Exit.** The app can be told to update itself, its limits come from config, and
the money surface exists behind a flag nobody has turned on.

---

## AV2-7.1 — The update gate · `M` · Backlog

**Depends on:** AV2-3.5 **Reads:** [`GAP_ANALYSIS.md`](../analysis/GAP_ANALYSIS.md) §3.7
**Files:** `ReleaseGate` wiring, `UpdateSurfaces`, `android/app/build.gradle.kts`

`ReleasePolicy` already carries an `android: PlatformRelease` alongside `ios`, so
the backend side is done. Only the client wiring is missing.

### Tasks
- [ ] **a.** Wire `ReleaseGate` and `UpdateSurfaces` on Android.
- [ ] **b.** **Play In-App Updates** for the soft path — a flexible update the
      user can take without leaving the app. iOS links out to the App Store
      because it has no other option; Android does, and copying the link-out for
      both would be porting an iOS constraint (see the rule in `README.md`).
- [ ] **c.** `storeUrl` for the hard path, when the version floor forces it.
- [ ] **d.** Verify the gate against a version below `minVersion` and one above.

### Why this matters more than it looks
The version floor in `ReleasePolicy.android.minVersion` is what eventually
retires risk R2 — a v1 device wiping a v2 device's boards. It cannot be set until
this ships, and it is the only lever that ends the `stations` dual-write.

### Acceptance criteria
- [ ] A build below the floor is blocked and offers the update.
- [ ] A soft update completes without leaving the app.

### Handoff notes
_(none yet)_

---

## AV2-7.2 — Config and quotas · `L` · Backlog

**Depends on:** AV2-3.5 **Files:** SDUI config consumers, `BoardQuota`, `BoardPolicy`

### Tasks
- [ ] **a.** Audit the `android` platform key across the SDUI config surface —
      layouts, login/register/about, home config, theme tokens, announcements.
- [ ] **b.** Board and line quotas via `BoardQuota` / `BoardPolicy`, with
      `StationLimitSheet` as the surface.
- [ ] **c.** The announcement banner.
- [ ] **d.** Confirm the offline fallbacks render when config cannot be fetched.
      Every SDUI surface needs one; a config fetch failing must not be a blank
      screen.

### ⚠️ Additive only
Never delete a config key. Thirty keys once believed dead turned out to be **live
Android** — the shipped app was reading them and nobody knew. The v1 client is
still out there reading keys this branch does not use.

### Acceptance criteria
- [ ] Every SDUI surface renders on Android, including its offline fallback.
- [ ] Quota limits come from config, not from a constant.
- [ ] No config key was removed. Diff the key list to prove it.

### Handoff notes
_(none yet)_

---

## AV2-7.3 — Support, built and off · `S` · Backlog

**Depends on:** AV2-3.5 **Reads:** [`DECISIONS.md`](../DECISIONS.md) D4

Per D4: build the Android surfaces behind the same `enabled` flag iOS uses, and
ship v2 with support **disabled**.

### Tasks
- [ ] **a.** Confirm the support surfaces render on Android when the flag is on —
      `SupportBanner`, `SupportSheet`, `SupportProfileCard`, `SupportThanksOverlay`.
- [ ] **b.** Leave `SupportCheckout.android` stubbed. Its KDoc already states the
      position and why a no-op beats a throw: every support surface is gated on
      `enabled` plus a non-blank checkout URL, and an Android build has neither.
- [ ] **c.** Verify that with the flag **off**, nothing about support is visible
      or reachable anywhere in the app.
- [ ] **d.** Do **not** add a Play Billing dependency. Q1 decides the route, and
      adding the dependency pre-empts the decision.

### Acceptance criteria
- [ ] With the flag off — the shipping state — support is invisible.
- [ ] With the flag on in a debug build, the surfaces render and the checkout
      does nothing, which is the documented behaviour.

### Handoff notes
_(none yet)_
