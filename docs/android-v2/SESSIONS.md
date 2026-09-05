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

## S008 — 2026-09-05 — AV2-3.2 "Real actuals, batch A"
**Outcome:** PARTIAL → Review (two acceptance criteria need hardware)
**Gate:** GREEN both ends
**Commits:** see branch head

**Did:** The four stubs became real — connectivity, haptics, mode icons, device
identity — plus `AndroidAppContext`, which is where they get a `Context` and the
current Activity.

**Learned — "delegate to the existing implementation" is not possible, and that
is the whole story.** `:composeApp` cannot import `:android:app`; the dependency
runs the other way. So delegation means agreeing on a *file name*: the same
`SharedPreferences("StationlyDevice")` → `device_id`, the same
`filesDir/mode_icons/<safeName>.png`. Two implementations, one directory, no
compiler watching.

Every failure in that arrangement is silent. A changed prefs file issues every
v1 user a NEW device id, and the backend releases a subscription only when the
last device signs out — so the old session becomes a ghost logout can never
clear. A changed icon directory is quieter still: the shared UI re-downloads
into a second set of files and **the home-screen widget keeps rendering from the
first**, untinted. Neither errors on either side.

`V1V2StorageContractTest` reads those constants off both classes by reflection
and compares them, then runs `ModeIconCache.safeName` against
`modeIconFileName` over the real mode names. It lives in `:android:app` because
that is the only module that can see both — and only on staging, which is where
the shared UI is. AV2-3.5 deletes the v1 halves and this test with them.

**`ModeIconStore.sync` still writes `tints.json`, though nothing in the shared
interface reads tints.** v1's widget does. Do not tidy it out before AV2-3.5.

**Learned — haptics through a View, not `Vibrator`, and the reason is not
style.** `View.performHapticFeedback` respects the user's touch-feedback setting
and needs no `VIBRATE` permission, so adopting the shared UI adds no permission
to an app that is already live. The cost is needing an Activity, which is why
`AndroidAppContext` tracks one — weakly, cleared on pause. AV2-3.3 and AV2-3.4
both need that Activity anyway, so it is built once rather than three times.

**Two files outside the story's list, both logged in the epic.** `core`'s Android
`Platform.appContext` went `private lateinit` → `lateinit … private set` (one
line; the alternative was a second context holder with its own initialisation
order to forget, in an app that already has exactly one place for this).
And `composeApp/build.gradle.kts` gained an `androidUnitTest` source set, plus a
new `composeApp/src/androidMain/AndroidManifest.xml` declaring the two
permissions the Android actuals need — which merges to nothing today, because
`:android:app` already declares both, and that is the point.

**`commonMain` was untouched by both S007 and S008**, so the 20-minute
XCFramework assemble was correctly skipped twice. Check `git status` before
assuming you owe it.

**Next agent needs to know:** AV2-3.3 and AV2-3.4 are both Ready, both `M`, and
independent of each other — either order.

**Take AV2-3.4 first if a tester is waiting.** It is the one a human notices:
`signInWithGoogleInteractive()` currently returns a failure carrying the string
"Use the Google Sign-In button to continue." — v1's flow talking about a button
the shared `LoginScreen` does not have. So sign-in from the v2 door does not
work at all. Its other half, the per-flavour deep-link scheme, is the bug iOS
shipped: a hardcoded `"stationly"` silently dropped every staging link, and
`V2HostManifestTest` currently asserts the v2 host advertises no scheme at all —
that assertion is yours to update, deliberately, not to delete.

**Two device checks are now queued and they are the same trip.** AV2-3.1 needs
"the shared UI opens and navigates"; AV2-3.2 needs the offline banner and — the
one that matters — a v1 install opening the shared UI keeping ONE entry in the
account's device list rather than gaining a second.

---

## S007 — 2026-09-05 — AV2-3.1 "Host the shared UI" · **first change to the shipped app**
**Outcome:** PARTIAL → Review (one acceptance criterion needs hardware)
**Gate:** RED on arrival, GREEN at close
**Commits:** see branch head

**Did:** Gave the shared Compose Multiplatform UI an Android host.
`"stagingImplementation"(project(":composeApp"))`, a `V2MainActivity` in
`src/staging` calling `setContent { App(...) }`, a staging manifest registering it
as a second launcher, and a test that guards the manifest decisions.

**The gate was red when I opened the board**, which the board did not say. The
previous session had claimed AV2-3.1 and left
`stagingImplementation(project(":composeApp"))` in the tree. It does not compile:
Kotlin DSL generates type-safe accessors only for configurations that exist when
the script is compiled, and flavour configurations are created *by* this script.
`"stagingImplementation"(...)` works. **A claim row is not a statement about the
tree** — the protocol says run the gate on arrival, and this is why.

**Learned — the flavour scoping is measured, not cautious.** I checked the
resolved classpaths rather than trusting the comment:

    prod     compose.runtime 1.7.0   material3 1.3.0
    staging  compose.runtime 1.8.0   material3 1.3.2

`compose-bom:2024.09.00` loses to Compose Multiplatform 1.8.0. Plain
`implementation` would have moved the **live** app's Compose runtime out from
under `navigation-compose:2.8.0`, which is pinned to Compose 1.7 by a comment
recording a shipped blank-screen bug.

**⚠️ The flip side, and the next person on staging should know it: v1's own
screens on a staging build now run on Compose 1.8.0 with nav 2.8.0.** Same class
of mismatch, other direction. Prod untouched; AV2-3.5 deletes the v1 stack. If v1
misbehaves on staging during the two-door period, suspect this first.

**Learned — the blank-screen bug has a second, un-fixed half in `commonMain`.**
Shared `AppNavigation` derives `startDestination` from a plain `val`, so a restore
after process death can root a saved back stack on a destination that no longer
matches. v1 fixed exactly this with `rememberSaveable`. I closed the
logged-in/logged-out flip host-side (`startLoggedIn` goes through
`onSaveInstanceState`, because the shared code takes it as a *parameter* — Android
has to save it at the boundary), but the `isEmailProvider()`/`isEmailVerified()`
branch is still recomputed on every restore. Fixing that means editing
`commonMain` on a branch shipping to TestFlight, which is outside this story's
file list. Logged in the epic and in GAP_ANALYSIS §3.3, for AV2-3.5.

**Learned — home-screen pins reference the component name.** AV2-3.5 (a) cannot
just delete `com.stationly.mobile.MainActivity` and promote `.v2.V2MainActivity`:
every v1 user with a pinned icon would find it greyed out. Keep the old name as
the exported launcher, or add an `<activity-alias>`. Written into the epic.

**Two doors, two tasks.** `V2MainActivity` takes its own `taskAffinity`. Sharing
the default would put both launchers in one task where `singleTask` on either
clears the other off the top — opening v1 from the drawer would silently destroy
an open v2 screen, and the tester would debug the wrong thing all afternoon.

**Tested:** `V2HostManifestTest` reads both manifests as files and asserts the
five things that no compiler would notice going wrong — the v2 host is staging
only, v1 is still the launcher and still owns all four deep-link hosts, the v2
host advertises no scheme, it keeps `singleTask` + its own affinity, and the
staging manifest does not take over the `Application` class (which is the closest
static proof there is for task (d), since `Platform` exposes no "initialised"
flag).

**Next agent needs to know:** AV2-3.1 is in **Review**, not Done, for exactly one
reason — no Android device was attached, so "a staging build opens the shared UI
and can navigate" is unverified. Everything else is proven: both flavours
assemble, the prod merged manifest has zero references to the v2 host, and the
prod dependency graph is unchanged.

**AV2-3.2 is Ready and does not wait on that check.** Four stub actuals, and
`DeviceIdentity` is the one that matters: it hands out a fresh UUID per process
today, which breaks device sessions and "logging out releases my subscriptions"
in ways that look like backend bugs. iOS lost two days to this exact class of
problem. The real `DeviceIdProvider` already exists in `:android:app` — the work
is an application-context holder in `composeApp/androidMain` and four delegations.

---

## S006 — 2026-09-05 — AV2-2.3 "Close the drift permanently" · **EPIC-02 complete**
**Outcome:** DONE
**Gate:** GREEN, and it has a new task in it
**Commits:** see branch head

**Did:** Enabled `verifyMigrations`. The build now fails if the `.sq` and the
`.sqm` disagree.

**Learned — the old comment was right about the blocker and wrong about the
fix being hard.** `verifyMigrations.set(true)` does fail with *"Verifying a
migration requires a database file to be present… use the generate schema Gradle
task"*, and that task genuinely **is not registered** by SQLDelight 2.0.2 in this
configuration — `:core:tasks --all` lists only the two `verify…` tasks. That dead
end is what got the earlier attempt reverted.

The way through is realising the baseline does not need generating. It is the
schema as the **last released Android build** created it — a fact about a shipped
APK, not about anything in this tree. So it is built by hand from the same
fixture AV2-2.2 checked in, which means the two cannot disagree. Two shell lines,
recorded in `core/build.gradle.kts`.

**Proven to bite:** a column added to the `.sq` alone now fails the build and
names the column.

**⚠️ Trap for whoever writes `2.sqm`: do NOT touch `1.db`.** It is the start of
the chain, not a snapshot of the present. Regenerating it from the current schema
makes the check vacuous — it would compare the schema against itself and pass
forever. That warning is in the build file too.

**The task is not wired into `check`**, so it is named explicitly in the gate and
in CI.

**And it caught something immediately.** Switching it on broke interface
generation with `1.sqm: (149, 13): Duplicate index name prediction_lookup`. The
migration drops and rebuilds `PredictionEntity` and then recreates its index with
`direction` added; SQLite drops an index with its table, so this was
*functionally* fine — it ran under `sqlite3` and `MigrationTest` passed — but
SQLDelight's analyzer does not model the cascade. An explicit
`DROP INDEX IF EXISTS` before the `DROP TABLE` fixes it and reads better.

Three checks had already called that file correct. The flag found something on
its first run.

**Next agent needs to know:** sprint 1 is done. EPIC-01 and EPIC-02 are both
complete: the gate exists and runs in CI, v1's behaviour is recorded, and a real
v1 database migrates without loss on both schema shapes.

**AV2-3.1 is next and it is the first story that changes the shipped app.** It
adds the `:composeApp` dependency and a second Activity, staging-only, with v1's
`MainActivity` still the launcher. Do not make it the launcher — that is AV2-3.5,
after the actuals are real. And carry over the `launchMode="singleTask"` plus
`onNewIntent` reasoning; the manifest comment explains what breaks without it,
and it shipped as a blank screen once already.

---

## S005 — 2026-09-05 — AV2-2.2 "Prove the migration"
**Outcome:** DONE
**Gate:** GREEN — `:core` 403 tests (9 new). No `commonMain` change, so no
XCFramework run was required.
**Commits:** see branch head

**Did:** `MigrationTest`, 9 tests, against a real SQLite file rather than
`:memory:` — the thing under test is what happens to a database that already
exists on disk. Checked in the v1 DDL as `fixtures/v1/schema-v1.sql`, headed with
a warning that it is a fixture and not a mirror: it must hold still while the
live `.sq` moves, or it stops testing anything.

**Mutation-checked with the two mutations somebody will actually make:**

- Moving the `ActivityEventEntity` guard to the end of `1.sqm` — caught by *an
  iOS-shaped database is refused, and nothing in it is touched*.
- Adding a column to the `.sq` and not the `.sqm` — caught by *a migrated
  database is indistinguishable from a created one*.

The first matters most. The guard is one statement's position in a file, and now
something fails if it moves.

**Learned:** the iOS-shape test needs no transaction of its own and the data
still survives. That is the assertion, not an oversight — the guard being FIRST
means nothing destructive executes, so safety does not depend on the driver
wrapping the migration. The drivers do wrap it; the ordering is what makes it
safe without one.

**A gradle note:** a `:core:testDebugUnitTest` run took 16m46s here, against 1.2s
of actual test time. It was daemon lock contention with a background XCFramework
build, not the tests. If a test run seems to hang, check for a native compile
holding the daemon before you go looking at your test.

**Next agent needs to know:** EPIC-02 has one story left (AV2-2.3,
`verifyMigrations`), and AV2-3.1 is also Ready now. AV2-3.1 is the bigger
unlock — it is the first story where the shared UI actually runs on Android.
Keep the equivalence test in `MigrationTest` after AV2-2.3 lands: it tests the
schema where the flag tests the build, and they fail at different times.

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
