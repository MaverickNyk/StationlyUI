# Android v2 — session journal

Append-only. Newest first. One entry per session, written at close-out.
**Never delete or rewrite an entry** — a wrong turn recorded is worth more than a
tidy log, because the next agent would otherwise take it again.

Template:

```
## S0NN — YYYY-MM-DD — AV2-x.y "story title"
**Outcome:** DONE | PARTIAL | BLOCKED | GATE-FIX
**Gate:** GREEN | RED (which task)
**Commits:** <sha> <sha>
**Did:** one or two lines.
**Learned:** anything that changes the map. Move it into analysis/ if it does.
**Next agent needs to know:** the thing you would tell them in person.
```

---

## S001 — 2026-09-05 — AV2-1.1 "The gate, in CI"
**Outcome:** DONE
**Gate:** GREEN — `:core` 374 tests, `:composeApp` pass, `:android:app` 4 tests
(new), both Android compiles pass
**Commits:** see branch head

**Did:** Built the gate and put it in CI. Added `core/src/androidUnitTest` on the
SQLDelight JDBC driver — the source set EPIC-02 cannot be tested without, since
`commonTest` has no driver and an instrumented test would need a device on every
run. Added `SchemaHarnessTest` to prove the harness reaches the schema, and
`android/app`'s first ever unit test.

**Learned:**

1. **CI cannot compile `:android:app`.** `google-services.json` is gitignored and
   untracked; the `google-services` plugin fails outright without it. The
   workflow now runs the shared half unconditionally and the Android half only
   when a `GOOGLE_SERVICES_STAGING_B64` secret exists, emitting a GitHub warning
   annotation when it does not — so it can never pass quietly while implying
   coverage it lacks. Raised as **Q4**; one `base64` command from the owner
   closes it.
2. `BackendErrorUtil` classifies partly on exception type and partly on message
   *text* ("timeout", "failed to connect"). That is the kind of rule that rots
   silently when a library changes its wording, which is why it was chosen as
   the first thing in `:android:app` to get a test.

**Next agent needs to know:**

- **A tripwire is armed and AV2-2.1 will trip it.** `SchemaHarnessTest.the schema
  version matches the migration count` asserts `1`. Adding `migrations/1.sqm`
  makes the version `2` and the test fails. This is deliberate — SQLDelight
  derives the version from the migration count, so the assertion is what catches
  a `.sq` changed without a matching `.sqm`. AV2-2.1 task (g) says bump it to `2`
  in the same commit. **Do not delete the test to make it pass.**
- An `ios-contract` job was added beyond the story's scope: macOS,
  `:composeApp:assembleComposeAppDebugXCFramework`. It is the C2 contract's only
  real check, because Xcode will link a stale framework and give a green build
  that proves nothing. It needs no secret.
- AV2-2.1 is now Ready. Read `analysis/MIGRATION.md` §1 in full before starting
  it, and regenerate the schema delta rather than trusting either written list.

---

## S000 — 2026-09-05 — project setup
**Outcome:** DONE
**Gate:** GREEN (`:core:testDebugUnitTest`, `:composeApp:testDebugUnitTest`,
`:android:app:compileStagingDebugKotlin`, `:composeApp:compileDebugKotlinAndroid`)
**Commits:** _(see branch head)_

**Did:** Cut `dev_android_bring_to_v2` from `ios-parity` @ `b7b7a1c`. Measured
the actual state of the repo rather than assuming it, took four decisions
(D1–D4), and built this workspace: gap analysis, migration plan, test strategy,
gap graph, and the board.

**Learned** — three measurements reframed the project:

1. `ios-parity` is a **strict superset** of `master` (117 ahead, 0 behind). There
   was never an Android branch to merge back.
2. `:android:app:compileStagingDebugKotlin` is **green** on `ios-parity`. The
   shipped app was kept compiling against the v2 core as it moved — 38 lines
   across 5 files is the whole of its adaptation.
3. `:composeApp:compileDebugKotlinAndroid` is **green**. The entire iOS v2 UI
   already builds for Android; its Android `actual`s exist and are deliberate
   placeholders, each one commented as a "build-verification surface only".

And one finding that changes a plan rather than the map: the hand-maintained
list in the `StationlyDatabase.sq` banner, of what an Android upgrade owes, is
**incomplete**. It names 7 items. The computed delta is 21 — it omits five
`UserSelectionEntity` columns, three `PredictionEntity` columns, and **both**
primary-key changes. Anyone writing the migration from that list would ship a
broken one. See `analysis/MIGRATION.md` §1.2.

Also recorded: `backup_rules.xml` includes `domain="database"`, so Android Auto
Backup can restore a **v1 database into a fresh v2 install**. The upgrade path is
a larger set than "devices that had v1 installed", and a test plan scoped to
in-place upgrades would miss it entirely.

**Next agent needs to know:** start at AV2-1.1. Do not start the migration
(EPIC-02) before the gate runs in CI — it is the one piece of work where a
regression reaches users who have been using the app the longest, and it is
invisible on every development device, all of which are fresh installs.
