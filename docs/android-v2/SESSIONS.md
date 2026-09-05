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

## S004 — 2026-09-05 — AV2-2.1 "Reinstate migrations"
**Outcome:** DONE
**Gate:** GREEN
**Commits:** see branch head

**Did:** Rewrote the `.sq` banner (the premise it rested on is dead, and the
hand-maintained list that was wrong is replaced by the command that regenerates
it). Wrote `migrations/1.sqm` as a table rebuild. Bumped the AV2-1.1 tripwire
1 → 2; it fired exactly as designed.

**Learned — and it changed the migration, not just the notes:**

**"Version 1" means two different schemas.** `StationlyDatabase.Schema` is shared
by both platforms and its version is the migration count, so the number is
global. But Android's released devices were created from the OLD `.sq` and iOS's
TestFlight devices from the CURRENT one — iOS databases are stamped version 1 and
already contain everything this migration adds.

Left alone, the rebuild would have copied only the eight columns an Android v1
row has and silently destroyed `parentStationId`, every filter, `viaKeys` and
`patternIds` on every iOS device, then crashed at the activity table anyway.

There is no pure-SQL migration correct for both shapes. So
`CREATE TABLE ActivityEventEntity` is now the FIRST statement, and its position
is the guard: on an iOS-shaped database it throws before a row is touched and the
transaction rolls back. Verified on both shapes; iOS data comes through
untouched. MIGRATION.md §1.4b has the reasoning.

**A harness trap worth an hour of somebody's life:** `sqlite3` without `-bail`
**keeps going after an error**. My first iOS-shape run sailed past the guard,
destroyed the data, and told me the guard did not work. It does — the harness was
wrong, and the drivers stop on the first failure. Always `-bail`.

Also verified by hand: `PRAGMA table_info` is identical between `Schema.create`
and the migration for all five tables, plus both indexes. AV2-2.2 turns that into
a test rather than discovering it.

**Next agent needs to know:** AV2-2.2, and it gained a task (g) — cover the
iOS-shaped database. The guard is one statement's position in a file and nothing
else protects it; a well-meaning reorder silently re-arms the data loss. Do not
add `IF NOT EXISTS` to that statement, for the same reason.

**Q5 raised:** iOS TestFlight testers on an older build must delete and reinstall
once when this ships.

---

## S003 — 2026-09-05 — AV2-1.3 "Capture v1's golden outputs" · **EPIC-01 complete**
**Outcome:** DONE
**Gate:** GREEN — `:core` 394 tests (11 new), XCFramework assembles
**Commits:** see branch head

**Did:** Four fixtures under `docs/android-v2/fixtures/v1/` and `V1GoldenTest`
reading them. Captured while a v1 build still exists to capture from — after
AV2-3.5 the only source is git history.

**Learned:**

1. **`AndroidWidgetManager.formatForWidget` is a placeholder.** It returns a
   hardcoded `"Loading..."` and always has. `FormatDeparturesUseCase` is the
   real formatter, called from `DepartureWidgetProvider`. Anyone wiring the v2
   widget by following the `WidgetManager` interface name would wire it to
   nothing. Flagged in EPIC-05.
2. **A v1 blob folds to a board per POLE.** No `parentStationId`, so `groupingId`
   falls back to `station`. That is what the user's phone shows today, not
   corruption — AV2-4.3 must not "repair" it.
3. **The destination truncation ends mid-token**: a raw 22-char cut, so
   `"Heathrow Terminals 2 & 3 via Hatton Cross"` becomes
   `"Heathrow Terminals 2 &..."`, dangling ampersand and all. My fixture guessed
   otherwise and the test corrected me — which is the fixtures doing their job on
   day one.

**Scope note:** as in S002, the story had nothing to point at for the deep-link
table. It was a `when` over `android.net.Uri` in `MainActivity` (deleted by
AV2-3.5), with iOS keeping its own copy. It is now
`core/.../model/deeplink/DeepLink.kt`, and **the scheme is a parameter** — the
iOS per-environment bug encoded so it cannot recur. Two test stories have now
each needed one small extraction. That is a pattern, not a coincidence: v1 put
shared logic in ViewModels and Activities, so anything worth pinning has to be
lifted out before it can be pinned.

**Next agent needs to know:** EPIC-01 is complete and **AV2-2.1 is next — the
riskiest story in the programme**. Read `analysis/MIGRATION.md` §1 in full and
regenerate the schema delta rather than trusting any written list, including the
one in that document. The `.sq` banner's own list is missing 14 of the 21 items.

And the tripwire from S001 fires here: `SchemaHarnessTest` asserts schema
version `1`; adding `migrations/1.sqm` makes it `2`. Bump it, do not delete it.

---

## S002 — 2026-09-05 — AV2-1.2 "Lock the v1 contract"
**Outcome:** DONE
**Gate:** GREEN — `:core` 383 tests (9 new), XCFramework assembles
**Commits:** see branch head

**Did:** Wrote `V1ContractTest` — the flat `stations` wire form both ways, and
the v1 FCM payload. Mutation-checked both halves rather than trusting a green
run: dropping `parentStationId` fails 3 tests, folding only the first queue per
board fails 3.

**Learned — and this one is a live bug, not a note:**

There was **no** `Board → SubscribedStation` conversion to test. It existed four
times, hand-written: three ViewModels outbound, `UserSyncRepository` inbound.
**Two of the three outbound copies drop `parentStationId`** —
`ProfileViewModel.loadStations` and `SummaryViewModel`'s delete-sync. A station
restored without it groups on its own naptan, so one bus hub comes back as a
card per pole with the same name on every one.

This is a v1 bug that is live today, and it is a direct hazard to AV2-4.3: the
dual-write's whole purpose is that a v1 device sees a degraded-but-*correct*
view, and a split hub is not correct. The four copies are now one —
`model/user/LegacyStationList.kt` — and `UserSyncRepository` calls it. The three
ViewModels are deleted by AV2-3.5, so they were left alone rather than patched.

**Scope note:** this test story made a production change. A contract test needs
one named thing to point at, and there wasn't one. Flagging it because the
protocol says work outside a story's file list is a finding, not a licence.

**Next agent needs to know:** AV2-1.3 is next and it is the last thing standing
between here and the database work. It captures v1's golden outputs **while a v1
build still exists to capture them from** — after AV2-3.5 deletes the v1 UI, the
only source is git history. `BoardTest` already covers `Board` ⇄ `UserSelection`
thoroughly (18 tests); do not re-cover it.

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
