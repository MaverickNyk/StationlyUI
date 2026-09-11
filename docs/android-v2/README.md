# Android v2 — project workspace

Bringing the live Android app (`versionCode 2`, frozen since launch) up to what
iOS v2 does, plus the things only Android can do.

**If you are an agent starting a session, read this file, then [`BOARD.md`](BOARD.md).
Nothing else, until the board tells you which story you are on.**

---

## The files

| File | What it is | Who writes it |
|---|---|---|
| [`BOARD.md`](BOARD.md) | **The kanban board.** Live column state, WIP, claims. | Every session, at start and end |
| [`BACKLOG.md`](BACKLOG.md) | Epic → story index with sizes and dependencies | When scope changes |
| [`epics/`](epics/) | One file per epic: stories, tasks, acceptance criteria, handoff notes | The session working that epic |
| [`SESSIONS.md`](SESSIONS.md) | Append-only journal. One entry per session. | Every session, at end |
| [`DECISIONS.md`](DECISIONS.md) | ADR log. Decisions that bind future sessions. | When a decision is taken |
| [`analysis/GAP_ANALYSIS.md`](analysis/GAP_ANALYSIS.md) | The map: what exists, what does not, why | When a *finding* changes |
| [`analysis/MIGRATION.md`](analysis/MIGRATION.md) | Schema, cloud state, prefs, topics, rollout | When the migration plan changes |
| [`analysis/TEST_STRATEGY.md`](analysis/TEST_STRATEGY.md) | Contracts, gates, definition of done | Rarely |
| [`analysis/gap-graph.json`](analysis/gap-graph.json) | Machine-readable gap + risk + dependency graph | With the gap analysis |

**Progress goes on the board. Findings go in the analysis. Never mix them** —
a document that carries both becomes a document nobody trusts for either.

---

## The session protocol

Sized for **one ~5h Opus session at medium effort**. One story per session.

### 1. Open
- Read `BOARD.md` §Live state.
- **Run the gate before touching anything.** If it is red on arrival, that is
  your story: fix it, log it, stop.
- Pick the top story in **Ready**. If a story is already **In Progress** with a
  stale claim (see below), you may take it over.

### 2. Claim
Edit `BOARD.md`: move the story to **In Progress** and fill its claim row with
your session id and the date. This is the lock. It is advisory, and it is
enough — one owner, one agent at a time.

### 3. Work
- Read the story in its epic file, and **only** the analysis section it names.
  The corpus is 700KB; reading it all is how a session ends with nothing built.
- Write the test first for anything the gap analysis marks `DIVERGENT`. That
  status is the one with a live user behind it.
- Stay inside the story's file list. If the work demands a file outside it, that
  is a finding: log it, then either stay in scope or stop.

### 4. Close — always, even if the story is unfinished
- Run the gate. If `commonMain` changed, the XCFramework assemble is part of it.
- **Commit. Nothing uncommitted, ever.** A half-applied change in a dirty tree
  is the worst possible handoff.
- Write the **handoff note** in the epic file under the story: what is done, what
  is not, what the next agent needs to know, what surprised you.
- Move the story to **Review**, **Done**, or back to **Ready** with progress
  noted. An unfinished story goes back to Ready with its handoff note, not left
  in In Progress.
- Append one line to `SESSIONS.md`.
- Update `BOARD.md` §Live state.

### Stale claims
A claim is stale if the session that made it did not close out. Check the git
log: if there are no commits from that session and the tree is clean, take the
story over and note the takeover in the handoff. A story is never blocked by a
claim alone.

---

## The gate

One command. Run at the start and the end of every session.

```bash
./gradlew :core:testDebugUnitTest \
          :core:verifyCommonMainStationlyDatabaseMigration \
          :composeApp:testDebugUnitTest \
          :android:app:compileStagingDebugKotlin \
          :composeApp:compileDebugKotlinAndroid
```

The migration verification is named explicitly because SQLDelight does **not**
wire it into `check`. It compares a database built by applying the migrations
against one built by `Schema.create`, and it is the only thing that catches the
`.sq` and the `.sqm` drifting apart.

Whenever `commonMain` changed, also:

```bash
./gradlew :composeApp:assembleComposeAppDebugXCFramework
```

Not optional and not slow-path: Xcode will happily link a **stale** framework and
give you a green build that proves nothing.

**It takes ~20 minutes** from cold (measured: 19m 26s on 2026-09-05 — Kotlin/Native
compiles the whole Compose Multiplatform module for two architectures). So batch
`commonMain` work and run it **once, at close-out**, rather than after each edit.
Start it in the background and write your handoff notes while it runs.

Do **not** use `allTests` — it dies on the `wasmJs` target and the iOS test
target will not compile the existing comma-named test functions.

---

## Rules that bind every story

1. **Never break iOS.** `:core` and `:composeApp` `commonMain` are shared with a
   build shipping to TestFlight right now.
2. **Never assume a v1 user does not exist.** The app is live with real users on
   `versionCode 2`.
3. **Do not port an iOS workaround for an iOS-only constraint.** The live stream
   and the widget refresh budget are the two live examples — Android has FCM and
   does not need either.
4. **SDUI config is additive only.** Keys once believed dead were live Android.
5. **A default is not an answer.** `UserSettings` prunes default-equal rows, so
   never read a preference straight off the map.
6. **The widget never guesses a station.** If a binding is missing it says so.
7. **Do not push this branch.** Local only until the owner says otherwise.

---

## Where the work sits

Branch `dev_android_bring_to_v2`, cut from `ios-parity` @ `b7b7a1c`.

The one-paragraph version: `:composeApp` — the whole iOS v2 UI, 101 files —
already compiles for Android with a full set of `actual`s that are deliberate
placeholders. `:android:app` still compiles against the v2 `core`. So this is an
**adoption** project: add one dependency edge, host `App()` in an Activity,
replace nine stub `actual`s with real ones, rebuild the widget for per-instance
configuration, and write the database migration the `.sq` file already says
Android owes. The risk is not in building the features. It is that the features
arrive all at once on a live app whose database has never been migrated.

**As of S016, all of it is done except the rollout.** `MainActivity` is
`setContent { App(...) }`, `:composeApp` is a plain `implementation`, every
placeholder is real, v1's UI is deleted, the widget is per-instance, the
screensaver is the shared one, and the data plane reads and writes the same list.
What is left of `com.stationly.mobile` is **entry points and the widget**: the
Activity, the Application, the FCM/auth services, `DepartureWidgetProvider` and
the `ui/util` + `util` helpers that only it still reads. If you are looking for
"the Android app's screens", they are in `composeApp/src/commonMain` and they are
the same files iOS runs.

24 of 27 stories are built. The three that are not need a phone or the owner:
walk a release build (AV2-8.1), verify both upgrade paths on hardware (AV2-8.2),
ship (AV2-8.3).

**The unmigrated-database risk is unchanged** and still the one that reaches the
users who have had the app longest — and it is now joined by a second thing only
hardware can answer, because **eleven stories were built in S016 and none of them
has been run on a phone.**
